package com.gitnova.agent.core.model;

import java.util.List;
public final class ModelTypes {
 private ModelTypes() {}
 public enum Finish { STOP, TOOL_CALLS, LENGTH, ERROR }
 public record ToolCall(String id, String name, String argumentsJson) {}
 public record ToolDefinition(String name, String description, String parametersJson) {}
 /** Provider-neutral message. User text is preserved exactly; execution identity is never embedded here. */
 public record Message(String role, String text, List<ToolCall> toolCalls, String toolCallId,
   String reasoningContent) {}
 public record Usage(Long inputTokens, Long outputTokens, Long totalTokens) {}
 public record Request(String modelCallId, String model, List<Message> messages,
   List<ToolDefinition> tools, int maxOutputTokens, String thinkingJson) {}
 public record Response(String text, String reasoningContent, List<ToolCall> toolCalls,
   Finish finish, Usage usage, String providerRequestId) {}
}
