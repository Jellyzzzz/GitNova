package com.gitnova.service.agent.journal;

import com.fasterxml.jackson.databind.JsonNode;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.storage.artifact.ArtifactRef;

import java.util.Optional;

public interface RunJournal {
    /** Recovery must project committed history, not start an existing execution from an empty transcript. */
    boolean hasModelHistory(RunJournalScope scope);

    AgentEventAppender.AppendResult appendModelCallStarted(
            RunJournalScope scope,
            ModelCallStartedPayload payload
    );

    AgentEventAppender.AppendResult appendModelResponse(
            RunJournalScope scope,
            ModelResponsePayload payload
    );

    AgentEventAppender.AppendResult appendToolResult(
            RunJournalScope scope,
            ToolResultPayload payload
    );

    /** Initial externalization of a committed Tool Result, not a second execution outcome. */
    AgentEventAppender.AppendResult appendToolObservation(
            RunJournalScope scope, String toolCallId, String contextPolicyVersion, JsonNode observation
    );

    Optional<ArtifactRef> findArtifact(String sessionId, String artifactId);

    AgentEventAppender.AppendResult appendHarnessFeedback(
            RunJournalScope scope,
            HarnessFeedbackPayload payload,
            String causationEventId
    );

    AgentEventAppender.AppendResult appendCompletionDecision(
            RunJournalScope scope,
            CompletionDecisionPayload payload
    );
}
