package com.gitnova.service.agent.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.dto.ToolCall;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.model.*;
import com.gitnova.service.agent.prompt.AssembledPrompt;
import com.gitnova.service.agent.prompt.PromptAssembler;
import com.gitnova.service.agent.review.ReviewDraft;
import com.gitnova.service.agent.review.ReviewVerification;
import com.gitnova.service.agent.review.ReviewVerifier;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;

import java.util.*;

public class AgentRuntime {
    public AgentRuntime(ModelGateway modelGateway, PromptAssembler promptAssembler, MessageFactory messageFactory, ToolRegistry toolRegistry, ReviewVerifier reviewVerifier, ObjectMapper objectMapper, AgentRuntimePolicy agentRuntimePolicy) {
        this.modelGateway = modelGateway;
        this.promptAssembler = promptAssembler;
        this.messageFactory = messageFactory;
        this.toolRegistry = toolRegistry;
        this.reviewVerifier = reviewVerifier;
        this.objectMapper = objectMapper;
        this.agentRuntimePolicy = agentRuntimePolicy;
    }

    private static final class RunState {
        private final List<ModelMessage> messages;

        private int turn;
        private int modelCallCount;
        private int toolCallCount;

        private int protocolCorrectionCount;
        private int finalDraftCorrectionCount;

        private boolean changesListed;
        private final Set<String> diffedFiles = new HashSet<>();
        private final Set<String> readFiles = new HashSet<>();

        private ProtocolDeviation lastProtocolDeviation;
        private final List<ModelUsage> modelUsages = new ArrayList<>();

        private RunState(List<ModelMessage>initialMessages){this.messages=new ArrayList<>(initialMessages);}
        static RunState start(List<ModelMessage> initialMessages){
            return new RunState(initialMessages);
        }
        ReviewCoverage coverage(){
            return new ReviewCoverage(changesListed,Set.copyOf(diffedFiles),Set.copyOf(readFiles));
        }
    }
    private final ModelGateway modelGateway;
    private final PromptAssembler promptAssembler;
    private final MessageFactory messageFactory;
    private final ToolRegistry toolRegistry;
    private final ReviewVerifier reviewVerifier;
    private final ObjectMapper objectMapper;
    private final AgentRuntimePolicy agentRuntimePolicy;

    public AgentRunResult run(AgentRunContext context){
        Objects.requireNonNull(context,"context must not be null");
        AssembledPrompt prompt=promptAssembler.assemble(context);
        List<ModelMessage>initialMessages=messageFactory.initialMessages(prompt);
        RunState state=RunState.start(initialMessages);
        List<ToolDefinition>toolDefinitions=toolRegistry.definitions();

        //Real-Loop
        while(true){
            if(state.modelCallCount>=agentRuntimePolicy.maxModelCalls()){
                return terminate(state,AgentTerminationReason.MAX_MODEL_CALLS_REACHED);
            }
            ModelRequest request=BuildRequest(context,state,toolDefinitions);
            state.modelCallCount++;
            ModelResponse response;
            try{
                response=modelGateway.complete(request);
            }catch (ModelGatewayException e){
                return terminate(state,AgentTerminationReason.MODEL_GATEWAY_FAILURE);
            }
            state.modelUsages.add(response.usage());
            state.messages.add(messageFactory.assistant(response));

            switch(response.finishReason()){
                case TOOL_CALLS -> {
                    Optional<AgentRunResult> runOutcome = handleToolCalls(state, context, response.toolCalls());
                    if(runOutcome.isPresent()) return runOutcome.get();
                }
                case LENGTH -> {
                    return terminate(state,AgentTerminationReason.MODEL_OUTPUT_LENGTH);
                }
                case CONTENT_FILTER -> {
                    return terminate(state, AgentTerminationReason.MODEL_CONTENT_FILTERED);
                }
                case STOP -> {
                    Optional<AgentRunResult>runOutcome=handleStopWithoutFinalize(state);
                    if(runOutcome.isPresent()) return runOutcome.get();
                }
                case UNKNOWN -> {
                    return terminate(state,AgentTerminationReason.INVALID_MODEL_PROTOCOL);
                }
            }
            state.turn++;
        }
    }
    private ModelRequest BuildRequest(AgentRunContext context,RunState state,List<ToolDefinition>toolDefinitions){
        String requestId=context.runId()+":turn"+state.turn+":call"+(state.modelCallCount+1);
        return new ModelRequest(agentRuntimePolicy.model(),List.copyOf(state.messages),toolDefinitions, agentRuntimePolicy.maxOutputTokens(), agentRuntimePolicy.temperature(),requestId);
    }
    private Optional<AgentRunResult>handleToolCalls(RunState state, AgentRunContext context, List<ToolCall>toolCalls){
        if(state.toolCallCount+toolCalls.size()>agentRuntimePolicy.maxToolCalls()){
            return Optional.of(terminate(state,AgentTerminationReason.MAX_TOOL_CALLS_REACHED));
        }
        long totalTerminalCount=toolCalls.stream()
                .filter(toolCall->toolRegistry.isTerminal(toolCall.name()))
                .count();
        if(totalTerminalCount>0&&toolCalls.size()!=1){
            return rejectMixedTerminalCalls(state,toolCalls);
        }
        if(totalTerminalCount==1){
            return handleFinalize(context,state,toolCalls.get(0));
        }
        for(ToolCall toolCall:toolCalls){
            executeOrdinaryTool(state,context,toolCall);
        }
        return Optional.empty();
    }
    private Optional<AgentRunResult> handleStopWithoutFinalize(RunState state){
        state.lastProtocolDeviation=ProtocolDeviation.MODEL_STOPPED_WITHOUT_FINALIZE;
        if(state.protocolCorrectionCount>= agentRuntimePolicy.maxProtocolCorrections()){
            return Optional.of(terminate(state,AgentTerminationReason.PROTOCOL_CORRECTION_EXHAUSTED));
        }
        state.protocolCorrectionCount++;
        state.messages.add(messageFactory.harnessFeedback("The review is not complete. "
                + "Call finalizeReview alone "
                + "with the structured final draft."));
        return Optional.empty();
    }
    private Optional<AgentRunResult>rejectMixedTerminalCalls(RunState state,List<ToolCall>toolCalls){
        state.lastProtocolDeviation=ProtocolDeviation.MIXED_TERMINAL_TOOL_CALLS;
        ToolResult rejection=ToolResult.error(ToolStatus.INVALID_ARGUMENT,"TERMINAL_TOOL_MUST_BE_EXCLUSIVE","Call finalizeReview alone after evidence gathering is complete.",false);
        for(ToolCall toolCall:toolCalls){
            state.toolCallCount++;
            state.messages.add(messageFactory.tool(toolCall,rejection));
        }
        if(state.protocolCorrectionCount>=agentRuntimePolicy.maxProtocolCorrections()){
            return Optional.of(terminate(state,AgentTerminationReason.PROTOCOL_CORRECTION_EXHAUSTED));
        }
        state.protocolCorrectionCount++;
        return Optional.empty();
    }
    private void executeOrdinaryTool(RunState state,AgentRunContext context,ToolCall call){
        ToolExecutionContext executionContext=new ToolExecutionContext(context,state.turn, call.id());
        state.toolCallCount++;
        ToolResult result=toolRegistry.execute(executionContext, call.name(), call.arguments());
        state.messages.add(messageFactory.tool(call,result));
        recordCoverage(state,call,result);
    }
    private void recordCoverage(RunState state,ToolCall toolCall,ToolResult result){
        if(!result.successful()) return;
        switch (toolCall.name()){
            case "listChanges"->state.changesListed=true;
            case "getDiff"->addCoveredPath(state.diffedFiles,toolCall);
            case "readFile"->addCoveredPath(state.readFiles,toolCall);
        }
    }
    private void addCoveredPath(Set<String> paths,ToolCall toolCall){
        String filePath=toolCall.arguments().path("filePath").asText();
        if(!filePath.isBlank()){
            paths.add(filePath);
        }
    }
    private Optional<AgentRunResult> handleFinalize(AgentRunContext context,RunState state,ToolCall toolCall){
        ToolExecutionContext execution=new ToolExecutionContext(context,state.turn,toolCall.id());
        state.toolCallCount++;
        ToolResult result=toolRegistry.execute(execution,toolCall.name(),toolCall.arguments());
        state.messages.add(messageFactory.tool(toolCall,result));

        if(!result.successful()){
            if(result.status()==ToolStatus.INVALID_ARGUMENT){
                return handleInvalidDraft(state,result.message());
            }
            return Optional.of(terminate(state,AgentTerminationReason.TOOL_EXECUTION_FAILURE));
        }
        ReviewDraft draft;
        try{
            draft=objectMapper.treeToValue(result.payload(),ReviewDraft.class);
        } catch (JsonProcessingException e) {
            return Optional.of(terminate(state,AgentTerminationReason.TOOL_EXECUTION_FAILURE));
        }
        ReviewVerification verification=reviewVerifier.verify(context,draft,state.coverage());
        if(verification.accepted()){
            return Optional.of(complete(state,draft));
        }
        if(!verification.correctable()){
            return Optional.of(terminate(state,AgentTerminationReason.INVALID_FINAL_DRAFT));
        }
        return handleInvalidDraft(state,String.join("\n",verification.feedback()));
    }
    private Optional<AgentRunResult> handleInvalidDraft(RunState state,String feedback){
        if(state.finalDraftCorrectionCount>=agentRuntimePolicy.maxFinalDraftCorrections()){
            return Optional.of(terminate(state,AgentTerminationReason.INVALID_FINAL_DRAFT));
        }
        state.finalDraftCorrectionCount++;
        state.messages.add(
                messageFactory.harnessFeedback(
                        "The final review draft was rejected:\n"
                                + feedback
                                + "\nCorrect the draft and call "
                                + "finalizeReview alone."
                )
        );
        return Optional.empty();
    }
    private AgentRunResult complete(RunState state, ReviewDraft draft){
        return new AgentRunResult(AgentRunStatus.COMPLETED,AgentTerminationReason.FINALIZE_SUCCEEDED,draft,state.coverage(),state.lastProtocolDeviation,state.modelCallCount,state.toolCallCount,List.copyOf(state.modelUsages));
    }
    private AgentRunResult terminate(RunState state,AgentTerminationReason reason){
        ReviewCoverage coverage=state.coverage();
        AgentRunStatus status=coverage.hasEvidence()?AgentRunStatus.PARTIAL:AgentRunStatus.FAILED;
        return new AgentRunResult(status,reason,null,coverage,state.lastProtocolDeviation,state.modelCallCount,state.toolCallCount,List.copyOf(state.modelUsages));
    }
}
