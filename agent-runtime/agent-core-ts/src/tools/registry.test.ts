import {test} from "node:test";
import assert from "node:assert/strict";
import {setTimeout} from "node:timers/promises";
import type{ToolCallBlock} from "../model/types.js";
import {toPiToolResultMessage} from "../model/pi-messages.js";
import {ToolRegistry} from "./registry.js";
import type{ToolExecutionResult} from "./registry.js";
import type{AgentTool,ToolContext,ToolResult,ToolUpdateCallback} from "./types.js";

function createRegistry(execute:AgentTool["execute"]):ToolRegistry{
    const registry=new ToolRegistry();
    registry.register({
        definition:{
            name:"inspect",
            description:"Inspect the working directory",
            parametersJson:JSON.stringify({
                type:"object",
                properties:{
                    command:{type:"string"},
                    timeout:{type:"number"},
                },
                required:["command"],
                additionalProperties:false,
            }),
        },
        execute:execute,
    });
    return registry;
}

function createCall(argumentsJson:string='{"command":"pwd"}'):ToolCallBlock{
    return {
        type:"tool_call",
        callId:"call-1",
        name:"inspect",
        argumentsJson:argumentsJson,
    };
}

function assertFailure(execution:ToolExecutionResult,call:ToolCallBlock):string{
    const message=toPiToolResultMessage(execution.message);
    assert.equal(message.toolCallId,call.callId);
    assert.equal(message.toolName,call.name);
    assert.equal(message.isError,true);
    const block=execution.message.content[0];
    assert.equal(block.type,"tool_result");
    assert.ok(block.type==="tool_result");
    assert.ok(block.content.length>0);
    return block.content;
}

test("registry: validates arguments and preserves context, content order and details",async()=>{
    const output:ToolResult={
        content:[
            {type:"text",text:"before image"},
            {type:"image",data:"aGVsbG8=",mimeType:"image/png"},
            {type:"text",text:"after image"},
        ],
        details:{exitCode:0},
    };
    let receivedArgs:unknown;
    let receivedContext:ToolContext|undefined;
    const registry=createRegistry(async(args,context)=>{
        receivedArgs=args;
        receivedContext=context;
        return output;
    });
    const call=createCall('{"command":"pwd","timeout":"30"}');
    const signal=new AbortController().signal;
    const execution=await registry.execute(call,"/workspace",signal);

    assert.deepEqual(receivedArgs,{command:"pwd",timeout:30});
    assert.deepEqual(receivedContext,{callId:call.callId,cwd:"/workspace",signal:signal});
    assert.deepEqual(execution.details,output.details);
    assert.deepEqual(execution.message.content.slice(1),output.content);
    const message=toPiToolResultMessage(execution.message);
    assert.equal(message.toolCallId,call.callId);
    assert.equal(message.toolName,call.name);
    assert.equal(message.isError,false);
    assert.deepEqual(message.content.slice(1),output.content);
});

test("registry: unknown tools produce a failed tool message",async()=>{
    const call={...createCall(),name:"missing"};
    const execution=await new ToolRegistry().execute(call,"/workspace",new AbortController().signal);
    assert.match(assertFailure(execution,call),/Tool not found: missing/);
});

test("registry: invalid JSON and schema violations do not execute the tool",async()=>{
    let calls=0;
    const registry=createRegistry(async()=>{
        calls++;
        return {content:[]};
    });
    for(const json of ["{","null","[]","42",'"text"',"{}",'{"command":{}}']){
        const call=createCall(json);
        const execution=await registry.execute(call,"/workspace",new AbortController().signal);
        assertFailure(execution,call);
    }
    assert.equal(calls,0);
});

test("registry: tool exceptions and thrown strings become failed messages",async()=>{
    for(const failure of [new Error("File not found"),"command failed"]){
        const registry=createRegistry(async()=>{throw failure;});
        const call=createCall();
        const execution=await registry.execute(call,"/workspace",new AbortController().signal);
        assert.equal(assertFailure(execution,call),failure instanceof Error?failure.message:failure);
    }
});

test("registry: cancellation before execution prevents tool invocation",async()=>{
    let calls=0;
    const registry=createRegistry(async()=>{
        calls++;
        return {content:[]};
    });
    const controller=new AbortController();
    const reason=new Error("User cancelled");
    controller.abort(reason);
    await assert.rejects(registry.execute(createCall(),"/workspace",controller.signal),error=>error===reason);
    assert.equal(calls,0);
});

test("registry: cancellation during execution propagates the signal reason",async()=>{
    const registry=createRegistry(async(_args,context)=>{
        await setTimeout(1000,undefined,{signal:context.signal});
        return {content:[]};
    });
    const controller=new AbortController();
    const pending=registry.execute(createCall(),"/workspace",controller.signal);
    const reason=new Error("User cancelled");
    controller.abort(reason);
    await assert.rejects(pending,error=>error===reason);
});

test("registry: AbortError is propagated even when the caller signal is active",async()=>{
    const error=new Error("Tool aborted");
    error.name="AbortError";
    const registry=createRegistry(async()=>{throw error;});
    await assert.rejects(
        registry.execute(createCall(),"/workspace",new AbortController().signal),
        failure=>failure===error,
    );
});

test("registry: updates stop when execution succeeds or fails",async()=>{
    const partial:ToolResult={content:[{type:"text",text:"working"}]};
    for(const shouldFail of [false,true]){
        const updates:ToolResult[]=[];
        let callback:ToolUpdateCallback|undefined;
        const registry=createRegistry(async(_args,_context,onUpdate)=>{
            callback=onUpdate;
            onUpdate?.(partial);
            if(shouldFail){
                throw new Error("Execution failed");
            }
            return partial;
        });
        await registry.execute(createCall(),"/workspace",new AbortController().signal,update=>updates.push(update));
        assert.deepEqual(updates,[partial]);
        callback?.({content:[{type:"text",text:"late update"}]});
        assert.deepEqual(updates,[partial]);
    }
});

test("registry: cancellation suppresses updates and a late successful return",async()=>{
    const controller=new AbortController();
    const reason=new Error("User cancelled");
    const updates:ToolResult[]=[];
    const registry=createRegistry(async(_args,_context,onUpdate)=>{
        controller.abort(reason);
        onUpdate?.({content:[{type:"text",text:"late update"}]});
        return {content:[{type:"text",text:"late success"}]};
    });
    await assert.rejects(
        registry.execute(createCall(),"/workspace",controller.signal,update=>updates.push(update)),
        error=>error===reason,
    );
    assert.deepEqual(updates,[]);
});
