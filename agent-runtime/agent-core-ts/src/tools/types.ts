import type{TextBlock,ImageBlock,ToolDefinition,JsonValue} from "../model/types.js";
export interface ToolResult{
    readonly content:readonly (TextBlock|ImageBlock)[];
    readonly details?:JsonValue;
}
export interface ToolContext{
    readonly callId:string;
    readonly cwd:string;
    readonly signal:AbortSignal;
}

export type ToolUpdateCallback=(partial:ToolResult)=>void;
export interface AgentTool{
    readonly definition:ToolDefinition;
    readonly mode?:ToolExecutionMode;
    execute(args:unknown,context:ToolContext,onUpdate?:ToolUpdateCallback):Promise<ToolResult>;
}
export type ToolExecutionMode="sequential"|"parallel";