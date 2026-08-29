package com.gitnova.entity.agent;

import lombok.Data;

import java.time.LocalDateTime;

/** Durable intent to publish one idempotent integration event. */
@Data
public class AgentOutboxEntity {
    private Long outboxId;
    private String eventId;
    private String eventDigest;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private String payloadJson;
    private String payloadDigest;
    private String status;
    private Integer attemptCount;
    private LocalDateTime availableAt;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
