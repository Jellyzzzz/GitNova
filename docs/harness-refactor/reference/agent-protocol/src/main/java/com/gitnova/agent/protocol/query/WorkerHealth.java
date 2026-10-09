package com.gitnova.agent.protocol.query;
/** Private control-plane data: never serialize this directly into the public Session response. */
public record WorkerHealth(String sessionId, String worklineId, long runnerEpoch, State state,
 String activeTaskId, String activeAttemptId, long lastSequence, String publishedHead,
 String runtimeConfigDigest, String problemCode) {
 public enum State { BOOTING, INITIALIZING, IDLE, RUNNING, CANCELLING, FINALIZING,
 QUIESCING, AWAITING_ARCHIVE, PARKED, BLOCKED, STOPPING, STOPPED }
}
