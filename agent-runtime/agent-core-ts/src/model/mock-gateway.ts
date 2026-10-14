import type{Request,Response} from "./types.js";
import type{ModelGateway} from "./gateway.js";
import { setTimeout } from "node:timers/promises";
export class MockGateway implements ModelGateway{
    async complete(request:Request,signal:AbortSignal):Promise<Response>{

            await setTimeout(1000, undefined, { signal });
            return {
            termination:{
                kind:"STOP",
                rawReason:null,
            },
            usage:{
                inputTokens:0,
                outputTokens:0,
                totalTokens:0,
                cachedReadTokens:0,
                cachedWriteTokens:0,
                reasoningTokens:0,
            },
            message:{
                role:"assistant",
                timestamp:Date.now(),
                content:[{
                    type:"text",
                    text:"Hello from mock model"
                }]
            },
            providerRequestId:null,
        }
    }
}