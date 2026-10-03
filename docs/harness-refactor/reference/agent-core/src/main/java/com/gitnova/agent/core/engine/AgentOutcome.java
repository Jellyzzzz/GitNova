package com.gitnova.agent.core.engine;

public record AgentOutcome(Status status, String reason, Answer answer) {
 public enum Status { COMPLETED, FAILED, CANCELLED, INTERRUPTED }
 public record Answer(String content, String modelCallId, String responseEventId, String answerEventId) {}
}
