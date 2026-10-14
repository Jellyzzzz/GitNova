export type Role="user"|"assistant"|"system"|"tool";
export interface TextBlock{
    readonly type:"text";
    readonly text:string;
}
export interface ToolCallBlock{
    readonly type:"tool_call";
    readonly callId:string;
    readonly name:string;
    readonly argumentsJson:string;
}
export interface ReasoningBlock{
    readonly type:"reasoning";
    readonly content:string;
}
export interface ToolResultBlock{
    readonly type:"tool_result";
    readonly name:string;
    readonly callId:string;
    readonly content:string;
    readonly isError:boolean;
}
export type ImageBlock={
    readonly type:"image";
    readonly data:string;
    readonly mimeType:string;
}
export type ContentBlock=TextBlock|ToolCallBlock|ReasoningBlock|ToolResultBlock|ImageBlock;
export interface Message{
    readonly role:Role;
    readonly content:readonly ContentBlock[];
    readonly timestamp:number;
    readonly providerReplay?:ProviderReplay;
}
export interface ToolDefinition{
    readonly name:string;
    readonly description:string;
    readonly parametersJson:string;
}
export interface Usage{
    readonly inputTokens:number|null;
    readonly outputTokens:number|null;
    readonly totalTokens:number|null;
    readonly cachedReadTokens:number|null;
    readonly cachedWriteTokens:number|null;
    readonly reasoningTokens:number|null;
}
export type JsonValue =
    | null
    | boolean
    | number
    | string
    | readonly JsonValue[]
    | { readonly [key: string]: JsonValue };

export interface ProviderReplay {
    readonly adapter: string;
    readonly version: number;

    readonly response?: JsonValue;
    readonly blocks?: readonly JsonValue[];
}
export interface ModelRef{
    readonly providerId:string;
    readonly modelId:string;
}
export type Finish="STOP"|"ERROR"|"TOOL_CALLS"|"LENGTH"|"CONTINUE";
export interface Request{
    readonly modelCallId:string;
    readonly model:ModelRef;
    readonly messages:readonly Message[];
    readonly tools:readonly ToolDefinition[];
    readonly maxOutputTokens:number;
    readonly thinkingJson:string;
}
export interface Response{
    readonly termination:Termination;
    readonly usage:Usage|null;
    readonly message:Message;
    readonly providerRequestId:string|null;
}
export type Termination={
    readonly kind:"ERROR";
    readonly rawReason:string|null;
    readonly failure:ModelFailure;
}|{
    readonly kind:Exclude<Finish,"ERROR">;
    readonly rawReason:string|null;
    readonly failure?:never;
}
interface ModelFailure{
    code:string;
    message:string;
    httpStatus:number|null;
}
export type ModelEvent={readonly type:"text_delta",readonly contentIndex:number,readonly delta:string }
                    |{readonly type:"reasoning_delta",readonly contentIndex:number,readonly delta:string}
                    |{readonly type:"done",readonly response:Response};
