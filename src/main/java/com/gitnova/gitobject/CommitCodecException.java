package com.gitnova.gitobject;

/** A deterministic rejection of malformed or unsupported canonical commit bytes. */
public final class CommitCodecException extends RuntimeException {

    public enum Reason {
        MALFORMED,
        UNSUPPORTED_VERSION,
        LIMIT_EXCEEDED,
        INVARIANT
    }

    private final Reason reason;

    public CommitCodecException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public CommitCodecException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
