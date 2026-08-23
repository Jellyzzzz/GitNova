package com.gitnova.service.agent.tools;

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
}
