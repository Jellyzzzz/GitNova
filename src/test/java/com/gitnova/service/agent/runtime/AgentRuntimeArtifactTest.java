package com.gitnova.service.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.dto.ToolCall;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.AgentTestExecutionConfigs;
import com.gitnova.service.agent.completion.*;
import com.gitnova.service.agent.context.ObservationPolicy;
import com.gitnova.service.agent.context.TokenEstimator;
import com.gitnova.service.agent.context.ToolObservationPreview;
import com.gitnova.service.agent.context.ContextBudget;
import com.gitnova.service.agent.context.SessionContextService;
import com.gitnova.service.agent.context.ContextSummary;
import com.gitnova.service.agent.context.InteractionGroup;
import com.gitnova.service.agent.execution.AgentExecutionControl;
import com.gitnova.service.agent.journal.*;
import com.gitnova.service.agent.model.*;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.persistence.CanonicalJsonCodec;
import com.gitnova.service.agent.prompt.AssembledPrompt;
import com.gitnova.service.agent.prompt.PromptAssembler;
import com.gitnova.service.agent.tool.*;
import com.gitnova.service.agent.tools.ReadArtifactTool;
import com.gitnova.service.agent.workspace.*;
import com.gitnova.storage.artifact.ArtifactRef;
import com.gitnova.storage.artifact.LocalArtifactStore;
import com.gitnova.storage.config.ArtifactStorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Registry/preview/filesystem/reader; scripted model and Journal commit boundary. No external services. */
class AgentRuntimeArtifactTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RunJournal journal = mock(RunJournal.class);
    private final ModelGateway model = mock(ModelGateway.class);
    private final AgentTool command = mock(AgentTool.class);
    private final AgentTool finish = mock(AgentTool.class);
    private final CompletionInspector inspector = mock(CompletionInspector.class);
    private final List<ModelRequest> requests = new ArrayList<>();
    private final List<ToolResultPayload> results = new ArrayList<>();
    private final List<JsonNode> projections = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final RunJournalScope scope = new RunJournalScope("session", "task", "run", "worker", 1, "a".repeat(64));
    private final ToolObservationPreview preview = new ToolObservationPreview(mapper, new TokenEstimator());
    private long sequence = 2;
    private LocalArtifactStore store;
    private ToolRegistry registry;
    private AgentRuntime runtime;
    private AgentExecutionContext context;
    private ToolResult original;
    private WorkspaceGateway workspace;
    private PromptAssembler prompt;

    @BeforeEach
    void setUp() {
        store = spy(new LocalArtifactStore(new ArtifactStorageProperties(root, 1024 * 1024, 4096), mapper));
        var schema = mapper.createObjectNode().put("type", "object");
        when(command.definition()).thenReturn(new ToolDefinition("runCommand", "Test command", schema));
        when(command.requiredCapabilities()).thenReturn(java.util.Set.of(AgentCapability.COMMAND_EXECUTE));
        when(finish.definition()).thenReturn(new ToolDefinition("finishTask", "Finish", schema));
        when(finish.requiredCapabilities()).thenReturn(java.util.Set.of(AgentCapability.CODE_READ));
        when(finish.terminal()).thenReturn(true);
        when(finish.execute(any(), any())).thenReturn(ToolResult.success(mapper.createObjectNode().put("generation", 7)));
        registry = new ToolRegistry(List.of(command, finish, new ReadArtifactTool(store, journal, mapper)));
        original = ToolResult.success(mapper.createObjectNode().put("status", "COMPLETED")
                .put("exitCode", 0).put("generationAfter", 7).put("durationMillis", 12)
                .put("stdout", "test output: stack frame 中文 line\n".repeat(1000)).put("stderr", ""));
        when(command.execute(any(), any())).thenAnswer(call -> { events.add("execute"); return original; });
        workspace = mock(WorkspaceGateway.class);
        when(workspace.refreshWorkspace(any())).thenReturn(new WorkspaceGateway.WorkspaceRefresh(7, 7, false));
        prompt = mock(PromptAssembler.class);
        when(prompt.assemble(any())).thenReturn(new AssembledPrompt("policy", "Use tools then finish."));
        var draft = new AgentCompletionDraft(7, "Done", List.of(), List.of(), List.of(), List.of(), List.of());
        when(inspector.inspect(any(), any(), any())).thenReturn(CompletionDecision.accepted(
                new AgentCompletionOutcome(CompletionDisposition.NO_CHANGES, draft,
                        new WorkspaceGateway.WorkspaceDiff(7, List.of(), 0, 0, 0, false, ""), null)));
        when(journal.appendModelCallStarted(any(), any())).thenAnswer(call -> commit("started"));
        when(journal.appendModelResponse(any(), any())).thenAnswer(call -> commit("response"));
        when(journal.appendToolResult(any(), any())).thenAnswer(call -> {
            results.add(call.getArgument(1)); return commit("result");
        });
        when(journal.appendToolObservation(any(), anyString(), anyString(), any())).thenAnswer(call -> {
            JsonNode observation = call.getArgument(3);
            // The content must already be fully published before its reference can commit.
            ArtifactRef ref = mapper.treeToValue(observation.path("externalization").path("artifact"), ArtifactRef.class);
            assertTrue(store.read(context, ref, 0, 4).nextOffset() > 0);
            projections.add(observation.deepCopy()); return commit("projection");
        });
        when(journal.appendCompletionDecision(any(), any())).thenAnswer(call -> commit("decision"));
        when(journal.findArtifact(eq("session"), anyString())).thenAnswer(call -> {
            for (JsonNode observation : projections) {
                ArtifactRef ref = mapper.treeToValue(observation.path("externalization").path("artifact"), ArtifactRef.class);
                if (ref.artifactId().equals(call.getArgument(1))) return Optional.of(ref);
            }
            return Optional.empty();
        });
        when(model.complete(any())).thenAnswer(call -> {
            requests.add(call.getArgument(0)); events.add("model");
            return requests.size() == 1 ? response("command", "runCommand") : response("finish", "finishTask");
        });
        runtime = new AgentRuntime(model, prompt, new MessageFactory(mapper), registry, workspace,
                inspector, AgentTestExecutionConfigs.resolver(registry), journal, new CanonicalJsonCodec(mapper), store, preview);
        context = context(new ObservationPolicy(1024, 512), registry);
    }

    @Test
    void shouldInheritAnotherTasksHistoryAndPersistMainUsageMeasurements() {
        var contexts = mock(SessionContextService.class);
        var historical = List.of(
                new ModelMessage(ModelRole.USER, "Do not change the public API", List.of(), null),
                new ModelMessage(ModelRole.ASSISTANT, "Previous task finished", List.of(), null),
                new ModelMessage(ModelRole.USER, context.taskText(), List.of(), null));
        when(contexts.load("session")).thenReturn(new SessionContextService.Snapshot("session", 20, null,
                List.of(new SessionContextService.TaskMessage("previous-task", 1, "Do not change the public API"),
                        new SessionContextService.TaskMessage("task", 20, context.taskText())),
                List.of(), List.of(
                        new SessionContextService.HistoryEntry(1, 1, historical.subList(0, 1)),
                        new SessionContextService.HistoryEntry(2, 2, historical.subList(1, 2)),
                        new SessionContextService.HistoryEntry(20, 20, historical.subList(2, 3))), null, null));
        sequence = 20;
        var config = context.executionConfig();
        context = new AgentExecutionContext(context.sessionId(), context.context(), context.actorId(), context.taskText(),
                context.workspace(), context.executionPermit(), new AgentExecutionConfig(config.policy(), config.capabilities(),
                config.toolSet(), config.contextPolicyVersion(), config.observationPolicy(), new ContextBudget(32000, 2000)));
        runtime = new AgentRuntime(model, prompt, new MessageFactory(mapper), registry, workspace, inspector,
                AgentTestExecutionConfigs.resolver(registry), journal, new CanonicalJsonCodec(mapper), store, preview,
                contexts, new TokenEstimator());
        doAnswer(call -> {
            requests.add(call.getArgument(0));
            var originalResponse = requests.size() == 1 ? response("command", "runCommand") : response("finish", "finishTask");
            return new ModelResponse(originalResponse.responseId(), originalResponse.text(), originalResponse.toolCalls(),
                    new ModelUsage(1500, 100, 1600), originalResponse.finishReason());
        }).when(model).complete(any());

        assertEquals(AgentRunStatus.COMPLETED, run().status());
        assertEquals(historical, requests.get(0).messages().subList(1, 4));
        assertEquals(1, requests.get(0).messages().stream().filter(m -> m.role() == ModelRole.SYSTEM).count());
        assertEquals(1, requests.get(0).messages().stream().filter(m -> context.taskText().equals(m.content())).count());
        var intents = ArgumentCaptor.forClass(ModelCallStartedPayload.class);
        verify(journal, times(2)).appendModelCallStarted(eq(scope), intents.capture());
        assertEquals(20, intents.getAllValues().get(0).contextThroughSessionSequence());
        assertEquals("LOCAL_ESTIMATE", intents.getAllValues().get(0).contextInput().source());
        assertEquals("PROVIDER_USAGE_PLUS_DELTA", intents.getAllValues().get(1).contextInput().source());
        assertTrue(intents.getAllValues().get(1).contextInput().estimatedInputTokens() > 1500);
        verify(contexts, times(1)).load("session"); // Live turns append; no full-history reload per model call.
    }

    @Test
    void shouldStopBeforeModelWhenSessionHistoryCannotBeSafelyProjected() {
        var contexts = mock(SessionContextService.class);
        when(contexts.load("session")).thenThrow(new IllegalStateException("Unresolved write"));
        var config = context.executionConfig();
        context = new AgentExecutionContext(context.sessionId(), context.context(), context.actorId(), context.taskText(),
                context.workspace(), context.executionPermit(), new AgentExecutionConfig(config.policy(), config.capabilities(),
                config.toolSet(), config.contextPolicyVersion(), config.observationPolicy(), new ContextBudget(32000, 2000)));
        runtime = new AgentRuntime(model, prompt, new MessageFactory(mapper), registry, workspace, inspector,
                AgentTestExecutionConfigs.resolver(registry), journal, new CanonicalJsonCodec(mapper), store, preview,
                contexts, new TokenEstimator());
        assertEquals(AgentTerminationReason.CONTEXT_PREPARATION_FAILURE, run().terminationReason());
        verifyNoInteractions(model);
        verify(command, never()).execute(any(), any());
    }

    @Test
    void summarizesBeforeMainCallAndKeepsCompactProjectionForFollowingTurns() {
        var contexts = mock(SessionContextService.class);
        var published = new AtomicReference<ContextSummary>();
        String oldText = "historical analysis ".repeat(5000);
        var oldUser = new ModelMessage(ModelRole.USER, "Keep public APIs", List.of(), null);
        var oldAnswer = new ModelMessage(ModelRole.ASSISTANT, oldText, List.of(), null);
        var currentUser = new ModelMessage(ModelRole.USER, context.taskText(), List.of(), null);
        var recentAnswer = new ModelMessage(ModelRole.ASSISTANT, "Ready to run tests", List.of(), null);
        var oldGroup = new InteractionGroup("old", List.of(oldAnswer), "previous", 2, 2);
        var recentGroup = new InteractionGroup("recent", List.of(recentAnswer), "task", 4, 4);
        var entries = List.of(new SessionContextService.HistoryEntry(1, 1, List.of(oldUser)),
                new SessionContextService.HistoryEntry(2, 2, oldGroup.messages()),
                new SessionContextService.HistoryEntry(3, 3, List.of(currentUser)),
                new SessionContextService.HistoryEntry(4, 4, recentGroup.messages()));
        when(contexts.load("session")).thenAnswer(call -> new SessionContextService.Snapshot("session", sequence,
                published.get(), List.of(new SessionContextService.TaskMessage("previous", 1, "Keep public APIs"),
                        new SessionContextService.TaskMessage("task", 3, context.taskText())),
                published.get() == null ? List.of(oldGroup, recentGroup) : List.of(recentGroup),
                published.get() == null ? entries : entries.subList(2, 4), null, null));
        when(contexts.saveControl(any(), anyString(), any())).thenAnswer(call -> commit("context-control"));
        when(contexts.recordSummaryResult(any(), anyString(), any(), anyString(), anyLong(), any())).thenAnswer(call -> commit("summary-result"));
        when(contexts.publishSummary(any(), any(), any())).thenAnswer(call -> {
            published.set(call.getArgument(2));
            return commit("summary-published");
        });
        sequence = 20;
        var config = context.executionConfig();
        context = new AgentExecutionContext(context.sessionId(), context.context(), context.actorId(), context.taskText(),
                context.workspace(), context.executionPermit(), new AgentExecutionConfig(config.policy(), config.capabilities(),
                config.toolSet(), config.contextPolicyVersion(), config.observationPolicy(), new ContextBudget(18000, 1000, .55, .85, 1)));
        runtime = new AgentRuntime(model, prompt, new MessageFactory(mapper), registry, workspace, inspector,
                AgentTestExecutionConfigs.resolver(registry), journal, new CanonicalJsonCodec(mapper), store, preview,
                contexts, new TokenEstimator());
        List<ModelRequest> summaryRequests = new ArrayList<>();
        doAnswer(call -> {
            ModelRequest request = call.getArgument(0);
            if (request.requestId().startsWith("run:summary:")) {
                summaryRequests.add(request);
                events.add("summary-model");
                return new ModelResponse("s", "Keep public APIs; investigation complete.", List.of(),
                        new ModelUsage(10000, 30, 10030), ModelFinishReason.STOP);
            }
            requests.add(request);
            assertFalse(request.messages().toString().contains(oldText));
            assertTrue(request.messages().toString().contains("<session_summary>"));
            var response = requests.size() == 1 ? response("command", "runCommand") : response("finish", "finishTask");
            return new ModelResponse(response.responseId(), response.text(), response.toolCalls(),
                    new ModelUsage(1500, 100, 1600), response.finishReason());
        }).when(model).complete(any());

        var result = run();
        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(2, result.modelCallCount());
        assertEquals(1, summaryRequests.size());
        assertEquals(2, requests.size());
        assertTrue(events.indexOf("summary-published") < events.indexOf("started"));
        var intents = ArgumentCaptor.forClass(ModelCallStartedPayload.class);
        verify(journal, times(2)).appendModelCallStarted(eq(scope), intents.capture());
        assertTrue(intents.getAllValues().get(0).contextThroughSessionSequence() > 20);
        assertEquals("PROVIDER_USAGE_PLUS_DELTA", intents.getAllValues().get(1).contextInput().source());
        assertTrue(intents.getAllValues().get(1).contextInput().estimatedInputTokens() < 10000);
        verify(contexts).publishSummary(eq(scope), any(), any());
    }

    @Test
    void externalizesBeforeNextRequestAndKeepsRawResultAndValidationEvidence() throws Exception {
        JsonNode before = mapper.valueToTree(original);
        assertEquals(AgentRunStatus.COMPLETED, run().status());
        assertEquals(List.of("started", "model", "response", "execute", "result", "projection",
                "started", "model", "response", "result", "decision"), events);
        assertEquals(original, results.get(0).result());
        assertEquals(before, mapper.valueToTree(original));
        var messages = requests.get(1).messages();
        assertEquals(List.of(ModelRole.SYSTEM, ModelRole.USER, ModelRole.ASSISTANT, ModelRole.TOOL),
                messages.stream().map(ModelMessage::role).toList());
        assertEquals("command", messages.get(3).toolCallId());
        JsonNode observation = mapper.readTree(messages.get(3).content());
        assertEquals(projections.get(0).toString(), messages.get(3).content());
        assertTrue(preview.estimateTokens(observation) <= 512);
        assertEquals(7, observation.path("payload").path("generationAfter").asInt());
        verify(journal).appendToolObservation(eq(scope), eq("command"), eq(context.executionConfig().contextPolicyVersion()), any());
        var view = ArgumentCaptor.forClass(RunStateView.class);
        verify(inspector).inspect(eq(context), view.capture(), eq(results.get(1).result()));
        assertEquals(7, view.getValue().latestSuccessfulValidation().orElseThrow().generation());
    }

    @Test
    void modelCanRequestStoredContentWithNewCallIdWithoutRecursiveExternalization() throws Exception {
        doAnswer(call -> {
            ModelRequest request = call.getArgument(0);
            requests.add(request);
            if (requests.size() == 1) return response("command", "runCommand");
            if (requests.size() == 2) {
                JsonNode observation = mapper.readTree(request.messages().get(3).content());
                String id = observation.path("externalization").path("artifact").path("artifactId").asText();
                return new ModelResponse("read", "", List.of(new ToolCall("artifact-read", "readArtifact",
                        mapper.createObjectNode().put("artifactId", id).put("offset", 0).put("maxBytes", 4096))),
                        ModelUsage.unknown(), ModelFinishReason.TOOL_CALLS);
            }
            return response("finish", "finishTask");
        }).when(model).complete(any());
        assertEquals(AgentRunStatus.COMPLETED, run().status());
        var messages = requests.get(2).messages();
        var readMessage = messages.get(messages.size() - 1);
        assertEquals("artifact-read", readMessage.toolCallId());
        JsonNode observation = mapper.readTree(readMessage.content());
        assertFalse(observation.has("externalization"));
        var payload = observation.path("payload");
        String captured = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(original);
        assertTrue(captured.startsWith(payload.path("content").asText()));
        assertTrue(payload.path("hasMore").asBoolean());
        assertTrue(payload.path("nextOffset").asLong() > 0);
        assertEquals(1, projections.size());
        verify(command, times(1)).execute(any(), any());
    }

    @Test
    void exactInlineBoundaryDoesNotCreateArtifact() throws Exception {
        int tokens = Math.toIntExact(preview.estimateTokens(original));
        context = context(new ObservationPolicy(tokens, 512), registry);
        assertEquals(AgentRunStatus.COMPLETED, run().status());
        assertEquals(mapper.valueToTree(original), mapper.readTree(requests.get(1).messages().get(3).content()));
        verify(store, never()).saveToolResult(any(), any());
        assertTrue(projections.isEmpty());
    }

    @Test
    void oneTokenOverInlineBoundaryTriggersExternalization() {
        int tokens = Math.toIntExact(preview.estimateTokens(original));
        context = context(new ObservationPolicy(tokens - 1, 512), registry);
        assertEquals(AgentRunStatus.COMPLETED, run().status());
        assertEquals(1, projections.size());
    }

    @Test
    void failedValidationStillPreservesLogsAndDoesNotCreateSuccessfulEvidence() {
        ((com.fasterxml.jackson.databind.node.ObjectNode) original.payload()).put("exitCode", 1);
        assertEquals(AgentRunStatus.COMPLETED, run().status());
        assertEquals(1, projections.size());
        assertEquals(1, projections.get(0).path("payload").path("exitCode").asInt());
        var view = ArgumentCaptor.forClass(RunStateView.class);
        verify(inspector).inspect(eq(context), view.capture(), any());
        assertTrue(view.getValue().latestSuccessfulValidation().isEmpty());
    }

    @Test
    void terminalPayloadIsNeverExternalizedAndInspectorReceivesOriginal() throws Exception {
        ToolResult terminal = ToolResult.success(mapper.createObjectNode().put("summary", "large final draft ".repeat(1000)));
        doReturn(terminal).when(finish).execute(any(), any());
        assertEquals(AgentRunStatus.COMPLETED, run().status());
        verify(inspector).inspect(eq(context), any(), same(terminal));
        verify(store, times(1)).saveToolResult(eq(context), eq(original));
        assertEquals(1, projections.size());
        assertEquals(terminal, results.get(1).result());
    }

    @Test
    void legacyRunRemainsInlineWithoutAcquiringANewPolicy() throws Exception {
        context = context(null, registry);
        assertEquals(AgentRunStatus.COMPLETED, run().status());
        verify(store, never()).saveToolResult(any(), any());
        assertEquals(mapper.valueToTree(original), mapper.readTree(requests.get(1).messages().get(3).content()));
    }

    @Test
    void enabledExternalizationWithoutFrozenReaderStopsBeforeAnyExecution() {
        context = context(new ObservationPolicy(1024, 512), new ToolRegistry(List.of(command, finish)));
        assertEquals(AgentTerminationReason.CONTEXT_PREPARATION_FAILURE, run().terminationReason());
        verifyNoInteractions(model);
        verify(command, never()).execute(any(), any());
        verify(journal, never()).appendModelCallStarted(any(), any());
    }

    @Test
    void enabledExternalizationCannotUseStandaloneEntryPoint() {
        // Prompt initialization is part of startup, but no model/tool may execute.
        var prompt = mock(PromptAssembler.class);
        when(prompt.assemble(any())).thenReturn(new AssembledPrompt("policy", "test"));
        var standalone = new AgentRuntime(model, prompt, new MessageFactory(mapper), registry,
                mock(WorkspaceGateway.class), inspector, AgentTestExecutionConfigs.resolver(registry));
        assertEquals(AgentTerminationReason.CONTEXT_PREPARATION_FAILURE, standalone.run(context).terminationReason());
        verifyNoInteractions(model);
    }

    @Test
    void artifactFailureStopsRemainingBatchAndDoesNotChangeOrRepeatExecutedTool() throws Exception {
        doThrow(new IOException("simulated disk failure")).when(store).saveToolResult(any(), any());
        doReturn(new ModelResponse("batch", "", List.of(
                response("command", "runCommand").toolCalls().get(0),
                response("second", "runCommand").toolCalls().get(0)), ModelUsage.unknown(), ModelFinishReason.TOOL_CALLS))
                .when(model).complete(any());
        var result = run();
        assertEquals(AgentTerminationReason.CONTEXT_PREPARATION_FAILURE, result.terminationReason());
        assertEquals(AgentRunStatus.PARTIAL, result.status());
        assertEquals(1, result.successfulToolCallCount());
        assertEquals(original, results.get(0).result());
        verify(command, times(1)).execute(any(), any());
        verify(model, times(1)).complete(any());
        verify(journal, never()).appendToolObservation(any(), anyString(), anyString(), any());
    }

    @Test
    void impossiblePreviewBudgetStopsRatherThanDroppingRequiredFields() {
        context = context(new ObservationPolicy(1024, 1), registry);
        assertEquals(AgentTerminationReason.CONTEXT_PREPARATION_FAILURE, run().terminationReason());
        verify(model, times(1)).complete(any());
        assertTrue(projections.isEmpty());
        assertEquals(original, results.get(0).result());
    }

    @Test
    void referenceCommitFailureDoesNotExposeReferenceOrRunAnotherModelCall() {
        doThrow(new IllegalStateException("simulated commit failure"))
                .when(journal).appendToolObservation(any(), anyString(), anyString(), any());
        assertThrows(IllegalStateException.class, this::run);
        verify(model, times(1)).complete(any());
        verify(command, times(1)).execute(any(), any());
        assertEquals(original, results.get(0).result());
    }

    @Test
    void rawResultCommitFailureDoesNotEvenStartArtifactStorage() throws Exception {
        doThrow(new IllegalStateException("simulated commit failure")).when(journal).appendToolResult(any(), any());
        assertThrows(IllegalStateException.class, this::run);
        verify(store, never()).saveToolResult(any(), any());
        verify(model, times(1)).complete(any());
        verify(command, times(1)).execute(any(), any());
    }

    private AgentExecutionContext context(ObservationPolicy policy, ToolRegistry frozenTools) {
        var frozen = AgentTestExecutionConfigs.forRegistry(frozenTools, AgentTestExecutionConfigs.defaultPolicy());
        var id = WorkspaceId.generate();
        return new AgentExecutionContext("session", new AgentRunContext("run", 1L, "1/1", SnapshotScope.of("a".repeat(40))),
                1L, "Run tests and inspect the result", new WorkspaceBinding(id), new WorkspaceExecutionPermit("run", id, 1),
                new AgentExecutionConfig(frozen.policy(), frozen.capabilities(), frozen.toolSet(),
                        frozen.contextPolicyVersion(), policy));
    }

    private AgentRunResult run() {
        return runtime.run(context, new AgentExecutionControl(), scope, 2);
    }

    private ModelResponse response(String id, String name) {
        var args = mapper.createObjectNode();
        if (name.equals("runCommand")) args.putArray("argv").add("test");
        return new ModelResponse("response-" + id, "", List.of(new ToolCall(id, name, args)),
                ModelUsage.unknown(), ModelFinishReason.TOOL_CALLS);
    }

    private AgentEventAppender.AppendResult commit(String event) {
        events.add(event);
        return new AgentEventAppender.AppendResult(++sequence, sequence, sequence, false);
    }
}
