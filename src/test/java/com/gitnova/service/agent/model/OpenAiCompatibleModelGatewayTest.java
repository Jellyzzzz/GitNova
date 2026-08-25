package com.gitnova.service.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.gitnova.dto.ToolCall;
import com.gitnova.dto.ToolDefinition;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("gateway")
class OpenAiCompatibleModelGatewayTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockWebServer server;
    private OpenAiCompatibleModelGateway gateway;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        gateway = new OpenAiCompatibleModelGateway(
                objectMapper,
                new OkHttpClient(),
                "test-api-key",
                server.url("/v1/chat/completions")
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void shouldMapInternalMessagesAndToolsToOpenAiCompatibleRequest() throws Exception {
        server.enqueue(successfulTextResponse("resp-1", "I will inspect the change."));

        gateway.complete(requestWithEveryMessageRole());

        RecordedRequest recorded = server.takeRequest();
        JsonNode root = objectMapper.readTree(recorded.getBody().readUtf8());

        assertEquals("POST", recorded.getMethod());
        assertEquals("/v1/chat/completions", recorded.getPath());
        assertEquals("Bearer test-api-key", recorded.getHeader("Authorization"));
        assertEquals("review-model", root.path("model").asText());
        assertFalse(root.path("stream").asBoolean());
        assertEquals(256, root.path("max_tokens").asInt());
        assertEquals(0.1d, root.path("temperature").asDouble());

        JsonNode messages = root.path("messages");
        assertEquals("system", messages.get(0).path("role").asText());
        assertEquals("Review only trusted revisions.", messages.get(0).path("content").asText());
        assertEquals("user", messages.get(1).path("role").asText());

        JsonNode assistant = messages.get(2);
        assertEquals("assistant", assistant.path("role").asText());
        assertTrue(assistant.path("content").isNull());
        assertEquals("call-1", assistant.path("tool_calls").get(0).path("id").asText());
        assertEquals("getDiff", assistant.path("tool_calls").get(0).path("function").path("name").asText());
        assertEquals(
                "src/UserService.java",
                objectMapper.readTree(
                        assistant.path("tool_calls").get(0).path("function").path("arguments").asText()
                ).path("filePath").asText()
        );

        JsonNode tool = messages.get(3);
        assertEquals("tool", tool.path("role").asText());
        assertEquals("call-1", tool.path("tool_call_id").asText());
        assertEquals("SUCCESS", objectMapper.readTree(tool.path("content").asText()).path("status").asText());

        JsonNode providerTool = root.path("tools").get(0);
        assertEquals("function", providerTool.path("type").asText());
        assertEquals("getDiff", providerTool.path("function").path("name").asText());
        assertTrue(providerTool.path("function").path("parameters").isObject());
    }

    @Test
    void shouldIncludeConfiguredThinkingMode() throws Exception {
        gateway = new OpenAiCompatibleModelGateway(
                objectMapper,
                new OkHttpClient(),
                "test-api-key",
                server.url("/v1/chat/completions"),
                "disabled"
        );
        server.enqueue(successfulTextResponse("resp-thinking", "Ready."));

        gateway.complete(simpleRequest());

        RecordedRequest recorded = server.takeRequest();
        JsonNode root = objectMapper.readTree(recorded.getBody().readUtf8());
        assertEquals("disabled", root.path("thinking").path("type").asText());
    }

    @Test
    void shouldParseProviderToolCallsAndUsage() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                {
                  "id": "resp-tool-1",
                  "choices": [{
                    "finish_reason": "tool_calls",
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [{
                        "id": "call-2",
                        "type": "function",
                        "function": {
                          "name": "readFile",
                          "arguments": "{\\"filePath\\":\\"src/UserService.java\\",\\"startLine\\":10}"
                        }
                      }]
                    }
                  }],
                  "usage": {
                    "prompt_tokens": 120,
                    "completion_tokens": 18,
                    "total_tokens": 138
                  }
                }
                """));

        ModelResponse response = gateway.complete(simpleRequest());

        assertEquals("resp-tool-1", response.responseId());
        assertNull(response.text());
        assertEquals(ModelFinishReason.TOOL_CALLS, response.finishReason());
        assertEquals(1, response.toolCalls().size());
        assertEquals("call-2", response.toolCalls().get(0).id());
        assertEquals("readFile", response.toolCalls().get(0).name());
        assertEquals("src/UserService.java", response.toolCalls().get(0).arguments().path("filePath").asText());
        assertEquals(10, response.toolCalls().get(0).arguments().path("startLine").asInt());
        assertEquals(new ModelUsage(120, 18, 138), response.usage());
    }

    @Test
    void shouldMapRateLimitAndRetryAfter() {
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "10")
                .addHeader("x-request-id", "provider-request-1")
                .setBody("""
                        {"error":{"code":"rate_limit_error","message":"Too many requests"}}
                        """));

        ModelGatewayException exception = assertThrows(
                ModelGatewayException.class,
                () -> gateway.complete(simpleRequest())
        );

        assertEquals(ModelGatewayErrorCode.RATE_LIMITED, exception.errorCode());
        assertTrue(exception.retryable());
        assertEquals(429, exception.providerStatusCode());
        assertEquals("rate_limit_error", exception.providerErrorCode());
        assertEquals("provider-request-1", exception.providerRequestId());
        assertEquals(Duration.ofSeconds(10), exception.retryAfter());
        assertTrue(exception.getMessage().contains("HTTP 429"));
    }

    @Test
    void shouldMapEmptyErrorBodyUsingHttpStatus() {
        server.enqueue(new MockResponse().setResponseCode(503));

        ModelGatewayException exception = assertThrows(
                ModelGatewayException.class,
                () -> gateway.complete(simpleRequest())
        );

        assertEquals(ModelGatewayErrorCode.PROVIDER_UNAVAILABLE, exception.errorCode());
        assertTrue(exception.retryable());
        assertEquals(503, exception.providerStatusCode());
        assertNull(exception.providerErrorCode());
        assertEquals("Model provider returned HTTP 503", exception.getMessage());
    }

    @Test
    void shouldClassifyProviderSilenceAsRetryableTimeout() {
        gateway = new OpenAiCompatibleModelGateway(
                objectMapper,
                new OkHttpClient.Builder()
                        .callTimeout(Duration.ofMillis(150))
                        .build(),
                "test-api-key",
                server.url("/v1/chat/completions")
        );
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        ModelGatewayException exception = assertThrows(
                ModelGatewayException.class,
                () -> gateway.complete(simpleRequest())
        );

        assertEquals(ModelGatewayErrorCode.TIMEOUT, exception.errorCode());
        assertTrue(exception.retryable());
        assertNull(exception.providerStatusCode());
    }

    @Test
    void shouldClassifyAbruptDisconnectAsRetryableNetworkFailure() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

        ModelGatewayException exception = assertThrows(
                ModelGatewayException.class,
                () -> gateway.complete(simpleRequest())
        );

        assertEquals(ModelGatewayErrorCode.NETWORK_ERROR, exception.errorCode());
        assertTrue(exception.retryable());
    }

    @Test
    void shouldRejectStopResponseWithoutTextOrToolCalls() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                {
                  "id": "resp-empty-stop",
                  "choices": [{
                    "finish_reason": "stop",
                    "message": {"role": "assistant", "content": null}
                  }]
                }
                """));

        ModelGatewayException exception = assertThrows(
                ModelGatewayException.class,
                () -> gateway.complete(simpleRequest())
        );

        assertEquals(ModelGatewayErrorCode.INVALID_RESPONSE, exception.errorCode());
        assertFalse(exception.retryable());
    }

    @Test
    void shouldRejectSuccessfulResponseWithMalformedToolArguments() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                {
                  "id": "resp-invalid-tool",
                  "choices": [{
                    "finish_reason": "tool_calls",
                    "message": {
                      "tool_calls": [{
                        "id": "call-3",
                        "function": {"name": "readFile", "arguments": "not-json"}
                      }]
                    }
                  }]
                }
                """));

        ModelGatewayException exception = assertThrows(
                ModelGatewayException.class,
                () -> gateway.complete(simpleRequest())
        );

        assertEquals(ModelGatewayErrorCode.INVALID_RESPONSE, exception.errorCode());
    }

    private ModelRequest requestWithEveryMessageRole() {
        ToolCall call = new ToolCall(
                "call-1",
                "getDiff",
                JsonNodeFactory.instance.objectNode().put("filePath", "src/UserService.java")
        );
        ToolDefinition definition = new ToolDefinition(
                "getDiff",
                "Read the change diff for one file.",
                JsonNodeFactory.instance.objectNode()
                        .put("type", "object")
        );

        return new ModelRequest(
                "review-model",
                List.of(
                        new ModelMessage(ModelRole.SYSTEM, "Review only trusted revisions.", List.of(), null),
                        new ModelMessage(ModelRole.USER, "Begin the review.", List.of(), null),
                        new ModelMessage(ModelRole.ASSISTANT, null, List.of(call), null),
                        new ModelMessage(
                                ModelRole.TOOL,
                                "{\"status\":\"SUCCESS\",\"payload\":{\"files\":[]}}",
                                List.of(),
                                "call-1"
                        )
                ),
                List.of(definition),
                256,
                0.1d,
                "request-1"
        );
    }

    private ModelRequest simpleRequest() {
        return new ModelRequest(
                "review-model",
                List.of(new ModelMessage(ModelRole.USER, "Review this change.", List.of(), null)),
                List.of(),
                null,
                null,
                "request-1"
        );
    }

    private static MockResponse successfulTextResponse(String id, String content) {
        return new MockResponse().setResponseCode(200).setBody("""
                {
                  "id": "%s",
                  "choices": [{
                    "finish_reason": "stop",
                    "message": {"role": "assistant", "content": "%s"}
                  }]
                }
                """.formatted(id, content));
    }
}
