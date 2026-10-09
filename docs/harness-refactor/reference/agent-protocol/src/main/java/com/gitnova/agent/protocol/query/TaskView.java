package com.gitnova.agent.protocol.query;
import java.util.List;
import com.gitnova.agent.protocol.platform.PlatformOperation;
/** A task may publish zero, one, or several times. Infrastructure lifecycle is not a UI state. */
public record TaskView(String taskId, String sessionId, String worklineId, String attemptId, Long runnerEpoch,
 ExecutionStatus status, String terminalReason, Answer answer,
 List<PlatformOperation.Result> progresses, Boolean unpublishedChanges) {
 public record Answer(String content, String answerEventId, String modelResponseEventId) {}
 public enum ExecutionStatus { PENDING_DISPATCH, ACCEPTED, RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED, REJECTED }
}
