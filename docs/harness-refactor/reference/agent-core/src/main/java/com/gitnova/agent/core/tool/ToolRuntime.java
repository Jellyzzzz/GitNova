package com.gitnova.agent.core.tool;

import com.gitnova.agent.core.model.ModelTypes;
import java.util.*;
/** Local dispatch only. Concrete tools own bounded argument decoding. */
public final class ToolRuntime {
 private final Map<String,AgentTool> tools;
 public ToolRuntime(List<AgentTool> registered) {
  LinkedHashMap<String,AgentTool> byName = new LinkedHashMap<>();
  for (AgentTool tool : registered) {
   Objects.requireNonNull(tool, "tool");
   String name = Objects.requireNonNull(tool.definition(), "definition").name();
   if (name == null || name.isBlank() || byName.putIfAbsent(name,tool) != null)
    throw new IllegalArgumentException("Invalid or duplicate tool name");
  }
  tools = Collections.unmodifiableMap(byName);
 }
 public List<ModelTypes.ToolDefinition> definitions() {
  return tools.values().stream().map(AgentTool::definition).toList();
 }
 public ToolTypes.Result execute(ModelTypes.ToolCall call, ToolTypes.Context context) {
  Objects.requireNonNull(call, "call"); Objects.requireNonNull(context, "context");
  AgentTool tool = tools.get(call.name());
  if (tool == null) return new ToolTypes.Result(ToolTypes.Status.INVALID_ARGUMENT,
    "UNKNOWN_TOOL", "Unknown tool", Map.of(), false);
  context.control().checkActive();
  // Do not catch persistence/cancellation failures and recast them as model-correctable errors.
  return Objects.requireNonNull(tool.execute(call.argumentsJson(), context), "tool result");
 }
}
