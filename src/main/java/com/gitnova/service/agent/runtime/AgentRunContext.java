package com.gitnova.service.agent.runtime;

import com.gitnova.service.agent.workspace.DiffScope;
import com.gitnova.service.agent.workspace.RevisionScope;

import java.util.Objects;

public record AgentRunContext
    (
            String runId,
            Long repoId,
            String repoKey,
            RevisionScope revisionScope

    ) {
        public AgentRunContext {
            Objects.requireNonNull(runId, "runId must not be null");
            Objects.requireNonNull(repoId, "repoId must not be null");
            Objects.requireNonNull(repoKey, "repoKey must not be null");
            Objects.requireNonNull(revisionScope, "revisionScope must not be null");
            repoKey = repoKey.replace('\\', '/');

            if (runId.isBlank()) {
                throw new IllegalArgumentException(
                        "runId must not be blank"
                );
            }

            if (!repoKey.matches("\\d+/\\d+")) {
                throw new IllegalArgumentException(
                        "repoKey must match ownerId/repoId"
                );
            }
            String repoIdPart = repoKey.substring(repoKey.lastIndexOf('/') + 1);

            if (!repoIdPart.equals(repoId.toString())) {
                throw new IllegalArgumentException(
                        "repoKey repoId must match context repoId"
                );
            }
        }

    /** Compatibility constructor for existing Review call sites during the scope migration. */
    public AgentRunContext(
            String runId,
            Long repoId,
            String repoKey,
            String baseSha1,
            String targetSha1
    ) {
        this(runId, repoId, repoKey, DiffScope.of(baseSha1, targetSha1));
    }

}
