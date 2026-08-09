package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.gitobject.ObjectStorageGitObjectReader;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.storage.FakeObjectStorage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class P0ToolRegistryTest {

    @Test
    void shouldExposeExactlyTheCompletedP0Tools() {
        ObjectMapper objectMapper = new ObjectMapper();
        var reader = new ObjectStorageGitObjectReader(new FakeObjectStorage());
        List<AgentTool> tools = List.of(
                new ListChangesTool(reader, objectMapper),
                new GetDiffTool(reader),
                new ReadFileTool(reader),
                new FinalizeReviewTool(objectMapper)
        );

        ToolRegistry registry = new ToolRegistry(tools);

        assertEquals(
                List.of("listChanges", "getDiff", "readFile", "finalizeReview"),
                registry.definitions().stream().map(definition -> definition.name()).toList()
        );
    }
}
