import type{Models}from "@earendil-works/pi-ai";
import type{ModelEvent, Request,Response}from "./types.js";
import {toPiOptions,toPiTools,toPiContext}from"./pi-request.js";
import {fromPiAssistantMessage}from "./pi-response.js";
import {ModelGateway}from"./gateway.js";
export class PiGateway implements ModelGateway{
    private readonly models:Models;
    constructor(models:Models){
        this.models=models;
    }

    async *stream(request:Request,signal:AbortSignal):AsyncIterable<ModelEvent> {
        signal.throwIfAborted();
        const model=this.models.getModel(request.model.providerId,request.model.modelId);
        if(model===undefined){
            throw new Error(`Model not found: ${request.model.providerId}/${request.model.modelId}`);
        }
        const options=toPiOptions(request,signal);
        const context=toPiContext(request);
        const piStream=this.models.streamSimple(model,context,options);
        for await(const event of piStream){
    switch(event.type){
        case "text_delta":
            yield {
                type:"text_delta",
                contentIndex:event.contentIndex,
                delta:event.delta,
            };
            break;

        case "thinking_delta":
            yield {
                type:"reasoning_delta",
                contentIndex:event.contentIndex,
                delta:event.delta,
            };
            break;

        case "done":
            yield {
                type:"done",
                response:fromPiAssistantMessage(event.message),
            };
            return;

        case "error":
            yield {
                type:"done",
                response:fromPiAssistantMessage(event.error),
            };
            return;

        default:
            break;
    }
}

throw new Error("Pi stream ended without a final response");
    }
    async complete(request:Request,signal:AbortSignal):Promise<Response>{
        for await(const event of this.stream(request,signal)){
            if(event.type==="done"){
                return event.response;
            }   
        }
        throw new Error("Model stream ended without a final response");
    }

}