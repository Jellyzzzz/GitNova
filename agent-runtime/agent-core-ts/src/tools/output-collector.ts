import { randomBytes } from "node:crypto";
import { tmpdir } from "node:os";
import { DEFAULT_MAX_LINES,DEFAULT_MAX_BYTES } from "./truncate.js";
import {TruncatedResult,truncateTail}  from "./truncate.js";
import {createWriteStream,WriteStream} from"node:fs";
import { join } from "node:path";
export const TEMP_FILE_PREIFIX="gitnova-output";
export interface OutputSnapShot{
    readonly content:string;
    readonly truncation:TruncatedResult;
    readonly fullOutputPath?:string;
}
export interface OutputOptions{
    readonly maxBytes?:number;
    readonly maxLines?:number;
}
export class OutputCollector{
    private readonly maxBytes:number;
    private readonly maxLines:number;
    private readonly maxRollingBytes:number;

    private tailText="";
    private totalBytes=0;
    private totalLines=0;
    private completedLines=0;
    private hasOpenLine=false;
    private currentLineBytes=0;
    private tailBytes=0;
    private tailStartsAtLineBoundary=true;

    private pendingChunks:string[]=[];
    private tempFileStream:WriteStream|undefined;
    private tempFilePath:string|undefined;
    private finished=false;
    private writeError:Error|undefined;

    constructor(options:OutputOptions={}){
        this.maxBytes=options.maxBytes??DEFAULT_MAX_BYTES;
        this.maxLines=options.maxLines??DEFAULT_MAX_LINES;
        this.maxRollingBytes=Math.max(this.maxBytes*2,1);
    }

    append(text:string):void{
        if(this.finished){
            throw new Error("Cannot append to a finished output accumulator");
        }
        this.updateStats(text);
        if(this.tempFileStream||this.shouldUseTempFile()){
            this.ensureTempFile();
            this.tempFileStream?.write(text);
        }else{
            if(text.length>0){
                this.pendingChunks.push(text);
            }
        }
    }

    finish():void{
        if(this.finished){
            return;
        }
        this.finished=true;
    }

    snapshot():OutputSnapShot{
        const tailTruncation=truncateTail(this.getSnapshotText(),{maxLines:this.maxLines,maxBytes:this.maxBytes});
        const truncated=this.totalLines>this.maxLines||this.totalBytes>this.maxBytes;
        const truncatedBy=truncated?tailTruncation.truncatedBy??(this.totalBytes>this.maxBytes?"bytes":"lines"):null;
        const truncation:TruncatedResult={
            content:tailTruncation.content,
            truncated:truncated,
            truncatedBy:truncatedBy,
            totalLines:this.totalLines,
            totalBytes:this.totalBytes,
            outputLines:tailTruncation.outputLines,
            outputBytes:tailTruncation.outputBytes,
            lastLinePartial:tailTruncation.lastLinePartial,
            firstLineExceedsLimit:tailTruncation.firstLineExceedsLimit,
            maxLines:this.maxLines,
            maxBytes:this.maxBytes
        };
        return {
            content:truncation.content,
            truncation:truncation,
            fullOutputPath:this.tempFilePath,
        };
    }

    private updateStats(text:string):void{
        if(text.length===0){
            return;
        }
        const bytes=Buffer.byteLength(text,"utf-8");
        this.totalBytes+=bytes;
        this.tailText+=text;
        this.tailBytes+=bytes;
        if(this.tailBytes>this.maxRollingBytes){
            this.trimTail();
        }
        
        let newLines=0;
        let lastNewLine=-1;
        for(let i=text.indexOf("\n");i!==-1;i=text.indexOf("\n",i+1)){
            newLines++;
            lastNewLine=i;
        }
        if(newLines===0){
            this.currentLineBytes+=Buffer.byteLength(text,"utf-8");
            this.hasOpenLine=true;
        }else{
            this.completedLines+=newLines;
            const tail=text.slice(lastNewLine+1);
            this.hasOpenLine=tail.length>0;
            this.currentLineBytes=Buffer.byteLength(tail,"utf-8");
        }
        this.totalLines=this.completedLines+(this.hasOpenLine?1:0);
    }

    private trimTail():void{
        const buffer=Buffer.from(this.tailText,"utf-8");
        if(buffer.length<=this.maxRollingBytes){
            this.tailBytes=buffer.length;
            return;
        }
        let start=Math.max(0,buffer.length-this.maxRollingBytes);
        while(start<buffer.length&&(buffer[start]&0xc0)===0x80){
            start++;
        }
        this.tailStartsAtLineBoundary=start===0?this.tailStartsAtLineBoundary:buffer[start-1]===0x0a;
        this.tailText=buffer.subarray(start).toString("utf-8");
        this.tailBytes=Buffer.byteLength(this.tailText,"utf-8");
    }

    private getSnapshotText():string{
        if(this.tailStartsAtLineBoundary){
            return this.tailText;
        }
        const firstNewLine=this.tailText.indexOf("\n");
        if(firstNewLine===-1){
            return this.tailText;
        }else {
            return this.tailText.slice(firstNewLine+1);
        }
    }

    async closeTempFile():Promise<void>{
        if(this.writeError!==undefined){
            throw this.writeError;
        }
        const stream=this.tempFileStream;
        this.tempFileStream=undefined;
        if(stream===undefined){
            return;
        }
        await new Promise<void>((resolve,reject)=>{
            const onError=(error:Error)=>{
                stream.off("finish",onFinish);
                reject(error);
            };
            const onFinish=()=>{
                stream.off("error",onError);
                resolve();
            };
            stream.once("error",onError);
            stream.once("finish",onFinish);
            stream.end();
        })
    }

    private shouldUseTempFile():boolean{
        return this.totalBytes>this.maxBytes||this.totalLines>this.maxLines;
    }
    private ensureTempFile():void{
        if(this.tempFileStream){
            return;
        }
        const id=randomBytes(8).toString("hex");
        this.tempFilePath=join(tmpdir(),`${TEMP_FILE_PREIFIX}-${id}.log`);
        this.tempFileStream=createWriteStream(this.tempFilePath);
        this.tempFileStream.on("error",(error:Error)=>{
            this.writeError=error;
        })
        for(const chunk of this.pendingChunks){
            this.tempFileStream.write(chunk);
        }
        this.pendingChunks=[];
    }
}