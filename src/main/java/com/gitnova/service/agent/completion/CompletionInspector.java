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
        Set<String> agentModifiedFiles = Set.copyOf(draft.agentModifiedFiles());
        if (!actualFiles.containsAll(agentModifiedFiles)) {
            return correctable(
                    "agentModifiedFiles contains a path that is absent from "
                            + "the canonical Workspace diff"
            );
        }

        // This is an optional observed command result, not a task-completion oracle.
        // All command outcomes (including failures) remain in TOOL_RESULT history.
        // The model's free-text claims remain unverified; do not infer their truth from argv.
        ValidationEvidence validation = state.latestSuccessfulValidation().orElse(null);
        if (validation != null && validation.generation() != currentGeneration) {
            validation = null;
        }

        return CompletionDecision.accepted(
                new AgentCompletionOutcome(
                        diff.files().isEmpty()
                                ? CompletionDisposition.NO_CHANGES
                                : CompletionDisposition.CHANGES_READY,
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
