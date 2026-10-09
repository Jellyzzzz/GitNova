package com.gitnova.agent.core.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public final class ModelTypes {
    private ModelTypes(){}
    public enum Finish{STOP,TOOL_CALLS,LENGTH,ERROR};

    public record ToolDefinition(String name,String definition,String parametersJson){};
    public record Message(Role role,List<ContentBlock>content){};
    public record Usage(Long InputTokens,Long outputTokens,Long cachedReadTokens,Long cachedWriteTokens,Long reasoningTokens,Long totalTokens){};
    public record Request(String modelCallId, String model, List<Message>messages,List<ToolDefinition>tools,int maxOutputTokens,String thinkingJson){};
    public record Response(Message message,Termination termination,Usage usage,String providerRequestId){};

    public sealed interface ContentBlock permits ToolCall,TextBlock,ReasoningBlock,ToolResult,ImageBlock,FileBlock{}
    public record ToolCall(String id, String name, JsonNode arguments)implements ContentBlock{};
    public record TextBlock(String text) implements ContentBlock{};
    public record ReasoningBlock(String content) implements ContentBlock{};
    public record ToolResult(String toolCallId,String content,boolean isError)implements ContentBlock{};
    public record ImageBlock()implements ContentBlock{};
    public record FileBlock()implements ContentBlock{};

    private enum Role{USER,ASSISTANT,TOOL,SYSTEM};
    private record Termination(Finish kind,String rawReason,ModelFailure failure){};
    private record ModelFailure(String code,String message,Integer httpStatus){};
}
