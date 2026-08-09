package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GetDiffToolTest {

    private static final String REPO_KEY = "1/10";
    private static final String BASE_SHA = "base-commit";
    private static final String TARGET_SHA = "target-commit";
    private static final String FILE_PATH = "src/Main.java";

    @Test
    void shouldReturnStablePaginatedHunksAndAcceptNullInitialCursor() {
        FakeObjectStorage storage = new FakeObjectStorage();
        writeTwoHunkScenario(storage);
        GetDiffTool tool = new GetDiffTool(
                new ObjectStorageGitObjectReader(storage)
        );
        ToolRegistry registry = new ToolRegistry(List.of(tool));

        ObjectNode firstArguments = arguments();
        firstArguments.putNull("cursor");
        ToolResult first = registry.execute(
                execution(),
                "getDiff",
                firstArguments
        );

        assertEquals(ToolStatus.SUCCESS, first.status());
        assertEquals(FILE_PATH, first.payload().path("filePath").asText());
        assertEquals(1, first.payload().path("hunks").size());
        assertEquals("h1", first.payload().path("hunks").get(0).path("hunkId").asText());
        assertTrue(first.payload().path("hunks").get(0).path("lines").toString().contains("-old-one"));
        assertTrue(first.payload().path("hunks").get(0).path("lines").toString().contains("+new-one"));
        assertEquals("h2", first.payload().path("nextCursor").asText());
        assertTrue(first.payload().path("hasMore").asBoolean());
        assertTrue(first.truncated());

        ObjectNode secondArguments = arguments();
        secondArguments.put("cursor", "h2");
        ToolResult second = registry.execute(
                execution(),
                "getDiff",
                secondArguments
        );

        assertEquals(ToolStatus.SUCCESS, second.status());
        assertEquals("h2", second.payload().path("hunks").get(0).path("hunkId").asText());
        assertTrue(second.payload().path("hunks").get(0).path("lines").toString().contains("-old-two"));
        assertTrue(second.payload().path("hunks").get(0).path("lines").toString().contains("+new-two"));
        assertTrue(second.payload().path("nextCursor").isNull());
        assertFalse(second.payload().path("hasMore").asBoolean());
        assertFalse(second.truncated());
    }

    @Test
    void shouldRejectFileOutsideTrustedChangeScope() {
        FakeObjectStorage storage = new FakeObjectStorage();
        writeTwoHunkScenario(storage);
        GetDiffTool tool = new GetDiffTool(
                new ObjectStorageGitObjectReader(storage)
        );
        ObjectNode arguments = arguments();
        arguments.put("filePath", "src/Unchanged.java");

        ToolResult result = tool.execute(execution(), arguments);

        assertEquals(ToolStatus.PERMISSION_DENIED, result.status());
        assertEquals("FILE_OUTSIDE_CHANGE_SCOPE", result.errorCode());
    }

    private ObjectNode arguments() {
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("filePath", FILE_PATH);
        arguments.put("maxHunks", 1);
        arguments.put("contextLines", 0);
        return arguments;
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

    private void writeTwoHunkScenario(FakeObjectStorage storage) {
        storage.writeObject(REPO_KEY, "old-blob", bytes(
                "keep-1\nold-one\nkeep-3\nkeep-4\nold-two\nkeep-6\n"
        ));
        storage.writeObject(REPO_KEY, "new-blob", bytes(
                "keep-1\nnew-one\nkeep-3\nkeep-4\nnew-two\nkeep-6\n"
        ));
        storage.writeObject(REPO_KEY, "same-blob", bytes("same\n"));

        Commit base = new Commit("base", null);
        base.setMapping(Map.of(
                FILE_PATH, "old-blob",
                "src/Unchanged.java", "same-blob"
        ));
        Commit target = new Commit("target", BASE_SHA);
        target.setMapping(Map.of(
                FILE_PATH, "new-blob",
                "src/Unchanged.java", "same-blob"
        ));
        storage.writeObject(REPO_KEY, BASE_SHA, Utils.serialize(base));
        storage.writeObject(REPO_KEY, TARGET_SHA, Utils.serialize(target));
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
