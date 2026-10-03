package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.workspace.WorkspaceOperationException;

final class WorkspaceToolResults {

    private WorkspaceToolResults() {
    }

    static ToolResult missingContext() {
        return ToolResult.error(
                ToolStatus.PERMISSION_DENIED,
                "WORKSPACE_CONTEXT_REQUIRED",
                "Tool execution is not bound to a Workspace",
                false
        );
    }

    static ToolResult error(WorkspaceOperationException exception) {
        ToolStatus status = switch (exception.reason()) {
            case INVALID_PATH -> ToolStatus.PERMISSION_DENIED;
            case NOT_FOUND -> ToolStatus.NOT_FOUND;
            case UNSUPPORTED_CONTENT -> ToolStatus.INVALID_ARGUMENT;
            case WORKSPACE_UNAVAILABLE -> ToolStatus.CONFLICT;
            case FILESYSTEM_FAILURE -> ToolStatus.INTERNAL_ERROR;
            case SNAPSHOT_UNAVAILABLE -> exception.errorCode().contains("STORAGE_UNAVAILABLE")
                    ? ToolStatus.TRANSIENT_ERROR
                    : ToolStatus.INTERNAL_ERROR;
        };
        return ToolResult.error(
                status,
                exception.errorCode(),
                exception.getMessage(),
                status == ToolStatus.TRANSIENT_ERROR
        );
    }

    static ToolResult invalid(String code, String message) {
        return ToolResult.error(
                ToolStatus.INVALID_ARGUMENT,
                code,
                message,
                false
        );
    }

    static ToolResult invalid(ToolExecutionContext execution, String code, String path,
                              String message, long actualBytes, long limitBytes) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("stage", "ARGUMENT_VALIDATION");
        payload.put("executionState", "NOT_STARTED");
        payload.put("nextAction", "Shorten or split the indicated command argument before retrying");
        payload.putArray("violations").addObject().put("path", path).put("code", code)
                .put("message", message).put("actualBytes", actualBytes).put("limitBytes", limitBytes);
        if (execution.observedWorkspaceGeneration() != null) {
            payload.putObject("workspaceState").put("observedGeneration", execution.observedWorkspaceGeneration())
                    .put("source", "PRE_DISPATCH_REFRESH");
        }
        return ToolResult.error(ToolStatus.INVALID_ARGUMENT, payload, code, message, false);
    }
}
