package com.gitnova.service.agent.journal;

import com.gitnova.service.agent.persistence.AgentEventAppender;

public interface RunJournal {
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
