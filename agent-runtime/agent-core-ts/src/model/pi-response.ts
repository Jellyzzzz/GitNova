import type{AssistantMessage as PiAssistantMessage}from "@earendil-works/pi-ai";
import type { ContentBlock,JsonValue,Response,Termination } from "./types.js";
export function toTermination(message:PiAssistantMessage):Termination{
    const rawReason=message.rawStopReason??null;
    switch (message.stopReason){
        case "stop":
            return {
                kind:"STOP",
                rawReason:rawReason,
            };
        case "toolUse":
            return {
                kind:"TOOL_CALLS",
                rawReason:rawReason,
            };
        case "length":
            return {
                kind:"LENGTH",
                rawReason:rawReason,
            };
        case "deferred":
            return {
                kind:"CONTINUE",
                rawReason:rawReason,
            };
        case "error":
            return {
                kind:"ERROR",
                rawReason:rawReason,
                failure:{
                    code:"MODEL_ERROR",
                    message:message.errorMessage??"Model request failed",
                    httpStatus:null
                },
            };
        case "aborted":{
            const error = new Error(
                message.errorMessage ?? "Model request aborted",
            );
            error.name = "AbortError";
            throw error;
        };
        case "pending":
            throw new Error(
                "Pi returned a non-terminal pending message",
            );
        }
}
export function fromPiAssistantMessage(message:PiAssistantMessage):Response{
    const content:ContentBlock[]=[];
    const replayBlocks:JsonValue[]=[];
    for(const block of message.content){
        switch (block.type){
            case "text":
                content.push({
                    type:"text",
                    text:block.text
                });
                replayBlocks.push(
                    {
                        type:"text",
                    textSignature:block.textSignature??null
                }
                );
                break;
            case "thinking":
                content.push({
                    type:"reasoning",
                    content:block.thinking,
                });
                replayBlocks.push({
                    type:"thinking",
                    thinkingSignature:block.thinkingSignature??null,
                    redacted:block.redacted??false
                });
                break;
            case "toolCall":
                content.push({
                    type:"tool_call",
                    callId:block.id,
                    name:block.name,
                    argumentsJson:JSON.stringify(block.arguments),
                })
                replayBlocks.push({
                    type:"tool_call",
                    thoughtSignature:block.thoughtSignature??null,
                    namespace:block.namespace??null,
                });
                break;
            }
        }
            return {
                termination:toTermination(message),

                usage:{
                    inputTokens:message.usage.input??null,
                    outputTokens:message.usage.output??null,
                    totalTokens:message.usage.totalTokens??null,
                    cachedReadTokens:message.usage.cacheRead??null,
                    cachedWriteTokens:message.usage.cacheWrite??null,
                    reasoningTokens:message.usage.reasoning??null,
                },

                message:{
                    role:"assistant",
                    content:content,
                    timestamp:message.timestamp,
                    providerReplay:{
                        adapter:"pi",
                        version:1,

                        response:{
                            api:message.api,
                            model:message.model,
                            provider:message.provider,
                            responseModel:message.responseModel??null,
                            responseId:message.responseId??null,
                            providerThinkingLevel:message.providerThinkingLevel??null,
                            thinkingLevel:message.thinkingLevel??null,

                            stopReason:message.stopReason,
                            rawStopReason:message.rawStopReason??null,
                            
                            usage: {
                        input: message.usage.input,
                        output: message.usage.output,
                        cacheRead: message.usage.cacheRead,
                        cacheWrite: message.usage.cacheWrite,
                        reasoning:
                            message.usage.reasoning ?? null,
                        totalTokens:
                            message.usage.totalTokens,

                        cost: {
                            input: message.usage.cost.input,
                            output: message.usage.cost.output,
                            cacheRead:
                                message.usage.cost.cacheRead,
                            cacheWrite:
                                message.usage.cost.cacheWrite,
                            total: message.usage.cost.total,
                        },
                    },
                        },
                        blocks:replayBlocks,
                    },
                },
                providerRequestId:null
            };
}
