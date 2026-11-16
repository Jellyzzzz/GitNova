import type{ModelGateway} from "../model/gateway.js";
import type{Request,Response,Message,ToolCallBlock} from "../model/types.js";
import {ToolRegistry} from "../tools/registry.js";
import type {AgentEventSink,CompactionReason,CompactionResult,LoopOptions,LoopStopReason,LoopResult} from "./types.js";
import{ToolExecutionResult,createToolErrorResult} from "../tools/registry.js";
import type { InputQueue } from "./input-queue.js";
import {randomUUID} from "node:crypto";
import {measureContext,type ContextBudget,type ContextUsageState} from "./context-budget.js";
import {
    buildModelContext,
    compact as createCompaction,
    CompactionError,
    findPendingToolCalls,
    type CompactionCheckpoint,
} from "./compaction.js";
export class AgentLoop{
    private readonly gateway:ModelGateway;
    private readonly registry:ToolRegistry;

    constructor(gateway:ModelGateway, registry:ToolRegistry) {
        this.gateway = gateway;
        this.registry = registry;
    }

    async run(input:Message,history:readonly Message[],options:LoopOptions,signal:AbortSignal,notify:AgentEventSink,InputQueue:InputQueue,contextUsage:ContextUsageState={},initialCheckpoint?:CompactionCheckpoint):Promise<LoopResult>{
        if(input.role!=="user"){
            throw new Error("AgentLoop input must be a user message");
        }
        if(!Number.isSafeInteger(options.maxModelCalls)||options.maxModelCalls<1){
            throw new Error("maxModelCalls must be a positive safe integer");
        }

        if(!Number.isSafeInteger(options.maxConcurrentTools)||options.maxConcurrentTools<1){
            throw new Error("maxConcurrentTools must be a positive safe integer");
        }

        const messages:Message[]=history.slice();
        let checkpoint = initialCheckpoint;
        const emit:AgentEventSink=async event=>{
            await notify(event);
            if(event.type==="message_end"){
                await notify({type:"context_update",contextBudget:this.estimateContext(messages,options,contextUsage,checkpoint)});
            }
        };
        const reserveTokens = options.reserveTokens ?? options.maxOutputTokens;
        let overflowRetryUsed = false;
        let modelCalls=0;
        let lastResponse:Response|null=null;
        let stopReason:LoopStopReason="max_model_calls";
        let errMessage:string|undefined;
        const applyCompaction = async (
            request: Request, reason: CompactionReason,
        ) => {
            const result = await this.applyCompaction(messages,checkpoint,request,options,reason,signal,emit);
            checkpoint = result.checkpoint;
            contextUsage.lastUsage = undefined;
            return {
                request: { ...request, messages: buildModelContext(messages, checkpoint) },
                contextBudget: result.contextBudget,
            };
        };

        try{
            await emit({
                type:"run_start",
            });
            signal.throwIfAborted();
            await this.recoverPendingToolCalls(messages,signal,emit);
            signal.throwIfAborted();
            await this.appendMessage(messages,input,emit);
            while(modelCalls<options.maxModelCalls){
                signal.throwIfAborted();
                // Overflow recovery retries the same input before draining the queue.
                if(lastResponse?.termination.kind!=="ERROR"){
                    let queued=InputQueue.takeSteering();
                    if(queued.length===0&&lastResponse?.termination.kind==="STOP"){
                        queued=InputQueue.takeFollowUps();
                    }
                    for(const message of queued){
                        await this.appendMessage(messages,message,emit);
                    }
                }

                let request=this.createRequest(buildModelContext(messages,checkpoint),options);
                let contextBudget=measureContext(request,options.contextWindow,reserveTokens,contextUsage.lastUsage);
                if(contextBudget.estimatedInputTokens>contextBudget.inputBudgetTokens){
                    ({request,contextBudget}=await applyCompaction(request,"threshold"));
                }
                signal.throwIfAborted();
                const modelCallId=request.modelCallId;
                await emit({
                    type:"turn_start",
                    modelCallId:modelCallId,
                    contextBudget:contextBudget,
                });
                let response:Response;
                try{
                    signal.throwIfAborted();
                    modelCalls++;
                    response=await this.callModel(request,signal,emit);
                    lastResponse=response;
                    signal.throwIfAborted();

                    const {termination,message}=response;
                    if(termination.kind==="STOP"||termination.kind==="TOOL_CALLS"){
                        const calls=message.content.filter(block=>block.type==="tool_call");
                        const expectedToolCalls=termination.kind==="TOOL_CALLS";
                        if(message.role!=="assistant"||expectedToolCalls!==(calls.length>0)
                            ||findPendingToolCalls([message],0)===undefined){
                            stopReason="protocol_error";
                            errMessage="Model response has an invalid role or tool call list";
                            break;
                        }
                        await this.appendMessage(messages,message,emit);
                        const totalTokens=response.usage?.totalTokens;
                        if(typeof totalTokens==="number"&&Number.isSafeInteger(totalTokens)&&totalTokens>0){
                            contextUsage.lastUsage={
                                totalTokens,
                                model:request.model,
                                systemPrompt:request.systemPrompt,
                                tools:request.tools,
                                prefix:[...request.messages,message],
                            };
                            await emit({type:"context_update",contextBudget:this.estimateContext(messages,options,contextUsage,checkpoint)});
                        }
                        if(expectedToolCalls){
                            await this.executeToolCalls(calls,messages,options,signal,emit);
                        }
                    }
                }finally{
                    await emit({
                        type:"turn_end",
                        modelCallId:modelCallId,
                    });
                }
                const {termination}=response;
                if(termination.kind==="ERROR"){
                    const overflow=termination.failure.code==="CONTEXT_OVERFLOW";
                    if(overflow&&!overflowRetryUsed&&modelCalls<options.maxModelCalls){
                        overflowRetryUsed=true;
                        await applyCompaction(request,"overflow");
                        continue;
                    }
                    stopReason=overflow?"context_limit":"model_error";
                    errMessage=overflow&&overflowRetryUsed
                        ? `Context overflow persisted after one compaction retry: ${termination.failure.message}`
                        : termination.failure.message;
                    break;
                }
                if(termination.kind==="LENGTH"){
                    stopReason="output_limit";
                    errMessage="Model output reached its token limit";
                    break;
                }
                if(termination.kind==="CONTINUE"){
                    stopReason="protocol_error";
                    errMessage="Deferred response requires a provider continuation handler";
                    break;
                }
                overflowRetryUsed=false;
                if(termination.kind==="STOP"&&!InputQueue.hasPending()){
                    stopReason="stop";
                    break;
                }
            }
            signal.throwIfAborted();
        }catch(error){
            const abortError=error instanceof Error&&error.name==="AbortError";

            if(abortError||(signal.aborted&&error===signal.reason)){
                stopReason="cancelled";
                errMessage=undefined;
            }else if(error instanceof CompactionError){
                stopReason="context_limit";
                errMessage=error.message;
            }else{
                throw error;
            }
        }
        const result:LoopResult={
            stopReason,
            lastResponse,
            modelCalls,
            errMessage,
        };
        await emit({
            type:"run_end",
            result,
        });
        return result;
    }   
    private async callModel(request:Request,signal:AbortSignal,emit:AgentEventSink):Promise<Response>{
        for await(const event of this.gateway.stream(request,signal)){
            await emit({
                type:"model_event",
                modelCallId:request.modelCallId,
                event:event,
            })
            if(event.type==="done"){
                return event.response;
            }
        }
        throw new Error("Model stream ended without a final response");
    }
    async compact(
        history:readonly Message[],checkpoint:CompactionCheckpoint|undefined,options:LoopOptions,
        contextUsage:ContextUsageState,signal:AbortSignal,notify:AgentEventSink,customInstructions?:string,
    ):Promise<CompactionResult>{
        const messages=history.slice();
        const emit:AgentEventSink=async event=>{
            await notify(event);
            if(event.type==="message_end"){
                await notify({type:"context_update",contextBudget:this.estimateContext(messages,options,contextUsage,checkpoint)});
            }
        };
        await this.recoverPendingToolCalls(messages,signal,emit);
        const request=this.createRequest(buildModelContext(messages,checkpoint),options);
        return this.applyCompaction(messages,checkpoint,request,options,"manual",signal,emit,customInstructions);
    }

    private async applyCompaction(
        messages:readonly Message[],checkpoint:CompactionCheckpoint|undefined,request:Request,
        options:LoopOptions,reason:CompactionReason,signal:AbortSignal,emit:AgentEventSink,
        customInstructions?:string,
    ):Promise<CompactionResult>{
        signal.throwIfAborted();
        const reserveTokens=options.reserveTokens??options.maxOutputTokens;
        // Compare before and after using the same full local estimate.
        const estimatedTokensBefore=measureContext(request,options.contextWindow,reserveTokens).estimatedInputTokens;
        await emit({type:"context_compaction_start",reason,estimatedTokensBefore});
        const next=await createCompaction(
            messages,checkpoint,request,options,reason,this.gateway,signal,emit,customInstructions,
        );
        const nextRequest={...request,messages:buildModelContext(messages,next)};
        const contextBudget=measureContext(nextRequest,options.contextWindow,reserveTokens);
        signal.throwIfAborted();
        const result={checkpoint:next,estimatedTokensBefore,contextBudget};
        await emit({type:"context_compaction",...result});
        return result;
    }

    estimateContext(
        messages:readonly Message[],options:LoopOptions,contextUsage:ContextUsageState,
        checkpoint?:CompactionCheckpoint,
    ):ContextBudget{
        const request=this.createRequest(buildModelContext(messages,checkpoint),options);
        return measureContext(request,options.contextWindow,options.reserveTokens??options.maxOutputTokens,contextUsage.lastUsage);
    }

    private createRequest(messages:readonly Message[],options:LoopOptions):Request{
        return {
            modelCallId:randomUUID(),
            model:options.model,
            messages:messages.slice(),
            tools:this.registry.definitions(),
            maxOutputTokens:options.maxOutputTokens,
            thinkingJson:options.thinkingJson,
            systemPrompt:options.systemPrompt,
        };
    }
    private async appendMessage(messages:Message[],message:Message,emit:AgentEventSink):Promise<void>{
        messages.push(message);
        await emit({
            type:"message_end",
            message:message,
        });
    }
    private async recoverPendingToolCalls(
        messages:Message[],signal:AbortSignal,emit:AgentEventSink,
    ):Promise<void>{
        let start=messages.length-1;
        while(messages[start]?.role==="tool"||messages[start]?.role==="system") start--;
        const pending=findPendingToolCalls(messages,Math.max(0,start));
        if(pending===undefined) throw new Error("Session ends with an invalid tool call/result batch");

        for(const call of pending.values()){
            signal.throwIfAborted();
            const {message}=createToolErrorResult(
                call,"No tool result was recorded before the session ended. The tool may have made changes.",
            );
            await this.appendMessage(messages,message,emit);
        }
    }
    private async executeToolCall(call:ToolCallBlock,cwd:string,signal:AbortSignal,emit:AgentEventSink):Promise<ToolExecutionResult>{
        signal.throwIfAborted();
        await emit({
            type:"tool_start",
            call,
        });

        const updateEvents:Promise<void>[]=[];
        let result:ToolExecutionResult;
        try{
            result=await this.registry.execute(
                call,
                cwd,
                signal,
                (partial)=>{
                    const update=Promise.resolve().then(()=>emit({
                        type:"tool_update",
                        callId:call.callId,
                        result:partial,
                    }));

                    void update.catch(()=>{});
                    updateEvents.push(update);
                },
            );
        }finally{
            const updates=await Promise.allSettled(updateEvents);
            for(const update of updates){
                if(update.status==="rejected"){
                    throw update.reason;
                }
            }
        }
        await emit({
            type:"tool_end",
            callId:call.callId,
            result,
        });
        return result;
    }

    private async executeToolCalls(calls:readonly ToolCallBlock[],messages:Message[],options:LoopOptions,signal:AbortSignal,emit:AgentEventSink):Promise<void>{
        if(calls.length===0){
            return;
        }
        if(
            !Number.isSafeInteger(options.maxConcurrentTools)||
            options.maxConcurrentTools<1
        ){
            throw new Error(
            "maxConcurrentTools must be a positive safe integer",
            );
        }
        let isSequential=false;
        if(options.ToolExecutionMode==="sequential"){
            isSequential=true;
        }else {
            for(const call of calls){
                if(this.registry.executionMode(call.name)==="sequential"){
                    isSequential=true;
                    break;
                }
            }
        }
        const concurrency=isSequential?1:Math.min(options.maxConcurrentTools,calls.length);
        const results:(ToolExecutionResult|undefined)[]=new Array(calls.length);

        const started:boolean[]=new Array(calls.length).fill(false);
        let nextIndex=0;
        let stopped=false;

        const executeNext=async():Promise<void>=>{
            while(!stopped){
                const index=nextIndex++;
                const call=calls[index];
                if(call===undefined){
                    return;
                }
                try{
                    signal.throwIfAborted();
                    started[index]=true;
                    results[index]=await this.executeToolCall(call,options.cwd,signal,async(event)=>{
                        if(event.type==="tool_end"){
                            results[index]=event.result;
                    }
                        await emit(event);
                });
                }catch(error){
                stopped=true;
                throw error;
                }
            }
        };
        const workers:Promise<void>[]=[];
        for(let i=0;i<concurrency;i++){
            workers.push(executeNext());
        }
        const outComes=await Promise.allSettled(workers);
        for(let index=0;index<calls.length;index++){
            const call=calls[index];
            if(call===undefined){
                continue;
            }
            let result=results[index];
            if(result===undefined){
                result=createToolErrorResult(call,started[index]
                ?"No final result was recorded because the tool batch was interrupted. The tool may have made changes."
                :"Tool call was not started because the tool batch was interrupted.",)
                if(started[index]){
                    await emit({
                        type:"tool_end",
                        callId:call.callId,
                        result,
                    });
                }
            }
            await this.appendMessage(messages,result.message,emit);
        }
        signal.throwIfAborted();
        for(const outcome of outComes){
            if(outcome.status==="rejected"){
                throw outcome.reason;
            }
        }
    }
}
