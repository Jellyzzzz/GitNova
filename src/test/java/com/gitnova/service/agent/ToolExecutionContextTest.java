package com.gitnova.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.service.agent.runtime.AgentExecutionContext;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.workspace.SnapshotScope;
import com.gitnova.service.agent.workspace.WorkspaceId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolExecutionContextTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldExposeHarnessOwnedAgentRunAndWorkspaceContext() {
        AgentRunContext run = run();
        WorkspaceId workspaceId = WorkspaceId.generate();
        AgentExecutionContext agent = AgentTestContexts.agent(run, workspaceId);

        ToolExecutionContext execution = new ToolExecutionContext(
                agent,
                1,
                "call-workspace"
        );

        assertSame(agent, execution.agent());
        assertSame(run, execution.run());
        assertEquals(workspaceId, execution.requireWorkspaceId());
        assertEquals(agent.capabilities(), execution.capabilities());
        assertEquals(1, execution.turn());
        assertEquals("call-workspace", execution.toolCallId());
    }

    @Test
    void shouldRejectInvalidHarnessMetadata() {
        AgentExecutionContext agent = AgentTestContexts.agent(run());
        assertThrows(
                NullPointerException.class,
                () -> new ToolExecutionContext(null, 0, "call-1")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolExecutionContext(agent, -1, "call-1")
        );
        assertThrows(
                NullPointerException.class,
                () -> new ToolExecutionContext(agent, 0, null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolExecutionContext(agent, 0, "   ")
        );
    }

    @Test
    void shouldSerializeNestedTrustedContextWithoutFlatteningModelArguments() throws Exception {
        ToolExecutionContext execution = new ToolExecutionContext(
                AgentTestContexts.agent(run()),
                3,
                "call-4"
        );

        JsonNode root = objectMapper.readTree(objectMapper.writeValueAsString(execution));

        assertEquals(3, root.path("turn").asInt());
        assertEquals("call-4", root.path("toolCallId").asText());
        assertEquals("run-1", root.path("agent").path("context").path("runId").asText());
        assertEquals("1/10", root.path("agent").path("context").path("repoKey").asText());
    }

    private AgentRunContext run() {
        return new AgentRunContext(
                "run-1",
                10L,
                "1/10",
                SnapshotScope.of("a".repeat(40))
        );
    }
}
