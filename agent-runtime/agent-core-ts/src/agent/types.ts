import type{
    Message,
    ModelRef,
    ModelEvent,
    Response,
    ToolCallBlock,
} from "../model/types.js";
import type{
    ToolResult,
    ToolExecutionMode,
} from "../tools/types.js";
import type{ToolExecutionResult} from "../tools/registry.js";
export type InputMode="steering"|"follow_up";
export interface LoopOptions{
    readonly cwd:string;
    readonly model:ModelRef;
    readonly thinkingJson:string;
    readonly maxOutputTokens:number;
    readonly maxModelCalls:number;
    readonly maxConcurrentTools:number;
    readonly ToolExecutionMode:ToolExecutionMode;
}
export type LoopStopReason=|"stop"
    |"cancelled"
    |"max_model_calls"
    |"context_limit"
    |"model_error"
    |"output_limit"
    |"protocol_error"
    |"policy_stop";
export interface LoopResult{
    readonly stopReason:LoopStopReason;
    readonly lastResponse:Response|null;
    readonly modelCalls:number;
    readonly errMessage?:string;
}
export type AgentEvent=|{
        readonly type:"run_start";
    }
    |{
        readonly type:"run_end";
        readonly result:LoopResult;
    }
    |{
        readonly type:"turn_start";
        readonly modelCallId:string;
    }
    |{
        readonly type:"turn_end";
        readonly modelCallId:string;
    }
    |{
        readonly type:"model_event";
        readonly modelCallId:string;
        readonly event:ModelEvent;
    }
    |{
        readonly type:"tool_start";
        readonly call:ToolCallBlock;
    }
    |{
        readonly type:"tool_update";
        readonly callId:string;
        readonly result:ToolResult;
    }
    |{
        readonly type:"tool_end";
        readonly callId:string;
        readonly result:ToolExecutionResult;
    }
    |{
        readonly type:"approval_required";
        readonly approvalId:string;
        readonly call:ToolCallBlock;
    }
    |{
        readonly type:"input_queued";
        readonly mode:InputMode;
        readonly message:Message;
    }
    |{
        readonly type:"tools_changed";
        readonly names:readonly string[];
    }
    |{
        readonly type:"message_end";
        readonly message:Message;
    };
export type AgentEventSink=
    (event:AgentEvent)=>void|Promise<void>//Loop交给session的事件数据结构

export interface AgentState{
    messages:Message[];
    readonly isStreaming:boolean;
    readonly pendingToolCalls:ReadonlySet<string>;
    readonly errorMessage?:string;
}