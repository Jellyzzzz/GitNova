package com.gitnova.gitobject;

public class GitObjectReadException extends RuntimeException {
    public enum Reason {
        NOT_FOUND,
        CORRUPT,
        TRANSIENT
    }

    private final Reason reason;

    public GitObjectReadException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public GitObjectReadException(
            Reason reason,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
