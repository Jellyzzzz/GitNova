package com.gitnova.service.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.gitnova.dto.ToolCall;
import com.gitnova.service.agent.prompt.AssembledPrompt;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MessageFactory factory = new MessageFactory(objectMapper);

    @Test
    void shouldCreateSystemAndInitialReviewRequest() {
        List<ModelMessage> messages = factory.initialMessages(
                new AssembledPrompt("prompt-1", "Review only the authorized change.")
        );

        assertEquals(2, messages.size());
        assertEquals(ModelRole.SYSTEM, messages.get(0).role());
        assertEquals("Review only the authorized change.", messages.get(0).content());
        assertEquals(ModelRole.USER, messages.get(1).role());
        assertTrue(messages.get(1).content().contains("start with listChanges"));
        assertTrue(messages.stream().allMatch(message -> message.toolCalls().isEmpty()));
        assertTrue(messages.stream().allMatch(message -> message.toolCallId() == null));
    }

    @Test
    void shouldPreserveToolOnlyAssistantResponse() {
        ToolCall call = toolCall("call-1", "listChanges");
        ModelResponse response = new ModelResponse(
                "response-1",
                null,
                List.of(call),
                ModelUsage.unknown(),
                ModelFinishReason.TOOL_CALLS
        );

        ModelMessage message = factory.assistant(response);

        assertEquals(ModelRole.ASSISTANT, message.role());
        assertNull(message.content());
        assertEquals(List.of(call), message.toolCalls());
        assertNull(message.toolCallId());
    }

    @Test
    void shouldSerializeCompleteSuccessfulToolResultAndPreserveCallId() throws Exception {
        JsonNode payload = JsonNodeFactory.instance.objectNode().put("totalFiles", 2);

        ModelMessage message = factory.tool(
                toolCall("call-2", "listChanges"),
                ToolResult.success(payload)
        );

        JsonNode observation = objectMapper.readTree(message.content());
        assertEquals(ModelRole.TOOL, message.role());
        assertEquals("call-2", message.toolCallId());
        assertTrue(message.toolCalls().isEmpty());
        assertEquals(ToolStatus.SUCCESS.name(), observation.path("status").asText());
        assertEquals(2, observation.path("payload").path("totalFiles").asInt());
        assertFalse(observation.path("retryable").asBoolean());
        assertFalse(observation.path("truncated").asBoolean());
    }

    @Test
    void shouldSerializeToolErrorMetadata() throws Exception {
        ModelMessage message = factory.tool(
                toolCall("call-3", "readFile"),
                ToolResult.error(ToolStatus.NOT_FOUND, "FILE_NOT_FOUND", "File does not exist", false)
        );

        JsonNode observation = objectMapper.readTree(message.content());
        assertEquals(ToolStatus.NOT_FOUND.name(), observation.path("status").asText());
        assertEquals("FILE_NOT_FOUND", observation.path("errorCode").asText());
        assertEquals("File does not exist", observation.path("message").asText());
        assertFalse(observation.path("retryable").asBoolean());
    }

    @Test
    void shouldSerializePartialStateAndPreserveCallId() throws Exception {
        JsonNode payload = JsonNodeFactory.instance.objectNode()
                .put("generationBefore", 3)
                .put("generationAfter", 4);

        ModelMessage message = factory.tool(
                toolCall("call-patch", "applyPatch"),
                ToolResult.partialSuccess(
                        payload,
                        "PATCH_OPERATION_FAILED",
                        "A confirmed prefix was applied before the patch failed"
                )
        );

        JsonNode observation = objectMapper.readTree(message.content());
        assertEquals(ModelRole.TOOL, message.role());
        assertEquals("call-patch", message.toolCallId());
        assertEquals(
                ToolStatus.PARTIAL_SUCCESS.name(),
                observation.path("status").asText()
        );
        assertEquals(4, observation.path("payload").path("generationAfter").asInt());
        assertEquals(
                "PATCH_OPERATION_FAILED",
                observation.path("errorCode").asText()
        );
        assertFalse(observation.path("retryable").asBoolean());
    }

    @Test
    void shouldCreateTaggedHarnessFeedbackWithoutToolBinding() {
        ModelMessage message = factory.harnessFeedback("Call finalizeReview alone after evidence gathering.");

        assertEquals(ModelRole.USER, message.role());
        assertTrue(message.content().startsWith("<runtime_feedback>"));
        assertTrue(message.content().contains("Call finalizeReview alone"));
        assertTrue(message.content().endsWith("</runtime_feedback>"));
        assertTrue(message.toolCalls().isEmpty());
        assertNull(message.toolCallId());
    }

    private static ToolCall toolCall(String id, String name) {
        return new ToolCall(id, name, JsonNodeFactory.instance.objectNode());
    }
}
