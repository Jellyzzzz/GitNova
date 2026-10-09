package com.gitnova.entity.activity;
import java.time.LocalDateTime;
/** Mapping-only reference. UTC LocalDateTime; convert at API boundaries. No production service bodies. */
public final class DomainEventRows {
 private DomainEventRows() {}
 /** domain_event_outbox; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class OutboxRow {
  public String eventId;
  public Integer schemaVersion;
  public String type;
  public String scopeKey;
  public Long scopeSequence;
  public String causationEventId;
  public Long repoId;
  public Long actorId;
  public String subjectId;
  public LocalDateTime occurredAt;
  public String visibilityAtCreation;
  public String payloadJson;
  public String payloadHash;
  public String status;
  public String claimToken;
  public LocalDateTime leaseUntil;
  public Integer attempts;
  public LocalDateTime nextAttemptAt;
  public String errorCode;
  public LocalDateTime createdAt;
  public LocalDateTime publishedAt;
 }
 /** domain_event_receipt; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class ReceiptRow {
  public String consumerName;
  public String eventId;
  public String payloadHash;
  public LocalDateTime processedAt;
 }
 /** domain_event_failure; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class FailureRow {
  public String consumerName;
  public String eventId;
  public String payloadHash;
  public String envelopeJson;
  public String errorCode;
  public Integer attempts;
  public LocalDateTime nextAttemptAt;
  public String state;
  public LocalDateTime updatedAt;
 }
 /** activity_entry; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class ActivityRow {
  public Long id;
  public String eventId;
  public Long actorId;
  public Long repoId;
  public String subjectType;
  public String subjectId;
  public String type;
  public String visibilityAtCreation;
  public LocalDateTime occurredAt;
 }
 /** repo_counter; generated fields read-only. Existing Session columns require H0 verification. */
 public static final class RepoCounterRow {
  public Long repoId;
  public Long nextPrNumber;
  public Long eventSequence;
 }
}
