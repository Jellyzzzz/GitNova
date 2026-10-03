package com.gitnova.service.agent.model;

import java.util.Objects;
import java.time.Duration;

/**
 * A categorized provider failure. Retrying is deliberately decided by AgentRuntime,
 * so a gateway must never implement an unbounded retry loop itself.
 */
public final class ModelGatewayException extends RuntimeException {
    private final ModelGatewayErrorCode errorCode;
    private final boolean retryable;
    private final Integer providerStatusCode;
    private final String providerErrorCode;
    private final String providerRequestId;
    private final Duration retryAfter;
    private final ResponseDiagnostic responseDiagnostic;

    /** Safe parser facts only: never provider text, argument values, reasoning, or response bodies. */
    public record ResponseDiagnostic(String reason, String path, Integer line, Integer column) {
        public ResponseDiagnostic {
            if (reason == null || !reason.matches("[A-Z0-9_]{1,80}")) {
                throw new IllegalArgumentException("Invalid response diagnostic reason");
            }
            if (path == null || !path.matches("/[A-Za-z0-9_/-]{0,255}")) {
                throw new IllegalArgumentException("Invalid response diagnostic path");
            }
            if ((line != null && line < 1) || (column != null && column < 1)) {
                throw new IllegalArgumentException("JSON location must be positive when present");
            }
        }
    }

    public ModelGatewayException(
            ModelGatewayErrorCode errorCode,
            String message,
            boolean retryable,
            Throwable cause
    ) {
        this(errorCode, message, retryable, null, null, null, null, cause);
    }

    /**
     * Creates a classified failure without exposing a raw provider response body.
     *
     * @param errorCode stable, provider-neutral category used by AgentRuntime
     * @param message diagnostic for restricted inspection; may contain untrusted provider text, not for ordinary logs
     * @param retryable whether retrying the exact same request later may succeed
     * @param providerStatusCode provider HTTP status when headers were received, including a malformed HTTP 200 body
     * @param providerErrorCode optional provider machine code such as {@code rate_limit_error}
     * @param providerRequestId optional provider correlation id from a response header/body
     * @param retryAfter optional provider backoff hint; Runtime owns the actual retry policy
     * @param cause underlying exception retained for server-side diagnostics only
     */
    public ModelGatewayException(
            ModelGatewayErrorCode errorCode,
            String message,
            boolean retryable,
            Integer providerStatusCode,
            String providerErrorCode,
            String providerRequestId,
            Duration retryAfter,
            Throwable cause
    ) {
        this(errorCode, message, retryable, providerStatusCode, providerErrorCode, providerRequestId,
                retryAfter, cause, null);
    }

    public ModelGatewayException(
            ModelGatewayErrorCode errorCode, String message, boolean retryable,
            Integer providerStatusCode, String providerErrorCode, String providerRequestId,
            Duration retryAfter, Throwable cause, ResponseDiagnostic responseDiagnostic
    ) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
        this.retryable = retryable;
        this.providerStatusCode = validateStatusCode(providerStatusCode);
        this.providerErrorCode = optionalNonBlank(providerErrorCode, "providerErrorCode");
        this.providerRequestId = optionalNonBlank(providerRequestId, "providerRequestId");
        this.retryAfter = validateRetryAfter(retryAfter);
        this.responseDiagnostic = responseDiagnostic;
    }

    public ResponseDiagnostic responseDiagnostic() {
        return responseDiagnostic;
    }

    public ModelGatewayErrorCode errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public Integer providerStatusCode() {
        return providerStatusCode;
    }

    public String providerErrorCode() {
        return providerErrorCode;
    }

    public String providerRequestId() {
        return providerRequestId;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    private static Integer validateStatusCode(Integer value) {
        if (value != null && (value < 100 || value > 599)) {
            throw new IllegalArgumentException("providerStatusCode must be a valid HTTP status code");
        }
        return value;
    }

    private static String optionalNonBlank(String value, String field) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank when present");
        }
        return value;
    }

    private static Duration validateRetryAfter(Duration value) {
        if (value != null && value.isNegative()) {
            throw new IllegalArgumentException("retryAfter must not be negative");
        }
        return value;
    }
}
