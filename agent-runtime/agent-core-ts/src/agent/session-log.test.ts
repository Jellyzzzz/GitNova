import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtemp,rm,writeFile} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";
import type {Message,Request} from "../model/types.js";
import {buildModelContext,type CompactionRecord} from "./compaction.js";
import {estimateMessageTokens,measureContext} from "./context-budget.js";
import {appendSessionEntry,appendSessionMessage,loadSessionLog,restoreContextUsageAnchor} from "./session-log.js";

const request:Request={
    modelCallId:"next",model:{providerId:"deepseek",modelId:"model"},
    messages:[],tools:[],systemPrompt:"system",thinkingJson:"{}",maxOutputTokens:100,
};
const user=(text:string):Message=>({role:"user",timestamp:1,content:[{type:"text",text}]});
function assistant(totalTokens:number,stopReason="stop",model="model"):Message{
    return {role:"assistant",timestamp:2,content:[{type:"text",text:"reply"}],providerReplay:{
        adapter:"pi",version:1,response:{provider:"deepseek",model,stopReason,
            usage:{totalTokens,input:0,output:0,cacheRead:0,cacheWrite:0}},
    }};
}
const tool:Message={role:"tool",timestamp:3,content:[
    {type:"tool_result",name:"bash",callId:"1",content:"",isError:false},
    {type:"text",text:"tool output"},
]};

async function withLog(run:(path:string)=>Promise<void>):Promise<void>{
    const dir=await mkdtemp(join(tmpdir(),"owl-session-test-"));
    try { await run(join(dir,"session.jsonl")); }
    finally { await rm(dir,{recursive:true,force:true}); }
}

test("resume restores the latest valid assistant usage and estimates its entire tail",async()=>withLog(async path=>{
    for(const message of [user("question"),assistant(1000),tool,user("next")]){
        await appendSessionMessage(path,message);
    }
    const loaded=await loadSessionLog(path,true);
    const anchor=restoreContextUsageAnchor(loaded,request);
    assert.ok(anchor);
    assert.equal(anchor.prefix.length,2);
    const measured=measureContext({...request,messages:loaded.messages},10000,100,anchor);
    assert.equal(measured.estimatedInputTokens,1000+estimateMessageTokens(tool)+estimateMessageTokens(user("next")));
    assert.equal(restoreContextUsageAnchor(loaded,{...request,model:{providerId:"deepseek",modelId:"other"}}),undefined);
    const changedTools={...request,messages:loaded.messages,tools:[{name:"new",description:"changed",parametersJson:"{}"}]};
    assert.notEqual(measureContext(changedTools,10000,100,anchor).estimatedInputTokens,measured.estimatedInputTokens);
}));

test("compaction invalidates even a retained assistant until a later valid reply",async()=>withLog(async path=>{
    for(const message of [user("question"),assistant(1000),tool]) await appendSessionMessage(path,message);
    const checkpoint:CompactionRecord={type:"compaction",firstKeptMessageIndex:1,messageCount:3,
        summary:"earlier context",readFiles:[],modifiedFiles:[],timestamp:4};
    await appendSessionEntry(path,checkpoint);
    let loaded=await loadSessionLog(path,true);
    assert.equal(loaded.messages[1].role,"assistant");
    assert.equal(restoreContextUsageAnchor(loaded,request),undefined);

    await appendSessionMessage(path,user("after compaction"));
    await appendSessionMessage(path,assistant(200));
    await appendSessionMessage(path,assistant(0));
    await appendSessionMessage(path,assistant(500,"error"));
    loaded=await loadSessionLog(path,true);
    const anchor=restoreContextUsageAnchor(loaded,request);
    assert.ok(anchor);
    assert.equal(anchor.totalTokens,200);
    const visible=buildModelContext(loaded.messages,loaded.checkpoint);
    assert.equal(anchor.prefix.at(-1),loaded.messages[4]);
    assert.equal(visible[0],loaded.checkpoint?.summaryMessage);
    assert.equal(measureContext({...request,messages:visible},10000,100,anchor).estimatedInputTokens,
        200+estimateMessageTokens(loaded.messages[5])+estimateMessageTokens(loaded.messages[6]));
}));

test("session log accepts legacy and split tool output but rejects malformed tool blocks",async()=>withLog(async path=>{
    const legacy:Message={role:"tool",timestamp:1,content:[
        {type:"tool_result",name:"bash",callId:"1",content:"old error",isError:true},
    ]};
    await appendSessionMessage(path,legacy);
    await appendSessionMessage(path,tool);
    assert.equal((await loadSessionLog(path,true)).messages.length,2);
    const invalid={type:"message",message:{role:"tool",timestamp:1,content:[{type:"text",text:"missing metadata"}]}};
    await writeFile(path,JSON.stringify(invalid)+"\n");
    await assert.rejects(loadSessionLog(path,true),/Invalid session record/);
}));
