import type{Request,Response,ModelEvent} from "./types.js";
export interface ModelGateway{
    complete(request:Request,signal:AbortSignal):Promise<Response>;
    stream(request:Request,signal:AbortSignal):AsyncIterable<ModelEvent>;
}