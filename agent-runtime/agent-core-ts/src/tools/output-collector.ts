import { DEFAULT_MAX_LINES,DEFAULT_MAX_BYTES } from "./truncate.js";
export interface OutputSnapShot{
    readonly text:string;
    readonly totalBytes:number;
    readonly shownBytes:number;
    readonly truncated:boolean;
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

    constructor(options:OutputOptions={}){
        this.maxBytes=options.maxBytes??DEFAULT_MAX_BYTES;
        this.maxLines=options.maxLines??DEFAULT_MAX_LINES;
        this.maxRollingBytes=Math.max(this.maxBytes*1.5,1);
    }

    append(text:string):void{
        this.updateStats(text);
        const buffer=Buffer.from(this.tailText+text,"utf-8");
        let start=Math.max(0,buffer.length-this.maxBytes);
        while(start<buffer.length&&(buffer[start]&0xc0)===0x80){
            start++;
        }
        this.tailText=buffer.subarray(start).toString("utf-8");
    }

    snapshot():OutputSnapShot{
        return {
            text:this.tailText,
            totalBytes:this.totalBytes,
            shownBytes:Buffer.byteLength(this.tailText,"utf-8"),
            truncated:this.totalBytes>this.maxBytes
        }
    }



    private updateStats(text:string):void{
        if(text.length===0){
            return;
        }
        this.totalBytes+=Buffer.byteLength(text,"utf-8");
        this.tailText+=text;
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
        }
        let start=Math.max(0,buffer.length-this.maxRollingBytes);
        while(start<buffer.length&&(buffer[start]&0xc0)===0x80){
            start++;
        }
        this.tailStartsAtLineBoundary=start===0?this.tailStartsAtLineBoundary:buffer[start-1]===0x0a;
        this.tailText=buffer.subarray(start).toString("utf-8");
        this.tailBytes=Buffer.byteLength(this.tailText,"utf-8");
    }
}