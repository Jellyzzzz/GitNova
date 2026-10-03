package com.gitnova.service.agent.journal;

import com.fasterxml.jackson.databind.JsonNode;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.model.ModelGatewayException;
import com.gitnova.storage.artifact.ArtifactRef;

import java.util.List;
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

    AgentEventAppender.AppendResult appendModelCallFailed(
            RunJournalScope scope, String modelCallId, ModelGatewayException failure
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

    /** Only a committed result/projection pair in this Session authorizes the short resource path. */
    Optional<ArtifactRef> findArtifactBySource(String sessionId, long sourceSequence);

    /** Capture once when starting a paged search; later appends belong to a new search. */
    long latestSessionSequence(String sessionId);

    /** Reads raw committed outcomes, never the lossy Session context projection. */
    List<HistoricalToolResult> readToolResults(String sessionId, long afterSequence,
                                              long throughSequence, int limit);

    default Optional<HistoricalToolResult> findToolResultBySource(String sessionId, long sourceSequence) {
        if (sourceSequence <= 0) throw new IllegalArgumentException("Source sequence must be positive");
        return readToolResults(sessionId, sourceSequence - 1, sourceSequence, 1).stream().findFirst();
    }

    /** Provenance remains historical; reading it does not create current validation evidence. */
    record HistoricalToolResult(long sourceSequence, String taskId, String runId,
                                Long workspaceEpoch, Long workspaceGeneration, ToolResultPayload payload) {}

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
