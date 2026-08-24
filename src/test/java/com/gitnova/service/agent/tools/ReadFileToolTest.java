package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.gitobject.CommitObject;
import com.gitnova.gitobject.GitObjectHasher;
import com.gitnova.gitobject.GitObjectId;
import com.gitnova.gitobject.GitObjectReader;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.workspace.SnapshotScope;
import com.gitnova.storage.FakeObjectStorage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.gitnova.gitobject.GitObjectTestFixtures.objectId;
import static com.gitnova.gitobject.GitObjectTestFixtures.reader;
import static com.gitnova.gitobject.GitObjectTestFixtures.writeCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadFileToolTest {

    private static final String REPO_KEY = "1/10";
    private static final String BASE_SHA = objectId('a');
    private static final String TARGET_SHA = objectId('b');
    @Test
    void shouldReadRequestedTargetRangeWithRealLineNumbers() {
        FakeObjectStorage storage = new FakeObjectStorage();
        byte[] content = "one\ntwo\nthree\nfour\n".getBytes(StandardCharsets.UTF_8);
        String targetBlobSha = GitObjectHasher.sha1(content).value();
        storage.writeObject(
                REPO_KEY,
                targetBlobSha,
                content
        );
        writeCommit(
                storage,
                REPO_KEY,
                TARGET_SHA,
                BASE_SHA,
                "target",
                Map.of("src/Main.java", targetBlobSha)
        );
        ReadFileTool tool = new ReadFileTool(
                reader(storage)
        );

        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("revision", "TARGET");
        arguments.put("filePath", "src/Main.java");
        arguments.put("startLine", 2);
        arguments.put("endLine", 3);
        ToolResult result = tool.execute(execution(), arguments);

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals("TARGET", result.payload().path("revision").asText());
        assertEquals(4, result.payload().path("totalLines").asInt());
        assertEquals(2, result.payload().path("lines").size());
        assertEquals(2, result.payload().path("lines").get(0).path("lineNumber").asInt());
        assertEquals("two", result.payload().path("lines").get(0).path("content").asText());
        assertEquals(3, result.payload().path("lines").get(1).path("lineNumber").asInt());
        assertEquals("three", result.payload().path("lines").get(1).path("content").asText());
    }

    @Test
    void shouldRejectTraversalPathBeforeReadingStorage() {
        ReadFileTool tool = new ReadFileTool(
                reader(new FakeObjectStorage())
        );
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("revision", "TARGET");
        arguments.put("filePath", "../secret.txt");
        arguments.put("startLine", 1);
        arguments.put("endLine", 1);

        ToolResult result = tool.execute(execution(), arguments);

        assertEquals(ToolStatus.PERMISSION_DENIED, result.status());
        assertEquals("INVALID_REPOSITORY_PATH", result.errorCode());
    }

    @Test
    void shouldResolveBaseAndTargetOnlyFromRunContext() {
        FakeObjectStorage storage = new FakeObjectStorage();
        byte[] baseContent = "base\n".getBytes(StandardCharsets.UTF_8);
        byte[] targetContent = "target\n".getBytes(StandardCharsets.UTF_8);
        String baseBlobSha = GitObjectHasher.sha1(baseContent).value();
        String targetBlobSha = GitObjectHasher.sha1(targetContent).value();
        storage.writeObject(REPO_KEY, baseBlobSha, baseContent);
        storage.writeObject(REPO_KEY, targetBlobSha, targetContent);
        writeCommit(
                storage,
                REPO_KEY,
                BASE_SHA,
                null,
                "base",
                Map.of("src/Main.java", baseBlobSha)
        );
        writeCommit(
                storage,
                REPO_KEY,
                TARGET_SHA,
                BASE_SHA,
                "target",
                Map.of("src/Main.java", targetBlobSha)
        );
        ReadFileTool tool = new ReadFileTool(reader(storage));

        ObjectNode baseArguments = readArguments("BASE");
        ObjectNode targetArguments = readArguments("TARGET");

        assertEquals(
                "base",
                tool.execute(execution(), baseArguments)
                        .payload().path("lines").get(0).path("content").asText()
        );
        assertEquals(
                "target",
                tool.execute(execution(), targetArguments)
                        .payload().path("lines").get(0).path("content").asText()
        );
    }

    @Test
    void shouldRejectBinaryAndOversizedFiles() {
        FakeObjectStorage binaryStorage = storageWithTargetBlob(new byte[]{'a', 0, 'b'});
        ReadFileTool binaryTool = new ReadFileTool(
                reader(binaryStorage)
        );
        ToolResult binary = binaryTool.execute(execution(), readArguments("TARGET"));
        assertEquals(ToolStatus.INVALID_ARGUMENT, binary.status());
        assertEquals("BINARY_FILE_UNSUPPORTED", binary.errorCode());

        byte[] oversizedContent = new byte[1024 * 1024 + 1];
        FakeObjectStorage oversizedStorage = storageWithTargetBlob(oversizedContent);
        ReadFileTool oversizedTool = new ReadFileTool(
                reader(oversizedStorage)
        );
        ToolResult oversized = oversizedTool.execute(execution(), readArguments("TARGET"));
        assertEquals(ToolStatus.INVALID_ARGUMENT, oversized.status());
        assertEquals("FILE_TOO_LARGE", oversized.errorCode());

        byte[] oversizedLine = "x".repeat(24 * 1024 + 1)
                .getBytes(StandardCharsets.UTF_8);
        ReadFileTool oversizedOutputTool = new ReadFileTool(
                reader(storageWithTargetBlob(oversizedLine))
        );
        ToolResult oversizedOutput = oversizedOutputTool.execute(
                execution(),
                readArguments("TARGET")
        );
        assertEquals(ToolStatus.INVALID_ARGUMENT, oversizedOutput.status());
        assertEquals("READ_OUTPUT_TOO_LARGE", oversizedOutput.errorCode());
    }

    @Test
    void shouldUseBoundedStreamingBlobReadInsteadOfRequireBlob() {
        byte[] content = "streamed\ncontent\n".getBytes(StandardCharsets.UTF_8);
        String blobSha = GitObjectHasher.sha1(content).value();
        AtomicInteger requireBlobCalls = new AtomicInteger();
        AtomicInteger copyCalls = new AtomicInteger();
        GitObjectReader trackingReader = trackingReader(
                blobSha,
                destination -> {
                    copyCalls.incrementAndGet();
                    destination.write(content);
                    return (long) content.length;
                },
                requireBlobCalls
        );

        ToolResult result = new ReadFileTool(trackingReader).execute(
                execution(),
                readArguments("TARGET")
        );

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals(1, copyCalls.get());
        assertEquals(0, requireBlobCalls.get());
    }

    @Test
    void shouldStopStreamingAsSoonAsBlobExceedsLimit() {
        String blobSha = objectId('e');
        AtomicInteger attemptedBytes = new AtomicInteger();
        GitObjectReader trackingReader = trackingReader(
                blobSha,
                destination -> {
                    byte[] chunk = new byte[4096];
                    for (int offset = 0; offset < 2 * 1024 * 1024; offset += chunk.length) {
                        attemptedBytes.addAndGet(chunk.length);
                        destination.write(chunk);
                    }
                    return 2L * 1024 * 1024;
                },
                new AtomicInteger()
        );

        ToolResult result = new ReadFileTool(trackingReader).execute(
                execution(),
                readArguments("TARGET")
        );

        assertEquals(ToolStatus.INVALID_ARGUMENT, result.status());
        assertEquals("FILE_TOO_LARGE", result.errorCode());
        assertTrue(attemptedBytes.get() < 2 * 1024 * 1024);
    }

    @Test
    void shouldRejectTargetReadWhenRunHasSnapshotScope() {
        ReadFileTool tool = new ReadFileTool(reader(new FakeObjectStorage()));
        AgentRunContext context = new AgentRunContext(
                "context-1",
                10L,
                REPO_KEY,
                SnapshotScope.of(BASE_SHA)
        );

        ToolResult result = tool.execute(
                com.gitnova.service.agent.AgentTestContexts.toolExecution(context, 0, "call-1"),
                readArguments("TARGET")
        );

        assertEquals(ToolStatus.CONFLICT, result.status());
        assertEquals("TARGET_REVISION_MISSING", result.errorCode());
        assertTrue(result.message().contains("TARGET"));
    }

    @Test
    void shouldReadBaseFromSnapshotScope() {
        byte[] content = "snapshot-base\n".getBytes(StandardCharsets.UTF_8);
        String blobSha = GitObjectHasher.sha1(content).value();
        FakeObjectStorage storage = new FakeObjectStorage();
        storage.writeObject(REPO_KEY, blobSha, content);
        writeCommit(
                storage,
                REPO_KEY,
                BASE_SHA,
                null,
                "base",
                Map.of("src/Main.java", blobSha)
        );
        AgentRunContext context = new AgentRunContext(
                "context-1",
                10L,
                REPO_KEY,
                SnapshotScope.of(BASE_SHA)
        );

        ToolResult result = new ReadFileTool(reader(storage)).execute(
                com.gitnova.service.agent.AgentTestContexts.toolExecution(context, 0, "call-1"),
                readArguments("BASE")
        );

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals(
                "snapshot-base",
                result.payload().path("lines").get(0).path("content").asText()
        );
    }

    private ObjectNode readArguments(String revision) {
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("revision", revision);
        arguments.put("filePath", "src/Main.java");
        arguments.put("startLine", 1);
        arguments.put("endLine", 1);
        return arguments;
    }

    private FakeObjectStorage storageWithTargetBlob(byte[] content) {
        FakeObjectStorage storage = new FakeObjectStorage();
        String targetBlobSha = GitObjectHasher.sha1(content).value();
        storage.writeObject(REPO_KEY, targetBlobSha, content);
        writeCommit(
                storage,
                REPO_KEY,
                TARGET_SHA,
                BASE_SHA,
                "target",
                Map.of("src/Main.java", targetBlobSha)
        );
        return storage;
    }

    private ToolExecutionContext execution() {
        return com.gitnova.service.agent.AgentTestContexts.toolExecution(
                new AgentRunContext(
                        "context-1",
                        10L,
                        REPO_KEY,
                        BASE_SHA,
                        TARGET_SHA
                ),
                0,
                "call-1"
        );
    }

    private GitObjectReader trackingReader(
            String blobSha,
            StreamWriter writer,
            AtomicInteger requireBlobCalls
    ) {
        CommitObject commit = new CommitObject(
                Optional.empty(),
                Instant.parse("2026-08-24T00:00:00Z"),
                "stream fixture",
                Map.of("src/Main.java", GitObjectId.of(blobSha))
        );
        return new GitObjectReader() {
            @Override
            public CommitObject requireCommit(String repoKey, String sha1) {
                return commit;
            }

            @Override
            public byte[] requireBlob(String repoKey, String sha1) {
                requireBlobCalls.incrementAndGet();
                throw new AssertionError("requireBlob must not be used by ReadFileTool");
            }

            @Override
            public long copyBlobTo(
                    String repoKey,
                    String sha1,
                    OutputStream destination
            ) throws IOException {
                return writer.write(destination);
            }
        };
    }

    @FunctionalInterface
    private interface StreamWriter {
        long write(OutputStream destination) throws IOException;
    }
}
