package com.gitnova.service.agent.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.model.MessageFactory;
import com.gitnova.service.agent.model.ModelMessage;
import com.gitnova.service.agent.tools.ArtifactTextReader;
import com.gitnova.storage.artifact.ArtifactRef;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;

/** Deterministic model-visible previews. Does not decide when to externalize or mutate ToolResult. */
@Component
public final class ToolObservationPreview {
    // Hints for exact excerpt selection, never classifiers of command/task success.
    private static final Pattern DIAGNOSTIC = Pattern.compile("(?i)\\b(FAIL(?:ED|URE)?|ERROR|EXCEPTION|FATAL|PANIC|ASSERTION)\\b");
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
        return tokenEstimator.estimateText(MessageFactory.modelVisibleObservation(
                Objects.requireNonNull(observation, "observation")).toString()).tokens();
    }

    /** Uses the same full ToolResult serialization as MessageFactory.tool(), not just payload. */
    public long estimateTokens(ToolResult result) {
        Objects.requireNonNull(result, "result");
        JsonNode observation = objectMapper.valueToTree(result);
        return estimateTokens(observation);
    }

    /** Count the prepared message, including source paths. Equality still fits inline. */
    public boolean exceedsInlineBudget(ModelMessage observation, ObservationPolicy policy) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(policy, "policy");
        return policy.externalizationEnabled()
                && tokenEstimator.estimateText(observation.content()).tokens() > policy.maxInlineTokens();
    }

    public ObjectNode preview(String toolName, ToolResult result, ArtifactRef artifact, int maxPreviewTokens) {
        return preview(toolName, result, artifact, maxPreviewTokens, null, 0);
    }

    public ObjectNode preview(String toolName, ToolResult result, ArtifactRef artifact, int maxPreviewTokens,
                              String sessionId, long sourceSequence) {
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
        if (sourceSequence > 0) {
            if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("Source Session is required");
            metadata.put("sourceSessionId", sessionId).put("sourceStepSequence", sourceSequence);
            ObjectNode resources = metadata.putObject("resources");
            for (String view : ArtifactTextReader.views(payload).keySet()) {
                resources.put(view, "artifact://tool-results/" + sourceSequence + "/" + view);
            }
            metadata.put("readHint", "readFile(filePath=resource,startLine=1,endLine=100), no revision; follow nextRequest. Historical captured content only.");
        }
        metadata.put("previewOnly", true).put("previewFormat", "line-excerpts");
        ArrayNode previewedFields = metadata.putArray("previewedFields");
        ObjectNode capturedCounts = metadata.putObject("capturedCounts");
        ObjectNode omittedCounts = metadata.putObject("omittedCounts");
        Map<String, List<String>> originalLines = new HashMap<>();
        Map<String, List<Integer>> priority = new HashMap<>();
        Map<String, Integer> selectedCounts = new HashMap<>();

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
                        : original.textValue().lines().count());
            }
            JsonNode value = payload.get(largestField);
            if (value instanceof ArrayNode entries) {
                int keep = entries.size() / 2;
                while (entries.size() > keep) entries.remove(entries.size() - 1);
                omittedCounts.put(pointer, capturedCounts.path(pointer).asInt() - keep);
            } else {
                if (!originalLines.containsKey(largestField)) {
                    List<String> lines = value.textValue().lines().toList();
                    originalLines.put(largestField, lines);
                    var order = new LinkedHashSet<Integer>();
                    if (toolName.equals("runCommand")) {
                        for (int i = 0; i < lines.size(); i++) {
                            if (DIAGNOSTIC.matcher(lines.get(i)).find()) order.add(i);
                        }
                        // Candidate lines first, then head/tail, then adjacent context. Deduplicate overlaps.
                        var diagnosticLines = new ArrayList<>(order);
                        if (!lines.isEmpty()) { order.add(0); order.add(lines.size() - 1); }
                        for (int i : diagnosticLines) {
                            if (i > 0) order.add(i - 1);
                            if (i + 1 < lines.size()) order.add(i + 1);
                        }
                    }
                    // Diff uses an exact prefix: headers precede body, never a tail detached from its hunk/file.
                    for (int i = 0; i < lines.size(); i++) order.add(i);
                    if (toolName.equals("runCommand")) {
                        // A giant single line must not crowd out every other diagnostic. It remains readable by cursor.
                        order.removeIf(i -> tokenEstimator.estimateText(lines.get(i)).tokens() > maxPreviewTokens / 2);
                    }
                    priority.put(largestField, new ArrayList<>(order));
                    selectedCounts.put(largestField, lines.size());
                    ObjectNode facts = metadata.withObject("/text").putObject(pointer);
                    facts.put("capturedLines", lines.size())
                            .put("capturedBytes", value.textValue().getBytes(StandardCharsets.UTF_8).length)
                            .put("captureTruncated", result.payload().path(largestField + "Truncated").asBoolean(result.truncated()));
                }
                List<String> lines = originalLines.get(largestField);
                int previousCount = selectedCounts.get(largestField);
                int keep = Math.min(priority.get(largestField).size(), Math.min(128, previousCount > 1 ? (previousCount + 1) / 2 : 0));
                if (largestField.equals("unifiedDiff")) {
                    // End before a new hunk, not midway through one. If the first hunk cannot fit,
                    // show only its file/hunk headers and let the model follow the full diff resource.
                    int firstHeader = -1;
                    int completeBoundary = 0;
                    for (int i = 0; i < keep; i++) {
                        if (lines.get(i).startsWith("@@ ")) {
                            if (firstHeader < 0) firstHeader = i;
                            else completeBoundary = i;
                        }
                    }
                    keep = completeBoundary > 0 ? completeBoundary : firstHeader < 0 ? 0 : firstHeader + 1;
                }
                selectedCounts.put(largestField, keep);
                List<Integer> selected = priority.get(largestField).subList(0, keep).stream().sorted().toList();
                StringBuilder excerpt = new StringBuilder();
                for (int line : selected) {
                    if (!excerpt.isEmpty()) excerpt.append('\n');
                    excerpt.append('L').append(line + 1).append(' ').append(lines.get(line));
                }
                payload.put(largestField, excerpt.toString());
                ((ObjectNode) metadata.path("text").path(pointer)).put("displayedLines", keep)
                        .put("omittedLines", lines.size() - keep);
                omittedCounts.put(pointer, lines.size() - keep);
            }
        }
        return observation;
    }
}
