package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.workspace.SnapshotScope;
import com.gitnova.storage.FakeObjectStorage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.gitnova.gitobject.GitObjectTestFixtures.objectId;
import static com.gitnova.gitobject.GitObjectTestFixtures.reader;
import static com.gitnova.gitobject.GitObjectTestFixtures.writeCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GetDiffToolTest {

    private static final String REPO_KEY = "1/10";
    private static final String BASE_SHA = objectId('a');
    private static final String TARGET_SHA = objectId('b');
    private static final String OLD_BLOB_SHA = objectId('c');
    private static final String NEW_BLOB_SHA = objectId('d');
    private static final String SAME_BLOB_SHA = objectId('e');
    private static final String FILE_PATH = "src/Main.java";

    @Test
    void shouldReturnStablePaginatedHunksAndAcceptNullInitialCursor() {
        FakeObjectStorage storage = new FakeObjectStorage();
        writeTwoHunkScenario(storage);
        GetDiffTool tool = new GetDiffTool(
                reader(storage)
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
                reader(storage)
        );
        ObjectNode arguments = arguments();
        arguments.put("filePath", "src/Unchanged.java");

        ToolResult result = tool.execute(execution(), arguments);

        assertEquals(ToolStatus.PERMISSION_DENIED, result.status());
        assertEquals("FILE_OUTSIDE_CHANGE_SCOPE", result.errorCode());
    }

    @Test
    void shouldRejectSnapshotScopeForReviewOnlyTool() {
        GetDiffTool tool = new GetDiffTool(reader(new FakeObjectStorage()));
        ToolExecutionContext execution = com.gitnova.service.agent.AgentTestContexts.toolExecution(
                new AgentRunContext(
                        "context-1",
                        10L,
                        REPO_KEY,
                        SnapshotScope.of(BASE_SHA)
                ),
                0,
                "call-1"
        );

        ToolResult result = tool.execute(execution, arguments());

        assertEquals(ToolStatus.PERMISSION_DENIED, result.status());
        assertEquals("REVIEW_DIFF_SCOPE_REQUIRED", result.errorCode());
    }

    private ObjectNode arguments() {
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("filePath", FILE_PATH);
        arguments.put("maxHunks", 1);
        arguments.put("contextLines", 0);
        return arguments;
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

    private void writeTwoHunkScenario(FakeObjectStorage storage) {
        storage.writeObject(REPO_KEY, OLD_BLOB_SHA, bytes(
                "keep-1\nold-one\nkeep-3\nkeep-4\nold-two\nkeep-6\n"
        ));
        storage.writeObject(REPO_KEY, NEW_BLOB_SHA, bytes(
                "keep-1\nnew-one\nkeep-3\nkeep-4\nnew-two\nkeep-6\n"
        ));
        storage.writeObject(REPO_KEY, SAME_BLOB_SHA, bytes("same\n"));

        writeCommit(
                storage,
                REPO_KEY,
                BASE_SHA,
                null,
                "base",
                Map.of(
                        FILE_PATH, OLD_BLOB_SHA,
                        "src/Unchanged.java", SAME_BLOB_SHA
                )
        );
        writeCommit(
                storage,
                REPO_KEY,
                TARGET_SHA,
                BASE_SHA,
                "target",
                Map.of(
                        FILE_PATH, NEW_BLOB_SHA,
                        "src/Unchanged.java", SAME_BLOB_SHA
                )
        );
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
