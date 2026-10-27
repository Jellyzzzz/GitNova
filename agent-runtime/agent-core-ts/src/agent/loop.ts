import type{ModelGateway} from "../model/gateway.js";
import type{Request,Response} from "../model/types.js";
import {ToolRegistry} from "../tools/registry.js";
import { AgentEventSink } from "./types.js";

export class AgentLoop{
    private readonly gateway:ModelGateway;
    private readonly registry:ToolRegistry;

    constructor(gateway:ModelGateway, registry:ToolRegistry) {
        this.gateway = gateway;
        this.registry = registry;
    }

    private async callModel(request:Request,signal:AbortSignal,emit:AgentEventSink):Promise<Response>{
        for await(const event of this.gateway.stream(request,signal)){
            await emit({
                type:"model_event",
                modelCallId:request.modelCallId,
                event:event,
            })
            if(event.type==="done"){
                return event.response;
            }
        }
        throw new Error("Model stream ended without a final response");
    }
}