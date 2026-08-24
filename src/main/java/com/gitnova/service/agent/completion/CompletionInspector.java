package com.gitnova.service.agent.completion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.service.agent.runtime.AgentExecutionContext;
import com.gitnova.service.agent.runtime.RunStateView;
import com.gitnova.service.agent.runtime.ValidationEvidence;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.agent.workspace.WorkspaceId;

import java.util.Objects;
import java.util.Set;

import static java.util.stream.Collectors.toUnmodifiableSet;

public final class CompletionInspector {
    private final ObjectMapper objectMapper;
    private final WorkspaceGateway workspaceGateway;

    public CompletionInspector(ObjectMapper objectMapper, WorkspaceGateway workspaceGateway) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.workspaceGateway = Objects.requireNonNull(
                workspaceGateway,
                "workspaceGateway must not be null"
        );
    }

    public CompletionDecision inspect(
            AgentExecutionContext context,
            RunStateView state,
            ToolResult finishResult
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(finishResult, "finishResult must not be null");
        if (!finishResult.successful()) {
            return CompletionDecision.rejected(
                    "Completion inspection requires a successful finishTask result"
            );
        }

        AgentCompletionDraft draft;
        try {
            draft = objectMapper.treeToValue(
                    finishResult.payload(),
                    AgentCompletionDraft.class
            );
        } catch (Exception exception) {
            return CompletionDecision.rejected(
                    "finishTask returned a malformed completion payload"
            );
        }

        WorkspaceId workspaceId = context.workspace().workspaceId();
        WorkspaceGateway.WorkspaceRefresh refresh = workspaceGateway.refreshWorkspace(workspaceId);
        long currentGeneration = refresh.generationAfter();

        if (draft.expectedGeneration()
                != currentGeneration) {
            return correctable(
                    "Workspace changed. Re-inspect the Workspace "
                            + "and finish using generation "
                            + currentGeneration
            );
        }
        WorkspaceGateway.WorkspaceDiff diff = workspaceGateway.getWorkspaceDiff(workspaceId);
        if (diff.generation() != currentGeneration) {
            return correctable(
                    "Workspace changed during completion inspection"
            );
        }

        WorkspaceGateway.WorkspaceRefresh finalRefresh =
                workspaceGateway.refreshWorkspace(workspaceId);
        if (finalRefresh.generationAfter() != currentGeneration || finalRefresh.changed()) {
            return correctable("Workspace changed during completion inspection");
        }

        Set<String> actualFiles = diff.files().stream()
                .map(WorkspaceGateway.DiffFile::filePath)
                .collect(toUnmodifiableSet());
        Set<String> claimedFiles = Set.copyOf(draft.claimedChangedFiles());
        if (!actualFiles.equals(claimedFiles)) {
            return correctable(
                    "claimedChangedFiles does not match "
                            + "the canonical Workspace diff"
            );
        }

        if (diff.files().isEmpty()) {
            return CompletionDecision.accepted(
                    new AgentCompletionOutcome(
                            CompletionDisposition.NO_CHANGES,
                            draft,
                            diff,
                            null
                    )
            );
        }
        ValidationEvidence validation = state.latestSuccessfulValidation().orElse(null);
        if (validation == null) {
            return correctable(
                    "Workspace changes require a successful validation"
            );
        }

        if (validation.generation()
                != currentGeneration) {
            return correctable(
                    "The latest validation belongs to a stale generation"
            );
        }

        boolean validationClaimed = draft.claimedValidations().stream()
                .anyMatch(claim -> claim.argv().equals(validation.argv()));
        if (!validationClaimed) {
            return correctable(
                    "claimedValidations must include the latest successful validation command"
            );
        }

        return CompletionDecision.accepted(
                new AgentCompletionOutcome(
                        CompletionDisposition.CHANGES_READY,
                        draft,
                        diff,
                        validation
                )
        );

    }
    private CompletionDecision correctable(
            String harnessFeedback
    ) {
        return CompletionDecision.correctable(harnessFeedback);
    }
}
