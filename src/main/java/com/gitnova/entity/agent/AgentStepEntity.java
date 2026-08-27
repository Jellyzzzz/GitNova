package com.gitnova.entity.agent;

import lombok.Data;

import java.time.LocalDateTime;

/** Append-only durable Agent Step row. */
@Data
public class AgentStepEntity {
    private Long stepId;
    private String eventId;
    private String eventDigest;
    private String sessionId;
    private Long sessionSequence;
    private String taskId;
    private String runId;
    private Long runStepSequence;
    private String stepType;
    private Integer schemaVersion;
    private String payloadJson;
    private String persistedPayloadDigest;
    private String causationEventId;
    private String correlationId;
    private Long workspaceEpoch;
    private Long workspaceGeneration;
    private LocalDateTime createdAt;
}
