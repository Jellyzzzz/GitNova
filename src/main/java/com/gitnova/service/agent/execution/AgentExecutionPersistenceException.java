package com.gitnova.service.agent.execution;

import java.util.Objects;

/** Stable persistence/state-machine failure exposed above the MyBatis adapter. */
public final class AgentExecutionPersistenceException extends RuntimeException {
    public enum Code {
        UNKNOWN_SESSION,
        UNKNOWN_TASK,
        UNKNOWN_RUN,
        IDEMPOTENCY_KEY_CONFLICT,
        STATE_CONFLICT,
        LEASE_LOST,
        FENCING_CONFLICT,
        PERSISTENCE_FAILURE
    }

    private final Code code;

    public AgentExecutionPersistenceException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    public Code code() {
        return code;
    }
}
