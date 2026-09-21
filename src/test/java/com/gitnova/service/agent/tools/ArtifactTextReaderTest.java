package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.gitobject.GitObjectReader;
import com.gitnova.service.agent.AgentTestContexts;
import com.gitnova.service.agent.context.TokenEstimator;
import com.gitnova.service.agent.journal.RunJournal;
import com.gitnova.service.agent.runtime.AgentExecutionContext;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.tool.schema.ToolSchemaValidator;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.storage.artifact.LocalArtifactStore;
import com.gitnova.storage.config.ArtifactStorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArtifactTextReaderTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RunJournal journal = mock(RunJournal.class);
    private final GitObjectReader git = mock(GitObjectReader.class);
    private final WorkspaceGateway workspace = mock(WorkspaceGateway.class);
    private final AgentExecutionContext context = AgentTestContexts.agent(
            new AgentRunContext("run", 2L, "1/2", "a".repeat(40), "b".repeat(40)));
    private final String path = "artifact://tool-results/24/stdout.txt";
    private LocalArtifactStore store;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        store = new LocalArtifactStore(new ArtifactStorageProperties(root, 2 * 1024 * 1024, 4096), mapper);
        var reader = new ArtifactTextReader(store, journal, mapper, new TokenEstimator());
        registry = new ToolRegistry(List.of(new ReadFileTool(git, workspace, reader), new SearchTextTool(workspace, mapper, reader)));
    }

    @Test
    void springRegistersUnifiedReadersWithSharedArtifactDependencies() {
        new ApplicationContextRunner().withBean(ObjectMapper.class, () -> mapper)
                .withBean(GitObjectReader.class, () -> git).withBean(WorkspaceGateway.class, () -> workspace)
                .withBean(LocalArtifactStore.class, () -> store).withBean(RunJournal.class, () -> journal)
                .withBean(TokenEstimator.class, TokenEstimator::new)
                .withUserConfiguration(ArtifactTextReader.class, ReadFileTool.class,
                        WorkspaceAgentToolConfiguration.class, ToolRegistry.class)
                .run(application -> {
                    assertNull(application.getStartupFailure());
                    assertTrue(application.getBean(ToolRegistry.class).definitions().stream().anyMatch(d -> d.name().equals("readFile")));
                    assertTrue(application.getBean(ToolRegistry.class).definitions().stream().anyMatch(d -> d.name().equals("searchText")));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"filePath\":\"src/A.java\",\"revision\":\"WORKSPACE\",\"startLine\":1,\"endLine\":100}",
            "{\"filePath\":\"src/A.java\",\"revision\":\"BASE\",\"startLine\":1,\"endLine\":10}",
            "{\"filePath\":\"src/A.java\",\"revision\":\"TARGET\",\"startLine\":1,\"endLine\":10}",
            "{\"filePath\":\"artifact://tool-results/24/stdout.txt\",\"startLine\":60,\"endLine\":100}",
            "{\"filePath\":\"artifact://tool-results/24/stdout.txt\",\"cursor\":\"issued-cursor\"}"
    })
    void declaresTheThreeSupportedArgumentForms(String json) throws Exception {
        var definition = registry.definitions().stream().filter(d -> d.name().equals("readFile")).findFirst().orElseThrow();
        assertEquals(3, definition.inputSchema().path("anyOf").size());
        assertTrue(ToolSchemaValidator.validate(definition, mapper.readTree(json)).isEmpty());
        // This validates the request shape only; the reader must still verify an issued cursor and trusted scope.
        verifyNoInteractions(git, workspace, journal);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"filePath\":\"src/A.java\"}",
            "{\"filePath\":\"src/A.java\",\"startLine\":1,\"endLine\":10}",
            "{\"filePath\":\"src/A.java\",\"revision\":\"WORKSPACE\",\"endLine\":10}",
            "{\"filePath\":\"src/A.java\",\"revision\":\"WORKSPACE\",\"startLine\":1}",
            "{\"filePath\":\"src/A.java\",\"revision\":null,\"startLine\":1,\"endLine\":10}",
            "{\"filePath\":\"src/A.java\",\"cursor\":\"issued-cursor\"}",
            "{\"filePath\":\"src/A.java\",\"revision\":\"BASE\",\"startLine\":1,\"endLine\":10,\"cursor\":\"x\"}",
            "{\"filePath\":\"artifact://tool-results/24/stdout.txt\"}",
            "{\"filePath\":\"artifact://tool-results/24/stdout.txt\",\"startLine\":1}",
            "{\"filePath\":\"artifact://tool-results/24/stdout.txt\",\"startLine\":1,\"endLine\":10,\"revision\":\"BASE\"}",
            "{\"filePath\":\"artifact://tool-results/24/stdout.txt\",\"cursor\":\"x\",\"startLine\":1,\"endLine\":10}",
            "{\"filePath\":\"artifact://tool-results/24/stdout.txt\",\"cursor\":null}"
    })
    void rejectsIncompleteOrMixedFormsBeforeReadingAnyStorage(String json) throws Exception {
        var result = call("readFile", mapper.readTree(json));
        assertEquals(ToolStatus.INVALID_ARGUMENT, result.status());
        assertEquals("SCHEMA_VALIDATION_FAILED", result.errorCode());
        verifyNoInteractions(git, workspace, journal);
    }

    @Test
    void decodesEmbeddedNewlinesAndReadsOnlyTheRequestedHistoricalRange() throws Exception {
        publish("header\r\nPASS one\nFAIL 中文🧪\nfooter\n", false);
        var result = call("readFile", range(path, 2, 3));
        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals("PASS one", result.payload().path("lines").get(0).path("content").asText());
        assertEquals("FAIL 中文🧪", result.payload().path("lines").get(1).path("content").asText());
        assertEquals(3, result.payload().path("lines").get(1).path("lineNumber").asInt());
        assertEquals(4, result.payload().path("totalCapturedLines").asInt());
        assertEquals(24, result.payload().path("sourceStepSequence").asInt());
        assertTrue(result.payload().path("historical").asBoolean());
        var next = call("readFile", result.payload().path("nextRequest"));
        assertEquals("footer", next.payload().path("lines").get(0).path("content").asText());
        assertFalse(next.payload().path("hasMore").asBoolean());
        verifyNoInteractions(git, workspace);
    }

    @Test
    void longUnicodeLineMakesProgressAndReassemblesExactlyWithoutNewArtifacts() throws Exception {
        String text = "中文🧪é".repeat(5000);
        publish(text, false);
        JsonNode args = range(path, 1, 1);
        var seen = new HashSet<String>();
        StringBuilder restored = new StringBuilder();
        int pages = 0;
        while (true) {
            var result = call("readFile", args);
            assertEquals(ToolStatus.SUCCESS, result.status(), result.message());
            assertTrue(new TokenEstimator().estimateText(mapper.valueToTree(result).toString()).tokens() <= ArtifactTextReader.MAX_PAGE_TOKENS);
            JsonNode line = result.payload().path("lines").get(0);
            assertFalse(line.path("content").asText().isEmpty());
            assertEquals(restored.codePointCount(0, restored.length()), line.path("characterOffset").asInt());
            restored.append(line.path("content").asText());
            assertTrue(++pages < 100);
            if (!result.payload().path("hasMore").asBoolean()) break;
            args = result.payload().path("nextRequest");
            assertEquals(path, args.path("filePath").asText());
            assertTrue(seen.add(args.path("cursor").asText()));
        }
        assertEquals(text, restored.toString());
        assertTrue(pages > 1);
        try (var files = Files.walk(root)) { assertEquals(1, files.filter(Files::isRegularFile).count()); }
    }

    @Test
    void literalSearchPaginatesWithoutLosingOrDuplicatingMatchingLines() throws Exception {
        publish("HEADER\n" + "FAIL literal.* 中文\n".repeat(300) + "tail\n", false);
        JsonNode args = mapper.createObjectNode().put("path", path).put("query", "literal.*").put("caseSensitive", true);
        var found = new HashSet<Integer>();
        int pages = 0;
        do {
            var result = call("searchText", args);
            assertEquals(ToolStatus.SUCCESS, result.status(), result.message());
            assertTrue(new TokenEstimator().estimateText(mapper.valueToTree(result).toString()).tokens() <= ArtifactTextReader.MAX_PAGE_TOKENS);
            for (JsonNode item : result.payload().path("matches")) {
                assertEquals("FAIL literal.* 中文", item.path("content").asText());
                assertTrue(found.add(item.path("lineNumber").asInt()));
            }
            assertTrue(++pages < 100);
            args = result.payload().path("nextRequest");
        } while (!args.isMissingNode());
        assertEquals(300, found.size());
        assertTrue(found.contains(2) && found.contains(301));
    }

    @Test
    void rejectsScopeInjectionMalformedUrisRevisionAndWrongCursor() throws Exception {
        publish("hello\nworld\n", false);
        assertEquals("SCHEMA_VALIDATION_FAILED", call("readFile", range(path, 1, 1).put("revision", "WORKSPACE")).errorCode());
        assertEquals("SCHEMA_VALIDATION_FAILED", call("readFile", range(path, 1, 1).put("sessionId", "other")).errorCode());
        for (String invalid : List.of("artifact://tool-results/0/stdout.txt", "artifact://tool-results/24/../stdout.txt",
                "artifact://tool-results/24/%73tdout.txt", "artifact://tool-results/24/stdout.txt?session=other",
                "artifact://tool-results/9999999999999999999/stdout.txt", "artifact://other/24/stdout.txt")) {
            assertEquals("INVALID_ARTIFACT_ARGUMENTS", call("readFile", range(invalid, 1, 1)).errorCode(), invalid);
        }
        assertEquals("INVALID_ARTIFACT_ARGUMENTS", call("readFile", range(path, 1, 201)).errorCode());
        assertEquals("SCHEMA_VALIDATION_FAILED", call("readFile", mapper.createObjectNode().put("filePath", "src/A.java")).errorCode());
        var page = call("readFile", range(path, 1, 1));
        var next = page.payload().path("nextRequest").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) next).put("filePath", "artifact://tool-results/24/stderr.txt");
        assertEquals("INVALID_ARTIFACT_ARGUMENTS", call("readFile", next).errorCode());
        verifyNoInteractions(git, workspace);
    }

    @Test
    void cursorCannotBeReusedInAnotherAuthorizedSessionWithTheSameSourceNumber() throws Exception {
        publish("one\ntwo\n", false);
        var next = call("readFile", range(path, 1, 1)).payload().path("nextRequest");
        var other = AgentTestContexts.agent(new AgentRunContext("other", 2L, "1/2", "a".repeat(40), "b".repeat(40)));
        var otherRef = store.saveToolResult(other, ToolResult.success(mapper.createObjectNode().put("stdout", "other\nsecond\n")));
        when(journal.findArtifactBySource(other.sessionId(), 24)).thenReturn(Optional.of(otherRef));
        var result = registry.execute(AgentTestContexts.toolExecution(other, 1, "read"), "readFile", next);
        assertEquals("INVALID_ARTIFACT_ARGUMENTS", result.errorCode());
        var ownPage = registry.execute(AgentTestContexts.toolExecution(other, 1, "read"), "readFile", range(path, 1, 1));
        assertEquals("other", ownPage.payload().path("lines").get(0).path("content").asText());
    }

    @Test
    void missingReferenceOtherSessionAndMissingViewAreRejected() throws Exception {
        publish("data\n", false);
        assertEquals("ARTIFACT_NOT_FOUND", call("readFile", range("artifact://tool-results/25/stdout.txt", 1, 1)).errorCode());
        var other = AgentTestContexts.agent(new AgentRunContext("other", 2L, "1/2", "a".repeat(40), "b".repeat(40)));
        var result = registry.execute(AgentTestContexts.toolExecution(other, 1, "read"), "readFile", range(path, 1, 1));
        assertEquals("ARTIFACT_NOT_FOUND", result.errorCode());
        assertEquals("ARTIFACT_VIEW_NOT_FOUND", call("readFile", range("artifact://tool-results/24/secret.txt", 1, 1)).errorCode());
    }

    @Test
    void distinguishesCaptureLossFromPageOmissionAndSupportsEmptyViews() throws Exception {
        publish("complete\npartial", true);
        var page = call("readFile", range(path, 1, 2));
        assertTrue(page.payload().path("captureTruncated").asBoolean());
        assertTrue(page.payload().path("lines").get(0).path("completeLine").asBoolean());
        assertFalse(page.payload().path("lines").get(1).path("completeLine").asBoolean());
        assertFalse(page.payload().path("hasMore").asBoolean()); // Reading more cannot recover uncaptured output.
        var empty = call("readFile", range("artifact://tool-results/24/stderr.txt", 1, 10));
        assertEquals(ToolStatus.SUCCESS, empty.status());
        assertTrue(empty.payload().path("lines").isEmpty());
        assertFalse(empty.payload().path("captureTruncated").asBoolean());
    }

    @Test
    void exposesDecodedDiffAndCompleteArrayEntriesWhileKeepingRawJson() throws Exception {
        var payload = mapper.createObjectNode().put("generation", 7)
                .put("unifiedDiff", "--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n-old\n+new\n");
        payload.putArray("files").addObject().put("filePath", "A.java").put("changeType", "MODIFIED");
        var ref = store.saveToolResult(context, ToolResult.success(payload));
        when(journal.findArtifactBySource(context.sessionId(), 24)).thenReturn(Optional.of(ref));
        var diff = call("readFile", range("artifact://tool-results/24/diff.patch", 3, 5));
        assertEquals("@@ -1 +1 @@", diff.payload().path("lines").get(0).path("content").asText());
        var files = call("readFile", range("artifact://tool-results/24/files.jsonl", 1, 5));
        assertEquals(payload.path("files").get(0), mapper.readTree(files.payload().path("lines").get(0).path("content").asText()));
        var raw = call("readFile", range("artifact://tool-results/24/result.json", 1, 100));
        StringBuilder restored = new StringBuilder();
        for (JsonNode line : raw.payload().path("lines")) restored.append(line.path("content").asText()).append('\n');
        assertEquals(mapper.valueToTree(ToolResult.success(payload)), mapper.readTree(restored.toString()));
    }

    @Test
    void longSearchLinesHaveReadRequestsAndQueryIsNotTreatedAsRegex() throws Exception {
        publish("begin " + "中".repeat(10000) + " NEEDLE .* " + "文".repeat(10000), false);
        var args = mapper.createObjectNode().put("path", path).put("query", "needle").put("caseSensitive", false);
        var found = call("searchText", args);
        assertEquals(ToolStatus.SUCCESS, found.status());
        var hit = found.payload().path("matches").get(0);
        assertTrue(hit.path("content").asText().contains("NEEDLE"));
        assertFalse(hit.path("completeLine").asBoolean());
        assertEquals(ToolStatus.SUCCESS, call("readFile", hit.path("readRequest")).status());
        args.put("query", "missing.*");
        assertTrue(call("searchText", args).payload().path("matches").isEmpty());
        args.put("query", "中".repeat(4096));
        assertEquals("INVALID_ARTIFACT_ARGUMENTS", call("searchText", args).errorCode());
    }

    @Test
    void rejectsCorruptAndDeletedContentWithoutLeakingStoragePaths() throws Exception {
        publish("hello\n", false);
        Path file;
        try (var files = Files.walk(root)) { file = files.filter(Files::isRegularFile).findFirst().orElseThrow(); }
        Files.writeString(file, "broken");
        var corrupt = call("readFile", range(path, 1, 1));
        assertEquals("ARTIFACT_READ_FAILED", corrupt.errorCode());
        assertFalse(corrupt.message().contains(root.toString()));
        Files.delete(file);
        assertEquals("ARTIFACT_CONTENT_MISSING", call("readFile", range(path, 1, 1)).errorCode());
    }

    private void publish(String stdout, boolean truncated) throws Exception {
        var result = ToolResult.success(mapper.createObjectNode().put("stdout", stdout).put("stderr", "")
                .put("stdoutTruncated", truncated).put("stderrTruncated", false).put("exitCode", 1), truncated);
        when(journal.findArtifactBySource(context.sessionId(), 24)).thenReturn(Optional.of(store.saveToolResult(context, result)));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode range(String path, int start, int end) {
        return mapper.createObjectNode().put("filePath", path).put("startLine", start).put("endLine", end);
    }

    private ToolResult call(String tool, JsonNode args) {
        return registry.execute(AgentTestContexts.toolExecution(context, 1, "call"), tool, args);
    }
}
