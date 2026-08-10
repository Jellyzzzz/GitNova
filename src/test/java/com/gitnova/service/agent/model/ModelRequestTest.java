package com.gitnova.service.agent.model;

import com.gitnova.dto.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelRequestTest {

    @Test
    void shouldDefensivelyCopyMessagesAndTools() {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(systemMessage());
        List<ToolDefinition> tools = new ArrayList<>();

        ModelRequest request = new ModelRequest(
                "deepseek-chat",
                messages,
                tools,
                1_024,
                0.2d,
                "request-1"
        );
        messages.clear();
        tools.clear();

        assertEquals(1, request.messages().size());
        assertEquals(0, request.tools().size());
        assertThrows(UnsupportedOperationException.class, () -> request.messages().add(systemMessage()));
    }

    @Test
    void shouldRejectEmptyConversation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelRequest("model", List.of(), List.of(), null, null, "request-1")
        );
    }

    @Test
    void shouldRejectInvalidGenerationLimits() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelRequest("model", List.of(systemMessage()), List.of(), 0, null, "request-1")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelRequest("model", List.of(systemMessage()), List.of(), null, Double.NaN, "request-1")
        );
    }

    private ModelMessage systemMessage() {
        return new ModelMessage(ModelRole.SYSTEM, "Review code changes.", null, null);
    }
}
