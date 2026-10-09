package com.gitnova.entity.social;
import java.time.LocalDateTime;
/** Mapping-only reference. UTC LocalDateTime; convert at API boundaries. No production service bodies. */
public final class SocialRows {
 private SocialRows() {}
 /** user_follow; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class FollowRow {
  public Long followerId;
  public Long followeeId;
  public LocalDateTime createdAt;
 }
 /** repository_star; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class StarRow {
  public Long userId;
  public Long repoId;
  public LocalDateTime createdAt;
 }
 /** repository_watch; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class WatchRow {
  public Long userId;
  public Long repoId;
  public String mode;
  public Boolean includePr;
  public Boolean includeComments;
  public Boolean includeBranchUpdates;
  public Long version;
  public LocalDateTime createdAt;
  public LocalDateTime updatedAt;
 }
 /** user_social_stats; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class UserStatsRow {
  public Long userId;
  public Long followingCount;
  public Long followersCount;
  public Long version;
 }
 /** repo_social_stats; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class RepoStatsRow {
  public Long repoId;
  public Long starCount;
  public Long version;
 }
}
