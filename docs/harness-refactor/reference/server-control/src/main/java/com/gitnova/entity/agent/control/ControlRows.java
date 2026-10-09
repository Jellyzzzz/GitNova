package com.gitnova.entity.agent.control;
import java.time.LocalDateTime;
/** Mapping-only reference. UTC LocalDateTime; convert at API boundaries. No production service bodies. */
public final class ControlRows {
 private ControlRows() {}
 /** agent_session; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class SessionRow {
  public String sessionId;
  public String creationIdempotencyKey;
  public Long createdByActorId;
  public Long repoId;
  public String repoKey;
  public String status;
  public Long version;
  public LocalDateTime createdAt;
  public LocalDateTime updatedAt;
  public LocalDateTime closedAt;
  public String executionBackend;
  public String activeTaskId;
  public String activeWorklineId;
  public Long sessionVersion;
  public String syncOperationId;
  public Long nextRunnerEpoch;
  public Long currentRunnerEpoch;
  public String workflowState;
  public String creationRequestDigest;
 }
 /** agent_sandbox_binding; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class BindingRow {
  public String sessionId;
  public String worklineId;
  public Long runnerEpoch;
  public String bootstrapId;
  public String bootstrapSha256;
  public String createOperationId;
  public String sandboxId;
  public String volumeName;
  public String status;
  public String tokenSalt;
  public String imageRef;
  public String runtimeConfigJson;
  public String runtimeConfigDigest;
  public Long lastArchivedSequence;
  public LocalDateTime expiresAt;
  public LocalDateTime lastObservedAt;
  public LocalDateTime lastUsedAt;
  public String operationOwner;
  public LocalDateTime operationUntil;
  public Long version;
  public String lastErrorCode;
  public LocalDateTime createdAt;
  public LocalDateTime updatedAt;
 }
 /** agent_control_task; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class TaskRow {
  public String taskId;
  public String sessionId;
  public String worklineId;
  public String idempotencyKey;
  public Long actorId;
  public String message;
  public String requestDigest;
  public String status;
  public String currentAttemptId;
  public Long attemptNumber;
  public String answerEventId;
  public String terminalReason;
  public Integer cancelRequested;
  public Long version;
  public LocalDateTime createdAt;
  public LocalDateTime updatedAt;
  public LocalDateTime finishedAt;
 }
 /** agent_control_attempt; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class AttemptRow {
  public String attemptId;
  public String taskId;
  public String sessionId;
  public String worklineId;
  public Long attemptNumber;
  public Long runnerEpoch;
  public String status;
  public String expectedPublishedHead;
  public LocalDateTime deadlineAt;
  public String configDigest;
  public String answerEventId;
  public String terminalReason;
  public LocalDateTime createdAt;
  public LocalDateTime finishedAt;
 }
 /** agent_control_command; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class CommandRow {
  public String commandId;
  public String sessionId;
  public String worklineId;
  public Long runnerEpoch;
  public String taskId;
  public String attemptId;
  public String type;
  public String envelopeJson;
  public String envelopeDigest;
  public String status;
  public String receiptJson;
  public Integer sendCount;
  public LocalDateTime nextAttemptAt;
  public String lastErrorCode;
  public LocalDateTime createdAt;
  public LocalDateTime updatedAt;
 }
 /** agent_event_archive; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class EventRow {
  public Long archiveOffset;
  public String eventId;
  public String sessionId;
  public String worklineId;
  public Long runnerEpoch;
  public Long sequence;
  public String taskId;
  public String attemptId;
  public String type;
  public String eventJson;
  public String eventDigest;
  public LocalDateTime occurredAt;
  public LocalDateTime archivedAt;
 }
 /** agent_snapshot_archive; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class ArchiveRow {
  public String archiveId;
  public String sessionId;
  public String worklineId;
  public String exportId;
  public Long runnerEpoch;
  public String taskId;
  public String attemptId;
  public Long throughSequence;
  public String treeDigest;
  public String stateDigest;
  public String bundleSha256;
  public Long bundleBytes;
  public String storageKey;
  public String manifestJson;
  public String executionOutcome;
  public String purpose;
  public String status;
  public String confirmedPublishedHead;
  public String checkpointCommandId;
  public LocalDateTime createdAt;
  public LocalDateTime updatedAt;
 }
 /** agent_platform_operation; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class PlatformOperationRow {
  public String operationId;
  public String sessionId;
  public String worklineId;
  public String taskId;
  public String attemptId;
  public Long runnerEpoch;
  public String toolCallId;
  public String type;
  public String requestJson;
  public String requestDigest;
  public String status;
  public String resultJson;
  public String errorCode;
  public Integer attemptCount;
  public LocalDateTime nextAttemptAt;
  public LocalDateTime createdAt;
  public LocalDateTime updatedAt;
 }
 /** agent_publication; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class PublicationRow {
  public String operationId;
  public String publicationId;
  public String sessionId;
  public String worklineId;
  public String exportId;
  public String archiveId;
  public String taskId;
  public Long branchId;
  public String branchName;
  public String expectedHead;
  public String treeDigest;
  public String message;
  public Long authorId;
  public Long commitEpochMillis;
  public String resultCommit;
  public String status;
  public String errorCode;
  public Long version;
  public LocalDateTime createdAt;
  public LocalDateTime updatedAt;
 }
}
