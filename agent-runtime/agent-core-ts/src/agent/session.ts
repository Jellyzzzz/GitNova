import type{Message,Usage} from "../model/types.js";
import type{AgentState,AgentEvent,LoopResult,LoopOptions} from "./types.js";
import {InputQueue} from "./input-queue.js";
import type{AgentLoop} from "./loop.js";
import { createUsageTotals,addUsageTotals } from "./usage.js";
import type{UsageTotals} from "./usage.js";
import type { ContextUsageAnchor,ContextUsageState } from "./context-budget.js";
import {
    buildModelContext,
    type CompactionCheckpoint,
    type CompactionRecord,
} from "./compaction.js";
type MutableAgentState=Omit<AgentState,"isStreaming"|"pendingToolCalls"|"errorMessage">&{
    isStreaming:boolean;
    pendingToolCalls:Set<string>;
    errorMessage?:string;
}
type ActiveRun={
    promise:Promise<void>;
    resolve:()=>void;
    abortController:AbortController;
}
type AgentEventListener=(event:AgentEvent,signal:AbortSignal)=>void|Promise<void>;//session内部更新后通知订阅者的数据结构
export class AgentSession{
    private readonly _state:MutableAgentState;
    private readonly inputQueue:InputQueue=new InputQueue();
    private readonly listeners:Set<AgentEventListener> =new Set<AgentEventListener>();
    private readonly loop:AgentLoop;
    private activeRun:ActiveRun|undefined;
    private persistMessage?:(message:Message)=>Promise<void>;
    private _runUsage=createUsageTotals();
    private _sessionUsage:UsageTotals;
    private readonly persistUsage?:SessionUsageOptions["persist"];
    private readonly contextUsage:ContextUsageState;
    private checkpoint?:CompactionCheckpoint;
    private readonly persistCompaction?: SessionUsageOptions["persistCompaction"];
    constructor(loop:AgentLoop,InitialMessage:Message[]=[],persistMessage?:(message:Message)=>Promise<void>,usageOptions:SessionUsageOptions={},){
        this.loop=loop;
        this.persistMessage=persistMessage;
        this.persistUsage=usageOptions.persist;
        this.contextUsage={lastUsage:usageOptions.initialContextAnchor};
        this._sessionUsage=usageOptions.initialTotals??createUsageTotals();
        this._state={
            messages:InitialMessage.slice(),
            isStreaming:false,
            pendingToolCalls:new Set<string>(),
            errorMessage:undefined,
        };
        this.checkpoint=usageOptions.initialCheckpoint;
        this.persistCompaction = usageOptions.persistCompaction;
    }
    get state():AgentState{
        return {...this._state,messages:this._state.messages.slice()};
    }

    get signal():AbortSignal|undefined{
        return this.activeRun?.abortController.signal;
    }
    get runUsage(){
        return this._runUsage;
    }
    get sessionUsage(){
        return this._sessionUsage;
    }
    get modelMessages(): readonly Message[] {
        return buildModelContext(this._state.messages, this.checkpoint);
    }
    get compactionCheckpoint(): CompactionCheckpoint | undefined {
        return this.checkpoint;
    }

    abort():void{
        return this.activeRun?.abortController.abort();
    }
    waitIdle():Promise<void>{
        return this.activeRun?.promise??Promise.resolve();
    }

    subscribe(listener:AgentEventListener):()=>void{
        this.listeners.add(listener);
        return ()=>{
            this.listeners.delete(listener);
        }/*
    如何调用？
    const unsubscribe=session.subcribe((event,signal))=>{};此时注册监听
    unsubscribe();此时取消监听
    */
    }
    prompt(text:string,options:LoopOptions):Promise<LoopResult>{
        const input:Message={
            role:"user",
            content:[{type:"text",text:text}],
            timestamp:Date.now(),
        };
        return this.runWithLifecycle((signal)=>this.loop.run(input,this._state.messages,options,signal,(event)=>this.processEvents(event),this.inputQueue,this.contextUsage,this.checkpoint));
    }
    private async processEvents(event:AgentEvent):Promise<void>{
        switch(event.type){
            case "message_end":
                await this.persistMessage?.(event.message);
                this._state.messages.push(event.message);
                break;
            case "tool_start":{
                const pending=new Set(this._state.pendingToolCalls);
                pending.add(event.call.callId);
                this._state.pendingToolCalls=pending;
                break;
            }
            case "tool_end":{
                const pending=new Set(this._state.pendingToolCalls);
                pending.delete(event.callId);
                this._state.pendingToolCalls=pending;
                break;
            }
            case "run_start":{
                this._runUsage=createUsageTotals();
                break;
            }
            case "model_event":{
                if(event.event.type==="done"){
                    await this.recordUsage(event.modelCallId,event.event.response.usage);
                }
                break;
            }
            case "compaction_usage":{
                await this.recordUsage(event.modelCallId,event.usage,"compaction");
                break;
            }
            case "context_compaction":{
                await this.persistCompaction?.(event.checkpoint.record);
                this.checkpoint = event.checkpoint;
                this.contextUsage.lastUsage = undefined;
                break;
            }
            case "run_end":{
                this._state.errorMessage=event.result.errMessage;
                break;
            }
        }
        const signal=this.activeRun?.abortController.signal;
        if(signal===undefined){
        throw new Error("Agent event received outside an active run");
    }
        for(const listener of this.listeners){
            await listener(event,signal);
        }
    }
    private async recordUsage(
        modelCallId: string, usage: Usage | null, purpose?: "compaction",
        ): Promise<void> {
        await this.persistUsage?.(modelCallId, usage, purpose);
        this._runUsage = addUsageTotals(this._runUsage, usage);
        this._sessionUsage = addUsageTotals(this._sessionUsage, usage);
    }
    private async runWithLifecycle(executor:(siganl:AbortSignal)=>Promise<LoopResult>):Promise<LoopResult>{
        if(this.activeRun!==undefined){
            throw new Error("Agent is already processing.");
        }
        const abortController=new AbortController();
        let resolvePromise=()=>{};
        const promise=new Promise<void>((resolve)=>{
            resolvePromise=resolve;
        })
        this.activeRun={
            promise,
            resolve:resolvePromise,
            abortController,
        };
        this._state.isStreaming=true;
        this._state.errorMessage=undefined;
        try{
            return await executor(abortController.signal);
        }catch(error){
            if(!abortController.signal.aborted){
                this._state.errorMessage=error instanceof Error?error.message:String(error);
            }
            throw error;
        }finally{
            this.finishRun();
        }
    }
    private finishRun():void{
        this._state.isStreaming=false;
        this._state.pendingToolCalls=new Set<string>();
        this.activeRun?.resolve();
        this.activeRun=undefined;
    }
}
type SessionUsageOptions = {
    readonly initialTotals?: UsageTotals;
    readonly initialContextAnchor?: ContextUsageAnchor;
    readonly initialCheckpoint?: CompactionCheckpoint;
    readonly persistCompaction?: (record: CompactionRecord) => Promise<void>;
    readonly persist?: (
        modelCallId: string, usage: Usage | null, purpose?: "compaction",
    ) => Promise<void>;
};
