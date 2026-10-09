package com.gitnova.agent.protocol.platform;
/** Durable identity for externally visible operations; all privileges are derived on Server. */
public final class PlatformOperation {
 private PlatformOperation() {}
 public enum Kind { REPORT_PROGRESS, CREATE_PULL_REQUEST }
 public enum State { PENDING, PROCESSING, SUCCEEDED, FAILED, REJECTED, CONFLICT }
 public enum Outcome { PUBLISHED, NO_CHANGES, PR_CREATED, PR_EXISTS }
 public sealed interface Payload permits ReportProgress, CreatePullRequest {}
 public record Request(int schemaVersion, String operationId, String sessionId, String worklineId,
   long runnerEpoch, String taskId, String attemptId, String toolCallId, Kind type, Payload payload) {}
 public record ReportProgress(String exportId, String bundleSha256, String treeDigest,
   String expectedHead, String message, String summary) implements Payload {}
 public record CreatePullRequest(String expectedSourceHead, String title, String body,
   boolean draft) implements Payload {}
 public record Result(String operationId, State state, Outcome outcome, String branch,
   String commit, String treeDigest, Long pullRequestId, String errorCode, String message) {}
}
