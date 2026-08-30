package com.gitnova.service.agent.dispatch;

import com.gitnova.entity.agent.AgentOutboxEntity;
import com.gitnova.mapper.agent.AgentOutboxMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    AgentOutboxMapper outboxMapper;

    @Mock
    RabbitTemplate rabbitTemplate;

    @Test
    void shouldMarkTheRowPublishedOnlyAfterBrokerAck() {
        AgentOutboxEntity event = event();
        AtomicReference<CorrelationData> sentCorrelation = new AtomicReference<>();
        when(outboxMapper.findPublishable(100)).thenReturn(List.of(event));
        doAnswer(invocation -> {
            sentCorrelation.set(invocation.getArgument(3));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(AgentRabbitConfiguration.RUN_EXCHANGE),
                eq(AgentRabbitConfiguration.RUN_ROUTING_KEY),
                eq(new RunDispatchMessage("dispatch-1", "run-1")),
                any(CorrelationData.class)
        );

        publisher().publishPending();

        assertEquals("dispatch-1", sentCorrelation.get().getId());
        sentCorrelation.get().getFuture().complete(new CorrelationData.Confirm(true, null));
        verify(outboxMapper).markPublished(17L);
        verify(outboxMapper, never()).recordFailure(any(Long.class), any(LocalDateTime.class));
    }

    @Test
    void shouldBackOffWhenRabbitRejectsTheSendSynchronously() {
        AgentOutboxEntity event = event();
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
        when(outboxMapper.findPublishable(100)).thenReturn(List.of(event));
        doThrow(new AmqpException("RabbitMQ is unavailable"))
                .when(rabbitTemplate)
                .convertAndSend(
                        eq(AgentRabbitConfiguration.RUN_EXCHANGE),
                        eq(AgentRabbitConfiguration.RUN_ROUTING_KEY),
                        any(RunDispatchMessage.class),
                        any(CorrelationData.class)
                );

        publisher().publishPending();

        ArgumentCaptor<LocalDateTime> nextAttempt =
                ArgumentCaptor.forClass(LocalDateTime.class);
        verify(outboxMapper).recordFailure(eq(17L), nextAttempt.capture());
        verify(outboxMapper, never()).markPublished(17L);
        assertTrue(nextAttempt.getValue().isAfter(before.plusSeconds(4)));
    }

    @Test
    void shouldBackOffWhenTheBrokerNacksThePublish() {
        AgentOutboxEntity event = event();
        AtomicReference<CorrelationData> sentCorrelation = new AtomicReference<>();
        when(outboxMapper.findPublishable(100)).thenReturn(List.of(event));
        doAnswer(invocation -> {
            sentCorrelation.set(invocation.getArgument(3));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(AgentRabbitConfiguration.RUN_EXCHANGE),
                eq(AgentRabbitConfiguration.RUN_ROUTING_KEY),
                any(RunDispatchMessage.class),
                any(CorrelationData.class)
        );

        publisher().publishPending();
        sentCorrelation.get().getFuture().complete(new CorrelationData.Confirm(false, "rejected"));

        verify(outboxMapper).recordFailure(eq(17L), any(LocalDateTime.class));
        verify(outboxMapper, never()).markPublished(17L);
    }

    @Test
    void shouldBackOffWhenMandatoryDeliveryIsReturned() {
        AgentOutboxEntity event = event();
        AtomicReference<CorrelationData> sentCorrelation = new AtomicReference<>();
        when(outboxMapper.findPublishable(100)).thenReturn(List.of(event));
        doAnswer(invocation -> {
            sentCorrelation.set(invocation.getArgument(3));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(AgentRabbitConfiguration.RUN_EXCHANGE),
                eq(AgentRabbitConfiguration.RUN_ROUTING_KEY),
                any(RunDispatchMessage.class),
                any(CorrelationData.class)
        );

        publisher().publishPending();
        sentCorrelation.get().setReturned(new ReturnedMessage(
                new Message(new byte[0]),
                312,
                "NO_ROUTE",
                AgentRabbitConfiguration.RUN_EXCHANGE,
                AgentRabbitConfiguration.RUN_ROUTING_KEY
        ));
        sentCorrelation.get().getFuture().complete(new CorrelationData.Confirm(true, null));

        verify(outboxMapper).recordFailure(eq(17L), any(LocalDateTime.class));
        verify(outboxMapper, never()).markPublished(17L);
    }

    private OutboxPublisher publisher() {
        return new OutboxPublisher(outboxMapper, rabbitTemplate);
    }

    private AgentOutboxEntity event() {
        AgentOutboxEntity event = new AgentOutboxEntity();
        event.setOutboxId(17L);
        event.setEventId("dispatch-1");
        event.setAggregateId("run-1");
        return event;
    }
}
