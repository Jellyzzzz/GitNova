package com.gitnova.service.agent.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.persistence.AgentStepType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Component
public class DefaultRunJournal implements RunJournal {
    private final AgentEventAppender appender;
    private final ObjectMapper objectMapper;

    public DefaultRunJournal(
            AgentEventAppender appender,
            ObjectMapper objectMapper
    ) {
        this.appender = Objects.requireNonNull(appender, "appender must not be null");
        this.objectMapper = Objects.requireNonNull(
                objectMapper,
                "objectMapper must not be null"
        );
    }

    @Override
    @Transactional
    public AgentEventAppender.AppendResult appendModelCallStarted(
            RunJournalScope scope,
            ModelCallStartedPayload payload
    ) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        ObjectNode persistedPayload = objectMapper.createObjectNode();
        String eventId = "run:"
                + scope.runId()
                + ":model-call:"
                + payload.modelCallId()
                + ":started";

        persistedPayload.put("modelCallId", payload.modelCallId());
        persistedPayload.put(
                "requestDigest",
                payload.requestDigest()
        );
        persistedPayload.put(
                "contextThroughRunStepSequence",
                payload.contextThroughRunStepSequence()
        );
        persistedPayload.put(
                "workspaceGeneration",
                payload.workspaceGeneration()
        );
        persistedPayload.put(
                "executionConfigDigest",
                scope.executionConfigDigest()
        );
        persistedPayload.put("eventId", eventId);

        return append(scope, new AgentEventAppender.AppendCommand(
                eventId,
                scope.sessionId(),
                scope.taskId(),
                scope.runId(),
                AgentStepType.MODEL_CALL_STARTED,
                1,
                persistedPayload,
                null,
                scope.runId(),
                null,
                null
        ));
    }

    @Override
    @Transactional
    public AgentEventAppender.AppendResult appendModelResponse(RunJournalScope scope, ModelResponsePayload payload) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        String causationEventId = "run:"
                + scope.runId()
                + ":model-call:"
                + payload.modelCallId()
                + ":started";
        String eventId = "run:"
                + scope.runId()
                + ":model-call:"
                + payload.modelCallId()
                + ":response";
        ObjectNode persistedPayload = objectMapper.valueToTree(payload);

        return append(scope, new AgentEventAppender.AppendCommand(
                eventId,
                scope.sessionId(),
                scope.taskId(),
                scope.runId(),
                AgentStepType.MODEL_RESPONSE,
                1,
                persistedPayload,
                causationEventId,
                scope.runId(),
                null,
                null
        ));
    }

    @Override
    @Transactional
    public AgentEventAppender.AppendResult appendToolResult(RunJournalScope scope, ToolResultPayload payload) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        String causationEventId = "run:"
                + scope.runId()
                + ":model-call:"
                + payload.modelCallId()
                + ":response";
        String eventId = "run:"
                + scope.runId()
                + ":tool-call:"
                + payload.toolCallId()
                + ":result";
        ObjectNode persistedPayload = objectMapper.valueToTree(payload);

        return append(scope, new AgentEventAppender.AppendCommand(
                eventId,
                scope.sessionId(),
                scope.taskId(),
                scope.runId(),
                AgentStepType.TOOL_RESULT,
                1,
                persistedPayload,
                causationEventId,
                scope.runId(),
                null,
                null
        ));
    }

    @Override
    @Transactional
    public AgentEventAppender.AppendResult appendHarnessFeedback(
            RunJournalScope scope,
            HarnessFeedbackPayload payload,
            String causationEventId
    ) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        if (causationEventId != null && causationEventId.isBlank()) {
            throw new IllegalArgumentException(
                    "causationEventId must not be blank when present"
            );
        }
        String eventId = "run:"
                + scope.runId()
                + ":feedback:"
                + payload.feedbackId();
        ObjectNode persistedPayload = objectMapper.valueToTree(payload);

        return append(scope, new AgentEventAppender.AppendCommand(
                eventId,
                scope.sessionId(),
                scope.taskId(),
                scope.runId(),
                AgentStepType.HARNESS_FEEDBACK,
                1,
                persistedPayload,
                causationEventId,
                scope.runId(),
                null,
                null
        ));
    }

    @Override
    @Transactional
    public AgentEventAppender.AppendResult appendCompletionDecision(
            RunJournalScope scope,
            CompletionDecisionPayload payload
    ) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        String causationEventId = "run:"
                + scope.runId()
                + ":tool-call:"
                + payload.toolCallId()
                + ":result";
        String eventId = "run:"
                + scope.runId()
                + ":tool-call:"
                + payload.toolCallId()
                + ":completion-decision";
        ObjectNode persistedPayload = objectMapper.valueToTree(payload);

        return append(scope, new AgentEventAppender.AppendCommand(
                eventId,
                scope.sessionId(),
                scope.taskId(),
                scope.runId(),
                AgentStepType.COMPLETION_DECISION,
                1,
                persistedPayload,
                causationEventId,
                scope.runId(),
                null,
                null
        ));
    }

    private AgentEventAppender.AppendResult append(
            RunJournalScope scope,
            AgentEventAppender.AppendCommand command
    ) {
        AgentEventAppender.RunExecutionAuthority authority =
                new AgentEventAppender.RunExecutionAuthority(
                        scope.fencingToken(),
                        scope.workerId()
                );
        return appender.appendFence(command, authority);
    }
}
