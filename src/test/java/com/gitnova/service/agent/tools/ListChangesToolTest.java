package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.gitnova.gitlet.Commit;
import com.gitnova.gitlet.Utils;
import com.gitnova.gitobject.ObjectStorageGitObjectReader;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.storage.FakeObjectStorage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListChangesToolTest {

    private static final String REPO_KEY = "1/10";
    private static final String BASE_SHA = "base-commit";
    private static final String TARGET_SHA = "target-commit";

    @Test
    void shouldBuildManifestForAddedModifiedAndDeletedFiles() {
        FakeObjectStorage storage = new FakeObjectStorage();
        writeScenario(storage);
        ListChangesTool tool = new ListChangesTool(
                new ObjectStorageGitObjectReader(storage),
                new ObjectMapper()
        );

        ToolResult result = tool.execute(
                new ToolExecutionContext(
                        new AgentRunContext(
                                "run-1",
                                10L,
                                REPO_KEY,
                                BASE_SHA,
                                TARGET_SHA
                        ),
                        0,
                        "call-1"
                ),
                JsonNodeFactory.instance.objectNode()
        );

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals(3, result.payload().path("totalFiles").asInt());
        assertEquals(3, result.payload().path("totalHunks").asInt());
        assertEquals(2, result.payload().path("totalAddedLines").asInt());
        assertEquals(2, result.payload().path("totalDeletedLines").asInt());
        assertFalse(result.payload().path("containsBinary").asBoolean());

        assertEquals(3, result.payload().path("files").size());
        assertEquals("src/Added.java", result.payload().path("files").get(0).path("path").asText());
        assertEquals("ADDED", result.payload().path("files").get(0).path("changeType").asText());
        assertEquals(1, result.payload().path("files").get(0).path("addedLines").asInt());

        assertEquals("src/Modified.java", result.payload().path("files").get(1).path("path").asText());
        assertEquals("MODIFIED", result.payload().path("files").get(1).path("changeType").asText());
        assertEquals(1, result.payload().path("files").get(1).path("addedLines").asInt());
        assertEquals(1, result.payload().path("files").get(1).path("deletedLines").asInt());

        assertEquals("src/Removed.java", result.payload().path("files").get(2).path("path").asText());
        assertEquals("DELETED", result.payload().path("files").get(2).path("changeType").asText());
        assertEquals(1, result.payload().path("files").get(2).path("deletedLines").asInt());

        assertTrue(result.payload().path("files").findValuesAsText("path").stream()
                .noneMatch("docs/Keep.md"::equals));
    }

    @Test
    void shouldKeepMissingCorruptAndTransientObjectFailuresDistinct() {
        FakeObjectStorage missingStorage = new FakeObjectStorage();
        ToolResult missing = executeWith(missingStorage);
        assertEquals(ToolStatus.NOT_FOUND, missing.status());
        assertEquals("GIT_OBJECT_NOT_FOUND", missing.errorCode());
        assertFalse(missing.retryable());

        FakeObjectStorage corruptStorage = new FakeObjectStorage();
        corruptStorage.writeObject(REPO_KEY, BASE_SHA, new byte[]{1, 2, 3});
        ToolResult corrupt = executeWith(corruptStorage);
        assertEquals(ToolStatus.INTERNAL_ERROR, corrupt.status());
        assertEquals("CORRUPT_GIT_OBJECT", corrupt.errorCode());
        assertFalse(corrupt.retryable());

        FakeObjectStorage transientStorage = new FakeObjectStorage();
        writeScenario(transientStorage);
        transientStorage.failReadsWith(new IllegalStateException("offline"));
        ToolResult transientFailure = executeWith(transientStorage);
        assertEquals(ToolStatus.TRANSIENT_ERROR, transientFailure.status());
        assertEquals("GIT_OBJECT_READ_FAILED", transientFailure.errorCode());
        assertTrue(transientFailure.retryable());
    }

    @Test
    void shouldReturnEmptyManifestWhenSnapshotsAreIdentical() {
        FakeObjectStorage storage = new FakeObjectStorage();
        storage.writeObject(REPO_KEY, "same-blob", bytes("same\n"));
        Commit base = new Commit("base", null);
        base.setMapping(Map.of("src/Main.java", "same-blob"));
        Commit target = new Commit("target", BASE_SHA);
        target.setMapping(Map.of("src/Main.java", "same-blob"));
        storage.writeObject(REPO_KEY, BASE_SHA, Utils.serialize(base));
        storage.writeObject(REPO_KEY, TARGET_SHA, Utils.serialize(target));

        ToolResult result = executeWith(storage);

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals(0, result.payload().path("files").size());
        assertEquals(0, result.payload().path("totalFiles").asInt());
        assertEquals(0, result.payload().path("totalHunks").asInt());
        assertEquals(0, result.payload().path("totalAddedLines").asInt());
        assertEquals(0, result.payload().path("totalDeletedLines").asInt());
        assertFalse(result.payload().path("containsBinary").asBoolean());
    }

    @Test
    void shouldRejectMissingBaseRevision() {
        ListChangesTool tool = new ListChangesTool(
                new ObjectStorageGitObjectReader(new FakeObjectStorage()),
                new ObjectMapper()
        );
        ToolResult result = tool.execute(
                new ToolExecutionContext(
                        new AgentRunContext(
                                "run-1",
                                10L,
                                REPO_KEY,
                                null,
                                TARGET_SHA
                        ),
                        0,
                        "call-1"
                ),
                JsonNodeFactory.instance.objectNode()
        );

        assertEquals(ToolStatus.CONFLICT, result.status());
        assertEquals("BASE_REVISION_MISSING", result.errorCode());
    }

    @Test
    void shouldRejectModelSuppliedTrustedContextFieldsAtRegistryBoundary() {
        FakeObjectStorage storage = new FakeObjectStorage();
        writeScenario(storage);
        ListChangesTool tool = new ListChangesTool(
                new ObjectStorageGitObjectReader(storage),
                new ObjectMapper()
        );
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        var arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("repoKey", "999/999");
        arguments.put("baseSha1", "model-base");
        arguments.put("targetSha1", "model-target");

        ToolResult result = registry.execute(
                new ToolExecutionContext(
                        new AgentRunContext(
                                "run-1",
                                10L,
                                REPO_KEY,
                                BASE_SHA,
                                TARGET_SHA
                        ),
                        0,
                        "call-1"
                ),
                "listChanges",
                arguments
        );

        assertEquals(ToolStatus.INVALID_ARGUMENT, result.status());
        assertEquals("SCHEMA_VALIDATION_FAILED", result.errorCode());
    }

    private ToolResult executeWith(FakeObjectStorage storage) {
        ListChangesTool tool = new ListChangesTool(
                new ObjectStorageGitObjectReader(storage),
                new ObjectMapper()
        );
        return tool.execute(
                new ToolExecutionContext(
                        new AgentRunContext(
                                "run-1",
                                10L,
                                REPO_KEY,
                                BASE_SHA,
                                TARGET_SHA
                        ),
                        0,
                        "call-1"
                ),
                JsonNodeFactory.instance.objectNode()
        );
    }

    private void writeScenario(FakeObjectStorage storage) {
        storage.writeObject(REPO_KEY, "blob-keep", bytes("unchanged"));
        storage.writeObject(REPO_KEY, "blob-removed", bytes("class Removed {}\n"));
        storage.writeObject(REPO_KEY, "blob-modified-old", bytes("class Modified { int value = 1; }\n"));
        storage.writeObject(REPO_KEY, "blob-modified-new", bytes("class Modified { int value = 2; }\n"));
        storage.writeObject(REPO_KEY, "blob-added", bytes("class Added {}\n"));

        Commit base = new Commit("base", "parent-sha");
        base.setMapping(Map.of(
                "docs/Keep.md", "blob-keep",
                "src/Modified.java", "blob-modified-old",
                "src/Removed.java", "blob-removed"
        ));

        Commit target = new Commit("target", BASE_SHA);
        target.setMapping(Map.of(
                "docs/Keep.md", "blob-keep",
                "src/Modified.java", "blob-modified-new",
                "src/Added.java", "blob-added"
        ));

        storage.writeObject(REPO_KEY, BASE_SHA, Utils.serialize(base));
        storage.writeObject(REPO_KEY, TARGET_SHA, Utils.serialize(target));
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
