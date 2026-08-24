package com.gitnova.service.agent.review;

import com.gitnova.service.agent.context.Severity;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.runtime.ReviewCoverage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewVerifierTest {

    private final ReviewVerifier verifier = new ReviewVerifier();

    @Test
    void shouldAcceptDraftGroundedInTrustedCoverage() {
        ReviewVerification result = verifier.verify(
                context(),
                new ReviewDraft("Found one issue", List.of(issue("src/UserService.java"))),
                new ReviewCoverage(true, Set.of("src/UserService.java"), Set.of())
        );

        assertTrue(result.accepted());
        assertFalse(result.correctable());
    }

    @Test
    void shouldRequestCorrectionWhenIssueWasNotInspected() {
        ReviewVerification result = verifier.verify(
                context(),
                new ReviewDraft("Found one issue", List.of(issue("src/UserService.java"))),
                ReviewCoverage.empty()
        );

        assertFalse(result.accepted());
        assertTrue(result.correctable());
        assertTrue(result.feedback().stream()
                .anyMatch(message -> message.contains("listChanges")));
        assertTrue(result.feedback().stream()
                .anyMatch(message -> message.contains("was not inspected")));
    }

    private AgentRunContext context() {
        return new AgentRunContext(
                "context-1",
                10L,
                "1/10",
                "a".repeat(40),
                "b".repeat(40)
        );
    }

    private ReviewIssueDraft issue(String filePath) {
        return new ReviewIssueDraft(
                filePath,
                10,
                12,
                Severity.ERROR,
                "validation",
                "The old validation branch is removed.",
                "The request now bypasses validation.",
                "Restore the validation branch.",
                0.9
        );
    }
}
