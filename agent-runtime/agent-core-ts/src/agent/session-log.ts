import {appendFile,readFile,mkdir,readdir,stat} from "node:fs/promises";
import type{Message,Usage} from "../model/types.js";
import {dirname,join} from "node:path";
import type{UsageTotals} from "./usage.js";
import {createUsageTotals,addUsageTotals} from "./usage.js";
import {createCheckpoint,type CompactionCheckpoint,type CompactionRecord} from "./compaction.js";
type MessageEntry={
    readonly type:"message";
    readonly message:Message;
}
type UsageEntry={
    readonly type:"usage";
    readonly modelCallId:string;
    readonly usage:Usage|null;
    readonly purpose?:"compaction";
}
type SessionLogEntry=MessageEntry|UsageEntry|CompactionRecord;
export type LoadedSession={
    readonly messages:Message[];
    readonly usageTotals:UsageTotals;
    readonly checkpoint?:CompactionCheckpoint;
}
export async function appendSessionEntry(filePath:string,entry:SessionLogEntry):Promise<void>{
    await mkdir(dirname(filePath),{recursive:true});
    await appendFile(filePath,JSON.stringify(entry)+"\n",{
        encoding:"utf-8",
        mode:0o600,
    });
}
export function appendSessionMessage(filePath:string,message:Message):Promise<void>{
    return appendSessionEntry(filePath,{
        type:"message",
        message,
    });
}
export function appendSessionUsage(
    filePath:string,modelCallId:string,usage:Usage|null,purpose?:UsageEntry["purpose"],
):Promise<void>{
    return appendSessionEntry(filePath,{
        type:"usage",
        modelCallId,
        usage,
        purpose,
    });
}
function isStoredUsage(value:unknown):value is Usage|null{
    if(value===null) return true;
    if(typeof value!=="object"||Array.isArray(value)) return false;

    const fields=[
        "inputTokens","outputTokens","totalTokens",
        "cachedReadTokens","cachedWriteTokens","reasoningTokens",
    ] as const;
    const record=value as Record<string,unknown>;

    return fields.every(field=>{
        const count=record[field];
        return count===null
            ||(typeof count==="number"&&Number.isSafeInteger(count)&&count>=0);
    });
}
function isStoredCompaction(
    record:Partial<CompactionRecord>,messages:readonly Message[],previous?:CompactionCheckpoint,
):record is CompactionRecord{
    const {firstKeptMessageIndex,messageCount,summary,turnPrefixSummary,readFiles,modifiedFiles,timestamp}=record;
    if(typeof firstKeptMessageIndex!=="number"||!Number.isSafeInteger(firstKeptMessageIndex)
        ||firstKeptMessageIndex<=(previous?.record.firstKeptMessageIndex??0)
        ||firstKeptMessageIndex>=messages.length||messageCount!==messages.length){
        return false;
    }
    const role=messages[firstKeptMessageIndex].role;
    return (role==="user"||role==="assistant")
        &&typeof summary==="string"
        &&(turnPrefixSummary===undefined||(role==="assistant"&&typeof turnPrefixSummary==="string"))
        &&Boolean(summary.trim()||turnPrefixSummary?.trim())
        &&Array.isArray(readFiles)&&readFiles.every(path=>typeof path==="string")
        &&Array.isArray(modifiedFiles)&&modifiedFiles.every(path=>typeof path==="string")
        &&typeof timestamp==="number"&&Number.isFinite(timestamp);
}
export async function loadSessionLog(filePath:string,mustExist:boolean):Promise<LoadedSession>{
    const messages:Message[]=[];
    let checkpoint:CompactionCheckpoint|undefined;
    let usageTotals=createUsageTotals();
    const countedCalls=new Set<string>();
    let text:string;
    try{
        text=await readFile(filePath,"utf-8");
    }catch(error){
        if(isNotFound(error)){
        if(mustExist) throw new Error("Session file not found: "+filePath);
        return {messages,usageTotals};
    }
    throw error;
    }
    if(text.length>0&&!text.endsWith("\n")){
        throw new Error(`Incomplete session record at end of ${filePath}`);
    }
    const lines=text.split("\n");
    for(let index=0;index<lines.length;index++){
        const line=lines[index];
        if(line.trim()==="") continue;

        try{
            const entry=JSON.parse(line) as Partial<SessionLogEntry>|null;
            if(entry?.type==="usage"){
                if(typeof entry.modelCallId!=="string"||!entry.modelCallId.trim()||!isStoredUsage(entry.usage)
                    ||(entry.purpose!==undefined&&entry.purpose!=="compaction")){
                    throw new Error("Invalid usage entry");
                }
                if(!countedCalls.has(entry.modelCallId)){
                    usageTotals=addUsageTotals(usageTotals,entry.usage);
                    countedCalls.add(entry.modelCallId);
                }
                continue;
            }
            if(entry?.type==="compaction"){
                if(!isStoredCompaction(entry,messages,checkpoint)){
                    throw new Error("Invalid compaction checkpoint");
                }
                checkpoint=createCheckpoint(entry);
                continue;
            }
            const message=entry?.type==="message"?entry.message:undefined;
            if(entry?.type!=="message"||!message||!["user","assistant","tool","system"].includes(message.role)||!Array.isArray(message.content) || !Number.isFinite(message.timestamp)){
                throw new Error("Invalid message entry");
            }
            messages.push(message);
        } catch(cause){
            throw new Error(`Invalid session record at ${filePath}:${index + 1}`, { cause });
        }
    }
    return {messages,usageTotals,checkpoint};
}
function isNotFound(error:unknown):boolean{
    return typeof error==="object"
        &&error!==null
        &&"code" in error
        &&error.code==="ENOENT";
}
export type SessionInfo={
    readonly id:string;
    readonly path:string;
    readonly mtimeMs:number;
}
export async function listSessions(sessionDir:string):Promise<SessionInfo[]>{
    const entries=await readdir(sessionDir,{withFileTypes:true}).catch((error:unknown)=>{
        if(isNotFound(error)){
            return [];
        }
        throw error;
    });
    const sessions:SessionInfo[]=[];
    for(const entry of entries){
        if(!entry.isFile()||!entry.name.endsWith(".jsonl")){
            continue;
        }
        const path=join(sessionDir,entry.name);
        const info=await stat(path).catch((error:unknown)=>{
            if(isNotFound(error)){
                return undefined;
            }
            throw error;
        });
        if(info?.isFile()){
            sessions.push({
                id:entry.name.slice(0,-6),
                path,
                mtimeMs:info.mtimeMs,
            });
        }
    }
    return sessions.sort((a,b)=>b.mtimeMs-a.mtimeMs);
}
