package com.gitnova.service.agent.completion;

import com.gitnova.service.agent.runtime.ValidationEvidence;
import com.gitnova.service.agent.workspace.WorkspaceGateway;

import java.util.Objects;

/** Canonical server-owned completion state derived from the current Workspace. */
public record AgentCompletionOutcome(
        CompletionDisposition disposition,
        AgentCompletionDraft draft,
        WorkspaceGateway.WorkspaceDiff canonicalDiff,
        ValidationEvidence validation
) {
    public AgentCompletionOutcome {
        Objects.requireNonNull(disposition, "disposition must not be null");
        Objects.requireNonNull(draft, "draft must not be null");
        Objects.requireNonNull(canonicalDiff, "canonicalDiff must not be null");
        if (draft.expectedGeneration() != canonicalDiff.generation()) {
            throw new IllegalArgumentException(
                    "completion draft and canonical diff must bind the same generation"
            );
        }

        if (disposition == CompletionDisposition.NO_CHANGES) {
            if (!canonicalDiff.files().isEmpty() || validation != null) {
                throw new IllegalArgumentException(
                        "NO_CHANGES outcome must have an empty diff and no required validation"
                );
            }
        } else {
            Objects.requireNonNull(validation, "CHANGES_READY outcome requires validation");
            if (canonicalDiff.files().isEmpty()) {
                throw new IllegalArgumentException("CHANGES_READY outcome requires a non-empty diff");
            }
            if (validation.generation() != canonicalDiff.generation()) {
                throw new IllegalArgumentException(
                        "validation and canonical diff must bind the same generation"
                );
            }
        }
    }
}
