package com.gitnova.storage;

public class ObjectStorageException extends RuntimeException {

    public enum Reason {
        NOT_FOUND,
        CORRUPT,
        TRANSIENT
    }

    private final Reason reason;

    public ObjectStorageException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ObjectStorageException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
