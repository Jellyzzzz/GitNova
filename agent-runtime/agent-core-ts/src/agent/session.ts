import type{Message} from "../model/types.js";
import type{AgentState,AgentEvent,LoopResult} from "./types.js";
import {InputQueue} from "./input-queue.js";

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
    private activeRun:ActiveRun|undefined;

    constructor(InitialMessage:Message[]=[]){
        let messages=InitialMessage.slice();
        this._state={
            get messages(){return messages},

            set messages(nextMessages:Message[]){
                messages=nextMessages.slice();
            },
            isStreaming:false,
            pendingToolCalls:new Set<string>(),
            errorMessage:undefined,
        };
    }
    get state():AgentState{
        return this._state;
    }

    get signal():AbortSignal|undefined{
        return this.activeRun?.abortController.signal;
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
    private async processEvents(event:AgentEvent):Promise<void>{
        switch(event.type){
            case "message_end":
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
            case "model_event":{
                const modelEvent=event.event;
                if(modelEvent.type=="done"&&modelEvent.response.termination.kind==="ERROR"){
                    this._state.errorMessage=modelEvent.response.termination.failure.message;
                }
                break;
            }
            case "run_end":{
                const result=event.result;
                if(result.errMessage!==undefined){
                    this._state.errorMessage=result.errMessage;
                }
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
    private runWithLifeCycle()
}
