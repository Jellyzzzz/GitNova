package com.gitnova.agent.core.tool;

import java.util.Map;
import com.gitnova.agent.core.engine.ExecutionControl;
import com.gitnova.agent.core.engine.SessionRuntime;
public final class ToolTypes {
 private ToolTypes() {}
 public record Context(String taskId, String attemptId, String toolCallId,
   SessionRuntime session, ExecutionControl control) {}
 public record Result(Status status, String code, String message, Map<String,Object> payload,
   boolean historyReadback) {}
 public enum Status { SUCCESS, INVALID_ARGUMENT, CONFLICT, NOT_FOUND, EXECUTION_ERROR, CANCELLED }
}
