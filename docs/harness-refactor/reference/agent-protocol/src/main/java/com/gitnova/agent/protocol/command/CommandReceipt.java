package com.gitnova.agent.protocol.command;

public record CommandReceipt(String commandId, String requestDigest, ReceiptStatus status,
 String sessionId, String worklineId, long runnerEpoch, String taskId, String attemptId,
 Long acceptedSequence, boolean duplicate, String code, String message) {
 public enum ReceiptStatus { ACCEPTED, REJECTED }
}
