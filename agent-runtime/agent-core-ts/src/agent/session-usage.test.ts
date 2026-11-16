import {test} from "node:test";
import assert from "node:assert/strict";
import type {ModelGateway} from "../model/gateway.js";
import type {ContentBlock,Response} from "../model/types.js";
import {ToolRegistry} from "../tools/registry.js";
import {AgentLoop} from "./loop.js";
import {AgentSession} from "./session.js";
import {estimateMessageTokens} from "./context-budget.js";
import type {AgentEvent,LoopOptions} from "./types.js";

const options:LoopOptions={
    cwd:"/",model:{providerId:"test",modelId:"test"},thinkingJson:"{}",maxOutputTokens:100,
    maxModelCalls:5,maxConcurrentTools:1,ToolExecutionMode:"sequential",contextWindow:10000,
};
function response(kind:"STOP"|"TOOL_CALLS",content:ContentBlock[],totalTokens:number):Response{
    return {
        termination:{kind,rawReason:null},
        usage:{inputTokens:totalTokens-10,outputTokens:10,totalTokens,cachedReadTokens:0,cachedWriteTokens:0,reasoningTokens:0},
        message:{role:"assistant",timestamp:1,content},providerRequestId:null,
    };
}

test("session updates context after tool messages and tracks run and cumulative usage separately",async()=>{
    const responses=[
        response("TOOL_CALLS",[{type:"tool_call",callId:"1",name:"inspect",argumentsJson:"{}"}],100),
        response("STOP",[{type:"text",text:"done"}],200),
        response("STOP",[{type:"text",text:"done again"}],50),
    ];
    let index=0;
    const gateway:ModelGateway={
        async complete(){throw new Error("Unexpected compaction");},
        async *stream(){yield {type:"done",response:responses[index++]};},
    };
    const registry=new ToolRegistry();
    registry.register({
        definition:{name:"inspect",description:"inspect",parametersJson:'{"type":"object","properties":{}}'},
        async execute(){return {content:[{type:"text",text:"tool output"}]};},
    });
    const session=new AgentSession(new AgentLoop(gateway,registry));
    const events:AgentEvent[]=[];
    session.subscribe(event=>{events.push(event);});

    assert.equal((await session.prompt("hello",options)).stopReason,"stop");
    assert.equal(session.runUsage.reportedTokens,300);
    assert.equal(session.sessionUsage.reportedTokens,300);
    const toolIndex=events.findIndex(event=>event.type==="message_end"&&event.message.role==="tool");
    assert.ok(toolIndex>=0);
    const toolEvent=events[toolIndex];
    const update=events[toolIndex+1];
    assert.equal(toolEvent.type,"message_end");
    assert.equal(update.type,"context_update");
    if(toolEvent.type!=="message_end"||update.type!=="context_update") throw new Error("unexpected events");
    assert.equal(update.contextBudget.estimatedInputTokens,100+estimateMessageTokens(toolEvent.message));
    assert.equal(session.estimateContext(options).estimatedInputTokens,200);

    assert.equal((await session.prompt("again",options)).stopReason,"stop");
    assert.equal(session.runUsage.reportedTokens,50);
    assert.equal(session.sessionUsage.reportedTokens,350);
});
