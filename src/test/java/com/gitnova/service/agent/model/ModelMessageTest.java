package com.gitnova.service.agent.model;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.gitnova.dto.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelMessageTest {

    @Test
    void shouldAllowTextOnlyAssistantMessage() {
        ModelMessage message = new ModelMessage(
                ModelRole.ASSISTANT,
                "I will inspect the changed files.",
                null,
                null
        );

        assertEquals(ModelRole.ASSISTANT, message.role());
        assertFalse(message.hasToolCalls());
        assertTrue(message.toolCalls().isEmpty());
    }

    @Test
    void shouldAllowToolOnlyAssistantMessage() {
        ToolCall call = toolCall();

        ModelMessage message = new ModelMessage(
                ModelRole.ASSISTANT,
                null,
                List.of(call),
                null
        );

        assertTrue(message.hasToolCalls());
        assertEquals(List.of(call), message.toolCalls());
    }

    @Test
    void shouldRequireAssistantTextOrToolCall() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelMessage(ModelRole.ASSISTANT, " ", null, null)
        );
    }

    @Test
    void shouldRequireToolCallIdForToolResult() {
        assertThrows(
                NullPointerException.class,
                () -> new ModelMessage(ModelRole.TOOL, "{\"files\":[]}", null, null)
        );
    }

    @Test
    void shouldRejectToolCallsOnSystemMessageInsteadOfSilentlyDroppingThem() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelMessage(
                        ModelRole.SYSTEM,
                        "Review only the supplied revisions.",
                        List.of(toolCall()),
                        null
                )
        );
    }

    @Test
    void shouldRejectToolCallIdOnAssistantMessage() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelMessage(
                        ModelRole.ASSISTANT,
                        "Checking the file.",
                        null,
                        "call-1"
                )
        );
    }

    private ToolCall toolCall() {
        return new ToolCall(
                "call-1",
                "readFile",
                JsonNodeFactory.instance.objectNode()
        );
    }
}
