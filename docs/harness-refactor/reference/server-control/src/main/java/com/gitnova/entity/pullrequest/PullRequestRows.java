package com.gitnova.entity.pullrequest;
import java.time.LocalDateTime;
/** Mapping-only reference. UTC LocalDateTime; convert at API boundaries. No production service bodies. */
public final class PullRequestRows {
 private PullRequestRows() {}
 /** pull_request; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class PullRequestRow {
  public Long id;
  public Long repoId;
  public Long number;
  public Long authorId;
  public Long sourceBranchId;
  public Long targetBranchId;
  public String sourceBranchName;
  public String targetBranchName;
  public String title;
  public String body;
  public String status;
  public Boolean draft;
  public Long version;
  public Long createdRepoSequence;
  public Long closedRepoSequence;
  public Long mergedRepoSequence;
  public String closedSourceHead;
  public String closedTargetHead;
  public String closedComparisonBase;
  public String sourceWorklineId;
  public String mergedFromHead;
  public String mergedTargetHead;
  public String mergedCommit;
  public String createRequestKey;
  public String createRequestDigest;
  public LocalDateTime createdAt;
  public LocalDateTime updatedAt;
  public LocalDateTime mergedAt;
  public Integer openSlot; // generated/read-only
 }
 /** pull_request_revision; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class RevisionRow {
  public Long id;
  public Long prId;
  public Long sourceBranchId;
  public Long repoSequence;
  public String sourceHead;
  public String targetHeadSeen;
  public String comparisonBase;
  public String sourceEventId;
  public LocalDateTime occurredAt;
 }
 /** pull_request_comment; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class CommentRow {
  public Long id;
  public Long prId;
  public Long authorId;
  public String body;
  public Long version;
  public String requestKey;
  public String requestDigest;
  public Long replyTo;
  public Long threadRoot;
  public Boolean resolved;
  public LocalDateTime deletedAt;
  public String comparisonBase;
  public String sourceHead;
  public String path;
  public String side;
  public Integer startLine;
  public Integer endLine;
  public LocalDateTime createdAt;
  public LocalDateTime updatedAt;
 }
 /** pull_request_merge; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class MergeRow {
  public String mergeId;
  public Long prId;
  public Long actorId;
  public String requestDigest;
  public Long expectedPrVersion;
  public String sourceHead;
  public String targetHead;
  public String mergeBase;
  public String strategy;
  public String fixedMessage;
  public LocalDateTime fixedTimestamp;
  public String candidateCommit;
  public String state;
  public String errorCode;
  public LocalDateTime createdAt;
  public LocalDateTime updatedAt;
 }
 /** pull_request_mutation; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class MutationRow {
  public String mutationId;
  public Long prId;
  public Long actorId;
  public String requestKey;
  public String requestDigest;
  public String action;
  public String resultJson;
  public LocalDateTime createdAt;
 }
}
