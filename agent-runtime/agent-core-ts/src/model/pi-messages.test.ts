import { test } from "node:test";
import assert from "node:assert/strict";
import type { AssistantMessage as PiAssistantMessage } from "@earendil-works/pi-ai";
import type { Message,ToolResultBlock } from "./types.js";
import { toPiMessage,toPiMessages } from "./pi-messages.js";
import { fromPiAssistantMessage } from "./pi-response.js";

function assistantFixture():PiAssistantMessage{
    return {
        role:"assistant",
        api:"anthropic-messages",
        provider:"anthropic",
        model:"test-model",
        responseModel:"test-model-revision",
        responseId:"response-1",
        providerThinkingLevel:"high",
        thinkingLevel:"high",
        timestamp:1234,
        stopReason:"toolUse",
        rawStopReason:"tool_use",
        content:[
            {type:"thinking",thinking:"",thinkingSignature:"opaque-reasoning",redacted:true},
            {type:"text",text:"Reading the file",textSignature:"text-signature"},
            {type:"toolCall",id:"call-1",name:"read",arguments:{path:"a.txt",options:{offset:1}},thoughtSignature:"tool-signature",namespace:"files"},
        ],
        usage:{
            input:10,output:5,cacheRead:2,cacheWrite:1,reasoning:3,totalTokens:18,
            cost:{input:0.1,output:0.2,cacheRead:0.01,cacheWrite:0.02,total:0.33},
        },
    };
}

const toolResult:ToolResultBlock={
    type:"tool_result",
    callId:"call-1",
    name:"read",
    content:"file contents",
    isError:false,
};

test("assistant: JSON round trip preserves content, signatures and usage",()=>{
    const original=assistantFixture();
    const response=fromPiAssistantMessage(original);
    const restored:Message=JSON.parse(JSON.stringify(response.message));
    assert.deepEqual(toPiMessage(restored),original);
    assert.equal(response.termination.kind,"TOOL_CALLS");
    assert.equal(response.usage?.outputTokens,5);
});

test("message dispatch: preserves roles, order, images and timestamps",()=>{
    const messages:Message[]=[
        {role:"system",content:[{type:"text",text:"instructions"}],timestamp:1},
        {role:"user",content:[{type:"text",text:"read this"},{type:"image",data:"aGVsbG8=",mimeType:"image/png"}],timestamp:2},
        fromPiAssistantMessage(assistantFixture()).message,
        {role:"tool",content:[toolResult],timestamp:3},
    ];
    const result=toPiMessages(messages);
    assert.deepEqual(result.map(message=>message.role),["system","user","assistant","toolResult"]);
    assert.deepEqual(result[0],messages[0]);
    assert.deepEqual(result[1],messages[1]);
    assert.equal(result[3].timestamp,3);
});

test("tool result: preserves image, extra text, call identity and error flag",()=>{
    const image={type:"image" as const,data:"aGVsbG8=",mimeType:"image/png"};
    const note={type:"text" as const,text:"additional detail"};
    assert.deepEqual(toPiMessage({
        role:"tool",content:[{...toolResult,isError:true},image,note],timestamp:4,
    }),{
        role:"toolResult",toolCallId:"call-1",toolName:"read",isError:true,timestamp:4,
        content:[{type:"text",text:"file contents"},image,note],
    });
});

test("tool result: rejects missing, duplicate and unsupported blocks",()=>{
    assert.throws(()=>toPiMessage({role:"tool",content:[],timestamp:1}),/no tool_result/);
    assert.throws(()=>toPiMessage({role:"tool",content:[toolResult,toolResult],timestamp:1}),/multiple tool_result/);
    assert.throws(()=>toPiMessage({role:"tool",content:[toolResult,{type:"reasoning",content:"plan"}],timestamp:1}),/Unsupported block/);
    assert.throws(()=>toPiMessage({role:"system",content:[toolResult],timestamp:1}),/Invalid content block/);
    assert.throws(()=>toPiMessage({role:"user",content:[toolResult],timestamp:1}),/Invalid content block/);
});

test("assistant: rejects missing, shortened and mismatched replay",()=>{
    const message=fromPiAssistantMessage(assistantFixture()).message;
    const replay=message.providerReplay!;
    assert.throws(()=>toPiMessage({...message,providerReplay:undefined}),/non-Pi provider replay/);
    assert.throws(()=>toPiMessage({...message,providerReplay:{...replay,blocks:[]}}),/incorrect length/);
    assert.throws(()=>toPiMessage({
        ...message,
        providerReplay:{...replay,blocks:[{type:"text"},...replay.blocks!.slice(1)]},
    }),/index 0: expected "thinking", got "text"/);
});

test("assistant: rejects non-object tool arguments",()=>{
    const message=fromPiAssistantMessage(assistantFixture()).message;
    for(const argumentsJson of ["null","[]","42",'"text"']){
        const content=message.content.map(block=>block.type==="tool_call"?{...block,argumentsJson}:block);
        assert.throws(()=>toPiMessage({...message,content}),/Invalid tool call arguments/);
    }
});
