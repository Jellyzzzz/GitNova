package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.storage.FakeObjectStorage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.gitnova.gitobject.GitObjectTestFixtures.objectId;
import static com.gitnova.gitobject.GitObjectTestFixtures.reader;
import static com.gitnova.gitobject.GitObjectTestFixtures.writeCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReadFileToolTest {

    private static final String REPO_KEY = "1/10";
    private static final String BASE_SHA = objectId('a');
    private static final String TARGET_SHA = objectId('b');
    private static final String BASE_BLOB_SHA = objectId('c');
    private static final String TARGET_BLOB_SHA = objectId('d');

    @Test
    void shouldReadRequestedTargetRangeWithRealLineNumbers() {
        FakeObjectStorage storage = new FakeObjectStorage();
        storage.writeObject(
                REPO_KEY,
                TARGET_BLOB_SHA,
                "one\ntwo\nthree\nfour\n".getBytes(StandardCharsets.UTF_8)
        );
        writeCommit(
                storage,
                REPO_KEY,
                TARGET_SHA,
                BASE_SHA,
                "target",
                Map.of("src/Main.java", TARGET_BLOB_SHA)
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
        storage.writeObject(REPO_KEY, BASE_BLOB_SHA, "base\n".getBytes(StandardCharsets.UTF_8));
        storage.writeObject(REPO_KEY, TARGET_BLOB_SHA, "target\n".getBytes(StandardCharsets.UTF_8));
        writeCommit(
                storage,
                REPO_KEY,
                BASE_SHA,
                null,
                "base",
                Map.of("src/Main.java", BASE_BLOB_SHA)
        );
        writeCommit(
                storage,
                REPO_KEY,
                TARGET_SHA,
                BASE_SHA,
                "target",
                Map.of("src/Main.java", TARGET_BLOB_SHA)
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
        storage.writeObject(REPO_KEY, TARGET_BLOB_SHA, content);
        writeCommit(
                storage,
                REPO_KEY,
                TARGET_SHA,
                BASE_SHA,
                "target",
                Map.of("src/Main.java", TARGET_BLOB_SHA)
        );
        return storage;
    }

    private ToolExecutionContext execution() {
        return new ToolExecutionContext(
                new AgentRunContext(
                        "run-1",
                        10L,
                        REPO_KEY,
                        BASE_SHA,
                        TARGET_SHA
                ),
                0,
                "call-1"
        );
    }
}
