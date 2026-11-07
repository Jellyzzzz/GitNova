import {appendFile,readFile,mkdir} from "node:fs/promises";
import type{Message} from "../model/types.js";
import {dirname} from "node:path";
type MessageEntry={
    readonly type:"message";
    readonly message:Message;
}
export async function appendSessionMessage(filePath:string,message:Message):Promise<void>{
    const entry:MessageEntry={type:"message",message:message};
    await mkdir(dirname(filePath),{recursive:true});
    await appendFile(filePath,JSON.stringify(entry)+"\n",{
        encoding:"utf-8",
        mode:0o600,
    });
}
export async function loadSessionMessages(filePath:string):Promise<Message[]>{
    let text:string;
    try{
        text=await readFile(filePath,"utf-8");
    }catch(error){
        if (typeof error === "object" && error !== null && "code" in error && error.code === "ENOENT") {
            return [];
        }
        throw error;
    }
    if(text.length>0&&!text.endsWith("\n")){
        throw new Error(`Incomplete session record at end of ${filePath}`);
    }
    const messages:Message[]=[];
    const lines=text.split("\n");
    for(let index=0;index<lines.length;index++){
        const line=lines[index];
        if(line.trim()==="") continue;

        try{
            const entry=JSON.parse(line) as Partial<MessageEntry>|null;
            const message=entry?.message;
            if(entry?.type!=="message"||!message||!["user","assistant","tool","system"].includes(message.role)||!Array.isArray(message.content) || !Number.isFinite(message.timestamp)){
                throw new Error("Invalid message entry");
            }
            messages.push(message);
        } catch(cause){
            throw new Error(`Invalid session record at ${filePath}:${index + 1}`, { cause });
        }
    }
    return messages;
}