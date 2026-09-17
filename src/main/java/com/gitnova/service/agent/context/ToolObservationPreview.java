package com.gitnova.service.agent.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.storage.artifact.ArtifactRef;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic model-visible previews. Does not decide when to externalize or mutate ToolResult. */
@Component
public final class ToolObservationPreview {
    private static final Map<String, List<String>> FIELDS = Map.of(
            "runCommand", List.of("stdout", "stderr"),
            "getWorkspaceDiff", List.of("unifiedDiff", "files"),
            "searchText", List.of("matches"),
            "readFile", List.of("lines"),
            "getDiff", List.of("hunks"),
            "listFiles", List.of("entries"),
            "findFiles", List.of("paths"),
            "listChanges", List.of("files")
    );
    private final ObjectMapper objectMapper;
    private final TokenEstimator tokenEstimator;

    public ToolObservationPreview(ObjectMapper objectMapper, TokenEstimator tokenEstimator) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
    }

    public boolean supports(String toolName) {
        // In particular, do not externalize readArtifact recursively or hide mutation outcomes.
        return toolName != null && FIELDS.containsKey(toolName);
    }

    /** Count the complete model-visible JSON, including status, errors and projection metadata. */
    public long estimateTokens(JsonNode observation) {
        return tokenEstimator.estimateText(Objects.requireNonNull(observation, "observation").toString()).tokens();
    }

    /** Uses the same full ToolResult serialization as MessageFactory.tool(), not just payload. */
    public long estimateTokens(ToolResult result) {
        Objects.requireNonNull(result, "result");
        JsonNode observation = objectMapper.valueToTree(result);
        return estimateTokens(observation);
    }

    /** Equality fits inline. Tool eligibility and Artifact availability are separate checks. */
    public boolean exceedsInlineBudget(ToolResult result, ObservationPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        return estimateTokens(result) > policy.maxInlineTokens();
    }

    public ObjectNode preview(String toolName, ToolResult result, ArtifactRef artifact, int maxPreviewTokens) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(artifact, "artifact");
        if (!supports(toolName) || maxPreviewTokens <= 0) {
            throw new IllegalArgumentException("Unsupported tool or invalid preview budget");
        }
        ObjectNode observation = objectMapper.valueToTree(result);
        if (!(observation.get("payload") instanceof ObjectNode payload)) {
            throw new IllegalArgumentException("A tool preview requires an object payload");
        }
        ObjectNode metadata = observation.putObject("externalization");
        metadata.set("artifact", objectMapper.valueToTree(artifact));
        ArrayNode previewedFields = metadata.putArray("previewedFields");
        ObjectNode capturedCounts = metadata.putObject("capturedCounts");

        while (estimateTokens(observation) > maxPreviewTokens) {
            String largestField = null;
            long largestSize = 0;
            for (String field : FIELDS.get(toolName)) {
                JsonNode value = payload.get(field);
                if (value == null || (value.isTextual() && value.textValue().isEmpty())
                        || (value.isArray() && value.isEmpty())
                        || (!value.isTextual() && !value.isArray())) continue;
                long size = estimateTokens(value);
                if (size > largestSize) {
                    largestField = field;
                    largestSize = size;
                }
            }
            if (largestField == null) {
                throw new IllegalArgumentException("Required observation fields exceed the preview budget");
            }
            String pointer = "/payload/" + largestField;
            if (!capturedCounts.has(pointer)) {
                JsonNode original = payload.get(largestField);
                previewedFields.add(pointer);
                // Counts describe captured entries, not a fabricated total number of search matches.
                capturedCounts.put(pointer, original.isArray() ? original.size()
                        : original.textValue().codePointCount(0, original.textValue().length()));
            }
            JsonNode value = payload.get(largestField);
            if (value instanceof ArrayNode entries) {
                int keep = entries.size() / 2;
                while (entries.size() > keep) entries.remove(entries.size() - 1);
            } else {
                String text = value.textValue();
                int keep = text.codePointCount(0, text.length()) / 2;
                // Retain both ends: command failure summaries are often at the tail, not the head.
                String shortened = keep < 8 ? "" : text.substring(0, text.offsetByCodePoints(0, keep / 2))
                        + "\n…\n" + text.substring(text.offsetByCodePoints(text.length(), -(keep - keep / 2)));
                payload.put(largestField, shortened);
            }
        }
        return observation;
    }
}
