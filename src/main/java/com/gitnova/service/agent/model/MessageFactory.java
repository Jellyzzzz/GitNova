package com.gitnova.service.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.dto.ToolCall;
import com.gitnova.service.agent.prompt.AssembledPrompt;
import com.gitnova.service.agent.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public final class MessageFactory {

    private final ObjectMapper objectMapper;

    public MessageFactory(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /** Creates the server-controlled system instruction and initial review request. */
    public List<ModelMessage> initialMessages(AssembledPrompt prompt) {
        Objects.requireNonNull(prompt, "prompt must not be null");
        return List.of(
                new ModelMessage(ModelRole.SYSTEM, prompt.systemText(), List.of(), null),
                new ModelMessage(
                        ModelRole.USER,
                        "Begin the server-authorized code review. Follow the system workflow and start with listChanges.",
                        List.of(),
                        null
                )
        );
    }

    /** Preserves the normalized assistant text and tool calls for the next model request. */
    public ModelMessage assistant(ModelResponse response) {
        Objects.requireNonNull(response, "response must not be null");
        return new ModelMessage(ModelRole.ASSISTANT, response.text(), response.toolCalls(), null);
    }

    /** Serializes the complete structured tool result as one observation. */
    public ModelMessage tool(ToolCall toolCall, ToolResult result) {
        Objects.requireNonNull(toolCall, "toolCall must not be null");
        Objects.requireNonNull(result, "result must not be null");

        JsonNode observation = objectMapper.valueToTree(result);
        return new ModelMessage(ModelRole.TOOL, observation.toString(), List.of(), toolCall.id());
    }

    /**
     * Adds Harness-generated protocol or verifier feedback that has no preceding tool call.
     * It uses USER because a TOOL message would require a real toolCallId.
     */
    public ModelMessage harnessFeedback(String text) {
        Objects.requireNonNull(text, "text must not be null");
        return new ModelMessage(
                ModelRole.USER,
                "<runtime_feedback>\n" + text + "\n</runtime_feedback>",
                List.of(),
                null
        );
    }
}
