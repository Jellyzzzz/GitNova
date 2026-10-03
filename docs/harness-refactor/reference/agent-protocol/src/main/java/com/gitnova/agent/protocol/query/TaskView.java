package com.gitnova.agent.protocol.query;

public record TaskView(String taskId, String sessionId, String attemptId,
 ExecutionStatus status, SettlementStatus settlementStatus, String terminalReason,
 Answer answer, PublicationStatus publicationStatus, String exportId, String publishedHead) {
 public record Answer(String content, String answerEventId, String modelResponseEventId) {}
 public enum ExecutionStatus { PENDING_DISPATCH, ACCEPTED, RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED, REJECTED }
 public enum SettlementStatus { NONE, PENDING_SEAL, SEALED, ARCHIVED, WAITING_ACK, SETTLED, BLOCKED }
 public enum PublicationStatus { NOT_REQUIRED, PENDING, BUILDING, PUBLISHED, NO_CHANGES, FAILED, CONFLICT, SKIPPED }
}
