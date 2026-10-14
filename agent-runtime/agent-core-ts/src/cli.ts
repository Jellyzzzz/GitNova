import {createModels}from "@earendil-works/pi-ai";
import {deepseekProvider}from "@earendil-works/pi-ai/providers/deepseek";
import {PiGateway}from "./model/pi-gateway.js";
import type{Request}from "./model/types.js";

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
    const controller=new AbortController();

    const onInterrupt=()=>{
        controller.abort();
    };
    process.once("SIGINT",onInterrupt);

    const request:Request={
        modelCallId:crypto.randomUUID(),
        model:{
            providerId:"deepseek",
            modelId:modelId,
        },
        messages:[{
            role:"user",
            content:[{
                type:"text",
                text:process.argv.slice(2).join(" ")||"请只回答：通信成功",
            }],
            timestamp:Date.now(),
        }],
        tools:[{
    name:"get_weather",
    description:"Get weather for a city",
    parametersJson:JSON.stringify({
        type:"object",
        properties:{
            city:{type:"string"}
        },
        required:["city"]
    }),
}],
        maxOutputTokens:1024,
        thinkingJson:thinkingMode==="enabled"
            ?JSON.stringify({reasoning:"high"})
            :"{}",
    };

    try{
        for await(const event of gateway.stream(
            request,
            controller.signal
        )){
            switch(event.type){
                case "text_delta":
                    process.stdout.write(event.delta);
                    break;

                case "reasoning_delta":
                    process.stdout.write(event.delta);
                    break;

                case "done":
                    process.stdout.write("\n");
                    console.log("termination:",event.response.termination);
                    console.log("usage:",event.response.usage);

                    if(event.response.termination.kind==="ERROR"){
                        process.exitCode=1;
                    }
                    break;
            }
        }
    }finally{
        process.removeListener("SIGINT",onInterrupt);
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
