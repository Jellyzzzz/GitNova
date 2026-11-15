import {test} from "node:test";
import assert from "node:assert/strict";
import {getEventListeners} from "node:events";
import {performance} from "node:perf_hooks";
import {runBash} from "./bash-process.js";
import type{BashProcessOptions} from "./bash-process.js";

async function execute(command:string,options:Partial<BashProcessOptions>={}){
    let output="";
    const result=await runBash({
        cwd:process.cwd(),
        env:process.env,
        signal:new AbortController().signal,
        timeoutMSs:2000,
        drainTimeoutMs:1000,
        ...options,
        command:command,
        onData:(text)=>{
            output+=text;
            options.onData?.(text);
        },
    });
    return {result,output};
}

test("bash: captures both output streams and the actual nonzero exit code",async()=>{
    const controller=new AbortController();
    const {result,output}=await execute("printf 'stdout\\n'; printf 'stderr\\n' >&2; exit 7",{
        signal:controller.signal,
    });
    assert.deepEqual(result,{
        exitCode:7,
        signalCode:null,
        timeout:false,
        outputIncomplete:false,
    });
    assert.ok(output.includes("stdout\n"));
    assert.ok(output.includes("stderr\n"));
    assert.equal(getEventListeners(controller.signal,"abort").length,0);
});

test("bash: output arriving after shell exit does not become a command timeout",async()=>{
    const {result,output}=await execute("(sleep 0.8; printf 'late output\\n') & printf 'main done\\n'; exit 7",{
        timeoutMSs:400,
        drainTimeoutMs:2000,
    });
    assert.deepEqual(result,{
        exitCode:7,
        signalCode:null,
        timeout:false,
        outputIncomplete:false,
    });
    assert.equal(output,"main done\nlate output\n");
});

test("bash: inherited idle pipes have a separate bounded output drain",async()=>{
    const {result,output}=await execute("sleep 2 & printf 'main done\\n'",{
        timeoutMSs:1000,
        drainTimeoutMs:100,
    });
    assert.deepEqual(result,{
        exitCode:0,
        signalCode:null,
        timeout:false,
        outputIncomplete:true,
    });
    assert.equal(output,"main done\n");
});

test("bash: continuous descendant output cannot extend the drain deadline",async()=>{
    const {result,output}=await execute(
        "(for i in {1..40}; do printf 'tick\\n'; sleep 0.05; done) & printf 'main done\\n'",
        {timeoutMSs:1000,drainTimeoutMs:150},
    );
    assert.equal(result.exitCode,0);
    assert.equal(result.timeout,false);
    assert.equal(result.outputIncomplete,true);
    assert.ok(output.includes("main done\n"));
    assert.ok(output.includes("tick\n"));
    assert.ok(output.split("tick\n").length-1<40);
});

test("bash: a running foreground command is killed at its command deadline",async()=>{
    const start=performance.now();
    const {result,output}=await execute("printf 'started\\n'; sleep 5; printf 'finished\\n'",{
        timeoutMSs:100,
    });
    assert.equal(result.exitCode,null);
    assert.equal(result.signalCode,"SIGKILL");
    assert.equal(result.timeout,true);
    assert.equal(result.outputIncomplete,false);
    assert.ok(performance.now()-start>=100);
    assert.equal(output,"started\n");
});

test("bash: pre-cancelled execution rejects without starting the command",async()=>{
    const controller=new AbortController();
    const reason=new Error("User cancelled");
    controller.abort(reason);
    let output="";
    await assert.rejects(execute("printf 'must not run\\n'",{
        signal:controller.signal,
        onData:text=>{output+=text;},
    }),error=>error===reason);
    assert.equal(output,"");
    assert.equal(getEventListeners(controller.signal,"abort").length,0);
});

test("bash: running command cancellation preserves the caller reason",async()=>{
    const controller=new AbortController();
    const reason=new Error("User cancelled");
    await assert.rejects(execute("printf 'ready\\n'; sleep 5",{
        signal:controller.signal,
        onData:()=>controller.abort(reason),
    }),error=>error===reason);
    assert.equal(getEventListeners(controller.signal,"abort").length,0);
});

test("bash: cancellation also works after shell exit during output drain",async()=>{
    const controller=new AbortController();
    const reason=new Error("User cancelled during output drain");
    await assert.rejects(execute("(sleep 0.05; printf 'ready\\n'; sleep 5) & exit 0",{
        signal:controller.signal,
        onData:()=>controller.abort(reason),
    }),error=>error===reason);
    assert.equal(getEventListeners(controller.signal,"abort").length,0);
});

test("bash: rejects invalid command and output drain timeouts",async()=>{
    for(const value of [0,-1,NaN,Infinity,2_147_483_648]){
        await assert.rejects(execute("exit 0",{timeoutMSs:value}),/Invalid timeout value/);
        await assert.rejects(execute("exit 0",{drainTimeoutMs:value}),/Invalid drain timeout value/);
    }
});

test("bash: process startup errors reject and remove cancellation listeners",async()=>{
    const controller=new AbortController();
    await assert.rejects(execute("exit 0",{
        cwd:`/nonexistent-gitnova-${crypto.randomUUID()}`,
        signal:controller.signal,
    }),{code:"ENOENT"});
    assert.equal(getEventListeners(controller.signal,"abort").length,0);
});
