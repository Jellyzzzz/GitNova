package com.gitnova.service.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolCall;
import com.gitnova.service.agent.prompt.AssembledPrompt;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tools.ArtifactTextReader;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public final class MessageFactory {

    private final ObjectMapper objectMapper;

    public MessageFactory(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /** Creates the server-controlled system instruction and the explicit user task. */
    public List<ModelMessage> initialMessages(AssembledPrompt prompt, String taskText) {
        Objects.requireNonNull(prompt, "prompt must not be null");
        Objects.requireNonNull(taskText, "taskText must not be null");
        if (taskText.isBlank()) {
            throw new IllegalArgumentException("taskText must not be blank");
        }
        return List.of(
                new ModelMessage(ModelRole.SYSTEM, prompt.systemText(), List.of(), null),
                new ModelMessage(
                        ModelRole.USER,
                        taskText,
                        List.of(),
                        null
                )
        );
    }

    /** Preserves the normalized assistant text and tool calls for the next model request. */
    public ModelMessage assistant(ModelResponse response) {
        Objects.requireNonNull(response, "response must not be null");
        return new ModelMessage(ModelRole.ASSISTANT, response.text(), response.toolCalls(), null,
                response.reasoningContent());
    }

    /** Serializes the complete structured tool result as one observation. */
    public ModelMessage tool(ToolCall toolCall, ToolResult result) {
        return tool(toolCall, result, null, 0);
    }

    /** Adds read-back paths from a committed result's identity, without changing the stored result. */
    public ModelMessage tool(ToolCall toolCall, ToolResult result, String sessionId, long sourceSequence) {
        Objects.requireNonNull(toolCall, "toolCall must not be null");
        Objects.requireNonNull(result, "result must not be null");
        if (sourceSequence < 0 || sourceSequence > 0 && (sessionId == null || sessionId.isBlank())) {
            throw new IllegalArgumentException("Committed source requires a Session and a positive sequence");
        }

        ObjectNode observation = objectMapper.valueToTree(result);
        // Read-back pages already name their original source. Do not issue a new address for a copy.
        boolean historicalRead = "readArtifact".equals(toolCall.name())
                || result.payload().path("historical").asBoolean()
                || "readFile".equals(toolCall.name()) && toolCall.arguments().path("filePath").asText().startsWith("artifact:")
                || "searchText".equals(toolCall.name()) && toolCall.arguments().path("path").asText().startsWith("artifact:");
        if (sourceSequence > 0 && !historicalRead) {
            ObjectNode source = observation.putObject("source")
                    .put("sourceSessionId", sessionId).put("sourceStepSequence", sourceSequence);
            ObjectNode resources = source.putObject("resources");
            for (String view : ArtifactTextReader.views(result.payload()).keySet()) {
                resources.put(view, ArtifactTextReader.SESSION_RESULTS_PATH + sourceSequence + "/" + view);
            }
            source.put("readHint", "Already shown inline. These paths read the captured result in this Session; "
                    + "use them only for missing details or later recall.");
        }
        return new ModelMessage(ModelRole.TOOL, observation.toString(), List.of(), toolCall.id());
    }

    /** Keep integrity metadata in the journal, but use short Session-scoped paths in new observations. */
    public static JsonNode modelVisibleObservation(JsonNode observation) {
        if (!observation.path("externalization").path("resources").isObject()) return observation;
        ObjectNode visible = observation.deepCopy();
        ((ObjectNode) visible.get("externalization")).remove("artifact");
        return visible;
    }

    /** Wraps an already prepared/durably recorded model-visible projection; performs no storage I/O. */
    public ModelMessage toolObservation(ToolCall toolCall, JsonNode observation) {
        Objects.requireNonNull(toolCall, "toolCall");
        Objects.requireNonNull(observation, "observation");
        if (!observation.isObject()) throw new IllegalArgumentException("Observation must be an object");
        return new ModelMessage(ModelRole.TOOL, modelVisibleObservation(observation).toString(), List.of(), toolCall.id());
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
