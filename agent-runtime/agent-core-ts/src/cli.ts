import {createModels}from "@earendil-works/pi-ai";
import {deepseekProvider}from "@earendil-works/pi-ai/providers/deepseek";
import {PiGateway}from "./model/pi-gateway.js";
import type{Request}from "./model/types.js";
import {ToolRegistry} from "./tools/registry.js";
import {BashTool} from "./tools/bash.js";
import {AgentLoop} from "./agent/loop.js";
import {AgentSession} from "./agent/session.js";
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
    const session=new AgentSession(loop);

    const options:LoopOptions={
        cwd:process.cwd(),
        model:{providerId:"deepseek",modelId},
        thinkingJson:thinkingMode==="enabled"
            ?JSON.stringify({reasoning:"high"})
            :"{}",
        maxOutputTokens:1024,
        maxModelCalls:4,
        maxConcurrentTools:1,
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

    const onInterrupt=()=>{
        session.abort();
    };
    process.once("SIGINT",onInterrupt);

    try{
        const text=process.argv.slice(2).join(" ")||"请只回答：通信成功";
        const result=await session.prompt(text,options);

        console.log("run:",{
            stopReason:result.stopReason,
            modelCalls:result.modelCalls,
        });

        if(result.errMessage!==undefined){
            console.error(result.errMessage);
        }

        if(result.stopReason==="cancelled"){
            process.exitCode=130;
        }else if(result.stopReason!=="stop"){
            process.exitCode=1;
        }
    }finally{
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
