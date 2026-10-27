import {runBash} from "./bash-process.js";
import {OutputCollector} from "./output-collector.js";
import type {AgentTool,ToolResult} from "./types.js";
import {OutputSnapShot} from "./output-collector.js";
import {DEFAULT_MAX_LINES,DEFAULT_MAX_BYTES} from "./truncate.js";

const DEFAULT_TIMEOUT_SECONDS=120;
const UPDATE_INTERVAL_MS=100;

interface BashInput{
    readonly command:string;
    readonly timeout?:number;
}

function formatOutput(snapshot:OutputSnapShot):string{
    let text=snapshot.content||"(no output)";
    if(snapshot.truncation.truncated){
        text+=`\n\n[Output truncated. Full captured output: ${snapshot.fullOutputPath}]`;
    }
    return text;
}

export const BashTool:AgentTool={
    definition:{
        name:"bash",
        description:`Execute a command in the current working directory. Returns stdout and stderr. Output is truncated to last ${DEFAULT_MAX_LINES} lines or ${DEFAULT_MAX_BYTES / 1024}KB (whichever is hit first). If truncated, full output is saved to a temp file. Optionally provide a timeout in seconds.`,
        parametersJson:JSON.stringify({
            type:"object",
            properties:{
                command:{
                    type:"string",
                    description:"The Bash command to execute.",
                },
                timeout:{
                    type:"number",
                    exclusiveMinimum:0,
                    maximum:2147483.647,
                    description:"Timeout in secondes",
                },
            },
            required:["command"],
            additionalProperties:false,
        }),
    },

    async execute(args,context,onUpdate):Promise<ToolResult>{
        const input=args as BashInput;
        const timeoutSeconds=input.timeout??DEFAULT_TIMEOUT_SECONDS;
        const outputCollector=new OutputCollector();
        let lastUpdateAt=0;

        const handleData=(text:string):void=>{
            outputCollector.append(text);
            const now=Date.now();
            if(onUpdate!==undefined&&now-lastUpdateAt>=UPDATE_INTERVAL_MS){
                lastUpdateAt=now;
                const snapshot=outputCollector.snapshot();
                onUpdate({content:[{type:"text",text:snapshot.content}]});
            }
        };

        try{
            const result=await runBash({
                command:input.command,
                cwd:context.cwd,
                env:process.env,
                signal:context.signal,
                timeoutMSs:timeoutSeconds*1000,
                onData:handleData,
            });

            const snapshot=outputCollector.snapshot();
            let text=formatOutput(snapshot);

            if(result.outputIncomplete){
                text+="\n\n[Output capture stopped before all inherited output pipes closed.]";
            }
            if(result.timeout){
                throw new Error(`${text}\n\nCommand timed out after ${timeoutSeconds} seconds`);
            }
            if(result.signalCode!==null){
                throw new Error(`${text}\n\nCommand terminated by signal ${result.signalCode}`);
            }
            if(result.exitCode===null){
                throw new Error(`${text}\n\nCommand terminated without an exit code`);
            }
            if(result.exitCode!==0){
                throw new Error(`${text}\n\nCommand exited with code ${result.exitCode}`);
            }

            return {
                content:[{type:"text",text:text}],
                details:{
                    exitCode:result.exitCode,
                    signalCode:result.signalCode,
                    timeout:result.timeout,
                    outputIncomplete:result.outputIncomplete,
                    truncation:{...snapshot.truncation},
                    fullOutputPath:snapshot.fullOutputPath??null
                },
            };
        }finally{
            outputCollector.finish();
            await outputCollector.closeTempFile();
            if(onUpdate!==undefined&&!context.signal.aborted){
                const snapshot=outputCollector.snapshot();
                onUpdate({content:[{type:"text",text:snapshot.content}]});
            }
        }
    }
}