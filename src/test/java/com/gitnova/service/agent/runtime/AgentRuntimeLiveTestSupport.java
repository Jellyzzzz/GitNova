package com.gitnova.service.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.service.agent.completion.CompletionInspector;
import com.gitnova.service.agent.review.ReviewDraft;
import com.gitnova.service.agent.review.ReviewIssueDraft;
import com.gitnova.service.agent.workspace.PatchBatchResult;
import com.gitnova.service.agent.workspace.WorkspaceBinding;
import com.gitnova.service.agent.workspace.WorkspaceExecutionPermit;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.agent.workspace.WorkspaceId;
import com.gitnova.service.agent.workspace.WorkspaceMutationCommand;

import java.util.List;

/** Shared adapter for opt-in live tests while they exercise the universal Runtime. */
final class AgentRuntimeLiveTestSupport {

    private AgentRuntimeLiveTestSupport() {
    }

    static AgentExecutionContext execution(
            AgentRunContext run,
            String taskText,
            AgentExecutionConfig executionConfig
    ) {
        WorkspaceId workspaceId = WorkspaceId.generate();
        return new AgentExecutionContext(
                "session-" + run.runId(),
                run,
                1L,
                taskText,
                new WorkspaceBinding(workspaceId),
                new WorkspaceExecutionPermit(run.runId(), workspaceId, 1L),
                executionConfig
        );
    }

    static WorkspaceGateway workspace() {
        return new NoChangesWorkspace();
    }

    static CompletionInspector inspector(
            ObjectMapper objectMapper,
            WorkspaceGateway workspaceGateway
    ) {
        return new CompletionInspector(objectMapper, workspaceGateway);
    }

    static ReviewDraft reviewDraft(AgentRunResult result) {
        if (result.completionOutcome() == null) {
            return null;
        }
        List<ReviewIssueDraft> issues = result.completionOutcome().draft().findings().stream()
                .map(finding -> new ReviewIssueDraft(
                        finding.filePath(),
                        finding.startLine(),
                        finding.endLine(),
                        finding.severity(),
                        finding.category(),
                        finding.evidence(),
                        finding.explanation(),
                        finding.suggestion(),
                        finding.confidence()
                ))
                .toList();
        return new ReviewDraft(result.completionOutcome().draft().summary(), issues);
    }

    private static final class NoChangesWorkspace implements WorkspaceGateway {
        @Override
        public PatchBatchResult applyPatch(
                WorkspaceId workspaceId,
                WorkspaceExecutionPermit executionPermit,
                WorkspaceMutationCommand command
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkspaceRefresh refreshWorkspace(WorkspaceId workspaceId) {
            return new WorkspaceRefresh(0, 0, false);
        }

        @Override
        public WorkspaceDiff getWorkspaceDiff(WorkspaceId workspaceId) {
            return new WorkspaceDiff(0, List.of(), 0, 0, 0, false, "");
        }
    }
}
