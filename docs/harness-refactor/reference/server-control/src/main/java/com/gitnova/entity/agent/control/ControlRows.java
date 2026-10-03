package com.gitnova.entity.agent.control;

import java.time.LocalDateTime;

/** Mapper-only rows. UTC conversion belongs at the boundary. */
public final class ControlRows {
 private ControlRows() {}
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
  public String sourceBranchName;
  public String baseCommit;
  public String agentBranchName;
  public String activeTaskId;
  public String lastPublishedHead;
  public String latestArchiveId;
  public String workflowState;
  public Long currentRunnerEpoch;
  public String creationRequestDigest;
 }
 public static final class BindingRow {
  public String sessionId;
  public Long runnerEpoch;
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
 public static final class TaskRow {
  public String taskId;
  public String sessionId;
  public String idempotencyKey;
  public Long actorId;
  public String taskMode;
  public String message;
  public String requestDigest;
  public String status;
  public String settlementStatus;
  public String publicationStatus;
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
 public static final class AttemptRow {
  public String attemptId;
  public String taskId;
  public String sessionId;
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
 public static final class CommandRow {
  public String commandId;
  public String sessionId;
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
 public static final class EventRow {
  public Long archiveOffset;
  public String eventId;
  public String sessionId;
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
 public static final class ArchiveRow {
  public String archiveId;
  public String sessionId;
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
  public String status;
  public String settledPublishedHead;
  public String settledOutcome;
  public String settlementCommandId;
  public LocalDateTime createdAt;
  public LocalDateTime updatedAt;
 }
 public static final class PublicationRow {
  public String publicationId;
  public String sessionId;
  public String exportId;
  public String archiveId;
  public String taskId;
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
 public static final class PullRequestRow {
  public Long id;
  public Long repoId;
  public String sourceBranch;
  public String targetBranch;
  public Long authorId;
  public String agentSessionId;
  public String title;
  public String body;
  public String status;
  public Integer draft;
  public Long revision;
  public Integer openSlot;
  public String mergedFromHead;
  public String mergedCommit;
  public String mergeMethod;
  public LocalDateTime createdAt;
  public LocalDateTime updatedAt;
  public LocalDateTime mergedAt;
 }
 public static final class CommentRow {
  public Long id;
  public Long prId;
  public Long authorId;
  public String idempotencyKey;
  public String body;
  public String sourceHead;
  public LocalDateTime createdAt;
 }
 public static final class MergeRow {
  public String id;
  public Long prId;
  public String idempotencyKey;
  public String requestDigest;
  public Long actorId;
  public Long expectedRevision;
  public String sourceHead;
  public String targetHead;
  public String method;
  public Long commitEpochMillis;
  public String message;
  public String candidateCommit;
  public String status;
  public String errorJson;
  public LocalDateTime createdAt;
  public LocalDateTime updatedAt;
 }
}
