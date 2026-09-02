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


}
