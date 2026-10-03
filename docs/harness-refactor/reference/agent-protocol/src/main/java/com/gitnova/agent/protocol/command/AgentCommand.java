package com.gitnova.agent.protocol.command;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Wire envelope. HttpJson decodes payload by type; never enable Jackson default typing. */
public record AgentCommand(int schemaVersion, String commandId, String sessionId,
 long runnerEpoch, CommandType type, String taskId, String attemptId, Payload payload) {
 public sealed interface Payload permits Initialize, Submit, Steer, Cancel, Preview, Seal, Ack, Close {}
 public record Initialize(String bootstrapId, String bootstrapSha256, String baseCommit,
   String publishedHead, String runtimeConfigDigest) implements Payload {}
 public record Submit(TaskMode mode, String message, String expectedPublishedHead, Instant deadlineAt,
   String runtimeConfigDigest) implements Payload {}
 public record Steer(String message) implements Payload {}
 public record Cancel(String reason) implements Payload {}
 public record Preview(String expectedPublishedHead) implements Payload {}
 public record Seal(SealPurpose purpose, String expectedActiveTaskId) implements Payload {}
 public record Ack(String exportId, String archiveId, String publicationId,
   AckOutcome outcome, String publishedHead) implements Payload {}
 public record Close(String confirmedArchiveId) implements Payload {}
 public enum SealPurpose { SUSPEND, CLOSE, MANUAL }
 public enum AckOutcome { PUBLISHED, NO_CHANGES, SKIPPED, NOT_REQUIRED }
}
