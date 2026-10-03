package com.gitnova.service.agent.journal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.mapper.agent.AgentStepMapper;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.persistence.AgentStepType;
import com.gitnova.service.agent.model.ModelGatewayException;
import com.gitnova.storage.artifact.ArtifactRef;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
        String eventId = ModelResponsePayload.eventId(scope.runId(), payload.modelCallId());
        ObjectNode persistedPayload = objectMapper.valueToTree(payload);

        return append(scope, new AgentEventAppender.AppendCommand(
                eventId,
                scope.sessionId(),
                scope.taskId(),
                scope.runId(),
                AgentStepType.MODEL_RESPONSE,
                payload.reasoningContent() == null ? 1 : 2,
                persistedPayload,
                causationEventId,
                scope.runId(),
                null,
                null
        ));
    }

    @Override
    @Transactional
    public AgentEventAppender.AppendResult appendModelCallFailed(
            RunJournalScope scope, String modelCallId, ModelGatewayException failure) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(failure, "failure");
        if (modelCallId == null || modelCallId.isBlank()) throw new IllegalArgumentException("Model Call id is required");
        String prefix = "run:" + scope.runId() + ":model-call:" + modelCallId;
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("modelCallId", modelCallId);
        payload.put("errorCode", failure.errorCode().name());
        payload.put("retryable", failure.retryable());
        if (failure.providerStatusCode() != null) payload.put("httpStatus", failure.providerStatusCode());
        if (failure.responseDiagnostic() != null) payload.set("responseDiagnostic", objectMapper.valueToTree(failure.responseDiagnostic()));
        // Never serialize the exception itself: messages/causes/headers may contain provider data.
        return append(scope, new AgentEventAppender.AppendCommand(
                prefix + ":failed", scope.sessionId(), scope.taskId(), scope.runId(),
                AgentStepType.MODEL_CALL_FAILED, 1, payload, prefix + ":started", scope.runId(), null, null));
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
    public Optional<ArtifactRef> findArtifactBySource(String sessionId, long sourceSequence) {
        if (sessionId == null || sessionId.isBlank() || sourceSequence <= 0) {
            throw new IllegalArgumentException("Invalid Session or source sequence");
        }
        String payload = stepMapper.selectArtifactProjectionBySource(sessionId, sourceSequence);
        if (payload == null) return Optional.empty();
        try {
            ArtifactRef reference = objectMapper.treeToValue(objectMapper.readTree(payload)
                    .path("observation").path("externalization").path("artifact"), ArtifactRef.class);
            if (reference == null) throw new IllegalStateException("Persisted Artifact reference is missing");
            return Optional.of(reference);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Persisted Artifact reference is invalid", exception);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public long latestSessionSequence(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("Session is required");
        return stepMapper.latestSessionSequence(sessionId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<HistoricalToolResult> readToolResults(String sessionId, long afterSequence,
                                                     long throughSequence, int limit) {
        if (sessionId == null || sessionId.isBlank() || afterSequence < 0
                || throughSequence < afterSequence || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Invalid Session history range");
        }
        List<HistoricalToolResult> results = new ArrayList<>();
        long previous = afterSequence;
        for (var row : stepMapper.selectToolResultHistory(sessionId, afterSequence, throughSequence, limit)) {
            if (!sessionId.equals(row.getSessionId()) || !"TOOL_RESULT".equals(row.getStepType())
                    || !Integer.valueOf(1).equals(row.getSchemaVersion()) || row.getSessionSequence() == null
                    || row.getSessionSequence() <= previous || row.getSessionSequence() > throughSequence) {
                throw new IllegalStateException("Invalid committed Tool Result history or unsupported schema");
            }
            try {
                ToolResultPayload payload = objectMapper.readValue(row.getPayloadJson(), ToolResultPayload.class);
                if (payload == null) throw new IllegalStateException("Missing committed Tool Result");
                results.add(new HistoricalToolResult(row.getSessionSequence(), row.getTaskId(), row.getRunId(),
                        row.getWorkspaceEpoch(), row.getWorkspaceGeneration(), payload));
                previous = row.getSessionSequence();
            } catch (IOException | IllegalArgumentException exception) {
                throw new IllegalStateException("Invalid committed Tool Result payload", exception);
            }
        }
        return List.copyOf(results);
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
