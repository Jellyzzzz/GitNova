package com.gitnova.service.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolCall;
import com.gitnova.service.agent.model.MessageFactory;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.storage.artifact.ArtifactRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolObservationPreviewTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final TokenEstimator tokenEstimator = new TokenEstimator();
    private final ToolObservationPreview renderer = new ToolObservationPreview(mapper, tokenEstimator);
    private final ArtifactRef ref = new ArtifactRef("a".repeat(64), "a".repeat(64), 20000, "application/json");

    @Test
    void shouldCountTheExactFullToolMessageContentIncludingErrorMetadata() {
        var result = ToolResult.error(ToolStatus.INVALID_ARGUMENT, "INVALID_RANGE",
                "范围无效；check startLine and endLine. ".repeat(100), false);
        var message = new MessageFactory(mapper).tool(
                new ToolCall("call-1", "readFile", mapper.createObjectNode()), result);
        assertEquals(tokenEstimator.estimateText(message.content()).tokens(), renderer.estimateTokens(result));
        assertTrue(renderer.estimateTokens(result) > tokenEstimator.estimateText("null").tokens());
    }

    @Test
    void shouldOnlyExceedInlineBudgetAboveTheThreshold() {
        var result = ToolResult.success(mapper.createObjectNode().put("stdout", "test output ".repeat(100)));
        int estimate = Math.toIntExact(renderer.estimateTokens(result));
        assertFalse(renderer.exceedsInlineBudget(result, new ObservationPolicy(estimate + 1, 1)));
        assertFalse(renderer.exceedsInlineBudget(result, new ObservationPolicy(estimate, 1)));
        assertTrue(renderer.exceedsInlineBudget(result, new ObservationPolicy(estimate - 1, 1)));
    }

    @Test
    void shouldCountJsonEnvelopeEvenWhenPayloadIsEmpty() {
        var result = ToolResult.success(mapper.createObjectNode());
        assertTrue(renderer.estimateTokens(result) > tokenEstimator.estimateText("{}").tokens());
    }

    @Test
    void shouldIncludeArtifactAndPreviewMetadataInPreviewBudget() {
        var result = ToolResult.success(mapper.createObjectNode().put("stdout", "log line\n".repeat(5000)));
        var preview = renderer.preview("runCommand", result, ref, 400);
        assertTrue(renderer.estimateTokens(preview) <= 400);
        assertTrue(renderer.estimateTokens(preview) > renderer.estimateTokens(preview.path("payload")));
        assertTrue(preview.at("/payload/stdout").asText().length() < result.payload().path("stdout").asText().length());
    }

    @Test
    void shouldKeepCommandOutcomeAndBothEndsWithoutChangingOriginal() {
        ObjectNode payload = mapper.createObjectNode().put("exitCode", 1).put("generationAfter", 9)
                .put("stdout", "START\n" + "中文🧪".repeat(1000) + "\nEND")
                .put("stderr", "FAIL: assertion did not hold").put("timedOut", false);
        var result = ToolResult.success(payload, true);
        var original = mapper.valueToTree(result);
        var preview = renderer.preview("runCommand", result, ref, 1800);

        assertTrue(renderer.estimateTokens(preview) <= 1800);
        assertEquals(original, mapper.valueToTree(result));
        assertEquals(1, preview.at("/payload/exitCode").asInt());
        assertEquals(9, preview.at("/payload/generationAfter").asInt());
        assertTrue(preview.path("truncated").asBoolean());
        assertTrue(preview.at("/payload/stdout").asText().startsWith("START"));
        assertTrue(preview.at("/payload/stdout").asText().endsWith("END"));
        assertEquals(ref.artifactId(), preview.at("/externalization/artifact/artifactId").asText());
        assertEquals("/payload/stdout", preview.at("/externalization/previewedFields/0").asText());
    }

    @Test
    void shouldKeepCompleteSearchEntriesAndTruthfulCapturedCounts() {
        ObjectNode payload = mapper.createObjectNode().put("generation", 5).put("hasMore", true);
        var matches = payload.putArray("matches");
        for (int index = 0; index < 30; index++) {
            matches.addObject().put("filePath", "src/File" + index + ".java")
                    .put("line", index + 1).put("text", "line content ".repeat(10));
        }
        var result = ToolResult.success(payload);
        int previewBudget = Math.toIntExact(renderer.estimateTokens(result)) / 2;
        var preview = renderer.preview("searchText", result, ref, previewBudget);
        assertTrue(renderer.estimateTokens(preview) <= previewBudget);
        assertTrue(preview.at("/payload/matches").size() < 30);
        assertEquals(30, preview.at("/externalization/capturedCounts").path("/payload/matches").asInt());
        for (var entry : preview.at("/payload/matches")) {
            assertEquals(matches.get(entry.path("line").asInt() - 1), entry);
        }
        assertEquals(5, preview.at("/payload/generation").asInt());
        assertTrue(preview.at("/payload/hasMore").asBoolean());
        assertFalse(preview.path("truncated").asBoolean()); // Projection omission is a separate field.
    }

    @Test
    void shouldPreserveDiffTotalsAndGenerationWhileShorteningDiffBody() {
        var payload = mapper.createObjectNode().put("generation", 7)
                .put("totalAddedLines", 100).put("totalDeletedLines", 50).put("totalHunks", 30)
                .put("containsBinary", false).put("unifiedDiff", "--- old\n+++ new\n" + "+added\n".repeat(1000));
        payload.putArray("files").addObject().put("filePath", "src/Main.java").put("changeType", "MODIFIED");
        var preview = renderer.preview("getWorkspaceDiff", ToolResult.success(payload), ref, 1400);
        assertTrue(renderer.estimateTokens(preview) <= 1400);
        assertEquals(7, preview.at("/payload/generation").asInt());
        assertEquals(100, preview.at("/payload/totalAddedLines").asInt());
        assertEquals(50, preview.at("/payload/totalDeletedLines").asInt());
        assertEquals(30, preview.at("/payload/totalHunks").asInt());
        assertEquals(payload.path("files"), preview.at("/payload/files"));
    }

    @Test
    void shouldNotHidePatchOutcomesOrRecursivelyExternalizeArtifactReads() {
        assertFalse(renderer.supports("applyPatch"));
        assertFalse(renderer.supports("finishTask"));
        assertFalse(renderer.supports("readArtifact"));
        assertThrows(IllegalArgumentException.class, () -> renderer.preview("applyPatch",
                ToolResult.success(mapper.createObjectNode()), ref, 1000));
    }

    @Test
    void shouldFailIfRequiredFieldsCannotFitRatherThanDropThem() {
        var result = ToolResult.success(mapper.createObjectNode().put("generation", 3).put("stdout", "abc"));
        assertThrows(IllegalArgumentException.class, () -> renderer.preview("runCommand", result, ref, 10));
        assertEquals(3, result.payload().path("generation").asInt());
    }
}
