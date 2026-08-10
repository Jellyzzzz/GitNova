package com.gitnova.service.agent.model;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.gitnova.dto.ToolCall;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelResponseTest {

    @Test
    void shouldAcceptToolCallsOnlyWhenFinishReasonIsToolCalls() {
        ModelResponse response = new ModelResponse(
                "response-1",
                null,
                List.of(toolCall()),
                ModelUsage.unknown(),
                ModelFinishReason.TOOL_CALLS
        );

        assertTrue(response.hasToolCalls());
        assertEquals(ModelFinishReason.TOOL_CALLS, response.finishReason());
    }

    @Test
    void shouldRejectToolFinishReasonWithoutCalls() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelResponse(
                        "response-1",
                        null,
                        List.of(),
                        ModelUsage.unknown(),
                        ModelFinishReason.TOOL_CALLS
                )
        );
    }

    @Test
    void shouldRejectCallsWithNonToolFinishReason() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelResponse(
                        "response-1",
                        "I have a suggestion.",
                        List.of(toolCall()),
                        ModelUsage.unknown(),
                        ModelFinishReason.STOP
                )
        );
    }

    @Test
    void shouldDefensivelyCopyToolCalls() {
        List<ToolCall> calls = new ArrayList<>(List.of(toolCall()));
        ModelResponse response = new ModelResponse(
                "response-1",
                null,
                calls,
                new ModelUsage(10, 4, 14),
                ModelFinishReason.TOOL_CALLS
        );
        calls.clear();

        assertEquals(1, response.toolCalls().size());
        assertThrows(UnsupportedOperationException.class, () -> response.toolCalls().add(toolCall()));
    }

    @Test
    void shouldRetainGatewayErrorClassificationAndRetryability() {
        RuntimeException cause = new RuntimeException("HTTP 429");
        ModelGatewayException exception = new ModelGatewayException(
                ModelGatewayErrorCode.RATE_LIMITED,
                "Provider rate limit exceeded",
                true,
                429,
                "rate_limit_error",
                "provider-request-1",
                Duration.ofSeconds(10),
                cause
        );

        assertEquals(ModelGatewayErrorCode.RATE_LIMITED, exception.errorCode());
        assertTrue(exception.retryable());
        assertEquals(429, exception.providerStatusCode());
        assertEquals("rate_limit_error", exception.providerErrorCode());
        assertEquals("provider-request-1", exception.providerRequestId());
        assertEquals(Duration.ofSeconds(10), exception.retryAfter());
        assertEquals(cause, exception.getCause());
        assertFalse(exception.getMessage().isBlank());
    }

    private ToolCall toolCall() {
        return new ToolCall(
                "call-1",
                "listChanges",
                JsonNodeFactory.instance.objectNode()
        );
    }
}
