package com.gitnova.agent.protocol.command;
import java.time.Instant;
/** Server-to-Worker controls. Repository operations use a different channel. */
public record AgentCommand(int schemaVersion, String commandId, String sessionId, String worklineId,
 long runnerEpoch, CommandType type, String taskId, String attemptId, Payload payload) {
 public sealed interface Payload permits Initialize, Submit, Steer, Cancel, Preview, Checkpoint, CheckpointAck, StopWorker {}
 public record Initialize(String bootstrapId, String bootstrapSha256, String baseCommit,
   String publishedHead, String runtimeConfigDigest) implements Payload {}
 public record Submit(String message, String expectedPublishedHead, Instant deadlineAt,
   String runtimeConfigDigest, String worklineStatus) implements Payload {}
 public record Steer(String message) implements Payload {}
 public record Cancel(String reason) implements Payload {}
 public record Preview(String expectedPublishedHead) implements Payload {}
 public record Checkpoint(CheckpointPurpose purpose) implements Payload {}
 public record CheckpointAck(String exportId, String archiveId, String bundleSha256,
   long coveredThrough) implements Payload {}
 public record StopWorker(String confirmedArchiveId) implements Payload {}
 public enum CheckpointPurpose { IDLE_RECLAIM, MAINTENANCE }
}
