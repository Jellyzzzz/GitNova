package com.gitnova.service.agent.dispatch;

import java.util.Objects;

public record RunDispatchMessage(
        String eventId,
        String runId,
        RunDispatchReason reason,
        Long expiredFencingToken
) {
    public RunDispatchMessage {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        if (eventId.isBlank() || runId.isBlank()) {
            throw new IllegalArgumentException("dispatch identities must not be blank");
        }

        if (reason == RunDispatchReason.INITIAL
                && expiredFencingToken != null) {
            throw new IllegalArgumentException(
                    "INITIAL dispatch must not have expired fence"
            );
        }

        if (reason == RunDispatchReason.RECOVERY
                && (expiredFencingToken == null
                || expiredFencingToken <= 0)) {
            throw new IllegalArgumentException(
                    "RECOVERY dispatch requires expired fence"
            );
        }
    }
}
