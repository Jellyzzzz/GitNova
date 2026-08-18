package com.gitnova.service;

/** A deterministic hosted-push rejection, not an infrastructure failure. */
public final class TransferRejectedException extends RuntimeException {

    public enum Reason {
        NON_FAST_FORWARD,
        INVALID_HEAD,
        MISSING_OBJECT,
        CORRUPT_OBJECT
    }

    private final Reason reason;

    public TransferRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public TransferRejectedException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
