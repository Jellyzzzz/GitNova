package com.gitnova.storage.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.service.agent.AgentTestContexts;
import com.gitnova.service.agent.runtime.AgentExecutionContext;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.storage.config.ArtifactStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class LocalArtifactStoreTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentExecutionContext context = AgentTestContexts.agent(
            new AgentRunContext("run-1", 2L, "1/2", "a".repeat(40), "b".repeat(40)));

    @Test
    void shouldRoundTripDeduplicateAndReadAfterReopeningStore() throws Exception {
        var properties = new ArtifactStorageProperties(root, 8192, 4096);
        var store = new LocalArtifactStore(properties, mapper);
        var result = ToolResult.success(mapper.createObjectNode().put("stdout", "hello 中文 🧪"));
        var reference = store.saveToolResult(context, result);

        assertEquals(reference, store.saveToolResult(context, result));
        var reopened = new LocalArtifactStore(properties, mapper);
        var page = reopened.read(context, reference, 0, 4096);
        assertEquals(mapper.valueToTree(result), mapper.readTree(page.content()));
        assertEquals(reference.sizeBytes(), page.nextOffset());
        assertFalse(page.hasMore());
        assertEquals(reference.artifactId(), reference.sha256());
        try (var files = Files.walk(root)) {
            assertEquals(1, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void shouldPageWithoutSplittingUtf8AndRejectAnOffsetInsideACharacter() throws Exception {
        var store = new LocalArtifactStore(new ArtifactStorageProperties(root, 8192, 4096), mapper);
        var result = ToolResult.success(mapper.createObjectNode().put("stdout", "中🧪文".repeat(20)));
        var ref = store.saveToolResult(context, result);
        String whole = store.read(context, ref, 0, 4096).content();
        StringBuilder restored = new StringBuilder();
        long offset = 0;
        while (offset < ref.sizeBytes()) {
            var page = store.read(context, ref, offset, 4);
            assertTrue(page.nextOffset() > offset);
            assertTrue(page.nextOffset() - offset <= 4);
            restored.append(page.content());
            offset = page.nextOffset();
        }
        assertEquals(whole, restored.toString());
        assertEquals("", store.read(context, ref, offset, 4).content());
        long middle = whole.substring(0, whole.indexOf("中")).getBytes(StandardCharsets.UTF_8).length + 1L;
        assertThrows(IllegalArgumentException.class, () -> store.read(context, ref, middle, 4));
        assertThrows(IllegalArgumentException.class, () -> store.read(context, ref, -1, 4));
        assertThrows(IllegalArgumentException.class, () -> store.read(context, ref, ref.sizeBytes() + 1, 4));
        assertThrows(IllegalArgumentException.class, () -> store.read(context, ref, 0, 4097));
    }

    @Test
    void shouldIsolateSessionAndRepositoryNamespaces() throws Exception {
        var store = new LocalArtifactStore(new ArtifactStorageProperties(root, 8192, 4096), mapper);
        var ref = store.saveToolResult(context, ToolResult.success(mapper.createObjectNode()));
        var anotherSession = new AgentExecutionContext("other-session", context.context(), context.actorId(),
                context.taskText(), context.workspace(), context.executionPermit(), context.executionConfig());
        var anotherRepo = new AgentExecutionContext(context.sessionId(),
                new AgentRunContext("run-1", 3L, "1/3", "a".repeat(40), "b".repeat(40)), context.actorId(),
                context.taskText(), context.workspace(), context.executionPermit(), context.executionConfig());
        assertThrows(IOException.class, () -> store.read(anotherSession, ref, 0, 100));
        assertThrows(IOException.class, () -> store.read(anotherRepo, ref, 0, 100));
    }

    @Test
    void shouldRejectTamperingAndNeverOverwriteCorruptExistingContent() throws Exception {
        var store = new LocalArtifactStore(new ArtifactStorageProperties(root, 8192, 4096), mapper);
        var result = ToolResult.success(mapper.createObjectNode().put("stdout", "hello"));
        var ref = store.saveToolResult(context, result);
        Path file;
        try (var files = Files.walk(root)) {
            file = files.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        String content = Files.readString(file).replace("hello", "xxxxx");
        Files.writeString(file, content); // Same size: digest verification, not just a length check.
        assertThrows(IOException.class, () -> store.read(context, ref, 0, 100));
        assertThrows(IOException.class, () -> store.saveToolResult(context, result));
        assertEquals(content, Files.readString(file));
        try (var files = Files.walk(root)) {
            assertFalse(files.anyMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    @Test
    void shouldCleanUpWhenSerializationExceedsStorageLimit() throws Exception {
        var store = new LocalArtifactStore(new ArtifactStorageProperties(root, 128, 64), mapper);
        var result = ToolResult.success(mapper.createObjectNode().put("stdout", "x".repeat(4096)));
        assertThrows(IOException.class, () -> store.saveToolResult(context, result));
        try (var files = Files.walk(root)) {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void shouldRejectSymlinkInsteadOfFollowingIt() throws Exception {
        var store = new LocalArtifactStore(new ArtifactStorageProperties(root, 8192, 4096), mapper);
        var result = ToolResult.success(mapper.createObjectNode());
        var ref = store.saveToolResult(context, result);
        Path file;
        try (var files = Files.walk(root)) {
            file = files.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        Path outside = root.resolve("outside.txt");
        Files.move(file, outside);
        Files.createSymbolicLink(file, outside);
        assertThrows(IOException.class, () -> store.read(context, ref, 0, 100));
        assertThrows(IOException.class, () -> store.saveToolResult(context, result));
        assertTrue(Files.size(outside) > 0);
    }

    @Test
    void shouldPublishIdenticalConcurrentWritesWithoutReplacingContent() throws Exception {
        var store = new LocalArtifactStore(new ArtifactStorageProperties(root, 8192, 4096), mapper);
        var result = ToolResult.success(mapper.createObjectNode().put("stdout", "concurrent"));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> store.saveToolResult(context, result));
            var second = pool.submit(() -> store.saveToolResult(context, result));
            var ref = first.get();
            assertEquals(ref, second.get());
            assertEquals(mapper.valueToTree(result), mapper.readTree(store.read(context, ref, 0, 4096).content()));
        } finally {
            pool.shutdownNow();
        }
    }
}
