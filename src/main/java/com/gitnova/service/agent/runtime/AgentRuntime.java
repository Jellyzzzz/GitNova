package com.gitnova.service.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.gitnova.dto.ToolCall;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.completion.AgentCompletionOutcome;
import com.gitnova.service.agent.completion.CompletionDecision;
import com.gitnova.service.agent.completion.CompletionInspector;
import com.gitnova.service.agent.model.MessageFactory;
import com.gitnova.service.agent.model.ModelGateway;
import com.gitnova.service.agent.model.ModelGatewayException;
import com.gitnova.service.agent.model.ModelMessage;
import com.gitnova.service.agent.model.ModelRequest;
import com.gitnova.service.agent.model.ModelResponse;
import com.gitnova.service.agent.model.ModelUsage;
import com.gitnova.service.agent.prompt.AssembledPrompt;
import com.gitnova.service.agent.prompt.PromptAssembler;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.tools.FinishTaskTool;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.agent.workspace.WorkspaceOperationException;
import com.gitnova.service.agent.execution.AgentExecutionControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Provider-neutral universal Agent loop.
 *
 * <p>The Runtime owns model/tool protocol, budgets, correction, trusted execution context,
 * validation evidence, and terminal inspection. It does not contain Review-specific planning or
 * verification.</p>
 */
public final class AgentRuntime {

    private static final Logger logger = LoggerFactory.getLogger(AgentRuntime.class);

    private static final class RunState {
        private final List<ModelMessage> messages;
        private final List<ModelUsage> modelUsages = new ArrayList<>();

        private int turn;
        private int modelCallCount;
        private int toolCallCount;
        private int successfulToolCallCount;
        private int protocolCorrectionCount;
        private int completionCorrectionCount;
        private ProtocolDeviation lastProtocolDeviation;
        private ValidationEvidence latestSuccessfulValidation;

        private RunState(List<ModelMessage> initialMessages) {
            this.messages = new ArrayList<>(initialMessages);
        }

        static RunState start(List<ModelMessage> initialMessages) {
            return new RunState(initialMessages);
        }

        RunStateView view() {
            return new RunStateView(Optional.ofNullable(latestSuccessfulValidation));
        }
    }

    private final ModelGateway modelGateway;
    private final PromptAssembler promptAssembler;
    private final MessageFactory messageFactory;
    private final ToolRegistry toolRegistry;
    private final WorkspaceGateway workspaceGateway;
    private final CompletionInspector completionInspector;
    private final AgentRuntimePolicy policy;

    public AgentRuntime(
            ModelGateway modelGateway,
            PromptAssembler promptAssembler,
            MessageFactory messageFactory,
            ToolRegistry toolRegistry,
            WorkspaceGateway workspaceGateway,
            CompletionInspector completionInspector,
            AgentRuntimePolicy policy
    ) {
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway must not be null");
        this.promptAssembler = Objects.requireNonNull(
                promptAssembler,
                "promptAssembler must not be null"
        );
        this.messageFactory = Objects.requireNonNull(
                messageFactory,
                "messageFactory must not be null"
        );
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.workspaceGateway = Objects.requireNonNull(
                workspaceGateway,
                "workspaceGateway must not be null"
        );
        this.completionInspector = Objects.requireNonNull(
                completionInspector,
                "completionInspector must not be null"
        );
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    public AgentRunResult run(AgentExecutionContext context) {
        return run(context, new AgentExecutionControl());
    }

    public AgentRunResult run(
            AgentExecutionContext context,
            AgentExecutionControl executionControl
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(executionControl, "executionControl must not be null");
        AssembledPrompt prompt = promptAssembler.assemble(context.context());
        RunState state = RunState.start(
                messageFactory.initialMessages(prompt, context.taskText())
        );
        List<ToolDefinition> toolDefinitions = toolRegistry.definitions(context.capabilities());
        if (toolDefinitions.stream().noneMatch(definition ->
                FinishTaskTool.NAME.equals(definition.name()))) {
            throw new IllegalStateException("finishTask must be registered and authorized");
        }

        while (true) {
            executionControl.requireLease();
            if (state.modelCallCount >= policy.maxModelCalls()) {
                return terminate(state, AgentTerminationReason.MAX_MODEL_CALLS_REACHED);
            }

            WorkspaceGateway.WorkspaceRefresh beforeModel = synchronizeWorkspace(
                    context,
                    state
            );
            if (beforeModel == null) {
                return terminate(state, AgentTerminationReason.WORKSPACE_SYNC_FAILURE);
            }
            appendWorkspaceDriftFeedback(state, beforeModel);

            ModelRequest request = buildRequest(context, state, toolDefinitions);
            executionControl.requireLease();
            state.modelCallCount++;
            ModelResponse response;
            try {
                response = modelGateway.complete(request);
            } catch (ModelGatewayException exception) {
                return terminate(state, AgentTerminationReason.MODEL_GATEWAY_FAILURE);
            }
            executionControl.requireLease();

            state.modelUsages.add(response.usage());
            state.messages.add(messageFactory.assistant(response));

            Optional<AgentRunResult> outcome = switch (response.finishReason()) {
                case TOOL_CALLS -> handleToolCalls(
                        state,
                        context,
                        executionControl,
                        response.toolCalls()
                );
                case STOP -> handleStopWithoutFinish(state);
                case LENGTH -> Optional.of(terminate(
                        state,
                        AgentTerminationReason.MODEL_OUTPUT_LENGTH
                ));
                case CONTENT_FILTER -> Optional.of(terminate(
                        state,
                        AgentTerminationReason.MODEL_CONTENT_FILTERED
                ));
                case UNKNOWN -> Optional.of(terminate(
                        state,
                        AgentTerminationReason.INVALID_MODEL_PROTOCOL
                ));
            };
            if (outcome.isPresent()) {
                return outcome.get();
            }
            state.turn++;
        }
    }

    /**
     * Reconciles out-of-band filesystem changes at Runtime execution boundaries.
     *
     * <p>The Workspace remains authoritative: a detected drift advances its generation and
     * invalidates Runtime-held validation evidence. A stale model write is then rejected by the
     * normal expectedGeneration contract instead of overwriting the newer state.</p>
     */
    private WorkspaceGateway.WorkspaceRefresh synchronizeWorkspace(
            AgentExecutionContext context,
            RunState state
    ) {
        try {
            WorkspaceGateway.WorkspaceRefresh refresh = workspaceGateway.refreshWorkspace(
                    context.workspace().workspaceId()
            );
            if (refresh.changed()) {
                state.latestSuccessfulValidation = null;
            }
            return refresh;
        } catch (WorkspaceOperationException | IllegalArgumentException exception) {
            logger.error(
                    "Workspace synchronization failed: runId={}, turn={}, workspaceId={}",
                    context.context().runId(),
                    state.turn,
                    context.workspace().workspaceId(),
                    exception
            );
            return null;
        }
    }

    private void appendWorkspaceDriftFeedback(
            RunState state,
            WorkspaceGateway.WorkspaceRefresh refresh
    ) {
        if (!refresh.changed()) {
            return;
        }
        state.messages.add(messageFactory.harnessFeedback(
                "The Workspace changed outside the current Agent tool execution and is now "
                        + "authoritative at generation " + refresh.generationAfter() + ". "
                        + "Any validation or write prepared against an earlier generation is "
                        + "stale. Read the latest files or diff before retrying a mutation."
        ));
    }

    private ModelRequest buildRequest(
            AgentExecutionContext context,
            RunState state,
            List<ToolDefinition> toolDefinitions
    ) {
        String requestId = context.context().runId()
                + ":turn" + state.turn
                + ":call" + (state.modelCallCount + 1);
        return new ModelRequest(
                policy.model(),
                List.copyOf(state.messages),
                toolDefinitions,
                policy.maxOutputTokens(),
                policy.temperature(),
                requestId
        );
    }

    private Optional<AgentRunResult> handleToolCalls(
            RunState state,
            AgentExecutionContext context,
            AgentExecutionControl executionControl,
            List<ToolCall> toolCalls
    ) {
        if (state.toolCallCount + toolCalls.size() > policy.maxToolCalls()) {
            return Optional.of(terminate(
                    state,
                    AgentTerminationReason.MAX_TOOL_CALLS_REACHED
            ));
        }

        long terminalCount = toolCalls.stream()
                .filter(call -> toolRegistry.isTerminal(call.name()))
                .count();
        if (terminalCount > 0 && toolCalls.size() != 1) {
            return rejectMixedTerminalCalls(state, toolCalls);
        }
        if (terminalCount == 1) {
            executionControl.requireLease();
            ToolCall terminalCall = toolCalls.get(0);
            if (!FinishTaskTool.NAME.equals(terminalCall.name())) {
                return rejectUnsupportedTerminalCall(state, terminalCall);
            }
            WorkspaceGateway.WorkspaceRefresh refresh = synchronizeWorkspace(context, state);
            if (refresh == null) {
                rejectForWorkspaceSyncFailure(state, List.of(terminalCall));
                return Optional.of(terminate(
                        state,
                        AgentTerminationReason.WORKSPACE_SYNC_FAILURE
                ));
            }
            Optional<AgentRunResult> finish = handleFinish(context, state, terminalCall);
            executionControl.requireLease();
            if (finish.isEmpty()) {
                appendWorkspaceDriftFeedback(state, refresh);
            }
            return finish;
        }

        WorkspaceGateway.WorkspaceRefresh latestDrift = null;
        for (int index = 0; index < toolCalls.size(); index++) {
            executionControl.requireLease();
            ToolCall toolCall = toolCalls.get(index);
            WorkspaceGateway.WorkspaceRefresh refresh = synchronizeWorkspace(context, state);
            if (refresh == null) {
                rejectForWorkspaceSyncFailure(
                        state,
                        toolCalls.subList(index, toolCalls.size())
                );
                return Optional.of(terminate(
                        state,
                        AgentTerminationReason.WORKSPACE_SYNC_FAILURE
                ));
            }
            if (refresh.changed()) {
                latestDrift = refresh;
            }
            executeOrdinaryTool(state, context, toolCall);
            executionControl.requireLease();
        }
        if (latestDrift != null) {
            appendWorkspaceDriftFeedback(state, latestDrift);
        }
        return Optional.empty();
    }

    /**
     * Completes the assistant tool-call protocol when Workspace synchronization fails.
     * No rejected call is dispatched to its AgentTool.
     */
    private void rejectForWorkspaceSyncFailure(
            RunState state,
            List<ToolCall> rejectedCalls
    ) {
        ToolResult rejection = ToolResult.error(
                ToolStatus.CONFLICT,
                "WORKSPACE_SYNC_FAILED",
                "Workspace could not be synchronized before tool execution",
                false
        );
        for (ToolCall call : rejectedCalls) {
            state.toolCallCount++;
            state.messages.add(messageFactory.tool(call, rejection));
        }
    }

    private Optional<AgentRunResult> handleStopWithoutFinish(RunState state) {
        state.lastProtocolDeviation = ProtocolDeviation.MODEL_STOPPED_WITHOUT_FINISH;
        if (state.protocolCorrectionCount >= policy.maxProtocolCorrections()) {
            return Optional.of(terminate(
                    state,
                    AgentTerminationReason.PROTOCOL_CORRECTION_EXHAUSTED
            ));
        }
        state.protocolCorrectionCount++;
        state.messages.add(messageFactory.harnessFeedback(
                "The task is not complete. Call finishTask alone with the structured completion draft."
        ));
        return Optional.empty();
    }

    private Optional<AgentRunResult> rejectMixedTerminalCalls(
            RunState state,
            List<ToolCall> toolCalls
    ) {
        state.lastProtocolDeviation = ProtocolDeviation.MIXED_TERMINAL_TOOL_CALLS;
        ToolResult rejection = ToolResult.error(
                ToolStatus.INVALID_ARGUMENT,
                "TERMINAL_TOOL_MUST_BE_EXCLUSIVE",
                "Call finishTask alone after all other tool work is complete",
                false
        );
        for (ToolCall toolCall : toolCalls) {
            state.toolCallCount++;
            state.messages.add(messageFactory.tool(toolCall, rejection));
        }
        return consumeProtocolCorrectionOrTerminate(state);
    }

    private Optional<AgentRunResult> rejectUnsupportedTerminalCall(
            RunState state,
            ToolCall toolCall
    ) {
        state.toolCallCount++;
        ToolResult rejection = ToolResult.error(
                ToolStatus.INVALID_ARGUMENT,
                "UNSUPPORTED_TERMINAL_TOOL",
                "finishTask is the only terminal tool supported by this Runtime",
                false
        );
        state.messages.add(messageFactory.tool(toolCall, rejection));
        state.lastProtocolDeviation = ProtocolDeviation.MIXED_TERMINAL_TOOL_CALLS;
        return consumeProtocolCorrectionOrTerminate(state);
    }

    private Optional<AgentRunResult> consumeProtocolCorrectionOrTerminate(RunState state) {
        if (state.protocolCorrectionCount >= policy.maxProtocolCorrections()) {
            return Optional.of(terminate(
                    state,
                    AgentTerminationReason.PROTOCOL_CORRECTION_EXHAUSTED
            ));
        }
        state.protocolCorrectionCount++;
        return Optional.empty();
    }

    private void executeOrdinaryTool(
            RunState state,
            AgentExecutionContext context,
            ToolCall call
    ) {
        ToolExecutionContext execution = new ToolExecutionContext(
                context,
                state.turn,
                call.id()
        );
        state.toolCallCount++;
        ToolResult result = toolRegistry.execute(execution, call.name(), call.arguments());
        state.messages.add(messageFactory.tool(call, result));
        if (result.successful() || result.partiallySuccessful()) {
            state.successfulToolCallCount++;
        }
        recordValidationEvidence(state, call, result);
    }

    private void recordValidationEvidence(
            RunState state,
            ToolCall call,
            ToolResult result
    ) {
        if (!"runCommand".equals(call.name()) || !result.successful()) {
            return;
        }
        JsonNode payload = result.payload();
        if (!"COMPLETED".equals(payload.path("status").asText())) {
            state.latestSuccessfulValidation = null;
            return;
        }
        JsonNode exitCode = payload.path("exitCode");
        if (!exitCode.isIntegralNumber() || exitCode.intValue() != 0) {
            state.latestSuccessfulValidation = null;
            return;
        }

        JsonNode generationAfter = payload.path("generationAfter");
        JsonNode durationMillis = payload.path("durationMillis");
        JsonNode argvNode = call.arguments().path("argv");
        if (!generationAfter.isIntegralNumber()
                || generationAfter.longValue() < 0
                || !durationMillis.isIntegralNumber()
                || durationMillis.longValue() < 0
                || !argvNode.isArray()) {
            state.latestSuccessfulValidation = null;
            return;
        }
        List<String> argv = new ArrayList<>();
        for (JsonNode argument : argvNode) {
            if (!argument.isTextual() || argument.asText().isBlank()) {
                state.latestSuccessfulValidation = null;
                return;
            }
            argv.add(argument.asText());
        }
        if (argv.isEmpty()) {
            state.latestSuccessfulValidation = null;
            return;
        }
        state.latestSuccessfulValidation = new ValidationEvidence(
                argv,
                generationAfter.longValue(),
                0,
                durationMillis.longValue(),
                payload.path("stdoutTruncated").asBoolean(false),
                payload.path("stderrTruncated").asBoolean(false)
        );
    }

    private Optional<AgentRunResult> handleFinish(
            AgentExecutionContext context,
            RunState state,
            ToolCall call
    ) {
        ToolExecutionContext execution = new ToolExecutionContext(
                context,
                state.turn,
                call.id()
        );
        state.toolCallCount++;
        ToolResult result = toolRegistry.execute(execution, call.name(), call.arguments());
        state.messages.add(messageFactory.tool(call, result));

        if (!result.successful()) {
            if (result.status() == ToolStatus.INVALID_ARGUMENT) {
                return handleCorrectableCompletion(state, List.of(result.message()));
            }
            return Optional.of(terminate(
                    state,
                    AgentTerminationReason.TOOL_EXECUTION_FAILURE
            ));
        }
        state.successfulToolCallCount++;

        CompletionDecision decision;
        try {
            decision = completionInspector.inspect(context, state.view(), result);
        } catch (RuntimeException exception) {
            logger.error(
                    "Completion inspection failed: runId={}, turn={}, toolCallId={}",
                    context.context().runId(),
                    state.turn,
                    call.id(),
                    exception
            );
            return Optional.of(terminate(
                    state,
                    AgentTerminationReason.COMPLETION_INSPECTION_FAILURE
            ));
        }
        if (decision.accepted()) {
            return Optional.of(complete(state, decision.outcome()));
        }
        if (decision.correctable()) {
            return handleCorrectableCompletion(state, decision.feedback());
        }
        return Optional.of(terminate(
                state,
                AgentTerminationReason.INVALID_COMPLETION_DRAFT
        ));
    }

    private Optional<AgentRunResult> handleCorrectableCompletion(
            RunState state,
            List<String> feedback
    ) {
        if (state.completionCorrectionCount >= policy.maxFinalDraftCorrections()) {
            return Optional.of(terminate(
                    state,
                    AgentTerminationReason.INVALID_COMPLETION_DRAFT
            ));
        }
        state.completionCorrectionCount++;
        state.messages.add(messageFactory.harnessFeedback(
                "The completion draft was rejected:\n"
                        + String.join("\n", feedback)
                        + "\nGather or refresh any required evidence, then call finishTask alone."
        ));
        return Optional.empty();
    }

    private AgentRunResult complete(
            RunState state,
            AgentCompletionOutcome outcome
    ) {
        return new AgentRunResult(
                AgentRunStatus.COMPLETED,
                AgentTerminationReason.FINISH_SUCCEEDED,
                outcome,
                state.lastProtocolDeviation,
                state.modelCallCount,
                state.toolCallCount,
                state.successfulToolCallCount,
                List.copyOf(state.modelUsages)
        );
    }

    private AgentRunResult terminate(
            RunState state,
            AgentTerminationReason reason
    ) {
        AgentRunStatus status = state.successfulToolCallCount > 0
                ? AgentRunStatus.PARTIAL
                : AgentRunStatus.FAILED;
        return new AgentRunResult(
                status,
                reason,
                null,
                state.lastProtocolDeviation,
                state.modelCallCount,
                state.toolCallCount,
                state.successfulToolCallCount,
                List.copyOf(state.modelUsages)
        );
    }
}
