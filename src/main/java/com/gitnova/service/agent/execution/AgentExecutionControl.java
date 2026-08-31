package com.gitnova.service.agent.execution;

import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative stop signal shared by heartbeat ownership and the Agent loop. */
public final class AgentExecutionControl {
    private final AtomicBoolean leaseLost = new AtomicBoolean();

    public void markLeaseLost() {
        leaseLost.set(true);
    }

    public void requireLease() {
        if (leaseLost.get()) {
            throw new LeaseLostException();
        }
    }

    public static final class LeaseLostException extends RuntimeException {
        private LeaseLostException() {
            super("Agent Run lease was lost");
        }
    }
}
