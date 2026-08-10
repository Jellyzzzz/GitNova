package com.gitnova.service.agent.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelGatewayExceptionTest {

    @Test
    void shouldLeaveProviderMetadataUnknownForTransportFailures() {
        ModelGatewayException exception = new ModelGatewayException(
                ModelGatewayErrorCode.NETWORK_ERROR,
                "Connection to provider failed",
                true,
                new RuntimeException("connection reset")
        );

        assertNull(exception.providerStatusCode());
        assertNull(exception.providerErrorCode());
        assertNull(exception.providerRequestId());
        assertNull(exception.retryAfter());
    }

    @Test
    void shouldRejectInvalidDiagnosticMetadata() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelGatewayException(
                        ModelGatewayErrorCode.RATE_LIMITED,
                        "Rate limited",
                        true,
                        700,
                        null,
                        null,
                        null,
                        null
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelGatewayException(
                        ModelGatewayErrorCode.RATE_LIMITED,
                        "Rate limited",
                        true,
                        429,
                        null,
                        null,
                        Duration.ofSeconds(-1),
                        null
                )
        );
    }
}
