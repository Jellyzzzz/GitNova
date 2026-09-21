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
import com.gitnova.service.agent.prompt.PromptAssembler;
import com.gitnova.service.agent.prompt.PromptSection;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.tools.FinishTaskTool;
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
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeTest {

    private static final String CHANGED_FILE = "src/App.java";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentExecutionConfig executionConfig;

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
        var gateway = new FakeModelGateway().enqueueFailure(failure);
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
