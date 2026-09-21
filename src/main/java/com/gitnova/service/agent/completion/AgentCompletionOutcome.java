package com.gitnova.service.agent.completion;

import com.gitnova.service.agent.runtime.ValidationEvidence;
import com.gitnova.service.agent.workspace.WorkspaceGateway;

import java.util.Objects;

/**
 * Accepted report with a canonical Workspace diff, not a certification of task success.
 *
 * <p>The draft remains model-authored. Optional validation records only an observed exit-zero
 * command at this generation; it proves neither test coverage nor the draft's semantic claims.
 * Complete command outcomes, including failures, are retained in TOOL_RESULT history.</p>
 */
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
            if (!canonicalDiff.files().isEmpty()) {
                throw new IllegalArgumentException(
                        "NO_CHANGES outcome must have an empty diff"
                );
            }
        } else {
            if (canonicalDiff.files().isEmpty()) {
                throw new IllegalArgumentException("CHANGES_READY outcome requires a non-empty diff");
            }
        }
        if (validation != null && validation.generation() != canonicalDiff.generation()) {
            throw new IllegalArgumentException(
                    "validation and canonical diff must bind the same generation"
            );
        }
    }
}
