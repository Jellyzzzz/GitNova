import type{AssistantMessage as PiAssistantMessage,UserMessage as PiUserMessage,SystemMessage as PiSystemMessage,ToolResultMessage as PiToolResultMessage,ImageContent as PiImageContent,
    TextContent as PiTextContent,JsonObject as PiJsonObject,ThinkingContent as PiThinkingContent,
    ToolCall as PiToolCall,StopReason as PiStopReason,Usage as PiUsage,ModelThinkingLevel}from "@earendil-works/pi-ai";
import type { JsonValue,ToolResultBlock,Message } from "./types.js";
export function toPiAssistantMessage(message:Message):PiAssistantMessage{
    if(message.role!=="assistant"){
        throw new Error(`Cannot convert non-assistant message to Pi assistant message`);
    }
    const replay=message.providerReplay;
    if(replay===undefined||replay.adapter!=="pi"||replay.version!==1){
        throw new Error(`Cannot convert message with non-Pi provider replay to Pi assistant message`);
    }
    const response=asReplayObject(replay.response);
    if(response===undefined){
        throw new Error(`Cannot convert message with non-object Pi provider replay response to Pi assistant message`);
    }
    const result :PiAssistantMessage={
        role:"assistant",
        content:toPiAssistantContent(message),
        timestamp:message.timestamp,

        api:requireString(response,"api"),
        provider:requireString(response,"provider"),
        model:requireString(response,"model"),
        
        usage:decodeUsage(response),
        stopReason:requireStopReason(response,"stopReason")
    };
    const responseModel=optionalString(response,"responseModel");
    if(responseModel!==undefined){
        result.responseModel=responseModel;
    }
    const responseId=optionalString(response,"responseId");
    if(responseId!==undefined){
        result.responseId=responseId;
    }
    const thinkingLevel=optionalThinkingLevel(response);
    if(thinkingLevel!==undefined){
        result.thinkingLevel=thinkingLevel;
    }
    const providerThinkingLevel=optionalString(response,"providerThinkingLevel");
    if(providerThinkingLevel!==undefined){
        result.providerThinkingLevel=providerThinkingLevel;
    }
    const rawStopReason=optionalString(response,"rawStopReason");
    if(rawStopReason!==undefined){
        result.rawStopReason=rawStopReason;
    }
    return result;
}
export function toPiUserMessage(message:Message):PiUserMessage{
    if(message.role!=="user"){
        throw new Error(`Invalid message role: expected "user", got "${message.role}"`);
    }
    const content:Array<PiTextContent|PiImageContent>=[];
    for(const block of message.content){
        switch (block.type){
            case "text":
                content.push(
                    {
                        type:"text",
                        text:block.text
                }
                );
                break;
            case "image":
                content.push(
                    {
                        type:"image",
                        data:block.data,
                        mimeType:block.mimeType
                    }
                );
                break;
            default:
                throw new Error(`Invalid content block type: expected "text" or "image", got "${block.type}"`);
        }
    }
    return {
        role:"user",
        content:content,
        timestamp:message.timestamp
    };
}
export function toPiSystemMessage(message:Message):PiSystemMessage{
    if(message.role!=="system"){
        throw new Error(`Invalid message role: expected "system", got "${message.role}"`);
    }
    const content:Array<PiTextContent>=[];
    for(const block of message.content){
        if(block.type!=="text"){
            throw new Error(`Invalid content block type: expected "text", got "${block.type}"`);
        }
        content.push(
            {
                type:"text",
                text:block.text
            }
        );
    }
    return {
        role:"system",
        content:content,
        timestamp:message.timestamp
    }
}
export function toPiToolResultMessage(message:Message):PiToolResultMessage{
    if(message.role!=="tool"){
        throw new Error(`Invalid message role: expected "tool", got "${message.role}"`);
    }
    let toolResult:ToolResultBlock|undefined;
    const content:Array<PiTextContent|PiImageContent>=[];
    for(const block of message.content){
        switch(block.type){
            case "tool_result":
                if(toolResult!==undefined){
                    throw new Error(`Invalid message content: multiple tool_result blocks found`);
                }
                toolResult=block;
                content.push({
                    type:"text",
                    text:block.content
                    }
                )
                break;
            case "text":
                content.push({
                    type:"text",
                    text:block.text
                })
                break;
            case "image":
                content.push({
                    type:"image",
                    data:block.data,
                    mimeType:block.mimeType
                })
                break;
        case "reasoning":
        case "tool_call":
            throw new Error(
                `Unsupported block type "${block.type}" in tool message`,
            );
        }
    }if(toolResult===undefined){
            throw new Error(`Invalid message content: no tool_result block found`);
        }
        return {
            role:"toolResult",
            toolCallId:toolResult.callId,
            toolName:toolResult.name,
            content:content,
            isError:toolResult.isError,
            timestamp:message.timestamp
        };
}
export function toPiMessage(message:Message):PiAssistantMessage|PiUserMessage|PiSystemMessage|PiToolResultMessage{
    switch(message.role){
        case "assistant":
            return toPiAssistantMessage(message);
        case "user":
            return toPiUserMessage(message);
        case "system":
            return toPiSystemMessage(message);
        case "tool":
            return toPiToolResultMessage(message);
        default:
            throw new Error(`Invalid message role: expected "assistant", "user", "system", or "tool", got "${message.role}"`);
    }
}
export function toPiMessages(messages:readonly Message[]):(PiAssistantMessage|PiUserMessage|PiSystemMessage|PiToolResultMessage)[]{
    if(messages.length===0){
        throw new Error(`Invalid messages array: must contain at least one message`);
    }
    const result:(PiAssistantMessage|PiUserMessage|PiSystemMessage|PiToolResultMessage)[]=[];
    for(const message of messages){
        result.push(toPiMessage(message));
    }
    return result;
}

//helper
function toPiAssistantContent(message:Message):Array<PiTextContent|PiThinkingContent|PiToolCall>{
    const content:Array<PiTextContent|PiThinkingContent|PiToolCall>=[];
    for(let index=0;index<message.content.length;index++){
        const block=message.content[index];
        if(block===undefined){
            throw new Error(`Missing assistant content block at index ${index}`,)
        }
        switch(block.type){
            case "text":{
                const replay=getReplayBlock(message,index,"text");
                const piText:PiTextContent={
                    type:"text",
                    text:block.text,
                }
                const textSignature=replay?.["textSignature"];
                if(typeof textSignature==="string"){
                    piText.textSignature=textSignature;
                }
                content.push(piText);
                break;}
            case "reasoning":{
                const replay=getReplayBlock(message,index,"thinking");
                const piThinking:PiThinkingContent={
                    type:"thinking",
                    thinking:block.content,
                }
                const thinkingSignature=replay?.["thinkingSignature"];
                if(typeof thinkingSignature==="string"){
                    piThinking.thinkingSignature=thinkingSignature;
                }
                if(replay?.["redacted"]===true){
                    piThinking.redacted=true;
                }
                content.push(piThinking);
                break;}
            case "tool_call":{
                const parsedArguments:unknown=JSON.parse(block.argumentsJson);
                if(typeof parsedArguments!=="object"||parsedArguments===null||Array.isArray(parsedArguments)){
                    throw new Error(`Invalid tool call arguments at index ${index}`);
                }
                const replay=getReplayBlock(message,index,"tool_call");
                const piToolCall:PiToolCall={
                    type:"toolCall",
                    id:block.callId,
                    name:block.name,
                    arguments:parsedArguments as PiJsonObject,
                }
                const thoughtSignature=replay?.["thoughtSignature"];
                if(typeof thoughtSignature==="string"){
                    piToolCall.thoughtSignature=thoughtSignature;
                }
                const namespace=replay?.["namespace"];
                if(typeof namespace==="string"){
                    piToolCall.namespace=namespace;
                }
                content.push(piToolCall);
                break;
            }
            case "image":
            case "tool_result":
                throw new Error(`Unsupported assistant content block type "${block.type}" at index ${index}`);
        }
    }
    return content;
}
type ReplayObject={readonly [key:string]:JsonValue;};
function asReplayObject(value:JsonValue|undefined):ReplayObject|undefined{
    if(typeof value!=="object"||value===null||Array.isArray(value)){
        return undefined;
    }
    return value as ReplayObject;
}

function getReplayBlock(message:Message,index:number,expectedType:string):ReplayObject|undefined{
    const replay=message.providerReplay;
    if(replay===undefined||replay.adapter!=="pi"||replay.version!==1){
        return undefined;
    }
    if(replay.blocks===undefined||replay.blocks.length!==message.content.length){
        throw new Error(`Invalid Pi provider replay: blocks array is missing or has incorrect length`)  ;
    }
    const block=asReplayObject(replay.blocks[index]);
    if(block?.["type"]!==expectedType){
        throw new Error(`Invalid Pi provider replay block at index ${index}: expected "${expectedType}", got "${String(block?.["type"])}"`);
    }
    return block;
}
function requireString(object:ReplayObject,key:string):string{
    const value=object[key];
    if(typeof value!=="string"){
        throw new Error(`Invalid Pi replay field "${key}": expected string`,);
    }
    return value;
}
function optionalString(object:ReplayObject,key:string):string|undefined{
    const value=object[key];
    if(value===null||value===undefined){
        return undefined;
    }
    if(typeof value!=="string"){
        throw new Error(`Invalid Pi replay field "${key}": expected string or null`,)
    }
    return value;
}
function requireNumber(object:ReplayObject,key:string):number{
    const value=object[key];
    if(typeof value!=="number"||!Number.isFinite(value)){
        throw new Error(`Invalid Pi replay field "${key}": expected number`,);
    }
    return value;
}
function requireStopReason(object:ReplayObject,key:string):PiStopReason{
    const value=object[key];
    switch(value){
        case "stop":
        case "toolUse":
        case "length":
        case "deferred":
        case "error":
        case "aborted":
        case "pending":
            return value;
        default:
            throw new Error(
                `Invalid Pi replay stopReason: ${String(value)}`,
            );
    }
}
function decodeUsage(response:ReplayObject):PiUsage{
    const usage=asReplayObject(response["usage"]);
    if(usage===undefined){
        throw new Error(`Invalid Pi replay usage: expected object`,);
    }
    const cost=asReplayObject(usage["cost"]);
    if (cost === undefined) {
        throw new Error(
            "Invalid Pi replay: usage.cost must be an object",
        );
    }
    const result:PiUsage={
        input:requireNumber(usage,"input"),
        output:requireNumber(usage,"output"),
        cacheRead:requireNumber(usage,"cacheRead"),
        cacheWrite:requireNumber(usage,"cacheWrite"),
        totalTokens:requireNumber(usage,"totalTokens"),
        cost:{
            input:requireNumber(cost,"input"),
            output:requireNumber(cost,"output"),
            cacheRead:requireNumber(cost,"cacheRead"),
            cacheWrite:requireNumber(cost,"cacheWrite"),
            total:requireNumber(cost,"total"),
        }
    }
    const reasoning=usage["reasoning"];
    if(reasoning!==undefined&&reasoning!==null){
        if(typeof reasoning!=="number"||!Number.isFinite(reasoning)){
            throw new Error(`Invalid Pi replay usage.reasoning: expected number or null`,);
        }
        result.reasoning=reasoning;
    }
    return result;
}
function optionalThinkingLevel(response:ReplayObject):ModelThinkingLevel|undefined{
    const value=response["thinkingLevel"];
    if(value===undefined||value===null){
        return undefined;
    }
    switch(value){
        case "off":
        case "minimal":
        case "low":
        case "medium":
        case "high":
        case "xhigh":
        case "max":
            return value;
        
            default:
                throw new Error(
                `Invalid Pi replay thinkingLevel: ${String(value)}`,
            );
    }
}