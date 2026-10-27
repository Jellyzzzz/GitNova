import type{Message} from "../model/types.js";
import type{InputMode} from "./types.js";

export class InputQueue{
    private readonly steering:Message[]=[];
    private readonly followUps:Message[]=[];

    enqueue(message:Message,mode:InputMode):void{
        if(message.role!=="user"){
            throw new Error("Input queue only accepts user messages");
        }
        switch(mode){
            case "steering":
                this.steering.push(message);
                break;
            case "follow_up":
                this.followUps.push(message);
                break;
            default:
                throw new Error(`Unknown input mode: ${mode}`);
        }
    }

    takeSteering():Message[]{
        return this.steering.splice(0);
    }

    takeFollowUps():Message[]{
        return this.followUps.splice(0);
    }

    hasPending():boolean{
        return this.steering.length>0||this.followUps.length>0;
    }

    clear():void{
        this.steering.length=0;
        this.followUps.length=0;
    }
}