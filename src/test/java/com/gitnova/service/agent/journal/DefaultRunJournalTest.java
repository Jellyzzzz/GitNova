package com.gitnova.service.agent.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.mapper.agent.AgentStepMapper;
import com.gitnova.storage.artifact.ArtifactRef;
import com.gitnova.service.agent.completion.CompletionDecision;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.persistence.AgentStepType;
import com.gitnova.service.agent.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
