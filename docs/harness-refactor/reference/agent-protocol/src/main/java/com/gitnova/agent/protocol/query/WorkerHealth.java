package com.gitnova.agent.protocol.query;

public record WorkerHealth(String sessionId, long runnerEpoch, State state,
 String activeTaskId, String activeAttemptId, long lastSequence, String publishedHead,
 String runtimeConfigDigest, String problemCode) {
 public enum State { BOOTING, INITIALIZING, IDLE, RUNNING, CANCELLING, FINALIZING,
 WAITING_SETTLEMENT, BLOCKED, STOPPING, STOPPED }
}
