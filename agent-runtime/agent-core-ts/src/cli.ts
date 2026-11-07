import {randomUUID} from "node:crypto";
import {homedir} from "node:os";
import {join,resolve} from "node:path";
import {createInterface} from "node:readline";
import {parseArgs} from "node:util";
import {createModels}from "@earendil-works/pi-ai";
import {deepseekProvider}from "@earendil-works/pi-ai/providers/deepseek";
import {PiGateway}from "./model/pi-gateway.js";
import type{Message}from "./model/types.js";
import {ToolRegistry} from "./tools/registry.js";
import {BashTool} from "./tools/bash.js";
import {AgentLoop} from "./agent/loop.js";
import {AgentSession} from "./agent/session.js";
import {appendSessionMessage,loadSessionMessages} from "./agent/session-log.js";
import type{LoopOptions} from "./agent/types.js";
async function main():Promise<void>{
    const apiKey=process.env.DEEPSEEK_API_KEY;
    const modelId=process.env.DEEPSEEK_MODEL;
    const thinkingMode=process.env.DEEPSEEK_THINKING_MODE;

    if(!apiKey){
        throw new Error("Missing DEEPSEEK_API_KEY");
    }
    if(!modelId){
        throw new Error("Missing DEEPSEEK_MODEL");
    }
    if(thinkingMode!=="enabled"&&thinkingMode!=="disabled"){
        throw new Error("DEEPSEEK_THINKING_MODE must be enabled or disabled");
    }

    // 创建模型入口，并注册本次使用的 provider
    const models=createModels();
    models.setProvider(deepseekProvider());

    if(models.getModel("deepseek",modelId)===undefined){
        const available=models.getModels("deepseek")
            .map(model=>model.id)
            .join(", ");
        throw new Error(
            `Unknown model "${modelId}". Available: ${available}`
        );
    }

    // 将已装配的模型入口交给 Gateway
        const gateway=new PiGateway(models);
    const registry=new ToolRegistry();
    registry.register(BashTool);

    const loop=new AgentLoop(gateway,registry);
    const {values,positionals:args}=parseArgs({
        options:{session:{type:"string"}},
        allowPositionals:true,
    });
    if(values.session===""){
        throw new Error("--session requires a file path");
    }
    const sessionPath=values.session===undefined
        ?join(homedir(),".gitnova","sessions",`${randomUUID()}.jsonl`)
        :resolve(values.session);
    const messages=values.session===undefined?[]:await loadSessionMessages(sessionPath);
    let persistenceError:Error|undefined;
    const persistMessage=async(message:Message)=>{
        try{
            await appendSessionMessage(sessionPath,message);
        }catch(error:unknown){
            persistenceError=error instanceof Error?error:new Error(String(error));
            throw persistenceError;
        }
    };
    const session=new AgentSession(loop,messages,persistMessage);
    console.error("session:",sessionPath);

    const options:LoopOptions={
        cwd:process.cwd(),
        model:{providerId:"deepseek",modelId},
        thinkingJson:thinkingMode==="enabled"
            ?JSON.stringify({reasoning:"high"})
            :"{}",
        maxOutputTokens:1024,
        maxModelCalls:50,
        maxConcurrentTools:5,
        ToolExecutionMode:"sequential",
    };

    const unsubscribe=session.subscribe(event=>{
        if(event.type==="model_event"){
            const modelEvent=event.event;

            if(modelEvent.type==="done"){
                process.stdout.write("\n");
                console.log("termination:",modelEvent.response.termination.kind);
                console.log("usage:",modelEvent.response.usage);
            }else{
                process.stdout.write(modelEvent.delta);
            }
        }else if(event.type==="tool_start"){
            console.log("\n工具调用：",event.call.name,event.call.argumentsJson);
        }else if(event.type==="tool_end"){
            console.dir(event.result,{depth:null});
        }
    });

    const runPrompt=async(text:string)=>{
        const result=await session.prompt(text,options);
        // 取消收尾也可能写入工具回执，写入失败时应退出 CLI。
        if(persistenceError!==undefined){
            throw persistenceError;
        }

        console.log("run:",{
            stopReason:result.stopReason,
            modelCalls:result.modelCalls,
        });

        if(result.errMessage!==undefined){
            console.error(result.errMessage);
        }
        return result;
    };

    const terminal=process.stdin.isTTY===true&&process.stdout.isTTY===true;
    const input=args.length===0?createInterface({
        input:process.stdin,
        output:process.stdout,
        terminal,
        prompt:"gitnova> ",
    }):undefined;
    let inputClosed=false;
    input?.once("close",()=>{
        inputClosed=true;
    });

    const onInterrupt=()=>{
        if(session.signal!==undefined){
            session.abort();
        }else{
            process.exitCode=130;
            input?.close();
        }
    };
    process.on("SIGINT",onInterrupt);
    input?.on("SIGINT",onInterrupt);

    try{
        if(input===undefined){
            const result=await runPrompt(args.join(" ")||"请只回答：通信成功");
            if(result.stopReason==="cancelled"){
                process.exitCode=130;
            }else if(result.stopReason!=="stop"){
                process.exitCode=1;
            }
            return;
        }

        if(terminal){
            input.prompt();
        }
        for await(const line of input){
            const text=line.trim();
            if(text==="/exit"||text==="/quit"){
                break;
            }
            if(text.length>0){
                await runPrompt(text);
            }
            if(terminal&&!inputClosed){
                input.prompt();
            }
        }
    }finally{
        input?.close();
        process.removeListener("SIGINT",onInterrupt);
        unsubscribe();
    }
}

main().catch((error:unknown)=>{
    if(error instanceof Error&&error.name==="AbortError"){
        console.error("\n已取消");
        process.exitCode=130;
        return;
    }

    console.error(error instanceof Error?error.message:String(error));
    process.exitCode=1;
});
