package com.gitnova.service.agent.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.entity.agent.AgentStepEntity;
import com.gitnova.mapper.agent.AgentStepMapper;
import com.gitnova.storage.artifact.ArtifactRef;
import com.gitnova.service.agent.completion.CompletionDecision;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.persistence.AgentStepType;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.model.ModelFinishReason;
import com.gitnova.service.agent.model.ModelResponse;
import com.gitnova.service.agent.model.ModelUsage;
import com.gitnova.service.agent.model.ModelGatewayErrorCode;
import com.gitnova.service.agent.model.ModelGatewayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultRunJournalTest {

    private static final String DIGEST = "a".repeat(64);
    private static final RunJournalScope SCOPE = new RunJournalScope(
            "session-1",
            "task-1",
            "run-1",
            "worker-1",
            7L,
            DIGEST
    );

    private AgentEventAppender appender;
    private DefaultRunJournal journal;
    private AgentStepMapper stepMapper;

    @BeforeEach
    void setUp() {
        appender = mock(AgentEventAppender.class);
        when(appender.appendFence(any(), any())).thenReturn(
                new AgentEventAppender.AppendResult(1L, 2L, 3L, false)
        );
        stepMapper = mock(AgentStepMapper.class);
        journal = new DefaultRunJournal(appender, new ObjectMapper(), stepMapper);
    }

    @Test
    void shortResourceLookupUsesSourceSequenceWithinTheTrustedSession() throws Exception {
        var mapper = new ObjectMapper();
        var ref = new ArtifactRef("a".repeat(64), "a".repeat(64), 100, "application/json");
        var projection = mapper.createObjectNode();
        projection.putObject("observation").putObject("externalization").set("artifact", mapper.valueToTree(ref));
        when(stepMapper.selectArtifactProjectionBySource("session-1", 24)).thenReturn(projection.toString());
        assertEquals(ref, journal.findArtifactBySource("session-1", 24).orElseThrow());
        assertTrue(journal.findArtifactBySource("session-1", 25).isEmpty());
        assertTrue(journal.findArtifactBySource("session-other", 24).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> journal.findArtifactBySource("session-1", 0));
        when(stepMapper.selectArtifactProjectionBySource("session-1", 26)).thenReturn("{\"observation\":{}}");
        assertThrows(IllegalStateException.class, () -> journal.findArtifactBySource("session-1", 26));
    }

    @Test
    void inlineHistoryDecodesTheRawResultWithProvenanceAndWithoutAnArtifactProjection() throws Exception {
        var mapper = new ObjectMapper();
        var row = new AgentStepEntity();
        row.setSessionId("session-1");
        row.setSessionSequence(24L);
        row.setTaskId("old-task");
        row.setRunId("old-run");
        row.setStepType("TOOL_RESULT");
        row.setSchemaVersion(1);
        row.setWorkspaceEpoch(2L);
        row.setWorkspaceGeneration(4L);
        row.setPayloadJson(mapper.writeValueAsString(new ToolResultPayload("model", "call", "runCommand",
                ToolResult.success(mapper.createObjectNode().put("stdout", "original\n")))));
        when(stepMapper.selectToolResultHistory("session-1", 23, 24, 1)).thenReturn(List.of(row));
        var found = journal.findToolResultBySource("session-1", 24).orElseThrow();
        assertEquals("old-task", found.taskId());
        assertEquals(4L, found.workspaceGeneration());
        assertEquals("original\n", found.payload().result().payload().path("stdout").asText());
        assertTrue(journal.findToolResultBySource("other", 24).isEmpty());

        row.setSchemaVersion(2);
        assertThrows(IllegalStateException.class, () -> journal.findToolResultBySource("session-1", 24));
        row.setSchemaVersion(1);
        row.setSessionId("other");
        assertThrows(IllegalStateException.class, () -> journal.findToolResultBySource("session-1", 24));
        row.setSessionId("session-1");
        row.setPayloadJson("{}");
        assertThrows(IllegalStateException.class, () -> journal.findToolResultBySource("session-1", 24));
    }

    @Test
    void historyLookupRequiresTrustedScopeOrderedRangeAndBoundedPageSize() {
        assertThrows(IllegalArgumentException.class, () -> journal.latestSessionSequence(" "));
        assertThrows(IllegalArgumentException.class, () -> journal.readToolResults("", 0, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> journal.readToolResults("session-1", -1, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> journal.readToolResults("session-1", 10, 9, 1));
        assertThrows(IllegalArgumentException.class, () -> journal.readToolResults("session-1", 0, 10, 101));
        assertThrows(IllegalArgumentException.class, () -> journal.findToolResultBySource("session-1", 0));
        when(stepMapper.latestSessionSequence("session-1")).thenReturn(138L);
        assertEquals(138, journal.latestSessionSequence("session-1"));
    }

    @Test
    void thinkingResponseUsesVersionedPayloadAndPreservesTheExactOriginal() {
        String reasoning = "  original\nreasoning\r\n  ";
        var response = new ModelResponse("response", "Ready", java.util.List.of(),
                ModelUsage.unknown(), ModelFinishReason.STOP, reasoning);
        journal.appendModelResponse(SCOPE, ModelResponsePayload.from("model-1", response));

        var command = capturedCommand();
        assertEquals(AgentStepType.MODEL_RESPONSE, command.stepType());
        assertEquals(2, command.schemaVersion());
        assertEquals(reasoning, command.persistedPayload().path("reasoningContent").textValue());
        assertEquals("run:run-1:model-call:model-1:started", command.causationEventId());
        assertAuthority();
    }

    @Test
    void shouldAppendToolResultAfterItsModelResponse() {
        ObjectNode resultPayload = new ObjectMapper().createObjectNode();
        resultPayload.put("generation", 4L);
        ToolResultPayload payload = new ToolResultPayload(
                "model-1",
                "tool-1",
                "applyPatch",
                ToolResult.success(resultPayload)
        );
        resultPayload.put("generation", 99L);

        journal.appendToolResult(SCOPE, payload);

        AgentEventAppender.AppendCommand command = capturedCommand();
        assertEquals("run:run-1:tool-call:tool-1:result", command.eventId());
        assertEquals(AgentStepType.TOOL_RESULT, command.stepType());
        assertEquals(
                "run:run-1:model-call:model-1:response",
                command.causationEventId()
        );
        assertEquals(4L, command.persistedPayload()
                .path("result")
                .path("payload")
                .path("generation")
                .asLong());
        assertAuthority();
    }

    @Test
    void shouldAppendHarnessFeedbackWithExplicitCause() {
        HarnessFeedbackPayload payload = new HarnessFeedbackPayload(
                "feedback-1",
                HarnessFeedbackKind.PROTOCOL_CORRECTION,
                "Call finishTask separately."
        );

        journal.appendHarnessFeedback(
                SCOPE,
                payload,
                "run:run-1:model-call:model-1:response"
        );

        AgentEventAppender.AppendCommand command = capturedCommand();
        assertEquals("run:run-1:feedback:feedback-1", command.eventId());
        assertEquals(AgentStepType.HARNESS_FEEDBACK, command.stepType());
        assertEquals(
                "run:run-1:model-call:model-1:response",
                command.causationEventId()
        );
        assertEquals("PROTOCOL_CORRECTION", command.persistedPayload().path("kind").asText());
        assertAuthority();
    }

    @Test
    void shouldRejectBlankFeedbackCausationIdentity() {
        HarnessFeedbackPayload payload = new HarnessFeedbackPayload(
                "feedback-1",
                HarnessFeedbackKind.WORKSPACE_DRIFT,
                "Workspace changed."
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> journal.appendHarnessFeedback(SCOPE, payload, " ")
        );
    }

    @Test
    void parseFailureHasStableIdentityAndCorrectionReferencesThatFailure() {
        var failure = new ModelGatewayException(ModelGatewayErrorCode.INVALID_RESPONSE, "secret-provider-body", false,
                200, "secret-code", "secret-header", null, new IllegalArgumentException("secret-cause"),
                new ModelGatewayException.ResponseDiagnostic("TOOL_ARGUMENTS_JSON_INVALID",
                        "/choices/0/message/tool_calls/0/function/arguments", 1, 4267));
        journal.appendModelCallFailed(SCOPE, "model-1", failure);
        journal.appendHarnessFeedback(SCOPE, new HarnessFeedbackPayload("feedback-1",
                HarnessFeedbackKind.MODEL_RESPONSE_CORRECTION, "Regenerate valid JSON"),
                "run:run-1:model-call:model-1:failed");

        var commands = ArgumentCaptor.forClass(AgentEventAppender.AppendCommand.class);
        org.mockito.Mockito.verify(appender, org.mockito.Mockito.times(2)).appendFence(commands.capture(), any());
        var failed = commands.getAllValues().get(0);
        var feedback = commands.getAllValues().get(1);
        assertEquals(AgentStepType.MODEL_CALL_FAILED, failed.stepType());
        assertEquals("run:run-1:model-call:model-1:started", failed.causationEventId());
        assertEquals("run:run-1:model-call:model-1:failed", failed.eventId());
        assertEquals(failed.eventId(), feedback.causationEventId());
        assertEquals(4267, failed.persistedPayload().at("/responseDiagnostic/column").asInt());
        assertEquals("MODEL_RESPONSE_CORRECTION", feedback.persistedPayload().path("kind").asText());
        assertEquals(false, failed.persistedPayload().toString().contains("secret-"));
    }

    @Test
    void shouldAppendCompletionDecisionAfterTerminalToolResult() {
        CompletionDecisionPayload payload = new CompletionDecisionPayload(
                "model-1",
                "finish-1",
                CompletionDecision.correctable("Refresh the Workspace diff.")
        );

        journal.appendCompletionDecision(SCOPE, payload);

        AgentEventAppender.AppendCommand command = capturedCommand();
        assertEquals(
                "run:run-1:tool-call:finish-1:completion-decision",
                command.eventId()
        );
        assertEquals(AgentStepType.COMPLETION_DECISION, command.stepType());
        assertEquals(
                "run:run-1:tool-call:finish-1:result",
                command.causationEventId()
        );
        assertEquals(false, command.persistedPayload().path("decision").path("accepted").asBoolean());
        assertEquals(true, command.persistedPayload().path("decision").path("correctable").asBoolean());
        assertAuthority();
    }

    @Test
    void shouldAppendProjectionWithStableIdentityAfterToolResult() {
        var mapper = new ObjectMapper();
        var observation = mapper.createObjectNode().put("status", "SUCCESS");
        observation.putObject("externalization").set("artifact", mapper.valueToTree(
                new ArtifactRef(DIGEST, DIGEST, 100, "application/json")));
        String cause = "run:run-1:tool-call:tool-1:result";
        when(stepMapper.hasToolResult("session-1", "run-1", cause)).thenReturn(true);

        journal.appendToolObservation(SCOPE, "tool-1", "context-policy-1", observation);
        observation.put("status", "changed after append");

        var command = capturedCommand();
        assertEquals(AgentStepType.TOOL_OBSERVATION_PROJECTED, command.stepType());
        assertEquals(1, command.schemaVersion());
        assertEquals(cause, command.causationEventId());
        assertEquals("run:run-1:tool-call:tool-1:observation", command.eventId());
        assertEquals("SUCCESS", command.persistedPayload().at("/observation/status").asText());
        assertAuthority();
    }

    @Test
    void shouldRefuseProjectionBeforeResultAndRejectInvalidReference() {
        var mapper = new ObjectMapper();
        var observation = mapper.createObjectNode();
        assertThrows(IllegalArgumentException.class,
                () -> journal.appendToolObservation(SCOPE, "tool-1", "context-policy-1", observation));
        observation.putObject("externalization").set("artifact", mapper.valueToTree(
                new ArtifactRef(DIGEST, DIGEST, 100, "application/json")));
        assertThrows(IllegalStateException.class,
                () -> journal.appendToolObservation(SCOPE, "tool-1", "context-policy-1", observation));
        org.mockito.Mockito.verifyNoInteractions(appender);
    }

    @Test
    void shouldResolveArtifactOnlyFromGivenSessionProjection() {
        var mapper = new ObjectMapper();
        var payload = mapper.createObjectNode();
        var reference = new ArtifactRef(DIGEST, DIGEST, 100, "application/json");
        payload.putObject("observation").putObject("externalization")
                .set("artifact", mapper.valueToTree(reference));
        when(stepMapper.selectArtifactProjection("session-1", DIGEST)).thenReturn(payload.toString());
        assertEquals(reference, journal.findArtifact("session-1", DIGEST).orElseThrow());
        assertTrue(journal.findArtifact("other-session", DIGEST).isEmpty());
        when(stepMapper.selectArtifactProjection("session-1", DIGEST)).thenReturn("{}");
        assertThrows(IllegalStateException.class, () -> journal.findArtifact("session-1", DIGEST));
    }

    private AgentEventAppender.AppendCommand capturedCommand() {
        ArgumentCaptor<AgentEventAppender.AppendCommand> command =
                ArgumentCaptor.forClass(AgentEventAppender.AppendCommand.class);
        verify(appender).appendFence(command.capture(), any());
        return command.getValue();
    }

    private void assertAuthority() {
        ArgumentCaptor<AgentEventAppender.RunExecutionAuthority> authority =
                ArgumentCaptor.forClass(
                        AgentEventAppender.RunExecutionAuthority.class
                );
        verify(appender).appendFence(any(), authority.capture());
        assertEquals(7L, authority.getValue().fencingToken());
        assertEquals("worker-1", authority.getValue().workerId());
    }
}
