package com.gitnova.agent.protocol.command;

import java.time.Instant;

public record AgentCommand(
        int schemaVersion,
        String commandId,
        String sessionId,
        String worklineId,
        long runnerEpoch,
        CommandType type,
        String taskId,
        String attemptId,
        Payload payload
) {
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    public sealed interface Payload permits Initialize, Submit, Steer, Cancel,
            Preview, Checkpoint, CheckpointAck, StopWorker {}

    public record Initialize(
            String bootstrapId,
            String bootstrapSha256,
            String baseCommit,
            String publishedHead,
            String runtimeConfigDigest
    ) implements Payload {
        public Initialize {
            if (bootstrapId == null
                    || !bootstrapId.matches(UUID_PATTERN)) {
                throw new IllegalArgumentException("bootstrapId must be a canonical lowercase UUID");
            }
            if (bootstrapSha256 == null || !bootstrapSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("bootstrapSha256 must be a lowercase 64-hex digest");
            }
            if (baseCommit == null || !baseCommit.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("baseCommit must be a lowercase 40-hex SHA");
            }
            if (publishedHead == null || !publishedHead.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("publishedHead must be a lowercase 40-hex SHA");
            }
            if (runtimeConfigDigest == null || !runtimeConfigDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("runtimeConfigDigest must be a lowercase 64-hex digest");
            }
        }
    }

    public record Submit(
            String message,
            String expectedPublishedHead,
            Instant deadlineAt,
            String runtimeConfigDigest,
            String worklineStatus
    ) implements Payload {
        public Submit {
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("message must not be blank");
            }
            if (expectedPublishedHead == null || !expectedPublishedHead.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("expectedPublishedHead must be a lowercase 40-hex SHA");
            }
            // Expiration is checked when accepting/executing, not when replaying a historical command.
            if (deadlineAt == null) {
                throw new IllegalArgumentException("deadlineAt is required");
            }
            if (runtimeConfigDigest == null || !runtimeConfigDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("runtimeConfigDigest must be a lowercase 64-hex digest");
            }
            if (worklineStatus == null || worklineStatus.isBlank()) {
                throw new IllegalArgumentException("worklineStatus must not be blank");
            }
            if (!"ACTIVE".equals(worklineStatus)
                    && !"MERGED".equals(worklineStatus)
                    && !"SOURCE_MISSING".equals(worklineStatus)) {
                throw new IllegalArgumentException("worklineStatus must be ACTIVE, MERGED or SOURCE_MISSING");
            }
        }
    }

    public record Steer(String message) implements Payload {
        public Steer {
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("message must not be blank");
            }
        }
    }

    public record Cancel(String reason) implements Payload {
        public Cancel {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
        }
    }

    public record Preview(String expectedPublishedHead) implements Payload {
        public Preview {
            if (expectedPublishedHead == null || !expectedPublishedHead.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("expectedPublishedHead must be a lowercase 40-hex SHA");
            }
        }
    }

    public record Checkpoint(CheckpointPurpose purpose) implements Payload {
        public Checkpoint {
            if (purpose == null) {
                throw new IllegalArgumentException("purpose is required");
            }
        }
    }

    public record CheckpointAck(
            String exportId,
            String archiveId,
            String bundleSha256,
            long coveredThrough
    ) implements Payload {
        public CheckpointAck {
            if (exportId == null
                    || !exportId.matches(UUID_PATTERN)) {
                throw new IllegalArgumentException("exportId must be a canonical lowercase UUID");
            }
            if (archiveId == null
                    || !archiveId.matches(UUID_PATTERN)) {
                throw new IllegalArgumentException("archiveId must be a canonical lowercase UUID");
            }
            if (bundleSha256 == null || !bundleSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("bundleSha256 must be a lowercase 64-hex digest");
            }
            if (coveredThrough < 0) {
                throw new IllegalArgumentException("coveredThrough must not be negative");
            }
        }
    }

    public record StopWorker(String confirmedArchiveId) implements Payload {
        public StopWorker {
            if (confirmedArchiveId == null
                    || !confirmedArchiveId.matches(UUID_PATTERN)) {
                throw new IllegalArgumentException("confirmedArchiveId must be a canonical lowercase UUID");
            }
        }
    }

    public enum CheckpointPurpose {
        IDLE_RECLAIM, MAINTENANCE
    }

    public AgentCommand {
        if (schemaVersion != 2) throw new IllegalArgumentException("schemaVersion must be 2");
        if (runnerEpoch <= 0) throw new IllegalArgumentException("runnerEpoch must be positive");
        if (commandId == null || !commandId.matches(UUID_PATTERN)) {
            throw new IllegalArgumentException("commandId must be a canonical lowercase UUID");
        }
        if (sessionId == null || !sessionId.matches(UUID_PATTERN)) {
            throw new IllegalArgumentException("sessionId must be a canonical lowercase UUID");
        }
        if (worklineId == null || !worklineId.matches(UUID_PATTERN)) {
            throw new IllegalArgumentException("worklineId must be a canonical lowercase UUID");
        }

        if (type == null) throw new IllegalArgumentException("type must not be null");
        if (payload == null) throw new IllegalArgumentException("payload must not be null");
        if (type == CommandType.INITIALIZE && !(payload instanceof Initialize)) throw new IllegalArgumentException("INITIALIZE must have Initialize payload");
        if (type == CommandType.SUBMIT_TASK && !(payload instanceof Submit)) throw new IllegalArgumentException("SUBMIT_TASK must have Submit payload");
        if (type == CommandType.STEER_TASK && !(payload instanceof Steer)) throw new IllegalArgumentException("STEER_TASK must have Steer payload");
        if (type == CommandType.CANCEL_TASK && !(payload instanceof Cancel)) throw new IllegalArgumentException("CANCEL_TASK must have Cancel payload");
        if (type == CommandType.PREVIEW_CHANGES && !(payload instanceof Preview)) throw new IllegalArgumentException("PREVIEW_CHANGES must have Preview payload");
        if (type == CommandType.CHECKPOINT_SESSION && !(payload instanceof Checkpoint)) throw new IllegalArgumentException("CHECKPOINT_SESSION must have Checkpoint payload");
        if (type == CommandType.ACK_CHECKPOINT && !(payload instanceof CheckpointAck)) throw new IllegalArgumentException("ACK_CHECKPOINT must have CheckpointAck payload");
        if (type == CommandType.STOP_WORKER && !(payload instanceof StopWorker)) throw new IllegalArgumentException("STOP_WORKER must have StopWorker payload");

        boolean taskCommand = type == CommandType.SUBMIT_TASK
                || type == CommandType.STEER_TASK || type == CommandType.CANCEL_TASK;
        if (taskCommand) {
            if (taskId == null || !taskId.matches(UUID_PATTERN)) {
                throw new IllegalArgumentException("taskId must be a canonical lowercase UUID");
            }
            if (attemptId == null || !attemptId.matches(UUID_PATTERN)) {
                throw new IllegalArgumentException("attemptId must be a canonical lowercase UUID");
            }
        } else if (taskId != null || attemptId != null) {
            throw new IllegalArgumentException("taskId and attemptId must be null for non-task commands");
        }
    }
}
