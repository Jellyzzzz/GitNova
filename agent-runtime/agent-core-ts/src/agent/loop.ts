import type{ModelGateway} from "../model/gateway.js";
import type{Request,Response,Message,ToolCallBlock} from "../model/types.js";
import {ToolRegistry} from "../tools/registry.js";
import {AgentEventSink,LoopOptions,LoopStopReason,LoopResult} from "./types.js";
import{ToolExecutionResult,createToolErrorResult} from "../tools/registry.js";
import type { InputQueue } from "./input-queue.js";
import {randomUUID} from "node:crypto";
export class AgentLoop{
    private readonly gateway:ModelGateway;
    private readonly registry:ToolRegistry;

    constructor(gateway:ModelGateway, registry:ToolRegistry) {
        this.gateway = gateway;
        this.registry = registry;
    }

    async run(input:Message,history:readonly Message[],options:LoopOptions,signal:AbortSignal,emit:AgentEventSink,InputQueue:InputQueue):Promise<LoopResult>{
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
        let modelCalls=0;
        let lastResponse:Response|null=null;
        let stopReason:LoopStopReason="max_model_calls";
        let errMessage:string|undefined;
        
        try{
            await emit({
                type:"run_start",
            });
            signal.throwIfAborted();
            await this.appendMessage(messages,input,emit);
            while(modelCalls<options.maxModelCalls){
                signal.throwIfAborted();
                let queued=InputQueue.takeSteering();
                if(queued.length===0&&lastResponse?.termination.kind==="STOP"){
                    queued=InputQueue.takeFollowUps();
                }
                for(const message of queued){
                    await this.appendMessage(messages,message,emit);
                }
                const request=this.createRequest(messages,options);
                const modelCallId=request.modelCallId;
                await emit({
                    type:"turn_start",
                    modelCallId:modelCallId,
                });
                try{
                    signal.throwIfAborted();
                    modelCalls++;
                    
                    const response=await this.callModel(request,signal,emit);
                    lastResponse=response;
                    signal.throwIfAborted();

                    const {termination,message}=response;
                    if(termination.kind==="ERROR"){
                        stopReason="model_error";
                        errMessage=termination.failure.message;
                        break;
                    }
                    if(termination.kind==="LENGTH"){
                        stopReason="output_limit";
                        errMessage="Model output reached its token limit";
                        break;
                    }
                    if(termination.kind==="CONTINUE"){
                        stopReason="protocol_error"
                        errMessage="Deferred response requires a provider continuation handler";
                        break;
                    }
                    const calls:ToolCallBlock[]=[];
                    for(const block of message.content){
                        if(block.type==="tool_call"){
                            calls.push(block);
                        }
                    }
                    const expectedToolCalls=termination.kind==="TOOL_CALLS";
                    if(message.role!=="assistant"||expectedToolCalls!==(calls.length>0)){
                        stopReason="protocol_error";
                        errMessage="Model response has an invalid role or tool call list";
                        break;
                    }
                    await this.appendMessage(messages,message,emit);

                    if(expectedToolCalls){
                        await this.executeToolCalls(calls,messages,options,signal,emit);
                    }
                }finally{
                    await emit({
                        type:"turn_end",
                        modelCallId:modelCallId,
                    });
                }
                if(lastResponse?.termination.kind==="STOP"&&!InputQueue.hasPending()){
                    stopReason="stop";
                    break;
                }
            }
            signal.throwIfAborted();
        }catch(error){
            const abortError=error instanceof Error&&error.name==="AbortError";

            if(!signal.aborted&&!abortError){
                throw error;
            }

            stopReason="cancelled";
            errMessage=undefined;
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
    private createRequest(messages:readonly Message[],options:LoopOptions):Request{
        return {
            modelCallId:randomUUID(),
            model:options.model,
            messages:messages.slice(),
            tools:this.registry.definitions(),
            maxOutputTokens:options.maxOutputTokens,
            thinkingJson:options.thinkingJson,
        };
    }
    private async appendMessage(messages:Message[],message:Message,emit:AgentEventSink):Promise<void>{
        messages.push(message);
        await emit({
            type:"message_end",
            message:message,
        });
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