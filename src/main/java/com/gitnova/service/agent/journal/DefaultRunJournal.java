package com.gitnova.service.agent.journal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.mapper.agent.AgentStepMapper;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.persistence.AgentStepType;
import com.gitnova.storage.artifact.ArtifactRef;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

@Component
public class DefaultRunJournal implements RunJournal {
    private final AgentEventAppender appender;
    private final ObjectMapper objectMapper;
    private final AgentStepMapper stepMapper;

    public DefaultRunJournal(
            AgentEventAppender appender,
            ObjectMapper objectMapper,
            AgentStepMapper stepMapper
    ) {
        this.appender = Objects.requireNonNull(appender, "appender must not be null");
        this.stepMapper = Objects.requireNonNull(stepMapper, "stepMapper must not be null");
        this.objectMapper = Objects.requireNonNull(
                objectMapper,
                "objectMapper must not be null"
        );
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasModelHistory(RunJournalScope scope) {
        return stepMapper.hasModelCallStarted(Objects.requireNonNull(scope).runId());
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
        if (payload.contextInput() != null) {
            persistedPayload.put("contextThroughSessionSequence", payload.contextThroughSessionSequence());
            persistedPayload.set("contextInput", objectMapper.valueToTree(payload.contextInput()));
        }

        return append(scope, new AgentEventAppender.AppendCommand(
                eventId,
                scope.sessionId(),
                scope.taskId(),
                scope.runId(),
                AgentStepType.MODEL_CALL_STARTED,
                payload.contextInput() == null ? 1 : 2,
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
    public AgentEventAppender.AppendResult appendToolObservation(
            RunJournalScope scope, String toolCallId, String contextPolicyVersion, JsonNode observation) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(observation, "observation");
        if (toolCallId == null || toolCallId.isBlank()
                || contextPolicyVersion == null || contextPolicyVersion.isBlank()) {
            throw new IllegalArgumentException("Tool Call identity and context policy are required");
        }
        JsonNode reference = observation.path("externalization").path("artifact");
        try {
            objectMapper.treeToValue(reference, ArtifactRef.class);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Observation must contain a valid Artifact reference", exception);
        }
        if (!reference.isObject()) throw new IllegalArgumentException("Artifact reference is required");
        String cause = "run:" + scope.runId() + ":tool-call:" + toolCallId + ":result";
        if (!stepMapper.hasToolResult(scope.sessionId(), scope.runId(), cause)) {
            throw new IllegalStateException("Tool Result must be recorded before its Observation projection");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("toolCallId", toolCallId);
        payload.put("contextPolicyVersion", contextPolicyVersion);
        payload.set("observation", observation.deepCopy());
        return append(scope, new AgentEventAppender.AppendCommand(
                "run:" + scope.runId() + ":tool-call:" + toolCallId + ":observation",
                scope.sessionId(), scope.taskId(), scope.runId(),
                AgentStepType.TOOL_OBSERVATION_PROJECTED, 1, payload, cause,
                scope.runId(), null, null
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ArtifactRef> findArtifact(String sessionId, String artifactId) {
        if (sessionId == null || sessionId.isBlank() || artifactId == null
                || !artifactId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid Session or Artifact identity");
        }
        String payload = stepMapper.selectArtifactProjection(sessionId, artifactId);
        if (payload == null) return Optional.empty();
        try {
            ArtifactRef reference = objectMapper.treeToValue(objectMapper.readTree(payload)
                    .path("observation").path("externalization").path("artifact"), ArtifactRef.class);
            if (reference == null || !artifactId.equals(reference.artifactId())) {
                throw new IllegalStateException("Persisted Artifact reference does not match the lookup");
            }
            return Optional.of(reference);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Persisted Artifact reference is invalid", exception);
        }
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
