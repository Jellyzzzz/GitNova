package com.gitnova.service.agent.runtime;

import com.gitnova.service.agent.completion.AgentCompletionOutcome;
import com.gitnova.service.agent.model.ModelUsage;

import java.util.List;
import java.util.Objects;

/** Immutable outcome of one universal Agent Runtime attempt. */
public record AgentRunResult(
        AgentRunStatus status,
        AgentTerminationReason terminationReason,
        AgentCompletionOutcome completionOutcome,
        ProtocolDeviation lastProtocolDeviation,
        int modelCallCount,
        int toolCallCount,
        int successfulToolCallCount,
        List<ModelUsage> modelUsages
) {
    public AgentRunResult {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(terminationReason, "terminationReason must not be null");
        Objects.requireNonNull(modelUsages, "modelUsages must not be null");

        if (status == AgentRunStatus.COMPLETED) {
            Objects.requireNonNull(
                    completionOutcome,
                    "completed run must contain completionOutcome"
            );
            if (terminationReason != AgentTerminationReason.FINISH_SUCCEEDED) {
                throw new IllegalArgumentException(
                        "completed run must end with FINISH_SUCCEEDED"
                );
            }
        } else {
            if (completionOutcome != null) {
                throw new IllegalArgumentException(
                        "non-completed run must not contain completionOutcome"
                );
            }
            if (terminationReason == AgentTerminationReason.FINISH_SUCCEEDED) {
                throw new IllegalArgumentException(
                        "only a completed run can finalize successfully"
                );
            }
        }

        if (modelCallCount < 0
                || toolCallCount < 0
                || successfulToolCallCount < 0
                || successfulToolCallCount > toolCallCount) {
            throw new IllegalArgumentException("invalid call counters");
        }
        if (status == AgentRunStatus.PARTIAL && successfulToolCallCount == 0) {
            throw new IllegalArgumentException(
                    "partial run must contain at least one successful tool observation"
            );
        }
        if (status == AgentRunStatus.FAILED && successfulToolCallCount != 0) {
            throw new IllegalArgumentException(
                    "failed run must not claim successful tool observations"
            );
        }
        if (terminationReason == AgentTerminationReason.PROTOCOL_CORRECTION_EXHAUSTED
                && lastProtocolDeviation == null) {
            throw new IllegalArgumentException(
                    "protocol correction exhaustion must retain the last protocol deviation"
            );
        }
        modelUsages = List.copyOf(modelUsages);
    }
}
