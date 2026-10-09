package com.gitnova.agent.protocol.event;

import java.time.Instant;
import java.util.Map;
/** Payload is validated against EventType on ingest; provider tool outputs may be heterogeneous. */
public record AgentEvent(int schemaVersion, String sessionId, String worklineId, long runnerEpoch, long sequence,
 String eventId, String taskId, String attemptId, EventType type, Instant occurredAt,
 Map<String,Object> payload) {}
