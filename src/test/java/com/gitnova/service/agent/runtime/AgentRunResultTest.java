package com.gitnova.service.agent.runtime;

import com.gitnova.service.agent.completion.AgentCompletionDraft;
import com.gitnova.service.agent.completion.AgentCompletionOutcome;
import com.gitnova.service.agent.completion.CompletionDisposition;
import com.gitnova.service.agent.model.ModelUsage;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRunResultTest {

    @Test
    void shouldRepresentPartialRunBySuccessfulToolObservationsWithoutACompletion() {
        AgentRunResult result = new AgentRunResult(
                AgentRunStatus.PARTIAL,
                AgentTerminationReason.MAX_MODEL_CALLS_REACHED,
                null,
                null,
                3,
                4,
                2,
                List.of(ModelUsage.unknown())
        );

        assertEquals(2, result.successfulToolCallCount());
        assertEquals(1, result.modelUsages().size());
    }

    @Test
    void shouldRejectPartialOrFailedStatusThatContradictsSuccessfulToolCount() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRunResult(
                        AgentRunStatus.PARTIAL,
                        AgentTerminationReason.MAX_MODEL_CALLS_REACHED,
                        null,
                        null,
                        1,
                        0,
                        0,
                        List.of()
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRunResult(
                        AgentRunStatus.FAILED,
                        AgentTerminationReason.MODEL_GATEWAY_FAILURE,
                        null,
                        null,
                        1,
                        1,
                        1,
                        List.of()
                )
        );
    }

    @Test
    void shouldRequireProtocolDeviationWhenCorrectionBudgetIsExhausted() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRunResult(
                        AgentRunStatus.FAILED,
                        AgentTerminationReason.PROTOCOL_CORRECTION_EXHAUSTED,
                        null,
                        null,
                        2,
                        0,
                        0,
                        List.of()
                )
        );
    }

    @Test
    void shouldAllowCompletedRunWithCanonicalCompletionOutcome() {
        AgentCompletionDraft draft = new AgentCompletionDraft(
                0,
                "Explained the repository",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        WorkspaceGateway.WorkspaceDiff diff = new WorkspaceGateway.WorkspaceDiff(
                0,
                List.of(),
                0,
                0,
                0,
                false,
                ""
        );
        AgentCompletionOutcome outcome = new AgentCompletionOutcome(
                CompletionDisposition.NO_CHANGES,
                draft,
                diff,
                null
        );

        AgentRunResult result = new AgentRunResult(
                AgentRunStatus.COMPLETED,
                AgentTerminationReason.FINISH_SUCCEEDED,
                outcome,
                ProtocolDeviation.MIXED_TERMINAL_TOOL_CALLS,
                2,
                1,
                1,
                List.of(new ModelUsage(10, 4, 14))
        );

        assertEquals(outcome, result.completionOutcome());
        assertEquals(
                ProtocolDeviation.MIXED_TERMINAL_TOOL_CALLS,
                result.lastProtocolDeviation()
        );
    }
}
