package com.gitnova.service.agent.completion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.service.agent.runtime.AgentCapabilityPolicy;
import com.gitnova.service.agent.runtime.AgentExecutionContext;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.runtime.RunStateView;
import com.gitnova.service.agent.runtime.ValidationEvidence;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.workspace.PatchBatchResult;
import com.gitnova.service.agent.workspace.SnapshotScope;
import com.gitnova.service.agent.workspace.WorkspaceBinding;
import com.gitnova.service.agent.workspace.WorkspaceExecutionPermit;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.agent.workspace.WorkspaceId;
import com.gitnova.service.agent.workspace.WorkspaceMutationCommand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionInspectorTest {

    private static final String CHANGED_FILE = "src/App.java";
    private static final String EXTERNAL_FILE = "USER_NOTE.md";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldAcceptReadOnlyCompletionFromCanonicalEmptyDiff() {
        InspectingGateway gateway = new InspectingGateway(4, diff(4, List.of()));
        CompletionDecision decision = inspector(gateway).inspect(
                context(),
                RunStateView.empty(),
                finishResult(draft(4, List.of(), List.of()))
        );

        assertTrue(decision.accepted());
        assertFalse(decision.correctable());
        assertEquals(CompletionDisposition.NO_CHANGES, decision.outcome().disposition());
        assertNull(decision.outcome().validation());
        assertEquals(2, gateway.refreshCount);
    }

    @Test
    void shouldRejectStaleGenerationBeforeTrustingModelClaims() {
        CompletionDecision decision = inspector(
                new InspectingGateway(5, diff(5, List.of()))
        ).inspect(
                context(),
                RunStateView.empty(),
                finishResult(draft(4, List.of(), List.of()))
        );

        assertFalse(decision.accepted());
        assertTrue(decision.correctable());
        assertTrue(decision.feedback().get(0).contains("generation 5"));
    }

    @Test
    void shouldRequireFreshHarnessValidationForCanonicalChanges() {
        CompletionDecision missing = inspector(
                new InspectingGateway(7, diff(7, List.of(CHANGED_FILE)))
        ).inspect(
                context(),
                RunStateView.empty(),
                finishResult(draft(7, List.of(CHANGED_FILE), List.of()))
        );
        assertTrue(missing.correctable());
        assertTrue(missing.feedback().get(0).contains("successful validation"));

        ValidationEvidence stale = validation(6);
        CompletionDecision staleDecision = inspector(
                new InspectingGateway(7, diff(7, List.of(CHANGED_FILE)))
        ).inspect(
                context(),
                new RunStateView(Optional.of(stale)),
                finishResult(draft(
                        7,
                        List.of(CHANGED_FILE),
                        List.of(new ValidationClaim(stale.argv(), "passed"))
                ))
        );
        assertTrue(staleDecision.correctable());
        assertTrue(staleDecision.feedback().get(0).contains("stale generation"));
    }

    @Test
    void shouldSeparateAgentModifiedFilesFromTheCompleteCanonicalDiff() {
        ValidationEvidence validation = validation(7);
        AgentCompletionDraft draft = draft(
                7,
                List.of(CHANGED_FILE),
                List.of(new ValidationClaim(validation.argv(), "passed"))
        );

        CompletionDecision decision = inspector(
                new InspectingGateway(7, diff(7, List.of(CHANGED_FILE, EXTERNAL_FILE)))
        ).inspect(
                context(),
                new RunStateView(Optional.of(validation)),
                finishResult(draft)
        );

        assertTrue(decision.accepted());
        assertEquals(CompletionDisposition.CHANGES_READY, decision.outcome().disposition());
        assertEquals(validation, decision.outcome().validation());
        assertEquals(List.of(CHANGED_FILE), decision.outcome().draft().agentModifiedFiles());
        assertEquals(List.of(CHANGED_FILE, EXTERNAL_FILE),
                decision.outcome().canonicalDiff().files()
                .stream().map(WorkspaceGateway.DiffFile::filePath).toList());
    }

    @Test
    void shouldCorrectAgentFileOutsideCanonicalDiffOrValidationClaimMismatch() {
        ValidationEvidence validation = validation(7);
        CompletionDecision fileMismatch = inspector(
                new InspectingGateway(7, diff(7, List.of(CHANGED_FILE)))
        ).inspect(
                context(),
                new RunStateView(Optional.of(validation)),
                finishResult(draft(7, List.of("src/NotChanged.java"), List.of()))
        );
        assertTrue(fileMismatch.correctable());
        assertTrue(fileMismatch.feedback().get(0).contains("agentModifiedFiles"));

        CompletionDecision validationMismatch = inspector(
                new InspectingGateway(7, diff(7, List.of(CHANGED_FILE)))
        ).inspect(
                context(),
                new RunStateView(Optional.of(validation)),
                finishResult(draft(
                        7,
                        List.of(CHANGED_FILE),
                        List.of(new ValidationClaim(List.of("mvn", "verify"), "passed"))
                ))
        );
        assertTrue(validationMismatch.correctable());
        assertTrue(validationMismatch.feedback().get(0).contains("claimedValidations"));
    }

    @Test
    void shouldDetectWorkspaceChangeAfterCanonicalDiffWasRead() {
        InspectingGateway gateway = new InspectingGateway(2, diff(2, List.of()));
        gateway.changeOnSecondRefresh = true;

        CompletionDecision decision = inspector(gateway).inspect(
                context(),
                RunStateView.empty(),
                finishResult(draft(2, List.of(), List.of()))
        );

        assertTrue(decision.correctable());
        assertTrue(decision.feedback().get(0).contains("during completion inspection"));
    }

    @Test
    void shouldRejectMalformedSuccessfulToolPayloadWithoutThrowing() {
        CompletionDecision decision = inspector(
                new InspectingGateway(0, diff(0, List.of()))
        ).inspect(
                context(),
                RunStateView.empty(),
                ToolResult.success(objectMapper.createObjectNode().put("summary", "incomplete"))
        );

        assertFalse(decision.accepted());
        assertFalse(decision.correctable());
        assertNotNull(decision.feedback());
    }

    private CompletionInspector inspector(WorkspaceGateway gateway) {
        return new CompletionInspector(objectMapper, gateway);
    }

    private ToolResult finishResult(AgentCompletionDraft draft) {
        return ToolResult.success(objectMapper.valueToTree(draft));
    }

    private AgentCompletionDraft draft(
            long generation,
            List<String> changedFiles,
            List<ValidationClaim> validations
    ) {
        return new AgentCompletionDraft(
                generation,
                "Task completed",
                List.of(),
                changedFiles,
                validations,
                List.of(),
                List.of()
        );
    }

    private ValidationEvidence validation(long generation) {
        return new ValidationEvidence(
                List.of("mvn", "test"),
                generation,
                0,
                120,
                false,
                false
        );
    }

    private WorkspaceGateway.WorkspaceDiff diff(long generation, List<String> paths) {
        List<WorkspaceGateway.DiffFile> files = paths.stream()
                .map(path -> new WorkspaceGateway.DiffFile(
                        path,
                        WorkspaceGateway.DiffChangeType.MODIFIED,
                        1,
                        1,
                        1,
                        false
                ))
                .toList();
        return new WorkspaceGateway.WorkspaceDiff(
                generation,
                files,
                files.size(),
                files.size(),
                files.size(),
                false,
                files.isEmpty() ? "" : "diff"
        );
    }

    private AgentExecutionContext context() {
        AgentRunContext run = new AgentRunContext(
                "run-1",
                10L,
                "1/10",
                SnapshotScope.of("a".repeat(40))
        );
        WorkspaceId workspaceId = WorkspaceId.generate();
        return new AgentExecutionContext(
                "session-1",
                run,
                7L,
                "Inspect the repository",
                new WorkspaceBinding(workspaceId),
                new WorkspaceExecutionPermit(run.runId(), workspaceId, 1L),
                com.gitnova.service.agent.AgentTestExecutionConfigs.minimal()
        );
    }

    private static final class InspectingGateway implements WorkspaceGateway {
        private long generation;
        private final WorkspaceDiff diff;
        private int refreshCount;
        private boolean changeOnSecondRefresh;

        private InspectingGateway(long generation, WorkspaceDiff diff) {
            this.generation = generation;
            this.diff = diff;
        }

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
            refreshCount++;
            long before = generation;
            if (changeOnSecondRefresh && refreshCount == 2) {
                generation++;
            }
            return new WorkspaceRefresh(before, generation, generation != before);
        }

        @Override
        public WorkspaceDiff getWorkspaceDiff(WorkspaceId workspaceId) {
            return diff;
        }
    }
}
