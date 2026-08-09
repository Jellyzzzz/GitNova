package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.gitlet.Commit;
import com.gitnova.gitlet.Utils;
import com.gitnova.gitobject.ObjectStorageGitObjectReader;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.storage.FakeObjectStorage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReadFileToolTest {

    private static final String REPO_KEY = "1/10";

    @Test
    void shouldReadRequestedTargetRangeWithRealLineNumbers() {
        FakeObjectStorage storage = new FakeObjectStorage();
        storage.writeObject(
                REPO_KEY,
                "target-blob",
                "one\ntwo\nthree\nfour\n".getBytes(StandardCharsets.UTF_8)
        );
        Commit target = new Commit("target", "base-sha");
        target.setMapping(Map.of("src/Main.java", "target-blob"));
        storage.writeObject(REPO_KEY, "target-sha", Utils.serialize(target));
        ReadFileTool tool = new ReadFileTool(
                new ObjectStorageGitObjectReader(storage)
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
                new ObjectStorageGitObjectReader(new FakeObjectStorage())
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
        storage.writeObject(REPO_KEY, "base-blob", "base\n".getBytes(StandardCharsets.UTF_8));
        storage.writeObject(REPO_KEY, "target-blob", "target\n".getBytes(StandardCharsets.UTF_8));
        Commit base = new Commit("base", null);
        base.setMapping(Map.of("src/Main.java", "base-blob"));
        Commit target = new Commit("target", "base-sha");
        target.setMapping(Map.of("src/Main.java", "target-blob"));
        storage.writeObject(REPO_KEY, "base-sha", Utils.serialize(base));
        storage.writeObject(REPO_KEY, "target-sha", Utils.serialize(target));
        ReadFileTool tool = new ReadFileTool(new ObjectStorageGitObjectReader(storage));

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
                new ObjectStorageGitObjectReader(binaryStorage)
        );
        ToolResult binary = binaryTool.execute(execution(), readArguments("TARGET"));
        assertEquals(ToolStatus.INVALID_ARGUMENT, binary.status());
        assertEquals("BINARY_FILE_UNSUPPORTED", binary.errorCode());

        byte[] oversizedContent = new byte[1024 * 1024 + 1];
        FakeObjectStorage oversizedStorage = storageWithTargetBlob(oversizedContent);
        ReadFileTool oversizedTool = new ReadFileTool(
                new ObjectStorageGitObjectReader(oversizedStorage)
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
        storage.writeObject(REPO_KEY, "target-blob", content);
        Commit target = new Commit("target", "base-sha");
        target.setMapping(Map.of("src/Main.java", "target-blob"));
        storage.writeObject(REPO_KEY, "target-sha", Utils.serialize(target));
        return storage;
    }

    private ToolExecutionContext execution() {
        return new ToolExecutionContext(
                new AgentRunContext(
                        "run-1",
                        10L,
                        REPO_KEY,
                        "base-sha",
                        "target-sha"
                ),
                0,
                "call-1"
        );
    }
}
