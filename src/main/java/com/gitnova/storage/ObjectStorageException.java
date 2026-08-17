package com.gitnova.storage;

/**
 * Signals an I/O failure at the ObjectStorage boundary.
 *
 * <p>R0-04 will refine this into stable NOT_FOUND, CORRUPT, and TRANSIENT
 * classifications. Callers must not depend on the underlying filesystem
 * exception type.</p>
 */
public class ObjectStorageException extends RuntimeException {

    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
