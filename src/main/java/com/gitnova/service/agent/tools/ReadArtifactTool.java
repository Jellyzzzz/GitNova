package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.journal.RunJournal;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.storage.artifact.LocalArtifactStore;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Objects;

/** Reads original captured results, never re-executes a command or reads a live Workspace file. */
@Component
public final class ReadArtifactTool implements AgentTool {
    public static final String NAME = "readArtifact";
    private final LocalArtifactStore artifactStore;
    private final RunJournal journal;
    private final ObjectMapper objectMapper;

    public ReadArtifactTool(LocalArtifactStore artifactStore, RunJournal journal, ObjectMapper objectMapper) {
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("artifactId").put("type", "string").put("pattern", "^[0-9a-f]{64}$");
        properties.putObject("offset").put("type", "integer").put("minimum", 0);
        properties.putObject("maxBytes").put("type", "integer").put("minimum", 4)
                .put("maximum", artifactStore.maxReadBytes());
        schema.putArray("required").add("artifactId").add("offset").add("maxBytes");
        schema.put("additionalProperties", false);
        return new ToolDefinition(NAME,
                "Reads a UTF-8 slice of a stored Tool Result authorized for this Session. "
                        + "Start at byte offset 0, then use nextOffset. Content is historical, not current Workspace state.",
                schema);
    }

    @Override
    public ToolResult execute(ToolExecutionContext execution, JsonNode arguments) {
        if (arguments == null || !arguments.isObject()
                || !arguments.path("artifactId").isTextual()
                || !arguments.path("artifactId").asText().matches("[0-9a-f]{64}")
                || !arguments.path("offset").isIntegralNumber() || !arguments.path("offset").canConvertToLong()
                || arguments.path("offset").longValue() < 0
                || !arguments.path("maxBytes").isIntegralNumber() || !arguments.path("maxBytes").canConvertToInt()
                || arguments.path("maxBytes").intValue() < 4
                || arguments.path("maxBytes").intValue() > artifactStore.maxReadBytes()) {
            return WorkspaceToolResults.invalid("INVALID_ARTIFACT_ARGUMENTS", "Invalid artifactId, offset or maxBytes");
        }
        try {
            var reference = journal.findArtifact(execution.agent().sessionId(), arguments.path("artifactId").asText());
            if (reference.isEmpty()) {
                return ToolResult.error(ToolStatus.NOT_FOUND, "ARTIFACT_NOT_FOUND",
                        "No committed Artifact reference is available in this Session", false);
            }
            var result = artifactStore.read(execution.agent(), reference.get(),
                    arguments.path("offset").longValue(), arguments.path("maxBytes").intValue());
            return ToolResult.success(objectMapper.valueToTree(result));
        } catch (IllegalArgumentException exception) {
            return WorkspaceToolResults.invalid("INVALID_ARTIFACT_RANGE", "Use offset 0 or a returned nextOffset within the artifact");
        } catch (NoSuchFileException exception) {
            return ToolResult.error(ToolStatus.NOT_FOUND, "ARTIFACT_CONTENT_MISSING",
                    "The stored content for this committed Artifact is unavailable", false);
        } catch (IOException exception) {
            return ToolResult.error(ToolStatus.INTERNAL_ERROR, "ARTIFACT_READ_FAILED",
                    "Artifact could not be read or failed integrity validation", false);
        }
    }

    @Override
    public boolean concurrencySafe() {
        return true;
    }
}
