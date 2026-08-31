package com.gitnova.service.agent.execution;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Transactional aggregate boundary for Task, Run, Workspace ownership, Step and Outbox. */
public interface AgentTaskRunStore {
    CreateResult createTaskWithInitialRun(CreateTaskCommand command);

    ClaimResult claimRun(ClaimCommand command);

    HeartbeatResult heartbeat(HeartbeatCommand command);

    LeaseExpiryResult recordLeaseExpired(LeaseExpiryCommand command);

    TakeoverResult takeoverRun(TakeoverCommand command);

    TerminalResult terminateRun(TerminalCommand command);

    Optional<AgentTask> findTask(String taskId);

    Optional<AgentRun> findRun(String runId);

    List<AgentRun> findExpiredRuns(int limit);
    record CreateResult(AgentTask task, AgentRun initialRun, boolean created) {
        public CreateResult {
            Objects.requireNonNull(task, "task must not be null");
            Objects.requireNonNull(initialRun, "initialRun must not be null");
        }
    }

    record ClaimCommand(
            String eventId,
            String sessionId,
            String taskId,
            String runId,
            String workerId,
            int leaseSeconds
    ) {
        public ClaimCommand {
            validateOwnedRunCommand(eventId, sessionId, taskId, runId);
            requireNonBlank(workerId, "workerId");
            requireLeaseSeconds(leaseSeconds);
        }
    }

    enum ClaimDisposition {
        CLAIMED,
        ALREADY_CLAIMED,
        NOT_CLAIMABLE
    }

    record ClaimResult(ClaimDisposition disposition, AgentRun run) {
        public ClaimResult {
            Objects.requireNonNull(disposition, "disposition must not be null");
            if (disposition != ClaimDisposition.NOT_CLAIMABLE) {
                Objects.requireNonNull(run, "claimed Run must not be null");
            }
        }
    }

    record HeartbeatCommand(
            String runId,
            String workerId,
            long fencingToken,
            int leaseSeconds
    ) {
        public HeartbeatCommand {
            requireNonBlank(runId, "runId");
            requireNonBlank(workerId, "workerId");
            requirePositive(fencingToken, "fencingToken");
            requireLeaseSeconds(leaseSeconds);
        }
    }

    enum HeartbeatResult {
        EXTENDED,
        LEASE_LOST
    }

    record LeaseExpiryCommand(
            String eventId,
            String sessionId,
            String taskId,
            String runId,
            long expiredFencingToken
    ) {
        public LeaseExpiryCommand {
            validateOwnedRunCommand(eventId, sessionId, taskId, runId);
            requirePositive(expiredFencingToken, "expiredFencingToken");
        }
    }

    record LeaseExpiryResult(boolean recorded, AgentRun run) {
        public LeaseExpiryResult {
            if (recorded) {
                Objects.requireNonNull(run, "recorded expired Run must not be null");
            }
        }
    }

    record TakeoverCommand(
            String eventId,
            String sessionId,
            String taskId,
            String runId,
            String workerId,
            long expiredFencingToken,
            int leaseSeconds
    ) {
        public TakeoverCommand {
            validateOwnedRunCommand(eventId, sessionId, taskId, runId);
            requireNonBlank(workerId, "workerId");
            requirePositive(expiredFencingToken, "expiredFencingToken");
            requireLeaseSeconds(leaseSeconds);
        }
    }

    enum TakeoverDisposition {
        TAKEN_OVER,
        ALREADY_TAKEN_OVER,
        NOT_ELIGIBLE
    }

    record TakeoverResult(TakeoverDisposition disposition, AgentRun run) {
        public TakeoverResult {
            Objects.requireNonNull(disposition, "disposition must not be null");
            if (disposition != TakeoverDisposition.NOT_ELIGIBLE) {
                Objects.requireNonNull(run, "taken-over Run must not be null");
            }
        }
    }

    enum TerminalOutcome {
        COMPLETED,
        PARTIAL,
        FAILED,
        CANCELLED
    }

    record TerminalCommand(
            String runEventId,
            String taskEventId,
            String sessionId,
            String taskId,
            String runId,
            String workerId,
            long fencingToken,
            TerminalOutcome outcome,
            String terminationReason
    ) {
        public TerminalCommand {
            validateOwnedRunCommand(runEventId, sessionId, taskId, runId);
            requireNonBlank(taskEventId, "taskEventId");
            requireNonBlank(workerId, "workerId");
            requirePositive(fencingToken, "fencingToken");
            Objects.requireNonNull(outcome, "outcome must not be null");
            requireNonBlank(terminationReason, "terminationReason");
        }
    }

    record TerminalResult(AgentTask task, AgentRun run) {
        public TerminalResult {
            Objects.requireNonNull(task, "task must not be null");
            Objects.requireNonNull(run, "run must not be null");
        }
    }

    private static void validateOwnedRunCommand(
            String eventId,
            String sessionId,
            String taskId,
            String runId
    ) {
        requireNonBlank(eventId, "eventId");
        requireNonBlank(sessionId, "sessionId");
        requireNonBlank(taskId, "taskId");
        requireNonBlank(runId, "runId");
    }

    private static void requireLeaseSeconds(int leaseSeconds) {
        if (leaseSeconds <= 0 || leaseSeconds > 3600) {
            throw new IllegalArgumentException("leaseSeconds must be in range 1..3600");
        }
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
