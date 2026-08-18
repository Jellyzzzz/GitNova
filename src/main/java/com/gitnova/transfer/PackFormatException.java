package com.gitnova.transfer;

/** Client-caused Pack validation failure. Controllers map this to HTTP 400. */
public final class PackFormatException extends RuntimeException {

    public enum Reason {
        MALFORMED,
        LIMIT_EXCEEDED,
        DIGEST_MISMATCH
    }

    private final Reason reason;

    public PackFormatException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public PackFormatException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
