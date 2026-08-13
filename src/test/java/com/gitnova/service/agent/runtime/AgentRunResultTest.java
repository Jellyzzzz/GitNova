package com.gitnova.service.agent.runtime;

import com.gitnova.service.agent.model.ModelUsage;
import com.gitnova.service.agent.review.ReviewDraft;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunResultTest {

    @Test
    void shouldRepresentPartialReviewAsCoverageWithoutDraft() {
        ReviewCoverage coverage = new ReviewCoverage(
                true,
                Set.of("src/UserService.java"),
                Set.of("src/UserService.java")
        );

        AgentRunResult result = new AgentRunResult(
                AgentRunStatus.PARTIAL,
                AgentTerminationReason.MAX_MODEL_CALLS_REACHED,
                null,
                coverage,
                null,
                3,
                4,
                List.of(ModelUsage.unknown())
        );

        assertTrue(result.coverage().hasEvidence());
        assertEquals(1, result.coverage().diffedFiles().size());
        assertEquals(1, result.modelUsages().size());
    }

    @Test
    void shouldRejectPartialRunWithoutTrustedReviewEvidence() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRunResult(
                        AgentRunStatus.PARTIAL,
                        AgentTerminationReason.MAX_MODEL_CALLS_REACHED,
                        null,
                        ReviewCoverage.empty(),
                        null,
                        1,
                        0,
                        List.of()
                )
        );
    }

    @Test
    void shouldRejectFailedRunThatClaimsReviewCoverage() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRunResult(
                        AgentRunStatus.FAILED,
                        AgentTerminationReason.MODEL_GATEWAY_FAILURE,
                        null,
                        new ReviewCoverage(true, Set.of(), Set.of()),
                        null,
                        1,
                        1,
                        List.of()
                )
        );
    }

    @Test
    void shouldRequireProtocolDeviationOnlyWhenCorrectionBudgetIsExhausted() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRunResult(
                        AgentRunStatus.FAILED,
                        AgentTerminationReason.PROTOCOL_CORRECTION_EXHAUSTED,
                        null,
                        ReviewCoverage.empty(),
                        null,
                        2,
                        0,
                        List.of()
                )
        );

        AgentRunResult result = new AgentRunResult(
                AgentRunStatus.FAILED,
                AgentTerminationReason.PROTOCOL_CORRECTION_EXHAUSTED,
                null,
                ReviewCoverage.empty(),
                ProtocolDeviation.MODEL_STOPPED_WITHOUT_FINALIZE,
                2,
                0,
                List.of()
        );

        assertEquals(
                ProtocolDeviation.MODEL_STOPPED_WITHOUT_FINALIZE,
                result.lastProtocolDeviation()
        );
    }

    @Test
    void shouldAllowCompletedRunWithFormalDraft() {
        ReviewDraft draft = new ReviewDraft("No actionable defects found.", List.of());

        AgentRunResult result = new AgentRunResult(
                AgentRunStatus.COMPLETED,
                AgentTerminationReason.FINALIZE_SUCCEEDED,
                draft,
                ReviewCoverage.empty(),
                ProtocolDeviation.MIXED_TERMINAL_TOOL_CALLS,
                3,
                2,
                List.of(new ModelUsage(10, 4, 14))
        );

        assertEquals(draft, result.reviewDraft());
        assertEquals(
                ProtocolDeviation.MIXED_TERMINAL_TOOL_CALLS,
                result.lastProtocolDeviation()
        );
    }
}
