package com.gitnova.service.agent.workspace;

import java.util.Objects;

/** Typed Workspace failure mapped by Agent tools into a stable ToolResult. */
public final class WorkspaceOperationException extends RuntimeException {

    public enum Reason {
        INVALID_PATH,
        NOT_FOUND,
        UNSUPPORTED_CONTENT,
        SNAPSHOT_UNAVAILABLE,
        WORKSPACE_UNAVAILABLE,
        FILESYSTEM_FAILURE
    }

    private final Reason reason;
    private final String errorCode;

    public WorkspaceOperationException(
            Reason reason,
            String errorCode,
            String message
    ) {
        this(reason, errorCode, message, null);
    }

    public WorkspaceOperationException(
            Reason reason,
            String errorCode,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public Reason reason() {
        return reason;
    }

    public String errorCode() {
        return errorCode;
    }
}
