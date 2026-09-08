package com.gitnova.service.agent.dispatch;

import com.gitnova.service.agent.execution.AgentRun;
import com.gitnova.service.agent.execution.AgentTaskRunStore;
import com.gitnova.service.agent.execution.DurableRunExecutor;
import com.gitnova.service.agent.runtime.AgentCapability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real RabbitMQ contract for publisher confirms/returns and manual consumer ACK. */
@Tag("rabbit-it")
class RabbitMessagingIntegrationTest {

    private CachingConnectionFactory connectionFactory;
    private RabbitAdmin admin;
    private RabbitTemplate template;
    private DirectExchange exchange;
    private Queue queue;

    @BeforeEach
    void connect() {
        connectionFactory = new CachingConnectionFactory(
                System.getenv().getOrDefault("RABBITMQ_HOST", "localhost"),
                Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_PORT", "5672"))
        );
        connectionFactory.setUsername(System.getenv().getOrDefault("RABBITMQ_USERNAME", "guest"));
        connectionFactory.setPassword(System.getenv().getOrDefault("RABBITMQ_PASSWORD", "guest"));
        connectionFactory.setPublisherConfirmType(
                CachingConnectionFactory.ConfirmType.CORRELATED
        );
        connectionFactory.setPublisherReturns(true);

        String suffix = UUID.randomUUID().toString();
        exchange = new DirectExchange("gitnova.agent.it." + suffix, false, false);
        queue = new Queue("gitnova.agent.it." + suffix, true, false, false);
        admin = new RabbitAdmin(connectionFactory);
        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("dispatch"));

        template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        template.setMessageConverter(messageConverter());
    }

    @AfterEach
    void disconnect() {
        if (admin != null && exchange != null) {
            admin.deleteQueue(queue.getName());
            admin.deleteExchange(exchange.getName());
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void shouldConfirmRoutedMessagesAndReturnUnroutableMessages() throws Exception {
        CorrelationData routed = new CorrelationData("routed-1");
        template.convertAndSend(exchange.getName(), "dispatch", "payload", routed);

        CorrelationData.Confirm routedConfirm = routed.getFuture().get(5, TimeUnit.SECONDS);
        assertTrue(routedConfirm.isAck());
        assertNull(routed.getReturned());
        assertNotNull(template.receive(queue.getName(), 5_000));

        CorrelationData unroutable = new CorrelationData("unroutable-1");
        template.convertAndSend(exchange.getName(), "missing-route", "payload", unroutable);

        CorrelationData.Confirm unroutableConfirm = unroutable.getFuture().get(
                5,
                TimeUnit.SECONDS
        );
        assertTrue(unroutableConfirm.isAck());
        assertNotNull(unroutable.getReturned());
    }

    @Test
    void shouldAckDuplicateDeliveriesAfterOneDurableClaim() throws Exception {
        AgentTaskRunStore store = mock(AgentTaskRunStore.class);
        DurableRunExecutor executor = mock(DurableRunExecutor.class);
        AtomicReference<AgentRun> state = new AtomicReference<>(queuedRun());
        when(store.findRun("run-1")).thenAnswer(invocation -> Optional.of(state.get()));
        when(store.claimRun(any())).thenAnswer(invocation -> {
            AgentTaskRunStore.ClaimCommand command = invocation.getArgument(0);
            AgentRun claimed = runningRun(command.workerId());
            state.set(claimed);
            return new AgentTaskRunStore.ClaimResult(
                    AgentTaskRunStore.ClaimDisposition.CLAIMED,
                    claimed
            );
        });
        RunDispatchWorker worker = new RunDispatchWorker(store, executor);
        CountDownLatch delivered = new CountDownLatch(2);

        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(
                connectionFactory
        );
        container.setQueueNames(queue.getName());
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setConcurrentConsumers(1);
        Jackson2JsonMessageConverter converter = messageConverter();
        container.setMessageListener((ChannelAwareMessageListener) (
                Message message,
                com.rabbitmq.client.Channel channel
        ) -> {
            try {
                Object decoded = converter.fromMessage(message);
                worker.consume(
                        (RunDispatchMessage) decoded,
                        channel,
                        message.getMessageProperties().getDeliveryTag()
                );
            } finally {
                delivered.countDown();
            }
        });
        container.start();
        try {
            RunDispatchMessage dispatch = new RunDispatchMessage(
                    "dispatch-1",
                    "run-1",
                    RunDispatchReason.INITIAL,
                    null
            );
            template.convertAndSend("", queue.getName(), dispatch);
            template.convertAndSend("", queue.getName(), dispatch);

            assertTrue(delivered.await(10, TimeUnit.SECONDS));
            verify(store, times(1)).claimRun(any());
            verify(executor, times(1)).execute(
                    anyString(),
                    anyString(),
                    anyLong()
            );
        } finally {
            container.stop();
        }
    }

    private static Jackson2JsonMessageConverter messageConverter() {
        return (Jackson2JsonMessageConverter) new AgentRabbitConfiguration()
                .rabbitMessageConverter();
    }

    private static AgentRun queuedRun() {
        Instant now = Instant.parse("2026-09-06T08:00:00Z");
        return new AgentRun(
                "run-1",
                "session-1",
                "task-1",
                1L,
                null,
                AgentRun.Status.QUEUED,
                1L,
                null,
                null,
                null,
                com.gitnova.service.agent.AgentTestExecutionConfigs.minimal(
                        Set.of(AgentCapability.CODE_READ)
                ),
                "a".repeat(64),
                null,
                1L,
                now,
                null,
                null,
                null,
                now
        );
    }

    private static AgentRun runningRun(String workerId) {
        Instant now = Instant.parse("2026-09-06T08:00:00Z");
        return new AgentRun(
                "run-1",
                "session-1",
                "task-1",
                1L,
                null,
                AgentRun.Status.RUNNING,
                2L,
                workerId,
                now.plusSeconds(30),
                1L,
                com.gitnova.service.agent.AgentTestExecutionConfigs.minimal(
                        Set.of(AgentCapability.CODE_READ)
                ),
                "a".repeat(64),
                null,
                2L,
                now,
                now,
                now,
                null,
                now
        );
    }
}
