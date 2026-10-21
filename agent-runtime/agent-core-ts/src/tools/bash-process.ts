import {spawn} from "node:child_process";
import {performance} from "node:perf_hooks";
export interface BashProcessOptions{
    readonly command:string;
    readonly cwd:string;
    readonly env:NodeJS.ProcessEnv;
    readonly signal:AbortSignal;
    readonly timeoutMSs:number;

    readonly drainTimeoutMs?:number;

    readonly onData:(text:string)=>void;
}
export interface BashProvessResult{
    readonly exitCode:number|null;
    readonly signalCode:NodeJS.Signals|null;
    readonly timeout:boolean;
    readonly outputIncomplete:boolean;
}
export function runBash(options:BashProcessOptions):Promise<BashProvessResult>{
    const startedAt=performance.now();
    return new Promise<BashProvessResult>((resolve,reject)=>{
    options.signal.throwIfAborted();
    if(!Number.isFinite(options.timeoutMSs)||
    options.timeoutMSs<=0||
    options.timeoutMSs>2_147_483_647){
        throw new Error(`Invalid timeout value: ${options.timeoutMSs}`);
    }
    const drainTimeoutMs=options.drainTimeoutMs??5000;
    if(!Number.isFinite(drainTimeoutMs)||
    drainTimeoutMs<=0||
    drainTimeoutMs>2_147_483_647){
        throw new Error(`Invalid drain timeout value: ${drainTimeoutMs}`);
    }
    const deadline=startedAt+options.timeoutMSs;
    const child=spawn("/bin/bash",["-c",options.command],{
        cwd:options.cwd,
        env:options.env,
        detached:true,
        stdio:["ignore","pipe","pipe"]
    })
    child.stdout.setEncoding("utf-8");
    child.stderr.setEncoding("utf-8");
    let timeOut=false;
    let exited=false;
    let settled=false;
    let outputIncomplete=false;
    let exitCode:number|null=null;
    let signalCode:NodeJS.Signals|null=null;
    let timer:NodeJS.Timeout|undefined;
    let drainTimer:NodeJS.Timeout|undefined;

    function stopProcess():void{
        if(child.pid===undefined){
            return;
        }
        try{
            process.kill(-child.pid,"SIGKILL");
        }catch{
            try{
                child.kill("SIGKILL");
            }catch{}
        }
    }

    function onAbort():void{
        if(!settled){
            stopProcess();
        }
    }

    function cleanup():void{
        clearTimeout(timer);
        clearTimeout(drainTimer);
        options.signal.removeEventListener("abort",onAbort);
        child.stdout.removeListener("data",onData);
        child.stderr.removeListener("data",onData);
        child.removeListener("exit",onExit);
        child.removeListener("close",onClose);
        child.removeListener("error",onError);
    }

    function finish(error?:Error):void{
        if(settled){
            return;
        }
        settled=true;
        cleanup();
        if(options.signal.aborted){
            reject(options.signal.reason);
            return;
        }
        if(error!==undefined){
            reject(error);
            return;
        }
        resolve({
            exitCode:exitCode,
            signalCode:signalCode,
            timeout:timeOut,
            outputIncomplete:outputIncomplete,
        });
    }

    function onData(data:string):void{
        if(!settled){
            options.onData(data);
        }
    }

    function onTimeout():void{
        if(settled||exited||child.exitCode!==null||child.signalCode!==null){
            return;
        }
        const remaining=deadline-performance.now();
        if(remaining>0){
            timer=setTimeout(onTimeout,Math.ceil(remaining));
            return;
        }
        timeOut=true;
        stopProcess();
    }

    function onExit(code:number|null,signal:NodeJS.Signals|null):void{
        exited=true;
        exitCode=code;
        signalCode=signal;
        clearTimeout(timer);
        if(timeOut||options.signal.aborted){
            stopProcess();
        }
        drainTimer=setTimeout(()=>{
            if(settled){
                return;
            }
            outputIncomplete=true;
            stopProcess();
            child.stdout.destroy();
            child.stderr.destroy();
        },Math.ceil(drainTimeoutMs));
    }

    function onClose(code:number|null,signal:NodeJS.Signals|null):void{
        exitCode=code;
        signalCode=signal;
        finish();
    }

    function onError(error:Error):void{
        child.stdout.destroy();
        child.stderr.destroy();
        finish(error);
    }

    child.stdout.on("data",onData);
    child.stderr.on("data",onData);
    child.once("exit",onExit);
    child.once("close",onClose);
    child.once("error",onError);
    options.signal.addEventListener("abort",onAbort,{once:true});

    if(options.signal.aborted){
        onAbort();
    }else{
        onTimeout();
    }
    });
}
