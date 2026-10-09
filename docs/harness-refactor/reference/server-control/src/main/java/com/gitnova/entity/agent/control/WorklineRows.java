package com.gitnova.entity.agent.control;
import java.time.LocalDateTime;
/** Mapping-only reference. UTC LocalDateTime; convert at API boundaries. No production service bodies. */
public final class WorklineRows {
 private WorklineRows() {}
 /** agent_workline; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class WorklineRow {
  public String worklineId;
  public String sessionId;
  public Long ordinal;
  public Long repoId;
  public String baseCommit;
  public String publishedHead;
  public Long sourceBranchId;
  public String sourceBranchName;
  public Long targetBranchId;
  public String targetBranchName;
  public String status;
  public String latestRecoveryArchiveId;
  public Long mergedPrId;
  public Long version;
  public LocalDateTime createdAt;
  public LocalDateTime updatedAt;
 }
 /** agent_sync_operation; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class SyncRow {
  public String syncId;
  public String sessionId;
  public Long actorId;
  public String requestKey;
  public String requestDigest;
  public String oldWorklineId;
  public Long expectedSessionVersion;
  public Long targetBranchId;
  public String targetHead;
  public String changePolicy;
  public String expectedTreeDigest;
  public String oldArchiveId;
  public String oldTreeDigest;
  public String oldPublishedHead;
  public Long oldEpoch;
  public String bootstrapId;
  public String bootstrapSha256;
  public String bootstrapStorageKey;
  public String newWorklineId;
  public Long newEpoch;
  public String state;
  public String errorCode;
  public LocalDateTime createdAt;
  public LocalDateTime updatedAt;
 }
}
