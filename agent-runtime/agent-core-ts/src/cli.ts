import {createHash,randomUUID} from "node:crypto";
import {stat} from "node:fs/promises";
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
import {
    appendSessionMessage,appendSessionUsage,listSessions,
    loadSessionLog,type LoadedSession,
} from "./agent/session-log.js";
import {createUsageTotals} from "./agent/usage.js";
import type{AgentEvent,LoopOptions} from "./agent/types.js";

const SESSION_ID_PATTERN=/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
const LOCAL_COMMANDS={
    "/resume":"恢复会话",
    "/help":"查看命令",
    "/exit":"退出 Owl",
};

type SessionDirs={
    readonly current:string;
    readonly legacyProject:string;
    readonly legacyRoot:string;
};

function showCommands():void{
    for(const [name,description] of Object.entries(LOCAL_COMMANDS)){
        console.log(`${name.padEnd(9)}${description}`);
    }
}

function showHome(cwd:string):void{
    console.log(`\nOwl  ·  ${cwd}\n`);
    console.log("输入消息开始新会话。\n");
    showCommands();
    console.log();
}

function renderHistory(messages:readonly Message[]):void{
    for(const message of messages){
        if(message.role!=="user"&&message.role!=="assistant") continue;

        const parts:string[]=[];
        for(const block of message.content){
            if(block?.type==="text"&&typeof block.text==="string") parts.push(block.text);
        }
        const text=parts.join("\n");
        if(!text.trim()) continue;

        const display=message.role==="user"
            ?text.split("\n").map(line=>"> "+line).join("\n")
            :text;
        console.log(display+"\n");
    }
}

async function pickSession(dirs:SessionDirs,lines:AsyncIterator<string>):Promise<string|undefined>{
    const [current,legacyProject,legacyFlat]=await Promise.all([
        listSessions(dirs.current),
        listSessions(dirs.legacyProject),
        listSessions(dirs.legacyRoot),
    ]);
    const projectSessions=[...current,...legacyProject].sort((a,b)=>b.mtimeMs-a.mtimeMs);
    const sessions=[...projectSessions,...legacyFlat];
    if(sessions.length===0){
        console.log("当前工作目录还没有已保存的会话。");
        return;
    }

    for(const [index,session] of sessions.entries()){
        // if(index===projectSessions.length) console.log("旧会话 · 未记录工作目录");
        const time=new Date(session.mtimeMs).toLocaleString();
        console.log(`${index+1}. ${session.id}  ${time}`);
    }

    while(true){
        const line=await lines.next();
        if(line.done||line.value.trim()==="") return;

        const number=Number(line.value);
        const selected=sessions[number-1];
        if(Number.isInteger(number)&&selected!==undefined) return selected.path;
        console.log("编号无效，请重新输入。");
    }
}

async function findSessionById(id:string,dirs:SessionDirs):Promise<string>{
    const name=`${id.toLowerCase()}.jsonl`;
    for(const dir of [dirs.current,dirs.legacyProject,dirs.legacyRoot]){
        const path=join(dir,name);
        const info=await stat(path).catch((error:unknown)=>{
            if(typeof error==="object"&&error!==null&&"code" in error&&error.code==="ENOENT"){
                return undefined;
            }
            throw error;
        });
        if(info?.isFile()) return path;
    }
    throw new Error(`Session file not found: ${id}`);
}

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

    const {values,positionals:args}=parseArgs({
        options:{
            session:{type:"string"},
            resume:{type:"boolean",default:false},
            "parallel-tools":{type:"boolean",default:false},
        },
        allowPositionals:true,
    });
    if(values.session===""){
        throw new Error("--session requires a file path or session ID");
    }
    if(values.resume&&values.session!==undefined){
        throw new Error("Choose either --resume or --session");
    }
    const parallelTools=values["parallel-tools"];

    // 将已装配的模型入口交给 Gateway
    const gateway=new PiGateway(models);
    const registry=new ToolRegistry();
    registry.register(parallelTools?{
        ...BashTool,
        mode:"parallel",
        definition:{
            ...BashTool.definition,
            description:BashTool.definition.description+
                " Calls in the same response may run concurrently. Group independent read-only commands in one response. Run writes and dependent commands in separate responses.",
        },
    }:BashTool);
    const loop=new AgentLoop(gateway,registry);

    const cwd=process.cwd();
    const cwdKey=createHash("sha256").update(cwd).digest("hex");
    const legacyRoot=join(homedir(),".gitnova","sessions");
    const sessionDirs:SessionDirs={
        current:join(homedir(),".owl","sessions",cwdKey),
        legacyProject:join(legacyRoot,cwdKey),
        legacyRoot,
    };
    let activeSession:AgentSession|undefined;
    let unsubscribe:(()=>void)|undefined;
    let persistenceError:Error|undefined;

    function activateSession(
    filePath:string,
    loaded:LoadedSession={messages:[],usageTotals:createUsageTotals()},
):AgentSession{
    const {messages,usageTotals}=loaded;

    async function persist(operation:Promise<void>):Promise<void>{
        try{
            await operation;
        }catch(error:unknown){
            persistenceError=error instanceof Error?error:new Error(String(error));
            throw persistenceError;
        }
    }

    const session=new AgentSession(
        loop,
        messages,
        message=>persist(appendSessionMessage(filePath,message)),
        {
            initialTotals:usageTotals,
            persist:(modelCallId,usage)=>
                persist(appendSessionUsage(filePath,modelCallId,usage)),
        },
    );

    unsubscribe?.();
    persistenceError=undefined;
    activeSession=session;
    unsubscribe=session.subscribe(event=>onAgentEvent(event,session));
    console.error("session:",filePath);

    if(args.length===0){
        renderHistory(messages);
        if(session.sessionUsage.responses>0) renderUsage(session);
    }
    return session;
}

    async function resumeSession(lines:AsyncIterator<string>):Promise<boolean>{
        const filePath=await pickSession(sessionDirs,lines);
        if(filePath===undefined) return false;
        activateSession(filePath,await loadSessionLog(filePath,true));
        return true;
    }

    const options:LoopOptions={
        cwd,
        model:{providerId:"deepseek",modelId},
        thinkingJson:thinkingMode==="enabled"
            ?JSON.stringify({reasoning:"high"})
            :"{}",
        maxOutputTokens:1024,
        maxModelCalls:50,
        maxConcurrentTools:5,
        ToolExecutionMode:parallelTools?"parallel":"sequential",
    };

    function onAgentEvent(event:AgentEvent,session:AgentSession):void{
        if(event.type==="model_event"){
            const modelEvent=event.event;

            if(modelEvent.type==="done"){
                process.stdout.write("\n");
                const usage=session.sessionUsage;
                const missing=usage.missingTotals>0?`（${usage.missingTotals} 次未报告总量）`:"";
                console.log(`会话已使用 ${formatTokens(usage.reportedTokens)} tokens${missing}`);
            }else{
                process.stdout.write(modelEvent.delta);
            }
        }else if(event.type==="tool_start"){
            console.log("\n工具调用：",event.call.name,event.call.argumentsJson);
        }else if(event.type==="tool_end"){
            console.dir(event.result,{depth:null});
        }
    }
    function renderUsage(session:AgentSession):void{
    const usage=session.sessionUsage;
    const missing=usage.missingTotals>0
        ?`（${usage.missingTotals} 次未报告总量）`
        :"";
    console.log(`会话已使用 ${formatTokens(usage.reportedTokens)} tokens${missing}`);
}

    const runPrompt=async(text:string)=>{
        const session=activeSession??activateSession(
        join(sessionDirs.current,`${randomUUID()}.jsonl`),
        );
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
    const input=(args.length===0||values.resume)?createInterface({
        input:process.stdin,
        output:process.stdout,
        terminal,
        prompt:"owl> ",
    }):undefined;
    const lines=input?.[Symbol.asyncIterator]();
    let inputClosed=false;
    input?.once("close",()=>{
        inputClosed=true;
    });

    const onInterrupt=()=>{
        if(activeSession?.signal!==undefined){
            activeSession.abort();
        }else{
            process.exitCode=130;
            input?.close();
        }
    };
    process.on("SIGINT",onInterrupt);
    input?.on("SIGINT",onInterrupt);

    try{
        if(values.resume&&lines!==undefined){
            if(!await resumeSession(lines)) return;
        }else if(values.session!==undefined){
            const target=values.session;
            const isId=SESSION_ID_PATTERN.test(target);
            const filePath=isId?await findSessionById(target,sessionDirs):resolve(cwd,target);
            activateSession(filePath,await loadSessionLog(filePath,isId));
        }

        if(args.length>0){
            input?.close();
            const result=await runPrompt(args.join(" ")||"请只回答：通信成功");
            if(result.stopReason==="cancelled"){
                process.exitCode=130;
            }else if(result.stopReason!=="stop"){
                process.exitCode=1;
            }
            return;
        }

        if(terminal&&activeSession===undefined) showHome(cwd);
        while(lines!==undefined){
            if(terminal&&!inputClosed) input?.prompt();

            const line=await lines.next();
            if(line.done) break;
            const text=line.value.trim();
            if(text.length===0) continue;

            const [token]=text.split(/\s+/,1);
            const command=token==="/quit"?"/exit":token;
            if(Object.hasOwn(LOCAL_COMMANDS,command)&&text!==token){
                console.error(`${token} 不接受参数。`);
                continue;
            }
            if(command==="/exit"){
                break;
            }
            if(command==="/help"){
                showCommands();
                continue;
            }
            if(command==="/resume"){
                try{
                    await resumeSession(lines);
                }catch(error:unknown){
                    console.error(error instanceof Error?error.message:String(error));
                }
                continue;
            }
            if(/^\/[a-z][a-z0-9-]*$/i.test(text)){
                console.error(`未知命令 ${text}。输入 /help 查看命令。`);
                continue;
            }
            await runPrompt(text);
        }
    }finally{
        input?.close();
        process.removeListener("SIGINT",onInterrupt);
        unsubscribe?.();
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
function formatTokens(tokens:number):string{
    if(tokens>=1_000_000) return `${(tokens/1_000_000).toFixed(1)}M`;
    if(tokens>=1_000) return `${(tokens/1_000).toFixed(1)}k`;
    return String(tokens);
}