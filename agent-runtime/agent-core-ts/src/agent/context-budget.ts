import { Buffer } from "node:buffer";
import type { Message, Request,ModelRef} from "../model/types.js";
export type ContextBudget={
    readonly estimatedInputTokens: number;
    readonly inputBudgetTokens:number;
}
export function estimateMessageTokens(message:Message):number{
    let bytes=Buffer.byteLength(message.role);
    let imageTokens=0;
    for(const block of message.content){
        switch(block.type){
            case "text":
                bytes+=Buffer.byteLength(block.text);
                break;
            case "reasoning":
                bytes+=Buffer.byteLength(block.content);
                break;
            case "tool_call":
                bytes+=Buffer.byteLength(block.name)+Buffer.byteLength(block.callId)+Buffer.byteLength(block.argumentsJson);
                break;
            case "tool_result":
                bytes+=Buffer.byteLength(block.name)+Buffer.byteLength(block.callId)+Buffer.byteLength(block.content);
                break;
            case "image":
                imageTokens+=1200;
                break;
        }
    }
    return Math.ceil(bytes/4)+imageTokens;
}
export type ContextUsageAnchor={
    readonly totalTokens:number;
    readonly model:ModelRef;
    readonly systemPrompt?:string;
    readonly tools:Request["tools"];
    readonly prefix:readonly Message[];
}

export type ContextUsageState = {
    lastUsage?: ContextUsageAnchor;
};
function canReuseUsage(request:Request,anchor:ContextUsageAnchor):boolean{
    return request.model.providerId===anchor.model.providerId
        &&request.model.modelId===anchor.model.modelId
        &&request.systemPrompt===anchor.systemPrompt
        &&anchor.prefix.every((message,index)=>message===request.messages[index])
        &&request.tools.length===anchor.tools.length
        &&anchor.tools.every((tool,index)=>{
            const current=request.tools[index];
            return tool.name===current.name
                &&tool.description===current.description
                &&tool.parametersJson===current.parametersJson;
        });
}
export function measureContext(
    request:Request,
    contextWindow:number,
    reserveTokens:number|undefined,
    anchor?:ContextUsageAnchor,
):ContextBudget{
    if (!Number.isSafeInteger(contextWindow)
        ||reserveTokens===undefined
        || !Number.isSafeInteger(request.maxOutputTokens)
        || request.maxOutputTokens < 1
        || !Number.isSafeInteger(reserveTokens)
        || reserveTokens < request.maxOutputTokens
        || contextWindow <= reserveTokens) {
        throw new RangeError("Invalid context window or token reservation");
    }
    let estimatedInputTokens=0;
    let firstMessageIndex=0;
    if(anchor!==undefined&&canReuseUsage(request,anchor)){
        estimatedInputTokens=anchor.totalTokens;
        firstMessageIndex=anchor.prefix.length;
    }else{
        estimatedInputTokens=Math.ceil(Buffer.byteLength(request.systemPrompt??"")/4);
        for(const tool of request.tools){
            const bytes=Buffer.byteLength(tool.name)
                +Buffer.byteLength(tool.description)
                +Buffer.byteLength(tool.parametersJson);
            estimatedInputTokens+=Math.ceil(bytes/4);
        }
    }
    for(let index=firstMessageIndex;index<request.messages.length;index++){
        estimatedInputTokens+=estimateMessageTokens(request.messages[index]);
    }
    return {
        estimatedInputTokens,
        inputBudgetTokens:contextWindow-reserveTokens,
    };
}
