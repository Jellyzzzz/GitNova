import type { ImageBlock, Message, TextBlock, ToolResultBlock } from "./types.js";

export interface ReadToolResult {
    readonly result: ToolResultBlock;
    readonly content: readonly (TextBlock | ImageBlock)[];
}

/** Read the entire tool message in order, including legacy inline result text. */
export function readToolResult(message: Message): ReadToolResult {
    if (message.role !== "tool") {
        throw new Error('Invalid message role: expected "tool"');
    }

    let result: ToolResultBlock | undefined;
    const content: (TextBlock | ImageBlock)[] = [];
    for (const block of message.content) {
        if (typeof block !== "object" || block === null) {
            throw new Error("Invalid tool message content block");
        }
        switch (block.type) {
            case "tool_result":
                if (result !== undefined) {
                    throw new Error("Invalid message content: multiple tool_result blocks found");
                }
                if (typeof block.callId !== "string" || !block.callId.trim()
                    || typeof block.name !== "string" || !block.name.trim()
                    || typeof block.content !== "string" || typeof block.isError !== "boolean") {
                    throw new Error("Invalid tool_result block");
                }
                result = block;
                // Legacy errors store text here; newer results use sibling text blocks.
                content.push({ type: "text", text: block.content });
                break;
            case "text":
                if (typeof block.text !== "string") throw new Error("Invalid tool text block");
                content.push({ type: "text", text: block.text });
                break;
            case "image":
                if (typeof block.data !== "string" || typeof block.mimeType !== "string") {
                    throw new Error("Invalid tool image block");
                }
                content.push({ type: "image", data: block.data, mimeType: block.mimeType });
                break;
            default:
                throw new Error(`Unsupported block type "${block.type}" in tool message`);
        }
    }
    if (result === undefined) throw new Error("Invalid message content: no tool_result block found");
    return { result, content };
}
