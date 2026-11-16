import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtemp,rm} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {setTimeout as delay} from "node:timers/promises";
import type {ModelGateway} from "../model/gateway.js";
import type {Message,Request,Response} from "../model/types.js";
import {ToolRegistry} from "../tools/registry.js";
import {AgentLoop} from "./loop.js";
import {AgentSession} from "./session.js";
import {appendSessionEntry,appendSessionMessage,appendSessionUsage,loadSessionLog} from "./session-log.js";
import type {AgentEvent,LoopOptions} from "./types.js";

const options:LoopOptions={
    cwd:"/",model:{providerId:"test",modelId:"test"},thinkingJson:"{}",
    maxOutputTokens:1000,reserveTokens:1000,contextWindow:40000,keepRecentTokens:120,
    maxModelCalls:5,maxConcurrentTools:1,ToolExecutionMode:"sequential",
};
const message=(role:"user"|"assistant",text:string):Message=>({role,timestamp:1,content:[{type:"text",text}]});
const history=():Message[]=>[
    message("user","Original request "+"a".repeat(6000)),
    message("assistant","Verified progress "+"b".repeat(6000)),
    message("user","Current task "+"c".repeat(4000)),
    message("assistant","Recent answer"),
];
const usage={inputTokens:20,outputTokens:4,totalTokens:24,cachedReadTokens:0,cachedWriteTokens:0,reasoningTokens:0};
function summaryResponse(text="Concise verified facts and next steps."):Response{
    return {termination:{kind:"STOP",rawReason:null},usage,message:message("assistant",text),providerRequestId:null};
}
function gateway(complete:ModelGateway["complete"]):ModelGateway{
    return {complete,async *stream(){throw new Error("Unexpected normal model request");}};
}

test("manual compaction saves a checkpoint, carries user focus, counts usage and restores",async()=>{
    const dir=await mkdtemp(join(tmpdir(),"owl-manual-compact-"));
    try{
        const path=join(dir,"session.jsonl");
        const initial=history();
        for(const item of initial) await appendSessionMessage(path,item);
        const requests:Request[]=[];
        let saved=false;
        const session=new AgentSession(new AgentLoop(gateway(async request=>{
            requests.push(request);
            return summaryResponse();
        }),new ToolRegistry()),initial,item=>appendSessionMessage(path,item),{
            persist:(id,value,purpose)=>appendSessionUsage(path,id,value,purpose),
            persistCompaction:async record=>{await appendSessionEntry(path,record);saved=true;},
        });
        const events:AgentEvent[]=[];
        session.subscribe(event=>{
            if(event.type==="context_compaction") assert.equal(saved,true);
            events.push(event);
        });
        const before=session.estimateContext(options).estimatedInputTokens;
        const result=await session.compact(options,"保留迁移失败原因");
        assert.equal(result.estimatedTokensBefore,before);
        assert.ok(result.contextBudget.estimatedInputTokens<before);
        assert.equal(events.find(event=>event.type==="context_compaction_start")?.reason,"manual");
        assert.ok(requests.length>0);
        for(const request of requests){
            assert.equal(request.tools.length,0);
            const text=request.messages[0].content[0];
            assert.ok(text.type==="text");
            assert.match(text.text,/保留迁移失败原因/);
            assert.match(text.text,/Do not reproduce lengthy reasoning/);
        }
        assert.equal(session.runUsage.reportedTokens,requests.length*24);
        assert.equal(session.sessionUsage.reportedTokens,requests.length*24);
        assert.deepEqual(session.state.messages,initial);
        const restored=await loadSessionLog(path,true);
        assert.deepEqual(restored.checkpoint?.record,result.checkpoint.record);
        assert.equal(restored.usageTotals.reportedTokens,requests.length*24);

        const count=requests.length;
        await assert.rejects(session.compact(options),/没有新增内容/);
        assert.equal(requests.length,count);
        assert.equal(session.compactionCheckpoint,result.checkpoint);
    }finally{
        await rm(dir,{recursive:true,force:true});
    }
});

test("short sessions skip the summary model",async()=>{
    let calls=0;
    const session=new AgentSession(new AgentLoop(gateway(async()=>{calls++;return summaryResponse();}),new ToolRegistry()),[
        message("user","hello"),message("assistant","hi"),
    ]);
    await assert.rejects(session.compact(options),/会话内容不足/);
    assert.equal(calls,0);
    assert.equal(session.compactionCheckpoint,undefined);
});

test("invalid or failed summaries never publish a checkpoint",async()=>{
    const responses:Response[]=[
        {...summaryResponse(),termination:{kind:"ERROR",rawReason:null,failure:{code:"MODEL_ERROR",message:"bad",httpStatus:null}}},
        {...summaryResponse(),termination:{kind:"LENGTH",rawReason:null}},
        summaryResponse("  "),
        {...summaryResponse(),termination:{kind:"TOOL_CALLS",rawReason:null}},
    ];
    for(const response of responses){
        let saved=0;
        const session=new AgentSession(new AgentLoop(gateway(async()=>response),new ToolRegistry()),history(),undefined,{
            persistCompaction:async()=>{saved++;},
        });
        const before=session.modelMessages;
        await assert.rejects(session.compact(options));
        assert.equal(saved,0);
        assert.equal(session.compactionCheckpoint,undefined);
        assert.deepEqual(session.modelMessages,before);
        assert.equal(session.sessionUsage.reportedTokens,24);
    }
});

test("a second manual compaction updates the previous summary",async()=>{
    const requests:Request[]=[];
    const model:ModelGateway={
        async complete(request){requests.push(request);return summaryResponse("First checkpoint facts");},
        async *stream(){yield {type:"done",response:summaryResponse("Finished newer task")};},
    };
    const session=new AgentSession(new AgentLoop(model,new ToolRegistry()),history());
    await session.compact(options);
    const firstCount=requests.length;
    await session.prompt("Additional requirements "+"d".repeat(5000),options);
    await session.compact(options);
    assert.ok(requests.length>firstCount);
    assert.ok(requests.slice(firstCount).some(request=>{
        const block=request.messages[0].content[0];
        return block.type==="text"&&block.text.includes("First checkpoint facts");
    }));
});

test("cancellation and checkpoint write failure keep the old context",async()=>{
    let started!:()=>void;
    const waiting=new Promise<void>(resolve=>{started=resolve;});
    const cancellable=new AgentSession(new AgentLoop(gateway(async(_request,signal)=>{
        started();
        await delay(10000,undefined,{signal});
        return summaryResponse();
    }),new ToolRegistry()),history());
    const original=cancellable.modelMessages;
    const pending=cancellable.compact(options);
    await waiting;
    cancellable.abort();
    await assert.rejects(pending,error=>error instanceof Error&&error.name==="AbortError");
    assert.deepEqual(cancellable.modelMessages,original);
    assert.equal(cancellable.compactionCheckpoint,undefined);

    const unsaved=new AgentSession(new AgentLoop(gateway(async()=>summaryResponse()),new ToolRegistry()),history(),undefined,{
        persistCompaction:async()=>{throw new Error("storage failed");},
    });
    const before=unsaved.modelMessages;
    await assert.rejects(unsaved.compact(options),/storage failed/);
    assert.deepEqual(unsaved.modelMessages,before);
    assert.equal(unsaved.compactionCheckpoint,undefined);
});

test("manual compaction aborts a running tool and waits for its error receipt",async()=>{
    let started!:()=>void;
    const toolStarted=new Promise<void>(resolve=>{started=resolve;});
    const registry=new ToolRegistry();
    registry.register({
        definition:{name:"inspect",description:"inspect",parametersJson:'{"type":"object","properties":{}}'},
        async execute(_args,context){
            started();
            await delay(10000,undefined,{signal:context.signal});
            return {content:[]};
        },
    });
    const model:ModelGateway={
        async complete(){return summaryResponse();},
        async *stream(){yield {type:"done",response:{
            ...summaryResponse(),termination:{kind:"TOOL_CALLS",rawReason:null},
            message:{role:"assistant",timestamp:2,content:[{type:"tool_call",callId:"1",name:"inspect",argumentsJson:"{}"}]},
        }};},
    };
    const session=new AgentSession(new AgentLoop(model,registry),history());
    const events:AgentEvent[]=[];
    session.subscribe(event=>{events.push(event);});
    const pending=session.prompt("inspect this",options);
    await toolStarted;
    await session.compact(options);
    assert.equal((await pending).stopReason,"cancelled");
    const receipt=events.findIndex(event=>event.type==="message_end"&&event.message.role==="tool");
    const compactStart=events.findIndex(event=>event.type==="context_compaction_start");
    assert.ok(receipt>=0&&compactStart>receipt);
    const result=session.state.messages.find(item=>item.role==="tool");
    assert.equal(result?.content[0].type,"tool_result");
    assert.ok(result?.content[0].type==="tool_result"&&result.content[0].isError);
});

test("manual compaction stops if the interrupted tool receipt cannot be saved",async()=>{
    let started!:()=>void;
    const toolStarted=new Promise<void>(resolve=>{started=resolve;});
    const registry=new ToolRegistry();
    registry.register({
        definition:{name:"inspect",description:"inspect",parametersJson:'{"type":"object","properties":{}}'},
        async execute(_args,context){started();await delay(10000,undefined,{signal:context.signal});return {content:[]};},
    });
    let summaryCalls=0;
    const model:ModelGateway={
        async complete(){summaryCalls++;return summaryResponse();},
        async *stream(){yield {type:"done",response:{
            ...summaryResponse(),termination:{kind:"TOOL_CALLS",rawReason:null},
            message:{role:"assistant",timestamp:2,content:[{type:"tool_call",callId:"1",name:"inspect",argumentsJson:"{}"}]},
        }};},
    };
    const session=new AgentSession(new AgentLoop(model,registry),history(),async item=>{
        if(item.role==="tool") throw new Error("tool receipt storage failed");
    });
    const pending=session.prompt("inspect this",options);
    void pending.catch(()=>{});
    await toolStarted;
    await assert.rejects(session.compact(options),/tool receipt storage failed/);
    await assert.rejects(pending,/tool receipt storage failed/);
    assert.equal(summaryCalls,0);
    assert.equal(session.compactionCheckpoint,undefined);
});

test("automatic threshold and overflow compaction still share the same path",async()=>{
    for(const mode of ["threshold","overflow"] as const){
        let streams=0;
        let summaries=0;
        const model:ModelGateway={
            async complete(){summaries++;return summaryResponse();},
            async *stream(){
                streams++;
                if(mode==="overflow"&&streams===1){
                    yield {type:"done",response:{...summaryResponse(),termination:{
                        kind:"ERROR",rawReason:null,
                        failure:{code:"CONTEXT_OVERFLOW",message:"context too long",httpStatus:null},
                    }}};
                }else yield {type:"done",response:summaryResponse("done")};
            },
        };
        const session=new AgentSession(new AgentLoop(model,new ToolRegistry()),history());
        const events:AgentEvent[]=[];
        session.subscribe(event=>{events.push(event);});
        const runOptions=mode==="threshold"?{...options,contextWindow:10000,reserveTokens:8000}:options;
        assert.equal((await session.prompt("Continue",runOptions)).stopReason,"stop");
        assert.ok(summaries>0);
        assert.equal(events.find(event=>event.type==="context_compaction_start")?.reason,mode);
        assert.equal(streams,mode==="overflow"?2:1);
        assert.ok(session.compactionCheckpoint);
    }
});
