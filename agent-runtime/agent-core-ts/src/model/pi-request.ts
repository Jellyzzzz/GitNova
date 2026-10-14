import type{SimpleStreamOptions,ThinkingLevel,Context as PiContext,Tool as PiTool}from "@earendil-works/pi-ai";
import type{Request,ToolDefinition}from "./types.js";
import {toPiMessages} from"./pi-messages.js";
function isThinkingValue(value:unknown):value is ThinkingLevel{
    switch (value){
        case "minimal":
        case "low":
        case "medium":
        case "high":
        case "xhigh":
        case "max":
            return true;
        default :
        return false;
    }
}
export function toPiOptions(request:Request,signal:AbortSignal):SimpleStreamOptions{
    const thinking:unknown=JSON.parse(request.thinkingJson);
    if(typeof thinking!=="object"||thinking==null||Array.isArray(thinking)){
        throw new Error("Invalid thinkingJson: must be a JSON object");
    }
    const thinkingObject=thinking as Record<string, unknown>;
    for(const key of Object.keys(thinkingObject)){
        if(key!=="reasoning"){
            throw new Error(`Invalid thinkingJson: unexpected key "${key}"`);
        }
    }
    const options:SimpleStreamOptions={
        maxTokens:request.maxOutputTokens,
        signal:signal,
    };
    if("reasoning" in thinkingObject){
        if(!isThinkingValue(thinkingObject["reasoning"])){ 
            throw new Error(`Invalid thinkingJson: reasoning must be one of "minimal", "low", "medium", "high", "xhigh", or "max"`);
        }
        options.reasoning=thinkingObject.reasoning;
    }
    return options;
}
export function toPiTools(tools:readonly ToolDefinition[]):PiTool[]{
        const piTools:PiTool[]=[];
        for(const tool of tools){
            let parameters:unknown;
            try{
                parameters=JSON.parse(tool.parametersJson);
            }catch(e){
                throw new Error(`Invalid parametersJson for tool "${tool.name}": ${e}`);
            }
            if(typeof parameters!=="object"||parameters==null||Array.isArray(parameters)){
                throw new Error(`Invalid parametersJson for tool "${tool.name}": must be a JSON object`);
            }
            piTools.push({
                name:tool.name,
                description:tool.description,
                parameters:parameters as Record<string, unknown>,
            });
        }
        return piTools;
}
export function toPiContext(request:Request):PiContext{
    return {
        messages:toPiMessages(request.messages),
        tools:toPiTools(request.tools),
    }
}

