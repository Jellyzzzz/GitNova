package com.gitnova.service.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.dto.ToolCall;
import com.gitnova.entity.agent.AgentSessionEntity;
import com.gitnova.entity.agent.AgentStepEntity;
import com.gitnova.mapper.agent.AgentSessionMapper;
import com.gitnova.mapper.agent.AgentStepMapper;
import com.gitnova.service.agent.journal.*;
import com.gitnova.service.agent.model.*;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.persistence.CanonicalJsonCodec;
import com.gitnova.service.agent.prompt.AssembledPrompt;
import com.gitnova.service.agent.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionContextServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentStepMapper steps = mock(AgentStepMapper.class);
    private final AgentSessionMapper sessions = mock(AgentSessionMapper.class);
    private final AgentEventAppender appender = mock(AgentEventAppender.class);
    private final SessionContextService service = new SessionContextService(steps, sessions, appender, mapper, new MessageFactory(mapper));
    private final List<AgentStepEntity> history = new ArrayList<>();
    private final RunJournalScope scope = new RunJournalScope("session", "task-b", "run-b", "worker", 7, "a".repeat(64));

    @BeforeEach
    void setUp() {
        when(steps.latestSessionSequence("session")).thenAnswer(call -> (long) history.size());
        when(steps.selectSessionHistory(eq("session"), anyLong(), anyLong(), eq(256))).thenAnswer(call ->
                history.stream().filter(row -> row.getSessionSequence() > (long) call.getArgument(1)
                        && row.getSessionSequence() <= (long) call.getArgument(2)).limit(256).toList());
    }

    @Test
    void shouldPreserveUserTasksAndPairTheCommittedPreviewAcrossRuns() {
        user("task-a", "Do not change the public API");
        var response = response("call-1", "readFile");
        add("MODEL_RESPONSE", "task-a", "run-a", response);
        var raw = add("TOOL_RESULT", "task-a", "run-a", new ToolResultPayload("model-1", "call-1", "readFile",
                ToolResult.success(mapper.createObjectNode().put("text", "BIG ORIGINAL"))));
        var projected = mapper.createObjectNode().put("toolCallId", "call-1");
        projected.putObject("observation").put("preview", "small").put("artifactId", "ref");
        add("TOOL_OBSERVATION_PROJECTED", "task-a", "run-a", projected).setCausationEventId(raw.getEventId());
        user("task-b", "Add tests for the previous change");

        var snapshot = service.load("session");
        assertEquals(5, snapshot.throughSessionSequence());
        assertEquals(1, snapshot.groups().size());
        assertEquals(4, snapshot.groups().get(0).lastSessionSequence());
        assertTrue(snapshot.groups().get(0).closed());
        var messages = snapshot.modelMessages(new AssembledPrompt("p", "System"), "task-b", "Add tests for the previous change");
        assertEquals(List.of(ModelRole.SYSTEM, ModelRole.USER, ModelRole.ASSISTANT, ModelRole.TOOL, ModelRole.USER),
                messages.stream().map(ModelMessage::role).toList());
        assertFalse(messages.toString().contains("BIG ORIGINAL"));
        assertEquals("call-1", messages.get(3).toolCallId());
        assertEquals(1, messages.stream().filter(message -> message.content() != null
                && message.content().equals("Add tests for the previous change")).count());

        var input = snapshot.summaryInput("Add tests", snapshot.groups());
        assertEquals("Do not change the public API", input.userMessages().get(0).text());
        assertEquals(1, input.userMessages().size());
        assertThrows(IllegalStateException.class,
                () -> snapshot.modelMessages(new AssembledPrompt("p", "System"), "task-b", "Different request"));
    }

    @Test
    void shouldRejectUnresolvedCallsAndOrphanOrDuplicateResults() {
        add("MODEL_RESPONSE", "task-a", "run-a", response("call-1", "applyPatch"));
        assertThrows(IllegalStateException.class, () -> service.load("session"));
        add("TOOL_RESULT", "task-a", "run-a", new ToolResultPayload("model-1", "wrong-id", "applyPatch",
                ToolResult.success(mapper.createObjectNode())));
        assertThrows(IllegalStateException.class, () -> service.load("session"));
        history.clear();
        add("TOOL_RESULT", "task-a", "run-a", new ToolResultPayload("model-1", "call-1", "applyPatch",
                ToolResult.success(mapper.createObjectNode())));
        assertThrows(IllegalStateException.class, () -> service.load("session"));
    }

    @Test
    void shouldKeepInterleavedFeedbackAfterTheCompleteToolBatch() {
        add("MODEL_RESPONSE", "task-a", "run-a", response("call-1", "readFile"));
        add("HARNESS_FEEDBACK", "task-a", "run-a", new HarnessFeedbackPayload("f", HarnessFeedbackKind.WORKSPACE_DRIFT, "Generation changed"));
        add("TOOL_RESULT", "task-a", "run-a", new ToolResultPayload("model-1", "call-1", "readFile",
                ToolResult.success(mapper.createObjectNode())));
        var group = service.load("session").groups().get(0);
        assertTrue(group.closed());
        assertEquals(List.of(ModelRole.ASSISTANT, ModelRole.TOOL, ModelRole.USER), group.messages().stream().map(ModelMessage::role).toList());
    }

    @Test
    void shouldPageCommittedHistoryAndNeverReadAnotherSession() {
        for (int i = 0; i < 260; i++) add("RUN_QUEUED", "task-a", "run-a", mapper.createObjectNode());
        user("task-b", "Current task");
        assertEquals(261, service.load("session").throughSessionSequence());
        verify(steps).selectSessionHistory("session", 0, 261, 256);
        verify(steps).selectSessionHistory("session", 256, 261, 256);
        assertTrue(service.load("other-session").messages().isEmpty());
        history.get(0).setSessionId("wrong-session");
        assertThrows(IllegalStateException.class, () -> service.load("session"));
    }

    @Test
    void shouldRestoreUsageOnlyFromTheMatchingCommittedMainResponse() {
        var request = new ModelRequest("model", List.of(new ModelMessage(ModelRole.SYSTEM, "System", List.of(), null)),
                List.of(), 100, null, "id");
        var measured = new ContextUsage(new TokenEstimator(), new CanonicalJsonCodec(mapper), null).measure(request);
        var payload = mapper.createObjectNode().put("modelCallId", "model-1");
        payload.set("contextInput", mapper.valueToTree(measured));
        add("MODEL_CALL_STARTED", "task-a", "run-a", payload).setSchemaVersion(2);
        add("MODEL_RESPONSE", "task-a", "run-a", new ModelResponsePayload("model-1", "r", "Done", List.of(),
                new ModelUsage(300, 2, 302), ModelFinishReason.STOP));
        assertEquals(300, service.load("session").usageAnchor().inputTokens());
        history.get(1).setSchemaVersion(99);
        assertThrows(IllegalStateException.class, () -> service.load("session"));
    }

    @Test
    void shouldPublishWithFenceAndRetainAllStepsAfterTheSummaryCoverage() {
        user("task-a", "Old constraint");
        add("MODEL_RESPONSE", "task-a", "run-a", new ModelResponsePayload("m", "r", "Completed investigation", List.of(),
                ModelUsage.unknown(), ModelFinishReason.STOP));
        var source = service.load("session");
        user("task-b", "New request arriving while summary was generated");
        var row = new AgentSessionEntity();
        row.setLastSessionSequence(3L);
        when(sessions.selectForUpdate("session")).thenReturn(row);
        var result = new AgentEventAppender.AppendResult(4, 4, 1L, false);
        when(appender.appendFence(any(), any())).thenReturn(result);
        var summary = new ContextSummary("s1", "session", null, 2, "Old constraint and completed investigation");
        assertEquals(result, service.publishSummary(scope, source, summary));
        var command = ArgumentCaptor.forClass(AgentEventAppender.AppendCommand.class);
        verify(appender).appendFence(command.capture(), eq(new AgentEventAppender.RunExecutionAuthority(7, "worker")));
        assertEquals("CONTEXT_SUMMARY_CREATED", command.getValue().stepType().name());

        var summaryRow = add("CONTEXT_SUMMARY_CREATED", "task-b", "run-b", summary);
        when(steps.selectLatestContextSummary(eq("session"), anyLong())).thenReturn(summaryRow);
        var restored = service.load("session");
        assertEquals(summary, restored.summary());
        assertEquals(1, restored.messages().size());
        assertTrue(restored.messages().get(0).content().contains("New request"));
        assertEquals(4, history.size()); // Original source Steps were not deleted or rewritten.
        assertThrows(IllegalStateException.class, () -> service.publishSummary(scope, source,
                new ContextSummary("other", "session", null, 2, "Competing summary")));
    }

    @Test
    void shouldNotPublishOnCommitFailureOrPermitPartialGroupCoverage() {
        user("task-a", "Task");
        add("MODEL_RESPONSE", "task-a", "run-a", response("c", "readFile"));
        add("TOOL_RESULT", "task-a", "run-a", new ToolResultPayload("model-1", "c", "readFile", ToolResult.success(mapper.createObjectNode())));
        var source = service.load("session");
        assertThrows(IllegalArgumentException.class, () -> service.publishSummary(scope, source,
                new ContextSummary("s", "session", null, 2, "Cut inside a group")));
        var session = new AgentSessionEntity();
        session.setLastSessionSequence(3L);
        when(sessions.selectForUpdate("session")).thenReturn(session);
        when(appender.appendFence(any(), any())).thenThrow(new IllegalStateException("Database unavailable"));
        assertThrows(IllegalStateException.class, () -> service.publishSummary(scope, source,
                new ContextSummary("s", "session", null, 3, "Summary")));
        assertNull(service.load("session").summary());
    }

    @Test
    void candidateSummaryPreservesTheWholeRecentToolGroupAndInterveningUserMessage() {
        user("task-a", "Old task");
        add("MODEL_RESPONSE", "task-a", "run-a", response("old-call", "readFile"));
        add("TOOL_RESULT", "task-a", "run-a", new ToolResultPayload("model-1", "old-call", "readFile",
                ToolResult.success(mapper.createObjectNode().put("text", "old content"))));
        user("task-b", "Keep this exact constraint");
        add("MODEL_RESPONSE", "task-b", "run-b", response("recent-call", "readFile"));
        add("TOOL_RESULT", "task-b", "run-b", new ToolResultPayload("model-1", "recent-call", "readFile",
                ToolResult.success(mapper.createObjectNode().put("text", "recent content"))));
        var snapshot = service.load("session");
        var system = new ModelMessage(ModelRole.SYSTEM, "Policy", List.of(), null);
        var candidate = new ContextSummary("s", "session", null, 3, "Old task complete");
        var messages = snapshot.modelMessages(system, candidate, "task-b", "Keep this exact constraint");
        assertEquals(List.of(ModelRole.SYSTEM, ModelRole.USER, ModelRole.USER, ModelRole.ASSISTANT, ModelRole.TOOL),
                messages.stream().map(ModelMessage::role).toList());
        assertEquals("recent-call", messages.get(3).toolCalls().get(0).id());
        assertEquals("recent-call", messages.get(4).toolCallId());
        assertFalse(messages.toString().contains("old content"));
        assertTrue(messages.toString().contains("recent content"));
        assertThrows(IllegalArgumentException.class, () -> snapshot.modelMessages(system,
                new ContextSummary("bad", "session", null, 2, "Cut before tool result"), "task-b", "Keep this exact constraint"));
        assertNull(snapshot.summary());
        verifyNoInteractions(appender);
    }

    private ModelResponsePayload response(String id, String name) {
        return new ModelResponsePayload("model-1", "response", "", List.of(new ToolCall(id, name, mapper.createObjectNode())),
                ModelUsage.unknown(), ModelFinishReason.TOOL_CALLS);
    }

    private void user(String taskId, String text) {
        var payload = mapper.createObjectNode();
        payload.putObject("request").put("message", text);
        add("USER_MESSAGE_RECEIVED", taskId, null, payload);
    }

    private AgentStepEntity add(String type, String taskId, String runId, Object payload) {
        var row = new AgentStepEntity();
        row.setSessionId("session");
        row.setTaskId(taskId);
        row.setRunId(runId);
        row.setEventId("event-" + (history.size() + 1));
        row.setSessionSequence((long) history.size() + 1);
        row.setSchemaVersion(1);
        row.setStepType(type);
        row.setPayloadJson(mapper.valueToTree(payload).toString());
        history.add(row);
        return row;
    }
}
