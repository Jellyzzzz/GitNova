package com.gitnova.service.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.dto.ToolCall;
import com.gitnova.service.agent.AgentTestExecutionConfigs;
import com.gitnova.service.agent.completion.*;
import com.gitnova.service.agent.execution.AgentExecutionControl;
import com.gitnova.service.agent.journal.*;
import com.gitnova.service.agent.model.*;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.persistence.CanonicalJsonCodec;
import com.gitnova.service.agent.prompt.AssembledPrompt;
import com.gitnova.service.agent.prompt.PromptAssembler;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolSetResolver;
import com.gitnova.service.agent.tools.FinishTaskTool;
import com.gitnova.service.agent.workspace.*;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentRuntimeJournalTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ModelGateway model = mock(ModelGateway.class);
    private final ToolRegistry tools = mock(ToolRegistry.class);
    private final RunJournal journal = mock(RunJournal.class);
    private final CompletionInspector inspector = mock(CompletionInspector.class);
    private final List<String> actions = new ArrayList<>();
    private final List<ModelCallStartedPayload> intents = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong(2);
    private final RunJournalScope scope = new RunJournalScope("session", "task", "run", "worker", 1, "a".repeat(64));
    private AgentRuntime runtime;
    private AgentExecutionContext context;
    private final WorkspaceGateway workspace = mock(WorkspaceGateway.class);
    private final PromptAssembler prompt = mock(PromptAssembler.class);
    private final ToolSetResolver resolver = mock(ToolSetResolver.class);
    private String failAt;

    @BeforeEach
    void setUp() {
        when(workspace.refreshWorkspace(any())).thenReturn(new WorkspaceGateway.WorkspaceRefresh(0, 0, false));
        when(prompt.assemble(any())).thenReturn(new AssembledPrompt("policy", "Use tools then finish."));
        when(resolver.resolve(any(), any())).thenReturn(List.of(new FinishTaskTool(mapper).definition()));
        when(tools.isTerminal("finishTask")).thenReturn(true);
        when(tools.execute(any(), anyString(), any())).thenAnswer(call -> {
            actions.add("execute:" + call.getArgument(1));
            return ToolResult.success(mapper.createObjectNode().put("generation", 0));
        });
        var draft = new AgentCompletionDraft(0, "Observed repository state", List.of(), List.of(), List.of(), List.of(), List.of());
        var outcome = new AgentCompletionOutcome(CompletionDisposition.NO_CHANGES, draft,
                new WorkspaceGateway.WorkspaceDiff(0, List.of(), 0, 0, 0, false, ""), null);
        when(inspector.inspect(any(), any(), any())).thenReturn(CompletionDecision.accepted(outcome));
        when(journal.appendModelCallStarted(any(), any())).thenAnswer(call -> {
            intents.add(call.getArgument(1)); return commit("started");
        });
        when(journal.appendModelResponse(any(), any())).thenAnswer(call -> commit("response"));
        when(journal.appendModelCallFailed(any(), anyString(), any())).thenAnswer(call -> commit("failed"));
        when(journal.appendToolResult(any(), any())).thenAnswer(call -> commit("result"));
        when(journal.appendHarnessFeedback(any(), any(), nullable(String.class))).thenAnswer(call -> commit("feedback"));
        when(journal.appendCompletionDecision(any(), any())).thenAnswer(call -> commit("decision"));
        when(model.complete(any())).thenAnswer(call -> {
            actions.add("model");
            long number = actions.stream().filter("model"::equals).count();
            return response(number == 1 ? "read" : "finish", number == 1 ? "readFile" : "finishTask");
        });
        runtime = new AgentRuntime(model, prompt, new MessageFactory(mapper), tools, workspace,
                inspector, resolver, journal, new CanonicalJsonCodec(mapper));
        WorkspaceId id = WorkspaceId.generate();
        context = new AgentExecutionContext("session", new AgentRunContext("run", 1L, "1/1",
                SnapshotScope.of("a".repeat(40))), 1L, "Inspect repository", new WorkspaceBinding(id),
                new WorkspaceExecutionPermit("run", id, 1), new AgentExecutionConfig(
                AgentTestExecutionConfigs.defaultPolicy(), AgentCapabilityPolicy.cloudAgent().granted(),
                new ToolSetSnap(1, List.of("finishTask", "readFile"), "a".repeat(64)), "1"));
    }

    @Test
    void committedNaturalAnswerEndsWithoutCompletionDecision() {
        when(resolver.resolve(any(), any())).thenReturn(List.of());
        doAnswer(call -> {
            actions.add("model");
            return new ModelResponse("answer", "Changed the parser; tests were not run.", List.of(),
                    ModelUsage.unknown(), ModelFinishReason.STOP);
        }).when(model).complete(any());

        AgentRunResult result = run();

        assertEquals(AgentTerminationReason.ANSWER_DELIVERED, result.terminationReason());
        assertEquals("Changed the parser; tests were not run.", result.answer().content());
        assertEquals("run:turn0:call1", result.answer().modelCallId());
        assertEquals(List.of("started", "model", "response"), actions);
        verify(journal).appendModelResponse(eq(scope), argThat(payload ->
                payload.finishReason() == ModelFinishReason.STOP && payload.text().equals(result.answer().content())));
        verify(journal, never()).appendCompletionDecision(any(), any());
        verify(tools, never()).execute(any(), anyString(), any());
    }

    @Test
    void commitsEveryBoundaryBeforeProceedingAndPersistsAcceptedOutcome() {
        var result = run();
        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(List.of("started", "model", "response", "execute:readFile", "result",
                "started", "model", "response", "execute:finishTask", "result", "decision"), actions);
        assertEquals(2, intents.get(0).contextThroughRunStepSequence());
        assertEquals(5, intents.get(1).contextThroughRunStepSequence());
        assertTrue(intents.get(0).requestDigest().matches("[a-f0-9]{64}"));
        verify(journal).appendCompletionDecision(eq(scope), argThat(payload ->
                payload.decision().outcome().draft().summary().equals("Observed repository state")));
    }

    @Test
    void interruptedEmptyResponseIsStillJournaledBeforeTermination() {
        var usage = new ModelUsage(20000, 2048, 22048);
        doReturn(new ModelResponse("interrupted", null, List.of(), usage, ModelFinishReason.LENGTH)).when(model).complete(any());

        var result = run();

        assertEquals(AgentTerminationReason.MODEL_OUTPUT_LENGTH, result.terminationReason());
        assertEquals(List.of("started", "response"), actions);
        verify(journal).appendModelResponse(eq(scope), argThat(payload -> payload.finishReason() == ModelFinishReason.LENGTH
                && payload.toolCalls().isEmpty() && payload.usage().equals(usage)));
        verify(tools, never()).execute(any(), anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"started", "response", "result", "decision"})
    void stopsImmediatelyWhenJournalCannotCommit(String boundary) {
        failAt = boundary;
        assertThrows(IllegalStateException.class, this::run);
        assertEquals(boundary, actions.get(actions.size() - 1));
        if (boundary.equals("started")) verifyNoInteractions(model);
        if (boundary.equals("response")) verify(tools, never()).execute(any(), anyString(), any());
        if (boundary.equals("result")) verify(model, times(1)).complete(any());
    }

    @Test
    void feedbackMustCommitBeforeAnotherModelCall() {
        doReturn(new ModelResponse("text", "Done", List.of(),
                ModelUsage.unknown(), ModelFinishReason.STOP)).when(model).complete(any());
        failAt = "feedback";
        assertThrows(IllegalStateException.class, this::run);
        verify(model, times(1)).complete(any());
    }

    @Test
    void rejectedResponseCanBeCorrectedAfterDurableFailureAndFeedback() {
        useLimits(20, 1);
        doThrow(invalidResponse()).doReturn(response("finish", "finishTask")).when(model).complete(any());

        var result = run();

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(ProtocolDeviation.INVALID_MODEL_RESPONSE, result.lastProtocolDeviation());
        assertEquals(2, result.modelCallCount());
        assertEquals(1, result.toolCallCount());
        assertEquals(ModelUsage.unknown(), result.modelUsages().get(0));
        assertEquals(List.of("started", "failed", "feedback", "started", "response",
                "execute:finishTask", "result", "decision"), actions);
        var requests = ArgumentCaptor.forClass(ModelRequest.class);
        verify(model, times(2)).complete(requests.capture());
        var corrected = requests.getAllValues().get(1);
        assertEquals(List.of(ModelRole.SYSTEM, ModelRole.USER, ModelRole.USER),
                corrected.messages().stream().map(ModelMessage::role).toList());
        assertTrue(corrected.messages().get(2).content().contains("column=4267"));
        assertTrue(corrected.messages().get(2).content().contains("NO tool"));
        assertFalse(corrected.messages().toString().contains("secret-"));
        assertNotEquals(requests.getAllValues().get(0).requestId(), corrected.requestId());
        assertEquals(5, intents.get(1).contextThroughRunStepSequence());
        verify(journal).appendHarnessFeedback(eq(scope), argThat(payload ->
                payload.kind() == HarnessFeedbackKind.MODEL_RESPONSE_CORRECTION),
                eq("run:run:model-call:run:turn0:call1:failed"));
        verify(journal, times(1)).appendModelResponse(eq(scope), any());
    }

    @Test
    void repeatedParseFailureStopsAfterTheConfiguredCorrection() {
        useLimits(20, 1);
        doThrow(invalidResponse()).when(model).complete(any());

        var result = run();

        assertEquals(AgentTerminationReason.MODEL_GATEWAY_FAILURE, result.terminationReason());
        assertEquals(2, result.modelCallCount());
        assertEquals(2, result.modelUsages().size());
        assertEquals(List.of("started", "failed", "feedback", "started", "failed"), actions);
        verify(journal, never()).appendModelResponse(any(), any());
        verify(tools, never()).execute(any(), anyString(), any());
    }

    @ParameterizedTest
    @CsvSource({"20,0", "1,1"})
    void correctionNeverExceedsFrozenProtocolOrModelBudgets(int modelLimit, int correctionLimit) {
        useLimits(modelLimit, correctionLimit);
        doThrow(invalidResponse()).when(model).complete(any());
        assertEquals(AgentTerminationReason.MODEL_GATEWAY_FAILURE, run().terminationReason());
        assertEquals(List.of("started", "failed"), actions);
        verify(model, times(1)).complete(any());
    }

    @Test
    void malformedResponseSharesTheExistingProtocolCorrectionBudget() {
        useLimits(20, 1);
        doReturn(new ModelResponse("stop", "Done", List.of(), ModelUsage.unknown(), ModelFinishReason.STOP))
                .doThrow(invalidResponse()).when(model).complete(any());
        assertEquals(AgentTerminationReason.MODEL_GATEWAY_FAILURE, run().terminationReason());
        verify(model, times(2)).complete(any());
        verify(journal, times(1)).appendHarnessFeedback(any(), any(), anyString());
    }

    @ParameterizedTest
    @EnumSource(value = ModelGatewayErrorCode.class, mode = EnumSource.Mode.EXCLUDE, names = "INVALID_RESPONSE")
    void otherGatewayFailuresDoNotTriggerModelFormatCorrection(ModelGatewayErrorCode code) {
        doThrow(new ModelGatewayException(code, "provider detail", true, null)).when(model).complete(any());
        assertEquals(AgentTerminationReason.MODEL_GATEWAY_FAILURE, run().terminationReason());
        assertEquals(List.of("started", "failed"), actions);
        verify(model, times(1)).complete(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "feedback"})
    void correctionCannotProceedIfItsFactsWereNotCommitted(String boundary) {
        failAt = boundary;
        doThrow(invalidResponse()).when(model).complete(any());
        assertThrows(IllegalStateException.class, this::run);
        assertEquals(boundary, actions.get(actions.size() - 1));
        verify(model, times(1)).complete(any());
    }

    @Test
    void lostLeaseStopsCorrectionBeforePublishingFeedbackOrCallingAgain() {
        var control = new AgentExecutionControl();
        doAnswer(call -> { control.markLeaseLost(); throw invalidResponse(); }).when(model).complete(any());
        assertThrows(AgentExecutionControl.LeaseLostException.class, () -> runtime.run(context, control, scope, 2));
        verify(journal, never()).appendModelCallFailed(any(), anyString(), any());
        verify(journal, never()).appendHarnessFeedback(any(), any(), nullable(String.class));
        verify(model, times(1)).complete(any());
    }

    @Tag("gateway")
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realHttpParserCanRejectThenRegenerateWithoutReplayingAcceptedTools(boolean malformedArguments) throws Exception {
        useLimits(20, 1);
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setBody("""
                    {"id":"read","choices":[{"finish_reason":"tool_calls","message":{
                      "content":null,"reasoning_content":" original thinking ","tool_calls":[
                        {"id":"read-1","function":{"name":"readFile","arguments":"{}"}}]}}]}
                    """));
            // Even the valid first call in a rejected batch must not execute.
            var rejected = mapper.createObjectNode().put("id", "rejected");
            var message = rejected.putArray("choices").addObject().put("finish_reason", "tool_calls").putObject("message");
            message.put("reasoning_content", "rejected thinking");
            var calls = message.putArray("tool_calls");
            calls.addObject().put("id", "write-1").putObject("function").put("name", "applyPatch").put("arguments", "{}");
            calls.addObject().put("id", "bad-1").putObject("function").put("name", "finishTask")
                    .put("arguments", "{\"summary\":\"secret-unclosed");
            server.enqueue(new MockResponse().setBody(malformedArguments ? rejected.toString() : "{invalid response"));
            server.enqueue(new MockResponse().setBody("""
                    {"id":"fixed","choices":[{"finish_reason":"tool_calls","message":{
                      "content":null,"reasoning_content":"new thinking","tool_calls":[
                        {"id":"finish","function":{"name":"finishTask","arguments":"{}"}}]}}]}
                    """));
            var gateway = new OpenAiCompatibleModelGateway(mapper, "test-key", server.url("/").toString(), 5, "enabled");
            runtime = new AgentRuntime(gateway, prompt, new MessageFactory(mapper), tools, workspace,
                    inspector, resolver, journal, new CanonicalJsonCodec(mapper));

            var result = run();

            assertEquals(AgentRunStatus.COMPLETED, result.status());
            assertEquals(3, result.modelCallCount());
            assertEquals(2, result.toolCallCount());
            verify(tools, times(1)).execute(any(), eq("readFile"), any());
            verify(tools, never()).execute(any(), eq("applyPatch"), any());
            verify(journal, times(2)).appendModelResponse(any(), any());
            verify(journal, times(2)).appendToolResult(any(), any());
            server.takeRequest(2, TimeUnit.SECONDS);
            server.takeRequest(2, TimeUnit.SECONDS);
            var correction = mapper.readTree(server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8());
            var transcript = correction.path("messages");
            assertEquals(5, transcript.size());
            assertEquals(" original thinking ", transcript.get(2).path("reasoning_content").asText());
            assertEquals("read-1", transcript.get(3).path("tool_call_id").asText());
            assertEquals("user", transcript.get(4).path("role").asText());
            assertTrue(transcript.get(4).path("content").asText().contains(malformedArguments
                    ? "TOOL_ARGUMENTS_JSON_INVALID" : "RESPONSE_JSON_INVALID"));
            assertFalse(correction.toString().contains("secret-unclosed"));
            assertFalse(correction.toString().contains("rejected thinking"));
            assertEquals(3, server.getRequestCount());
            verify(journal).appendHarnessFeedback(eq(scope), any(), eq("run:run:model-call:run:turn1:call2:failed"));
        }
    }

    @Test
    void rejectedCompletionRecordsDecisionAndCausalFeedbackBeforeCorrection() {
        var accepted = inspector.inspect(context, new RunStateView(java.util.Optional.empty()),
                ToolResult.success(mapper.createObjectNode()));
        when(inspector.inspect(any(), any(), any())).thenReturn(CompletionDecision.correctable("Refresh evidence"), accepted);
        doReturn(response("finish-one", "finishTask"), response("finish-two", "finishTask"))
                .when(model).complete(any());
        assertEquals(AgentRunStatus.COMPLETED, run().status());
        verify(journal, times(2)).appendCompletionDecision(eq(scope), any());
        verify(journal).appendHarnessFeedback(eq(scope), argThat(payload ->
                payload.kind() == HarnessFeedbackKind.COMPLETION_CORRECTION),
                eq("run:run:tool-call:finish-one:result"));
        assertEquals(List.of("started", "response", "execute:finishTask", "result", "decision", "feedback",
                "started", "response", "execute:finishTask", "result", "decision"), actions);
    }

    @Test
    void mixedTerminalRejectionsAreJournaledWithoutExecutingRejectedCalls() {
        var mixed = new ModelResponse("mixed", "", List.of(
                new ToolCall("mixed-read", "readFile", mapper.createObjectNode()),
                new ToolCall("mixed-finish", "finishTask", mapper.createObjectNode())),
                ModelUsage.unknown(), ModelFinishReason.TOOL_CALLS);
        doReturn(mixed, response("finish-alone", "finishTask")).when(model).complete(any());
        assertEquals(AgentRunStatus.COMPLETED, run().status());
        verify(tools, times(1)).execute(any(), eq("finishTask"), any());
        verify(tools, never()).execute(any(), eq("readFile"), any());
        verify(journal, times(3)).appendToolResult(eq(scope), any());
    }

    @Test
    void rejectsDuplicateToolIdentityBeforeExecutingAgain() {
        doReturn(response("repeated", "readFile")).when(model).complete(any());
        assertEquals(AgentTerminationReason.INVALID_MODEL_PROTOCOL, run().terminationReason());
        verify(tools, times(1)).execute(any(), anyString(), any());
    }

    @Test
    void productionCannotSilentlyUseStandaloneEntryPoint() {
        assertThrows(IllegalStateException.class, () -> runtime.run(context));
        verifyNoInteractions(model);
    }

    @Test
    void committedIntentIsNotAutomaticallyReplayedWithoutAProjector() {
        doReturn(new AgentEventAppender.AppendResult(3, 3, 3L, true))
                .when(journal).appendModelCallStarted(any(), any());
        assertThrows(IllegalStateException.class, this::run);
        verifyNoInteractions(model);
    }

    @Test
    void recoveryStopsWithoutCallingModelOrToolsUntilHistoryCanBeProjected() {
        when(journal.hasModelHistory(scope)).thenReturn(true);
        assertEquals(AgentTerminationReason.RECOVERY_CONTEXT_REQUIRED, run().terminationReason());
        verifyNoInteractions(model);
        verify(tools, never()).execute(any(), anyString(), any());
    }

    private AgentRunResult run() {
        return runtime.run(context, new AgentExecutionControl(), scope, 2);
    }

    private ModelGatewayException invalidResponse() {
        return new ModelGatewayException(ModelGatewayErrorCode.INVALID_RESPONSE, "secret-provider-message", false,
                200, "secret-provider-code", "secret-provider-header", null, null,
                new ModelGatewayException.ResponseDiagnostic("TOOL_ARGUMENTS_JSON_INVALID",
                        "/choices/0/message/tool_calls/0/function/arguments", 1, 4267));
    }

    private void useLimits(int modelLimit, int correctionLimit) {
        var config = context.executionConfig();
        var policy = new AgentRuntimePolicy("fake-model", modelLimit, 50, correctionLimit, 2, 4096, 0.0,
                new ModelThinking("enabled", "high"));
        context = new AgentExecutionContext(context.sessionId(), context.context(), context.actorId(), context.taskText(),
                context.workspace(), context.executionPermit(), new AgentExecutionConfig(policy,
                config.capabilities(), config.toolSet(), config.contextPolicyVersion()));
    }

    private ModelResponse response(String id, String name) {
        return new ModelResponse("response-" + id, "", List.of(new ToolCall(id, name, mapper.createObjectNode())),
                ModelUsage.unknown(), ModelFinishReason.TOOL_CALLS);
    }

    private AgentEventAppender.AppendResult commit(String boundary) {
        actions.add(boundary);
        if (boundary.equals(failAt)) throw new IllegalStateException("simulated database failure");
        long seq = sequence.incrementAndGet();
        return new AgentEventAppender.AppendResult(seq, seq, seq, false);
    }
}
