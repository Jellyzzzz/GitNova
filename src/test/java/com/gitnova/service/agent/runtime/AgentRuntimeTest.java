package com.gitnova.service.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolCall;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.completion.CompletionDisposition;
import com.gitnova.service.agent.completion.CompletionInspector;
import com.gitnova.service.agent.model.FakeModelGateway;
import com.gitnova.service.agent.AgentTestExecutionConfigs;
import com.gitnova.service.agent.model.MessageFactory;
import com.gitnova.service.agent.model.ModelFinishReason;
import com.gitnova.service.agent.model.ModelGatewayErrorCode;
import com.gitnova.service.agent.model.ModelGatewayException;
import com.gitnova.service.agent.model.ModelMessage;
import com.gitnova.service.agent.model.ModelRequest;
import com.gitnova.service.agent.model.ModelResponse;
import com.gitnova.service.agent.model.ModelRole;
import com.gitnova.service.agent.model.ModelUsage;
import com.gitnova.service.agent.model.ModelThinking;
import com.gitnova.service.agent.prompt.PromptAssembler;
import com.gitnova.service.agent.prompt.PromptSection;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.tools.FinishTaskTool;
import com.gitnova.service.agent.tools.RunCommandTool;
import com.gitnova.service.agent.workspace.PatchBatchResult;
import com.gitnova.service.agent.workspace.SnapshotScope;
import com.gitnova.service.agent.workspace.WorkspaceBinding;
import com.gitnova.service.agent.workspace.WorkspaceExecutionPermit;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.agent.workspace.WorkspaceId;
import com.gitnova.service.agent.workspace.WorkspaceMutationCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class AgentRuntimeTest {

    private static final String CHANGED_FILE = "src/App.java";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentExecutionConfig executionConfig;

    @Test
    void shouldDeliverNaturalFinalTextWithoutCompletionTool() {
        RecordingTool read = new RecordingTool(definition("readContext"),
                ToolResult.success(objectMapper.createObjectNode().put("fact", "current workspace")));
        FakeModelGateway gateway = new FakeModelGateway()
                .enqueueResponse(toolResponse("read", call("read-1", "readContext",
                        objectMapper.createObjectNode()), ModelUsage.unknown()))
                .enqueueResponse(new ModelResponse("answer", "I inspected the current workspace; no files changed.",
                        List.of(), ModelUsage.unknown(), ModelFinishReason.STOP));
        AgentRuntime runtime = runtime(gateway, new InspectingWorkspace(0, List.of()), List.of(read));

        AgentRunResult result = runtime.run(context("Explain the current workspace"));

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(AgentTerminationReason.ANSWER_DELIVERED, result.terminationReason());
        assertEquals("I inspected the current workspace; no files changed.", result.answer().content());
        assertEquals(gateway.receivedRequests().get(1).requestId(), result.answer().modelCallId());
        assertNull(result.completionOutcome());
        assertEquals(List.of("readContext"), gateway.receivedRequests().get(0).tools().stream()
                .map(ToolDefinition::name).toList());
        assertEquals(1, read.invocationCount);
        assertEquals(2, result.modelCallCount());
        assertEquals(1, result.toolCallCount());
    }

    @Test
    void shouldNotDeliverEmptyOrReasoningOnlyStop() {
        FakeModelGateway gateway = new FakeModelGateway().enqueueResponse(new ModelResponse(
                "empty", "  ", List.of(), ModelUsage.unknown(), ModelFinishReason.STOP,
                "internal reasoning is not a user-facing answer"));
        RecordingTool read = new RecordingTool(definition("readContext"),
                ToolResult.success(objectMapper.createObjectNode()));

        AgentRunResult result = runtime(gateway, new InspectingWorkspace(0, List.of()), List.of(read))
                .run(context("Explain the current workspace"));

        assertEquals(AgentRunStatus.FAILED, result.status());
        assertEquals(AgentTerminationReason.INVALID_MODEL_PROTOCOL, result.terminationReason());
        assertNull(result.answer());
    }

    @Test
    void shouldRejectUnadvertisedLegacyFinishToolWithoutExecutingIt() {
        RecordingTool read = new RecordingTool(definition("readContext"),
                ToolResult.success(objectMapper.createObjectNode()));
        FakeModelGateway gateway = new FakeModelGateway()
                .enqueueResponse(toolResponse("guessed", call("finish-1", FinishTaskTool.NAME,
                        finishArguments(0, List.of(), null)), ModelUsage.unknown()))
                .enqueueResponse(new ModelResponse("answer", "No changes were needed.", List.of(),
                        ModelUsage.unknown(), ModelFinishReason.STOP));
        AgentRuntime runtime = runtime(gateway, new InspectingWorkspace(0, List.of()), List.of(read));

        AgentRunResult result = runtime.run(context("Inspect only"));

        assertEquals(AgentTerminationReason.ANSWER_DELIVERED, result.terminationReason());
        assertEquals(ProtocolDeviation.TOOL_NOT_AVAILABLE, result.lastProtocolDeviation());
        assertEquals(0, read.invocationCount);
        ModelMessage rejection = gateway.receivedRequests().get(1).messages().stream()
                .filter(message -> message.role() == ModelRole.TOOL).findFirst().orElseThrow();
        assertEquals("finish-1", rejection.toolCallId());
        assertTrue(rejection.content().contains("TOOL_NOT_AVAILABLE"));
    }

    @Test
    void shouldReconsiderFinalAnswerWhenWorkspaceChangesDuringModelCall() {
        WorkspaceGateway workspace = mock(WorkspaceGateway.class);
        when(workspace.refreshWorkspace(any())).thenReturn(
                new WorkspaceGateway.WorkspaceRefresh(0, 0, false),
                new WorkspaceGateway.WorkspaceRefresh(0, 1, true),
                new WorkspaceGateway.WorkspaceRefresh(1, 1, false),
                new WorkspaceGateway.WorkspaceRefresh(1, 1, false));
        FakeModelGateway gateway = new FakeModelGateway()
                .enqueueResponse(new ModelResponse("stale", "The file is unchanged.", List.of(),
                        ModelUsage.unknown(), ModelFinishReason.STOP))
                .enqueueResponse(new ModelResponse("current", "The Workspace changed; I have not verified it.",
                        List.of(), ModelUsage.unknown(), ModelFinishReason.STOP));
        RecordingTool read = new RecordingTool(definition("readContext"),
                ToolResult.success(objectMapper.createObjectNode()));

        AgentRunResult result = runtime(gateway, workspace, List.of(read))
                .run(context("Inspect the current file"));

        assertEquals("The Workspace changed; I have not verified it.", result.answer().content());
        assertEquals(2, result.modelCallCount());
        assertTrue(gateway.receivedRequests().get(1).messages().stream()
                .anyMatch(message -> message.content() != null
                        && message.content().contains("authoritative at generation 1")));
    }

    @Test
    void shouldCompleteReadOnlyTaskThroughToolObservationAndCanonicalInspection() throws Exception {
        RecordingTool readTool = new RecordingTool(
                definition("readContext"),
                ToolResult.success(objectMapper.createObjectNode().put("fact", "generation is monotonic"))
        );
        FakeModelGateway modelGateway = new FakeModelGateway()
                .enqueueResponse(toolResponse(
                        "response-read",
                        call("call-read", "readContext", objectMapper.createObjectNode()),
                        new ModelUsage(100, 10, 110)
                ))
                .enqueueResponse(toolResponse(
                        "response-finish",
                        call("call-finish", FinishTaskTool.NAME, finishArguments(0, List.of(), null)),
                        new ModelUsage(120, 20, 140)
                ));
        WorkspaceGateway workspace = new InspectingWorkspace(0, List.of());
        AgentRuntime runtime = runtime(
                modelGateway,
                workspace,
                List.of(readTool, new FinishTaskTool(objectMapper))
        );
        AgentExecutionContext context = context("Explain generation semantics");

        AgentRunResult result = runtime.run(context);

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(AgentTerminationReason.FINISH_SUCCEEDED, result.terminationReason());
        assertNotNull(result.completionOutcome());
        assertEquals(CompletionDisposition.NO_CHANGES, result.completionOutcome().disposition());
        assertEquals(2, result.modelCallCount());
        assertEquals(2, result.toolCallCount());
        assertEquals(2, result.successfulToolCallCount());
        assertEquals(1, readTool.invocationCount);
        assertSame(context, readTool.execution.agent());

        ModelRequest firstRequest = modelGateway.receivedRequests().get(0);
        assertEquals("Explain generation semantics", firstRequest.messages().get(1).content());
        assertEquals(
                List.of(FinishTaskTool.NAME, "readContext"),
                firstRequest.tools().stream().map(ToolDefinition::name).toList()
        );

        ModelRequest secondRequest = modelGateway.receivedRequests().get(1);
        assertEquals(4, secondRequest.messages().size());
        ModelMessage observation = secondRequest.messages().get(3);
        assertEquals(ModelRole.TOOL, observation.role());
        assertEquals("call-read", observation.toolCallId());
        JsonNode observationJson = objectMapper.readTree(observation.content());
        assertEquals(ToolStatus.SUCCESS.name(), observationJson.path("status").asText());
    }

    @Test
    void shouldRecordSuccessfulCommandAsFreshValidationEvidence() {
        ObjectNode commandPayload = objectMapper.createObjectNode();
        commandPayload.put("status", "COMPLETED");
        commandPayload.put("generationAfter", 1);
        commandPayload.put("exitCode", 0);
        commandPayload.put("durationMillis", 45);
        commandPayload.put("stdoutTruncated", false);
        commandPayload.put("stderrTruncated", false);
        RecordingTool command = new RecordingTool(
                definition("runCommand"),
                ToolResult.success(commandPayload)
        );

        ObjectNode commandArguments = objectMapper.createObjectNode();
        commandArguments.putArray("argv").add("mvn").add("test");
        FakeModelGateway modelGateway = new FakeModelGateway()
                .enqueueResponse(toolResponse(
                        "response-command",
                        call("call-command", "runCommand", commandArguments),
                        ModelUsage.unknown()
                ))
                .enqueueResponse(toolResponse(
                        "response-finish",
                        call(
                                "call-finish",
                                FinishTaskTool.NAME,
                                finishArguments(1, List.of(CHANGED_FILE), List.of("mvn", "test"))
                        ),
                        ModelUsage.unknown()
                ));
        AgentRuntime runtime = runtime(
                modelGateway,
                new InspectingWorkspace(1, List.of(CHANGED_FILE)),
                List.of(command, new FinishTaskTool(objectMapper))
        );

        AgentRunResult result = runtime.run(context("Fix the bug and run tests"));

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(CompletionDisposition.CHANGES_READY, result.completionOutcome().disposition());
        assertEquals(
                List.of("mvn", "test"),
                result.completionOutcome().validation().argv()
        );
        assertEquals(1, result.completionOutcome().validation().generation());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldPreserveFailedCommandFeedbackWithoutForcingAnotherValidation(boolean sameCheckFails)
            throws Exception {
        // Reproduce the live sequence: three passing checks, then an all-suite failure.
        // Also cover the same check later failing: never present the old pass as latest evidence.
        WorkspaceGateway workspace = spy(new InspectingWorkspace(1, List.of(CHANGED_FILE)));
        AtomicInteger executions = new AtomicInteger();
        doAnswer(invocation -> {
            int exitCode = executions.incrementAndGet() == 4 ? 1 : 0;
            return new WorkspaceGateway.CommandResult(
                    WorkspaceGateway.CommandStatus.COMPLETED, 1, 1, 1, exitCode, 45,
                    exitCode == 0 ? "PASS" : "FAIL: unresolved cases", "", false, false, null, null);
        }).when(workspace).runCommand(any(), any(), any());
        List<ToolCall> calls = new ArrayList<>();
        List<String> suites = List.of("pricing", "contract", "inventory", sameCheckFails ? "pricing" : "all");
        ObjectNode finish = finishArguments(1, List.of(CHANGED_FILE), null);
        finish.put("summary", sameCheckFails
                ? "Pricing was changed, but the last pricing check failed; the fix is incomplete."
                : "Pricing changes are ready; the full suite still has unresolved cases.");
        ((com.fasterxml.jackson.databind.node.ArrayNode) finish.path("risks"))
                .add("The last check failed; this report does not claim all tests passed.");
        ((com.fasterxml.jackson.databind.node.ArrayNode) finish.path("followUps"))
                .add("Investigate the remaining failures using the recorded output.");
        for (int index = 0; index < suites.size(); index++) {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("expectedGeneration", 1);
            args.putArray("argv").add("sh").add("run-tests.sh").add(suites.get(index));
            args.put("workingDirectory", ".");
            args.put("timeoutSeconds", 30);
            args.put("purpose", "Inspect validation results");
            calls.add(call("check-" + index, "runCommand", args));
            var claim = ((com.fasterxml.jackson.databind.node.ArrayNode) finish.path("claimedValidations"))
                    .addObject();
            claim.set("argv", args.path("argv").deepCopy());
            claim.put("result", index == 3 ? "exitCode=1; unresolved failures" : "exitCode=0 at this execution");
        }
        FakeModelGateway gateway = new FakeModelGateway()
                .enqueueResponse(new ModelResponse("checks", null, calls, ModelUsage.unknown(), ModelFinishReason.TOOL_CALLS))
                .enqueueResponse(toolResponse("report", call("finish", FinishTaskTool.NAME, finish), ModelUsage.unknown()));
        AgentRuntime runtime = runtime(gateway, workspace,
                List.of(new RunCommandTool(workspace, objectMapper), new FinishTaskTool(objectMapper)));

        AgentRunResult result = runtime.run(context("Work on pricing; report failures and remaining work honestly"));

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(AgentTerminationReason.FINISH_SUCCEEDED, result.terminationReason());
        assertEquals(2, result.modelCallCount());
        assertEquals(5, result.toolCallCount());
        assertEquals(4, executions.get());
        assertNull(result.completionOutcome().validation());
        assertEquals(4, result.completionOutcome().draft().claimedValidations().size());
        assertEquals(1, result.completionOutcome().draft().followUps().size());
        assertEquals(List.of(CHANGED_FILE), result.completionOutcome().canonicalDiff().files().stream()
                .map(WorkspaceGateway.DiffFile::filePath).toList());

        // Failure is already available to the next model decision, not erased by terminal inspection.
        List<ModelMessage> observations = gateway.receivedRequests().get(1).messages().stream()
                .filter(message -> message.role() == ModelRole.TOOL).toList();
        assertEquals(4, observations.size());
        for (int index = 0; index < observations.size(); index++) {
            assertEquals("check-" + index, observations.get(index).toolCallId());
            JsonNode resultJson = objectMapper.readTree(observations.get(index).content());
            assertEquals("SUCCESS", resultJson.path("status").asText());
            assertEquals(index == 3 ? 1 : 0, resultJson.path("payload").path("exitCode").asInt());
        }
        assertTrue(observations.get(3).content().contains("FAIL: unresolved cases"));
        assertFalse(gateway.receivedRequests().get(1).messages().stream()
                .anyMatch(message -> message.content() != null
                        && message.content().contains("completion draft was rejected")));
    }

    @Test
    void shouldTerminatePartialWithoutReplayingToolAfterRetryableModelTimeout() {
        RecordingTool readTool = new RecordingTool(
                definition("readContext"),
                ToolResult.success(objectMapper.createObjectNode().put("fact", "observed"))
        );
        FakeModelGateway modelGateway = new FakeModelGateway()
                .enqueueResponse(toolResponse(
                        "response-read",
                        call("call-read", "readContext", objectMapper.createObjectNode()),
                        ModelUsage.unknown()
                ))
                .enqueueFailure(new ModelGatewayException(
                        ModelGatewayErrorCode.TIMEOUT,
                        "provider thinking timed out",
                        true,
                        null
                ));
        AgentRuntime runtime = runtime(
                modelGateway,
                new InspectingWorkspace(0, List.of()),
                List.of(readTool, new FinishTaskTool(objectMapper))
        );

        AgentRunResult result = runtime.run(context("Inspect before provider timeout"));

        assertEquals(AgentRunStatus.PARTIAL, result.status());
        assertEquals(AgentTerminationReason.MODEL_GATEWAY_FAILURE, result.terminationReason());
        assertEquals(2, result.modelCallCount());
        assertEquals(1, result.toolCallCount());
        assertEquals(1, readTool.invocationCount);
        assertEquals(2, modelGateway.receivedRequests().size());
    }

    @Test
    void shouldLogGatewayCategoryWithoutLoggingProviderSecretsOrBodies() {
        var failure = new ModelGatewayException(ModelGatewayErrorCode.INVALID_RESPONSE,
                "secret-in-provider-message", false, 200, "secret-in-provider-code", "secret-in-provider-header",
                null, new IllegalArgumentException("secret-in-parser-cause"));
        var gateway = new FakeModelGateway().enqueueFailure(failure).enqueueFailure(failure);
        var runtime = runtime(gateway, new InspectingWorkspace(0, List.of()), List.of(new FinishTaskTool(objectMapper)));
        Logger logger = (Logger) LoggerFactory.getLogger(AgentRuntime.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            var result = runtime.run(context("Read the current Workspace"));
            assertEquals(AgentTerminationReason.MODEL_GATEWAY_FAILURE, result.terminationReason());
            var entry = logs.list.stream().filter(log -> log.getFormattedMessage().startsWith("Model gateway failed:")).findFirst().orElseThrow();
            assertTrue(entry.getFormattedMessage().contains("errorCode=INVALID_RESPONSE"));
            assertTrue(entry.getFormattedMessage().contains("httpStatus=200"));
            assertTrue(entry.getFormattedMessage().contains("retryable=false"));
            assertTrue(entry.getFormattedMessage().contains("requestId=" + gateway.receivedRequests().get(0).requestId()));
            assertFalse(entry.getFormattedMessage().contains("secret-in-"));
            assertTrue(entry.getThrowableProxy() == null); // A parser stack trace can embed the raw response.
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @ParameterizedTest
    @EnumSource(value = ModelFinishReason.class, names = {"LENGTH", "CONTENT_FILTER"})
    void shouldStopOnInterruptedGenerationWithoutInvokingTools(ModelFinishReason reason) {
        var usage = new ModelUsage(20000, 2048, 22048);
        var gateway = new FakeModelGateway().enqueueResponse(new ModelResponse("interrupted", null, List.of(), usage, reason));
        var tool = new RecordingTool(definition("readContext"), ToolResult.success(objectMapper.createObjectNode()));
        var runtime = runtime(gateway, new InspectingWorkspace(0, List.of()), List.of(tool, new FinishTaskTool(objectMapper)));

        var result = runtime.run(context("Inspect the Workspace"));

        assertEquals(reason == ModelFinishReason.LENGTH ? AgentTerminationReason.MODEL_OUTPUT_LENGTH
                : AgentTerminationReason.MODEL_CONTENT_FILTERED, result.terminationReason());
        assertEquals(1, result.modelCallCount());
        assertEquals(0, result.toolCallCount());
        assertEquals(0, tool.invocationCount);
        assertEquals(List.of(usage), result.modelUsages());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void runtimeUsesFrozenThinkingAndPreservesReasoningThroughToolObservation(boolean enabled) {
        var read = new RecordingTool(definition("readContext"), ToolResult.success(objectMapper.createObjectNode()));
        String reasoning = "  inspect then finish\n  ";
        var gateway = new FakeModelGateway()
                .enqueueResponse(new ModelResponse("read", null,
                        List.of(call("read-1", "readContext", objectMapper.createObjectNode())), ModelUsage.unknown(),
                        ModelFinishReason.TOOL_CALLS, reasoning))
                .enqueueResponse(toolResponse("finished", call("finish-1", FinishTaskTool.NAME,
                        finishArguments(0, List.of(), null)), ModelUsage.unknown()));
        var tools = List.<AgentTool>of(read, new FinishTaskTool(objectMapper));
        var runtime = runtime(gateway, new InspectingWorkspace(0, List.of()), tools);
        var thinking = enabled ? new ModelThinking("enabled", "ultra") : ModelThinking.disabled();
        executionConfig = AgentTestExecutionConfigs.forTools(tools,
                new AgentRuntimePolicy("fake-model", 6, 8, 1, 1, 8192, 0.0, thinking));

        var result = runtime.run(context("Inspect and report"));

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(2, gateway.receivedRequests().size());
        for (var request : gateway.receivedRequests()) assertEquals(thinking, request.thinking());
        var next = gateway.receivedRequests().get(1);
        assertEquals(reasoning, next.messages().get(2).reasoningContent());
        assertEquals("read-1", next.messages().get(3).toolCallId());
        assertNull(result.completionOutcome().validation()); // Reasoning is never validation evidence.
    }

    private AgentRuntime runtime(
            FakeModelGateway modelGateway,
            WorkspaceGateway workspace,
            List<AgentTool> tools
    ) {
        AgentRuntimePolicy policy = new AgentRuntimePolicy(
                "fake-model",
                6,
                8,
                1,
                1,
                1024,
                0.0
        );
        ToolRegistry registry = new ToolRegistry(tools);
        executionConfig = AgentTestExecutionConfigs.forTools(tools, policy);
        return new AgentRuntime(
                modelGateway,
                promptAssembler(),
                new MessageFactory(objectMapper),
                registry,
                workspace,
                new CompletionInspector(objectMapper, workspace),
                AgentTestExecutionConfigs.resolver(registry)
        );
    }

    private PromptAssembler promptAssembler() {
        PromptSection section = new PromptSection() {
            @Override
            public String key() {
                return "runtime-test";
            }

            @Override
            public int order() {
                return 10;
            }

            @Override
            public String render(AgentRunContext context) {
                return "Use tools and call finishTask alone.";
            }
        };
        return new PromptAssembler(List.of(section));
    }

    private AgentExecutionContext context(String taskText) {
        AgentRunContext run = new AgentRunContext(
                "run-1",
                42L,
                "7/42",
                SnapshotScope.of("a".repeat(40))
        );
        WorkspaceId workspaceId = WorkspaceId.generate();
        return new AgentExecutionContext(
                "session-1",
                run,
                9L,
                taskText,
                new WorkspaceBinding(workspaceId),
                new WorkspaceExecutionPermit(run.runId(), workspaceId, 1L),
                executionConfig
        );
    }

    private ModelResponse toolResponse(
            String responseId,
            ToolCall call,
            ModelUsage usage
    ) {
        return new ModelResponse(
                responseId,
                null,
                List.of(call),
                usage,
                ModelFinishReason.TOOL_CALLS
        );
    }

    private ToolCall call(String id, String name, ObjectNode arguments) {
        return new ToolCall(id, name, arguments);
    }

    private ObjectNode finishArguments(
            long generation,
            List<String> changedFiles,
            List<String> validationArgv
    ) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("expectedGeneration", generation);
        arguments.put("summary", "Task completed");
        arguments.putArray("findings");
        changedFiles.forEach(arguments.putArray("agentModifiedFiles")::add);
        var validations = arguments.putArray("claimedValidations");
        if (validationArgv != null) {
            ObjectNode validation = validations.addObject();
            validationArgv.forEach(validation.putArray("argv")::add);
            validation.put("result", "passed");
        }
        arguments.putArray("risks");
        arguments.putArray("followUps");
        return arguments;
    }

    private ToolDefinition definition(String name) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        schema.put("additionalProperties", true);
        return new ToolDefinition(name, "Test tool", schema);
    }

    private static final class RecordingTool implements AgentTool {
        private final ToolDefinition definition;
        private final ToolResult result;
        private int invocationCount;
        private ToolExecutionContext execution;

        private RecordingTool(ToolDefinition definition, ToolResult result) {
            this.definition = definition;
            this.result = result;
        }

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public ToolResult execute(ToolExecutionContext execution, JsonNode arguments) {
            invocationCount++;
            this.execution = execution;
            return result;
        }
    }

    private static final class InspectingWorkspace implements WorkspaceGateway {
        private final long generation;
        private final List<String> changedFiles;

        private InspectingWorkspace(long generation, List<String> changedFiles) {
            this.generation = generation;
            this.changedFiles = List.copyOf(changedFiles);
        }

        @Override
        public PatchBatchResult applyPatch(
                WorkspaceId workspaceId,
                WorkspaceExecutionPermit executionPermit,
                WorkspaceMutationCommand command
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkspaceRefresh refreshWorkspace(WorkspaceId workspaceId) {
            return new WorkspaceRefresh(generation, generation, false);
        }

        @Override
        public WorkspaceDiff getWorkspaceDiff(WorkspaceId workspaceId) {
            List<DiffFile> files = changedFiles.stream()
                    .map(path -> new DiffFile(
                            path,
                            DiffChangeType.MODIFIED,
                            1,
                            1,
                            1,
                            false
                    ))
                    .toList();
            return new WorkspaceDiff(
                    generation,
                    files,
                    files.size(),
                    files.size(),
                    files.size(),
                    false,
                    files.isEmpty() ? "" : "diff"
            );
        }
    }
}
