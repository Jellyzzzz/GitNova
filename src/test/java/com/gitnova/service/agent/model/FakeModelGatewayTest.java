package com.gitnova.service.agent.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FakeModelGatewayTest {

    @Test
    void shouldConsumeScriptedResponsesInOrderAndRecordRequests() {
        ModelResponse firstResponse = response("response-1", "First model result");
        ModelResponse secondResponse = response("response-2", "Second model result");
        FakeModelGateway gateway = new FakeModelGateway()
                .enqueueResponse(firstResponse)
                .enqueueResponse(secondResponse);

        ModelResponse actualFirst = gateway.complete(request("request-1"));
        ModelResponse actualSecond = gateway.complete(request("request-2"));

        assertSame(firstResponse, actualFirst);
        assertSame(secondResponse, actualSecond);
        assertEquals(
                List.of("request-1", "request-2"),
                gateway.receivedRequests().stream().map(ModelRequest::requestId).toList()
        );
        assertEquals(0, gateway.remainingOutcomes());
    }

    @Test
    void shouldRecordRequestBeforeThrowingScriptedGatewayFailure() {
        ModelGatewayException expected = new ModelGatewayException(
                ModelGatewayErrorCode.RATE_LIMITED,
                "Provider rate limit exceeded",
                true,
                null
        );
        FakeModelGateway gateway = new FakeModelGateway().enqueueFailure(expected);

        ModelGatewayException actual = assertThrows(
                ModelGatewayException.class,
                () -> gateway.complete(request("request-1"))
        );

        assertSame(expected, actual);
        assertEquals(List.of("request-1"), gateway.receivedRequests().stream()
                .map(ModelRequest::requestId)
                .toList());
        assertEquals(0, gateway.remainingOutcomes());
    }

    @Test
    void shouldFailClearlyWhenRuntimeMakesAnUnscriptedCall() {
        FakeModelGateway gateway = new FakeModelGateway();

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> gateway.complete(request("request-1"))
        );

        assertEquals(
                "FakeModelGateway has no scripted outcome for request request-1",
                exception.getMessage()
        );
        assertEquals(List.of("request-1"), gateway.receivedRequests().stream()
                .map(ModelRequest::requestId)
                .toList());
    }

    private static ModelRequest request(String requestId) {
        return new ModelRequest(
                "fake-model",
                List.of(new ModelMessage(ModelRole.USER, "Review this change.", List.of(), null)),
                List.of(),
                null,
                null,
                requestId
        );
    }

    private static ModelResponse response(String responseId, String text) {
        return new ModelResponse(
                responseId,
                text,
                List.of(),
                ModelUsage.unknown(),
                ModelFinishReason.STOP
        );
    }
}
