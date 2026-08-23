package com.gitnova.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldCreateSuccessfulResult() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("fileCount", 3);
        payload.put("message", "Tool executed successfully");

        ToolResult result = ToolResult.success(payload);

        assertTrue(result.successful());
        assertEquals(ToolStatus.SUCCESS, result.status());
        assertSame(payload, result.payload());

        assertEquals(
                3,
                result.payload().get("fileCount").asInt()
        );

        assertEquals(
                "Tool executed successfully",
                result.payload().get("message").asText()
        );

        assertNull(result.errorCode());
        assertNull(result.message());
        assertFalse(result.retryable());
        assertFalse(result.truncated());
    }

    @Test
    void shouldCreateTruncatedSuccessfulResult() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("content", "partial tool output");

        ToolResult result = ToolResult.success(
                payload,
                true
        );

        assertTrue(result.successful());
        assertEquals(ToolStatus.SUCCESS, result.status());
        assertTrue(result.truncated());
        assertFalse(result.retryable());

        assertEquals(
                "partial tool output",
                result.payload().get("content").asText()
        );
    }

    @Test
    void shouldCreateNonTruncatedSuccessfulResult() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();

        ToolResult result = ToolResult.success(
                payload,
                false
        );

        assertTrue(result.successful());
        assertFalse(result.truncated());
    }

    @Test
    void shouldCreateRetryableTransientError() {
        ToolResult result = ToolResult.error(
                ToolStatus.TRANSIENT_ERROR,
                "STORAGE_TEMPORARILY_UNAVAILABLE",
                "Object storage is temporarily unavailable",
                true
        );

        assertFalse(result.successful());
        assertEquals(
                ToolStatus.TRANSIENT_ERROR,
                result.status()
        );

        assertEquals(
                "STORAGE_TEMPORARILY_UNAVAILABLE",
                result.errorCode()
        );

        assertEquals(
                "Object storage is temporarily unavailable",
                result.message()
        );

        assertTrue(result.retryable());
        assertFalse(result.truncated());
        assertTrue(result.payload().isNull());
    }

    @Test
    void shouldCreateNonRetryablePermissionError() {
        ToolResult result = ToolResult.error(
                ToolStatus.PERMISSION_DENIED,
                "PATH_OUTSIDE_REPOSITORY",
                "The requested path is outside the repository",
                false
        );

        assertFalse(result.successful());

        assertEquals(
                ToolStatus.PERMISSION_DENIED,
                result.status()
        );

        assertEquals(
                "PATH_OUTSIDE_REPOSITORY",
                result.errorCode()
        );

        assertFalse(result.retryable());
        assertTrue(result.payload().isNull());
    }

    @Test
    void shouldCreatePartialSuccessWithAuthoritativeStatePayload() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("generationBefore", 3);
        payload.put("generationAfter", 4);
        payload.putArray("applied").add("src/A.java");
        payload.put("failedPath", "src/B.java");

        ToolResult result = ToolResult.partialSuccess(
                payload,
                "PATCH_OPERATION_FAILED",
                "One operation was applied before the next operation failed"
        );

        assertFalse(result.successful());
        assertTrue(result.partiallySuccessful());
        assertEquals(ToolStatus.PARTIAL_SUCCESS, result.status());
        assertSame(payload, result.payload());
        assertEquals("PATCH_OPERATION_FAILED", result.errorCode());
        assertFalse(result.retryable());
        assertFalse(result.truncated());
    }

    @Test
    void shouldCreateFailureWithCurrentStatePayload() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("expectedGeneration", 3);
        payload.put("currentGeneration", 4);

        ToolResult result = ToolResult.error(
                ToolStatus.CONFLICT,
                payload,
                "STALE_WORKSPACE_GENERATION",
                "Workspace generation no longer matches",
                false
        );

        assertFalse(result.successful());
        assertFalse(result.partiallySuccessful());
        assertSame(payload, result.payload());
        assertEquals(4, result.payload().path("currentGeneration").asInt());
    }

    @Test
    void shouldRejectPartialSuccessWithoutStatePayload() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ToolResult.partialSuccess(
                        JsonNodeFactory.instance.nullNode(),
                        "PATCH_OPERATION_FAILED",
                        "Patch partially applied"
                )
        );

        assertEquals(
                "Partial ToolResult must contain a state payload",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectRetryablePartialSuccess() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new ToolResult(
                        ToolStatus.PARTIAL_SUCCESS,
                        JsonNodeFactory.instance.objectNode(),
                        "PATCH_OPERATION_FAILED",
                        "Patch partially applied",
                        true,
                        false
                )
        );

        assertEquals(
                "Partial ToolResult cannot be retried unchanged",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectPartialStatusInErrorFactory() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ToolResult.error(
                        ToolStatus.PARTIAL_SUCCESS,
                        "PATCH_OPERATION_FAILED",
                        "Use the partial success factory",
                        false
                )
        );

        assertEquals(
                "Error result cannot use PARTIAL_SUCCESS status",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectNullStatus() {
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> new ToolResult(
                        null,
                        JsonNodeFactory.instance.objectNode(),
                        null,
                        null,
                        false,
                        false
                )
        );

        assertEquals(
                "status must not be null",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectNullPayload() {
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> new ToolResult(
                        ToolStatus.SUCCESS,
                        null,
                        null,
                        null,
                        false,
                        false
                )
        );

        assertEquals(
                "payload must not be null",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectNullPayloadInSuccessFactory() {
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> ToolResult.success(null)
        );

        assertEquals(
                "payload must not be null",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectSuccessWithErrorCode() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new ToolResult(
                        ToolStatus.SUCCESS,
                        payload,
                        "UNEXPECTED_ERROR",
                        null,
                        false,
                        false
                )
        );

        assertEquals(
                "Successful ToolResult must not contain error information",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectSuccessWithErrorMessage() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new ToolResult(
                        ToolStatus.SUCCESS,
                        payload,
                        null,
                        "Unexpected error",
                        false,
                        false
                )
        );

        assertEquals(
                "Successful ToolResult must not contain error information",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectRetryableSuccess() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new ToolResult(
                        ToolStatus.SUCCESS,
                        payload,
                        null,
                        null,
                        true,
                        false
                )
        );

        assertEquals(
                "Successful ToolResult cannot be retryable",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectFailureWithoutErrorCode() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new ToolResult(
                        ToolStatus.INTERNAL_ERROR,
                        JsonNodeFactory.instance.nullNode(),
                        null,
                        "Unexpected internal error",
                        false,
                        false
                )
        );

        assertEquals(
                "Failed ToolResult must contain errorCode",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectFailureWithBlankErrorCode() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ToolResult.error(
                        ToolStatus.INTERNAL_ERROR,
                        "   ",
                        "Unexpected internal error",
                        false
                )
        );

        assertEquals(
                "Failed ToolResult must contain errorCode",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectFailureWithoutMessage() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new ToolResult(
                        ToolStatus.NOT_FOUND,
                        JsonNodeFactory.instance.nullNode(),
                        "FILE_NOT_FOUND",
                        null,
                        false,
                        false
                )
        );

        assertEquals(
                "Failed ToolResult must contain message",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectFailureWithBlankMessage() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ToolResult.error(
                        ToolStatus.NOT_FOUND,
                        "FILE_NOT_FOUND",
                        "   ",
                        false
                )
        );

        assertEquals(
                "Failed ToolResult must contain message",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectSuccessStatusInErrorFactory() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ToolResult.error(
                        ToolStatus.SUCCESS,
                        "INVALID_STATUS",
                        "SUCCESS cannot represent an error",
                        false
                )
        );

        assertEquals(
                "Error result cannot use SUCCESS status",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectNullStatusInErrorFactory() {
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> ToolResult.error(
                        null,
                        "UNKNOWN",
                        "Unknown error",
                        false
                )
        );

        assertEquals(
                "status must not be null",
                exception.getMessage()
        );
    }

    @Test
    void shouldSerializeSuccessfulResultAsJson() throws Exception {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("totalFiles", 2);

        ToolResult result = ToolResult.success(payload);

        String json = objectMapper.writeValueAsString(result);
        JsonNode root = objectMapper.readTree(json);

        assertEquals(
                "SUCCESS",
                root.get("status").asText()
        );

        assertEquals(
                2,
                root.get("payload").get("totalFiles").asInt()
        );

        assertTrue(root.get("errorCode").isNull());
        assertTrue(root.get("message").isNull());
        assertFalse(root.get("retryable").asBoolean());
        assertFalse(root.get("truncated").asBoolean());
    }

    @Test
    void shouldSerializeErrorResultAsJson() throws Exception {
        ToolResult result = ToolResult.error(
                ToolStatus.INVALID_ARGUMENT,
                "MISSING_FILE_PATH",
                "filePath is required",
                false
        );

        String json = objectMapper.writeValueAsString(result);
        JsonNode root = objectMapper.readTree(json);

        assertEquals(
                "INVALID_ARGUMENT",
                root.get("status").asText()
        );

        assertTrue(root.get("payload").isNull());

        assertEquals(
                "MISSING_FILE_PATH",
                root.get("errorCode").asText()
        );

        assertEquals(
                "filePath is required",
                root.get("message").asText()
        );

        assertFalse(root.get("retryable").asBoolean());
        assertFalse(root.get("truncated").asBoolean());
    }
}
