import {validateToolArguments} from "@earendil-works/pi-ai";
import type{JsonObject as PiJsonObject} from "@earendil-works/pi-ai";
import type{ToolDefinition,ToolCallBlock,Message,JsonValue,ToolResultBlock}from "../model/types.js";
import type{AgentTool,ToolUpdateCallback}from "./types.js";
import {toPiTools} from "../model/pi-request.js";
export class ToolRegistry{
    private readonly tools=new Map<string,AgentTool>();
    
    register(tool:AgentTool):void{
        const name=tool.definition.name;
        if(name.trim().length==0){
            throw new Error("Tool name must not be empty");
        }
        if(this.tools.has(name)){
            throw new Error(`Tool already registered: ${name}`);
        }
        this.tools.set(name, tool);
    }

    definitions():ToolDefinition[]{
        const definitions:ToolDefinition[]=[];
        for(const tool of this.tools.values()){
            definitions.push(tool.definition);
        }
        return definitions;
    }
    
    async execute(call:ToolCallBlock,cwd:string,signal:AbortSignal,onUpdate?:ToolUpdateCallback,
    ):Promise<ToolExecutionResult>{
        signal.throwIfAborted();
        let acceptingUpdates=true;
        try {
            const tool=this.tools.get(call.name);
            if(tool===undefined){
                throw new Error(`Tool not found: ${call.name}`);
            }
            const args=this.parseArguments(tool,call);
            const context={
                callId:call.callId,
                cwd:cwd,
                signal:signal,
            };
            const handleUpdate:ToolUpdateCallback|undefined=onUpdate===undefined
                ?undefined
                :(partial)=>{
                    if(acceptingUpdates&&!signal.aborted){
                        onUpdate(partial);
                    }
                };
            const result=await tool.execute(args,context,handleUpdate);
            acceptingUpdates=false;
            signal.throwIfAborted();
            
            const resultBlock:ToolResultBlock={
                type:"tool_result",
                callId:call.callId,
                name:call.name,
                content:"",
                isError:false,
            };
            const message:Message={
                role:"tool",
                content:[
                    resultBlock,
                    ...result.content
                ],
                timestamp:Date.now(),
            }
            return {
                message:message,
                details:result.details,
            }
        } catch (error:unknown) {
            signal.throwIfAborted();
            if(error instanceof Error&&error.name==="AbortError"){
                throw error;
            }
            const resultBlock:ToolResultBlock={
                type:"tool_result",
                callId:call.callId,
                name:call.name,
                content:error instanceof Error?error.message:String(error),
                isError:true,
            };
            return {
                message:{
                    role:"tool",
                    content:[resultBlock],
                    timestamp:Date.now(),
                },
            };
        } finally {
            acceptingUpdates=false;
        }
    }

    private parseArguments(tool:AgentTool,call:ToolCallBlock):unknown{
        const args:unknown=JSON.parse(call.argumentsJson);
        if(
        typeof args!=="object"||
        args===null||
        Array.isArray(args)
    ){
        throw new Error("Tool arguments must be a JSON object");
    }
    const piTool=toPiTools([tool.definition])[0];
    if(piTool===undefined){
        throw new Error(`Missing tool definition: ${call.name}`);
    }
    const validated:unknown=validateToolArguments(
        piTool,
        {
            type:"toolCall",
            id:call.callId,
            name:call.name,
            arguments:args as PiJsonObject,
        },
    )
    return validated;
    }
}
export interface ToolExecutionResult{
        readonly message:Message;
        readonly details?:JsonValue;
    }
