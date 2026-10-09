package com.gitnova.entity.notification;
import java.time.LocalDateTime;
/** Mapping-only reference. UTC LocalDateTime; convert at API boundaries. No production service bodies. */
public final class NotificationRows {
 private NotificationRows() {}
 /** user_notification; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class NotificationRow {
  public Long id;
  public String eventId;
  public Long recipientId;
  public String channel;
  public Long repoId;
  public String subjectType;
  public String subjectId;
  public String type;
  public LocalDateTime createdAt;
  public LocalDateTime readAt;
  public Long version;
 }
}
