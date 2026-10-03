package com.gitnova.agent.core.tool;

import com.gitnova.agent.core.model.ModelTypes;
public interface AgentTool {
 ModelTypes.ToolDefinition definition();
 ToolTypes.Result execute(String argumentsJson, ToolTypes.Context context);
}
