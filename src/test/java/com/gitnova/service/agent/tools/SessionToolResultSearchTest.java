package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.entity.agent.AgentStepEntity;
import com.gitnova.mapper.agent.AgentStepMapper;
import com.gitnova.service.agent.AgentTestContexts;
import com.gitnova.service.agent.context.TokenEstimator;
import com.gitnova.service.agent.journal.DefaultRunJournal;
import com.gitnova.service.agent.journal.ToolResultPayload;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.runtime.AgentExecutionContext;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.storage.artifact.LocalArtifactStore;
import com.gitnova.storage.config.ArtifactStorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Journal decoding and text views; SQL transport is mocked, not a live-MySQL test. */
class SessionToolResultSearchTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentStepMapper steps = mock(AgentStepMapper.class);
    private final List<AgentStepEntity> history = new ArrayList<>();
    private final TokenEstimator tokens = new TokenEstimator();
    private final AgentExecutionContext context = AgentTestContexts.agent(
            new AgentRunContext("run", 2L, "1/2", "a".repeat(40), "b".repeat(40)));
    private ArtifactTextReader reader;
    private LocalArtifactStore store;
    private final WorkspaceGateway workspace = mock(WorkspaceGateway.class);
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        store = new LocalArtifactStore(new ArtifactStorageProperties(root, 2 * 1024 * 1024, 4096), mapper);
        reader = new ArtifactTextReader(store,
                new DefaultRunJournal(mock(AgentEventAppender.class), mapper, steps), mapper, tokens);
        registry = new ToolRegistry(List.of(new SearchTextTool(workspace, mapper, reader)));
        when(steps.latestSessionSequence(context.sessionId())).thenAnswer(call ->
                history.stream().mapToLong(AgentStepEntity::getSessionSequence).max().orElse(0));
        when(steps.selectToolResultHistory(anyString(), anyLong(), anyLong(), anyInt())).thenAnswer(call ->
                history.stream().filter(row -> row.getSessionId().equals(call.getArgument(0))
                                && "TOOL_RESULT".equals(row.getStepType())
                                && row.getSessionSequence() > (long) call.getArgument(1)
                                && row.getSessionSequence() <= (long) call.getArgument(2))
                        .limit((int) call.getArgument(3)).toList());
    }

    @Test
    void findsInlineResultBeforeSummaryAndReadsItsOriginalTextWithoutCreatingAnArtifact() throws Exception {
        add(85, "investigation", "runCommand", mapper.createObjectNode().put("stdout", "header\nFAIL cases=37\n")
                .put("stderr", "").put("generationAfter", 0));
        add(150, "repair", "runCommand", mapper.createObjectNode().put("stdout", "FAIL cases=10\n").put("generationAfter", 3));
        AgentStepEntity summary = new AgentStepEntity();
        summary.setSessionId(context.sessionId());
        summary.setSessionSequence(300L);
        summary.setStepType("CONTEXT_SUMMARY_CREATED");
        summary.setPayloadJson("{\"content\":\"Only the later failure is remembered\"}");
        history.add(summary);

        ToolResult result = search(query("cases=37"));
        assertEquals(ToolStatus.SUCCESS, result.status());
        assertTrue(result.payload().path("searchComplete").asBoolean());
        JsonNode hit = result.payload().path("matches").get(0);
        assertEquals("FAIL cases=37", hit.path("content").asText());
        assertEquals(85, hit.path("sourceStepSequence").asInt());
        assertEquals("investigation", hit.path("sourceTaskId").asText());
        assertEquals(0, hit.path("sourceGeneration").asInt());
        assertEquals("artifact://tool-results/85/stdout.txt", hit.path("originRef").asText());
        var read = reader.read(AgentTestContexts.toolExecution(context, 1, "read"), hit.path("viewPath").asText(), hit.path("readRequest"));
        assertEquals("FAIL cases=37", read.payload().path("lines").get(0).path("content").asText());
        assertEquals("investigation", read.payload().path("sourceTaskId").asText());
        assertTrue(read.payload().path("historical").asBoolean());
        verifyNoInteractions(workspace);
        try (var files = Files.walk(root)) { assertEquals(0, files.filter(Files::isRegularFile).count()); }
    }

    @Test
    void paginatesMatchesWithoutLossOrDuplicatesAndWithinTheCompleteOutputBudget() throws Exception {
        add(10, "first", "runCommand", mapper.createObjectNode().put("stdout", "FAIL literal.* 中文🧪\n".repeat(90)));
        JsonNode args = query("literal.*");
        var lines = new HashSet<Integer>();
        var cursors = new HashSet<String>();
        int pages = 0;
        do {
            ToolResult result = search(args);
            assertEquals(ToolStatus.SUCCESS, result.status(), result.message());
            assertTrue(tokens.estimateText(mapper.valueToTree(result).toString()).tokens() <= ArtifactTextReader.MAX_PAGE_TOKENS);
            for (JsonNode hit : result.payload().path("matches")) {
                assertEquals("FAIL literal.* 中文🧪", hit.path("content").asText());
                assertTrue(lines.add(hit.path("lineNumber").asInt()));
                assertTrue(hit.path("sourceGeneration").isNull()); // Never borrowed from the current Workspace.
            }
            args = result.payload().path("nextRequest");
            if (!args.isMissingNode()) assertTrue(cursors.add(args.path("cursor").asText()));
            assertTrue(++pages < 100);
        } while (!args.isMissingNode());
        assertEquals(90, lines.size());
        assertTrue(pages > 1);
    }

    @Test
    void automaticallyScansSeveralBatchesInOneToolCall() throws Exception {
        for (int i = 1; i <= 45; i++) add(i * 2L, "task", "runCommand",
                mapper.createObjectNode().put("stdout", i == 45 ? "NEEDLE" : "nothing"));

        var result = search(query("NEEDLE"));

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertTrue(result.payload().path("searchComplete").asBoolean());
        assertFalse(result.payload().has("nextRequest"));
        assertEquals(45, result.payload().path("scannedResults").asInt());
        assertEquals(45, result.payload().path("scannedLines").asInt());
        assertEquals(1, result.payload().path("matches").size());
        assertEquals(90, result.payload().path("matches").get(0).path("sourceStepSequence").asInt());
        verify(steps).selectToolResultHistory(context.sessionId(), 0, 90, 21);
        verify(steps).selectToolResultHistory(context.sessionId(), 40, 90, 21);
        verify(steps).selectToolResultHistory(context.sessionId(), 80, 90, 21);
        verify(steps, times(1)).latestSessionSequence(context.sessionId());
    }

    @Test
    void internalBatchesKeepTheInitialHistoryBoundaryDuringConcurrentAppend() throws Exception {
        for (int i = 1; i <= 45; i++) add(i, "task", "runCommand",
                mapper.createObjectNode().put("stdout", i == 45 ? "NEEDLE" : "nothing"));
        doAnswer(call -> {
            add(46, "later", "runCommand", mapper.createObjectNode().put("stdout", "NEEDLE"));
            return history.stream().filter(row -> row.getSessionSequence() > 20 && row.getSessionSequence() <= 45)
                    .limit(21).toList();
        }).when(steps).selectToolResultHistory(context.sessionId(), 20, 45, 21);

        var result = search(query("NEEDLE"));

        assertEquals(46, history.size());
        assertTrue(result.payload().path("searchComplete").asBoolean());
        assertEquals(45, result.payload().path("throughSessionSequence").asInt());
        assertEquals(45, result.payload().path("scannedResults").asInt());
        assertEquals(1, result.payload().path("matches").size());
        assertEquals(45, result.payload().path("matches").get(0).path("sourceStepSequence").asInt());
        verify(steps, times(1)).latestSessionSequence(context.sessionId());
    }

    @Test
    void exactBatchAndScanBoundariesFinishWithoutAnUnnecessaryContinuation() throws Exception {
        for (int size : new int[]{20, 40, 200}) {
            history.clear();
            clearInvocations(steps);
            for (int i = 1; i <= size; i++) add(i, "task", "runCommand",
                    mapper.createObjectNode().put("stdout", ""));

            var result = search(query("NEEDLE"));

            assertEquals(ToolStatus.SUCCESS, result.status());
            assertTrue(result.payload().path("searchComplete").asBoolean());
            assertFalse(result.payload().path("hasMore").asBoolean());
            assertFalse(result.payload().has("nextRequest"));
            assertEquals(size, result.payload().path("scannedResults").asInt());
            verify(steps, times(size / 20)).selectToolResultHistory(eq(context.sessionId()), anyLong(), eq((long) size), eq(21));
        }
    }

    @Test
    void scanLimitReturnsContinuationEvenWithoutMatchesAndExcludesConcurrentAppends() throws Exception {
        for (int i = 1; i <= 203; i++) add(i, "task", "runCommand",
                mapper.createObjectNode().put("stdout", i == 203 ? "NEEDLE" : "nothing"));
        var first = search(query("NEEDLE"));
        assertTrue(first.payload().path("matches").isEmpty());
        assertFalse(first.payload().path("searchComplete").asBoolean());
        assertEquals(200, first.payload().path("scannedResults").asInt());
        verify(steps, times(10)).selectToolResultHistory(eq(context.sessionId()), anyLong(), eq(203L), eq(21));
        add(204, "later", "runCommand", mapper.createObjectNode().put("stdout", "NEEDLE"));
        var second = search(first.payload().path("nextRequest"));
        assertTrue(second.payload().path("searchComplete").asBoolean());
        assertEquals(203, second.payload().path("throughSessionSequence").asInt());
        assertEquals(3, second.payload().path("scannedResults").asInt());
        assertEquals(1, second.payload().path("matches").size());
        assertEquals(203, second.payload().path("matches").get(0).path("sourceStepSequence").asInt());
        verify(steps).selectToolResultHistory(context.sessionId(), 200, 203, 21);
        verify(steps, times(1)).latestSessionSequence(context.sessionId());
    }

    @Test
    void readBackOnlyBatchesStillConsumeTheScanBudget() throws Exception {
        for (int i = 1; i <= 200; i++) add(i, "reread", "readFile",
                mapper.createObjectNode().put("historical", true).put("content", "NEEDLE copied"));
        add(201, "original", "runCommand", mapper.createObjectNode().put("stdout", "NEEDLE original"));

        var first = search(query("NEEDLE"));
        assertTrue(first.payload().path("matches").isEmpty());
        assertTrue(first.payload().path("hasMore").asBoolean());
        assertEquals(200, first.payload().path("scannedResults").asInt());
        assertEquals(0, first.payload().path("scannedLines").asInt());
        verify(steps, never()).selectArtifactProjectionBySource(anyString(), anyLong());

        var second = search(first.payload().path("nextRequest"));
        assertTrue(second.payload().path("searchComplete").asBoolean());
        assertEquals(1, second.payload().path("matches").size());
        assertEquals(201, second.payload().path("matches").get(0).path("sourceStepSequence").asInt());
    }

    @Test
    void outputPaginationAcrossInternalBatchesDoesNotLoseOrDuplicateMatches() throws Exception {
        var expected = new HashSet<String>();
        for (int i = 1; i <= 120; i++) {
            boolean hit = i % 20 == 0;
            add(i, "task", "runCommand", mapper.createObjectNode().put("stdout", hit ? "FAIL\n".repeat(12) : "nothing"));
            if (hit) for (int line = 1; line <= 12; line++) expected.add(i + ":" + line);
        }
        var found = new HashSet<String>();
        var cursors = new HashSet<String>();
        JsonNode args = query("FAIL");
        int pages = 0;
        do {
            var result = search(args);
            assertEquals(ToolStatus.SUCCESS, result.status(), result.message());
            assertTrue(tokens.estimateText(mapper.valueToTree(result).toString()).tokens() <= ArtifactTextReader.MAX_PAGE_TOKENS);
            for (JsonNode hit : result.payload().path("matches")) {
                assertTrue(found.add(hit.path("sourceStepSequence").asLong() + ":" + hit.path("lineNumber").asInt()));
            }
            args = result.payload().path("nextRequest");
            if (!args.isMissingNode()) assertTrue(cursors.add(args.path("cursor").asText()));
            assertTrue(++pages < 100);
        } while (!args.isMissingNode());
        assertEquals(expected, found);
        assertTrue(pages > 1);
    }

    @Test
    void lineBudgetIsSharedAcrossBatchesInsteadOfResetForEachDatabaseRead() throws Exception {
        for (int i = 1; i <= 20; i++) add(i, "task", "runCommand",
                mapper.createObjectNode().put("stdout", "nothing\n".repeat(500)));
        add(21, "task", "runCommand", mapper.createObjectNode().put("stdout", "NEEDLE"));

        var first = search(query("NEEDLE"));
        assertEquals(10000, first.payload().path("scannedLines").asInt());
        assertEquals(20, first.payload().path("scannedResults").asInt());
        assertTrue(first.payload().path("matches").isEmpty());
        assertTrue(first.payload().path("hasMore").asBoolean());
        verify(steps, times(1)).selectToolResultHistory(eq(context.sessionId()), anyLong(), eq(21L), eq(21));

        var second = search(first.payload().path("nextRequest"));
        assertTrue(second.payload().path("searchComplete").asBoolean());
        assertEquals(21, second.payload().path("matches").get(0).path("sourceStepSequence").asInt());
        assertEquals(1, second.payload().path("matches").get(0).path("lineNumber").asInt());
    }

    @Test
    void longNoMatchScanCanContinueAndFindEvidenceNearTheEnd() throws Exception {
        add(1, "task", "runCommand", mapper.createObjectNode().put("stdout", "unrelated\n".repeat(10000) + "NEEDLE\n"));
        var first = search(query("NEEDLE"));
        assertTrue(first.payload().path("matches").isEmpty());
        assertTrue(first.payload().path("hasMore").asBoolean());
        var second = search(first.payload().path("nextRequest"));
        assertEquals(10001, second.payload().path("matches").get(0).path("lineNumber").asInt());
        assertTrue(second.payload().path("searchComplete").asBoolean());
    }

    @Test
    void skipsHistoricalReadCopiesAndStillSearchesWorkspaceReadResults() throws Exception {
        add(1, "original", "runCommand", mapper.createObjectNode().put("stdout", "FAIL origin"));
        add(2, "reread", "readFile", mapper.createObjectNode().put("historical", true).put("content", "FAIL copied"));
        add(3, "search", "searchText", mapper.createObjectNode().put("historical", true).put("content", "FAIL copied again"));
        add(4, "legacy", "readArtifact", mapper.createObjectNode().put("content", "FAIL legacy"));
        var file = mapper.createObjectNode();
        file.putArray("lines").addObject().put("text", "FAIL in repository file");
        add(5, "read", "readFile", file);
        var result = search(query("FAIL"));
        assertEquals(2, result.payload().path("matches").size());
        assertEquals(1, result.payload().path("matches").get(0).path("sourceStepSequence").asInt());
        assertEquals(5, result.payload().path("matches").get(1).path("sourceStepSequence").asInt());
    }

    @Test
    void caseInsensitiveLiteralSearchPreservesCaptureLossAndOriginalLineNumbers() throws Exception {
        add(7, "task", "runCommand", mapper.createObjectNode().put("stdout", "head\nFAIL .*[a]\n")
                .put("stdoutTruncated", true).put("stderr", ""));
        var result = search(query("fail .*[a]").put("caseSensitive", false));
        var hit = result.payload().path("matches").get(0);
        assertEquals(2, hit.path("lineNumber").asInt());
        assertTrue(hit.path("captureTruncated").asBoolean());
        assertFalse(hit.path("completeLine").asBoolean());
        assertTrue(result.payload().path("searchComplete").asBoolean()); // Search completeness != capture completeness.
        assertTrue(search(query("fail .*[a]")).payload().path("matches").isEmpty());
    }

    @Test
    void supportsGenericResultJsonAndEmptyHistory() throws Exception {
        assertTrue(search(query("not there")).payload().path("searchComplete").asBoolean());
        add(3, "task", "applyPatch", mapper.createObjectNode().put("message", "EXACT small result"));
        var hit = search(query("EXACT")).payload().path("matches").get(0);
        assertEquals("artifact://tool-results/3/result.json", hit.path("viewPath").asText());
        assertTrue(hit.path("content").asText().contains("EXACT small result"));
    }

    @Test
    void rejectsUnknownScopeArgumentsAndCrossSessionOrCrossQueryCursor() throws Exception {
        add(10, "task", "runCommand", mapper.createObjectNode().put("stdout", "FAIL\n".repeat(60)));
        var next = (ObjectNode) search(query("FAIL")).payload().path("nextRequest");
        assertNotNull(next);
        assertEquals(ToolStatus.INVALID_ARGUMENT, search(next.deepCopy().put("query", "different")).status());
        assertEquals(ToolStatus.INVALID_ARGUMENT, search(query("FAIL").put("sessionId", "other")).status());
        assertEquals(ToolStatus.INVALID_ARGUMENT, search(query("")).status());
        assertEquals(ToolStatus.INVALID_ARGUMENT, search(query("FAIL").put("cursor", "fake")).status());
        var other = AgentTestContexts.agent(new AgentRunContext("other", 2L, "1/2", "a".repeat(40), "b".repeat(40)));
        assertEquals(ToolStatus.INVALID_ARGUMENT, reader.searchSession(AgentTestContexts.toolExecution(other, 1, "search"), next).status());
        var otherRead = reader.read(AgentTestContexts.toolExecution(other, 1, "read"), "artifact://tool-results/10/stdout.txt",
                mapper.createObjectNode().put("startLine", 1).put("endLine", 10));
        assertEquals(ToolStatus.NOT_FOUND, otherRead.status());
    }

    @Test
    void brokenExternalArtifactNeverFallsBackToTheStillPresentInlineResult() throws Exception {
        add(8, "task", "runCommand", mapper.createObjectNode().put("stdout", "FAIL saved"));
        var reference = store.saveToolResult(context, ToolResult.success(mapper.createObjectNode().put("stdout", "FAIL saved")));
        var projection = mapper.createObjectNode();
        projection.putObject("observation").putObject("externalization").set("artifact", mapper.valueToTree(reference));
        when(steps.selectArtifactProjectionBySource(context.sessionId(), 8)).thenReturn(projection.toString());
        assertEquals(1, search(query("FAIL")).payload().path("matches").size());
        Path file;
        try (var files = Files.walk(root)) { file = files.filter(Files::isRegularFile).findFirst().orElseThrow(); }
        Files.writeString(file, "corrupt");
        var broken = search(query("FAIL"));
        assertEquals("ARTIFACT_READ_FAILED", broken.errorCode());
        assertFalse(broken.message().contains(root.toString()));
    }

    @Test
    void toolDefinitionAdvertisesCollectionDiscoveryAndEmptyPageContinuation() {
        var definition = registry.definitions().get(0);
        assertTrue(definition.description().contains("path=artifact://tool-results/"));
        assertTrue(definition.description().contains("small inline results"));
        assertTrue(definition.description().contains("searchComplete=false"));
        assertFalse(definition.inputSchema().path("properties").has("sessionId"));
    }

    @Test
    void prefixCheckClassifiesTheResourceButDetailedReaderStillValidatesItsShape() throws Exception {
        assertEquals("INVALID_SEARCH_PATH", search(query("FAIL").put("path", "artifact://other/1/stdout.txt")).errorCode());
        assertEquals("INVALID_SEARCH_PATH", search(query("FAIL").put("path", "src/A.java")).errorCode());
        assertEquals("INVALID_ARTIFACT_ARGUMENTS", search(query("FAIL").put("path", "artifact://tool-results/0/stdout.txt")).errorCode());
        assertEquals("INVALID_ARTIFACT_ARGUMENTS", search(query("FAIL").put("path", "artifact://tool-results/1/../stdout.txt")).errorCode());
        add(1, "task", "runCommand", mapper.createObjectNode().put("stdout", "FAIL original"));
        var single = search(query("FAIL").put("path", "artifact://tool-results/1/stdout.txt"));
        assertEquals(ToolStatus.SUCCESS, single.status());
        assertEquals("FAIL original", single.payload().path("matches").get(0).path("content").asText());
        verifyNoInteractions(workspace);
    }

    private ObjectNode query(String text) {
        return mapper.createObjectNode().put("path", ArtifactTextReader.SESSION_RESULTS_PATH)
                .put("query", text).put("caseSensitive", true);
    }

    private ToolResult search(JsonNode args) {
        return registry.execute(AgentTestContexts.toolExecution(context, 1, "search"), "searchText", args);
    }

    private void add(long sequence, String task, String tool, JsonNode payload) throws Exception {
        var row = new AgentStepEntity();
        row.setSessionId(context.sessionId());
        row.setSessionSequence(sequence);
        row.setStepType("TOOL_RESULT");
        row.setSchemaVersion(1);
        row.setTaskId(task);
        row.setRunId("run-" + task);
        row.setPayloadJson(mapper.writeValueAsString(new ToolResultPayload("model", "call-" + sequence, tool, ToolResult.success(payload))));
        history.add(row);
    }
}
