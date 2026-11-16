import {test} from "node:test";
import assert from "node:assert/strict";
import type {Message,ToolResultBlock} from "./types.js";
import {readToolResult} from "./tool-result.js";
import {toPiToolResultMessage} from "./pi-messages.js";
import {estimateMessageTokens} from "../agent/context-budget.js";

const result:ToolResultBlock={type:"tool_result",callId:"1",name:"bash",content:"legacy",isError:true};
const message:Message={role:"tool",timestamp:1,content:[
    {type:"text",text:"before"},result,
    {type:"image",data:"YQ==",mimeType:"image/png"},
    {type:"text",text:"after"},
]};

test("tool result reader preserves all output blocks and their order",()=>{
    const read=readToolResult(message);
    assert.equal(read.result,result);
    assert.deepEqual(read.content,[
        {type:"text",text:"before"},{type:"text",text:"legacy"},
        {type:"image",data:"YQ==",mimeType:"image/png"},{type:"text",text:"after"},
    ]);
    assert.deepEqual(toPiToolResultMessage(message).content,read.content);
    assert.equal(estimateMessageTokens(message),1200+Math.ceil(Buffer.byteLength("toolbash1beforelegacyafter")/4));
});

test("tool result reader allows empty output and rejects malformed messages",()=>{
    assert.deepEqual(readToolResult({...message,content:[{...result,content:""}]}).content,[{type:"text",text:""}]);
    assert.throws(()=>readToolResult({...message,content:[result,result]}),/multiple tool_result/);
    assert.throws(()=>readToolResult({...message,content:[{type:"text",text:"no metadata"}]}),/no tool_result/);
    assert.throws(()=>readToolResult({...message,content:[{...result,isError:"no"} as unknown as ToolResultBlock]}),/Invalid tool_result/);
    assert.throws(()=>readToolResult({...message,content:[result,{type:"text",text:4} as never]}),/Invalid tool text/);
});
