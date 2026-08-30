package com.gitnova.service.agent.dispatch;

import com.gitnova.entity.agent.AgentOutboxEntity;
import com.gitnova.mapper.agent.AgentOutboxMapper;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublisher {
    private static final int BATCH_SIZE = 100;
    private static final long POLL_DELAY_MILLIS = 1_000;
    private static final int CONFIRM_TIMEOUT_SECONDS = 5;
    private static final int RETRY_DELAY_SECONDS = 5;

    private final AgentOutboxMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;

    public OutboxPublisher(
            AgentOutboxMapper outboxMapper,
            RabbitTemplate rabbitTemplate
    ) {
        this.outboxMapper = outboxMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelay = POLL_DELAY_MILLIS)
    public void publishPending() {
        List<AgentOutboxEntity> events = outboxMapper.findPublishable(BATCH_SIZE);
        for (AgentOutboxEntity event : events) {
            publishOne(event);
        }
    }

    private void publishOne(AgentOutboxEntity event) {
        CorrelationData correlationData = new CorrelationData(event.getEventId());
        RunDispatchMessage message = new RunDispatchMessage(
                event.getEventId(),
                event.getAggregateId()
        );
        try {
            rabbitTemplate.convertAndSend(
                    AgentRabbitConfiguration.RUN_EXCHANGE,
                    AgentRabbitConfiguration.RUN_ROUTING_KEY,
                    message,
                    correlationData
            );
        } catch (AmqpException exception) {
            recordFailure(event);
            return;
        }

        correlationData.getFuture()
                .orTimeout(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((confirm, error) -> {
                    if (error != null) {
                        recordFailure(event);
                        return;
                    }
                    if (!confirm.isAck()) {
                        recordFailure(event);
                        return;
                    }
                    if (correlationData.getReturned() != null) {
                        recordFailure(event);
                        return;
                    }
                    outboxMapper.markPublished(event.getOutboxId());
                });
    }

    private void recordFailure(AgentOutboxEntity event) {
        LocalDateTime nextAvailableAt =
                LocalDateTime.now(ZoneOffset.UTC).plusSeconds(RETRY_DELAY_SECONDS);

        outboxMapper.recordFailure(
                event.getOutboxId(),
                nextAvailableAt
        );
    }
}
