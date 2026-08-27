package com.gitnova.service.agent.persistence;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.Optional;

/**
 * Shared append-only event boundary used by Session, Task, Run and Tool persistence.
 *
 * <p>The current Session milestone supports Session-level events. Run-local sequence
 * allocation is added when the Run aggregate is introduced without changing this contract.</p>
 */
public interface AgentEventAppender {

    AppendResult append(AppendCommand command);

    record AppendCommand(
            String eventId,
            String sessionId,
            String taskId,
            String runId,
            AgentStepType stepType,
            int schemaVersion,
            JsonNode persistedPayload,
            String causationEventId,
            String correlationId,
            Long workspaceEpoch,
            Long workspaceGeneration
    ) {
        public AppendCommand {
            requireNonBlank(eventId, "eventId");
            requireNonBlank(sessionId, "sessionId");
            Objects.requireNonNull(stepType, "stepType must not be null");
            Objects.requireNonNull(persistedPayload, "persistedPayload must not be null");
            if (schemaVersion <= 0) {
                throw new IllegalArgumentException("schemaVersion must be positive");
            }
            requireOptionalNonBlank(taskId, "taskId");
            requireOptionalNonBlank(runId, "runId");
            requireOptionalNonBlank(causationEventId, "causationEventId");
            requireOptionalNonBlank(correlationId, "correlationId");
            if ((workspaceEpoch == null) != (workspaceGeneration == null)) {
                throw new IllegalArgumentException(
                        "workspaceEpoch and workspaceGeneration must either both be present or both be absent"
                );
            }
            if (workspaceEpoch != null && (workspaceEpoch < 0 || workspaceGeneration < 0)) {
                throw new IllegalArgumentException("Workspace coordinates must not be negative");
            }
            persistedPayload = persistedPayload.deepCopy();
        }

        public static AppendCommand sessionEvent(
                String eventId,
                String sessionId,
                AgentStepType stepType,
                JsonNode persistedPayload,
                Long workspaceEpoch,
                Long workspaceGeneration
        ) {
            return new AppendCommand(
                    eventId,
                    sessionId,
                    null,
                    null,
                    stepType,
                    1,
                    persistedPayload,
                    null,
                    sessionId,
                    workspaceEpoch,
                    workspaceGeneration
            );
        }
    }

    record AppendResult(
            long stepId,
            long sessionSequence,
            Long runStepSequence,
            boolean alreadyCommitted
    ) {
        public AppendResult {
            if (stepId <= 0 || sessionSequence <= 0) {
                throw new IllegalArgumentException("Persisted Step identities must be positive");
            }
            if (runStepSequence != null && runStepSequence <= 0) {
                throw new IllegalArgumentException("runStepSequence must be positive when present");
            }
        }
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireOptionalNonBlank(String value, String field) {
        Optional.ofNullable(value).ifPresent(actual -> requireNonBlank(actual, field));
    }
}
