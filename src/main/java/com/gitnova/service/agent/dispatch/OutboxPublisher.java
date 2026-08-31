package com.gitnova.service.agent.dispatch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.entity.agent.AgentOutboxEntity;
import com.gitnova.mapper.agent.AgentOutboxMapper;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublisher {
    private static final Logger logger = LoggerFactory.getLogger(
            OutboxPublisher.class
    );
    private static final int BATCH_SIZE = 100;
    private static final long POLL_DELAY_MILLIS = 1_000;
    private static final int CONFIRM_TIMEOUT_SECONDS = 5;
    private static final int RETRY_DELAY_SECONDS = 5;
    private final ObjectMapper objectMapper;
    private final AgentOutboxMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;

    public OutboxPublisher(
            ObjectMapper objectMapper,
            AgentOutboxMapper outboxMapper,
            RabbitTemplate rabbitTemplate
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.outboxMapper = Objects.requireNonNull(outboxMapper, "outboxMapper");
        this.rabbitTemplate = Objects.requireNonNull(
                rabbitTemplate,
                "rabbitTemplate"
        );
    }

    @Scheduled(fixedDelay = POLL_DELAY_MILLIS)
    public void publishPending() {
        List<AgentOutboxEntity> events = outboxMapper.findPublishable(BATCH_SIZE);
        for (AgentOutboxEntity event : events) {
            RunDispatchMessage message;
            try {
                DispatchPayload payload = objectMapper.readValue(
                        event.getPayloadJson(),
                        DispatchPayload.class
                );
                message = new RunDispatchMessage(
                        event.getEventId(),
                        payload.runId(),
                        payload.reason(),
                        payload.expiredFencingToken()
                );
            } catch (JsonProcessingException
                     | IllegalArgumentException
                     | NullPointerException exception) {
                quarantine(event, "INVALID_DISPATCH_PAYLOAD", exception);
                continue;
            }
            if (!event.getAggregateId().equals(message.runId())) {
                quarantine(event, "DISPATCH_AGGREGATE_MISMATCH", null);
                continue;
            }
            publishOne(event, message);
        }
    }

    private void publishOne(AgentOutboxEntity event, RunDispatchMessage message) {
        CorrelationData correlationData = new CorrelationData(event.getEventId());
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

    private void quarantine(
            AgentOutboxEntity event,
            String errorCode,
            Throwable exception
    ) {
        outboxMapper.markFailed(event.getOutboxId(), errorCode);
        if (exception == null) {
            logger.error(
                    "Quarantined invalid Run dispatch: outboxId={}, eventId={}, errorCode={}",
                    event.getOutboxId(),
                    event.getEventId(),
                    errorCode
            );
        } else {
            logger.error(
                    "Quarantined invalid Run dispatch: outboxId={}, eventId={}, errorCode={}",
                    event.getOutboxId(),
                    event.getEventId(),
                    errorCode,
                    exception
            );
        }
    }

    private record DispatchPayload(
            RunDispatchReason reason,
            String runId,
            Long expiredFencingToken
    ) {
    }
}
