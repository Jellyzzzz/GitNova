package com.gitnova.service.agent;



import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.service.agent.runtime.AgentCapability;
import com.gitnova.service.agent.runtime.AgentCapabilityPolicy;
import com.gitnova.service.agent.runtime.AgentExecutionContext;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.ToolAccessMode;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.workspace.SnapshotScope;
import com.gitnova.service.agent.workspace.WorkspaceBinding;
import com.gitnova.service.agent.workspace.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

    private AgentRunContext createRunContext() {
        return new AgentRunContext(
                "context-1",
                10L,
                "1/10",
                SnapshotScope.of("a".repeat(40))
        );
    }

    @Test
    void shouldExecuteRegisteredTool() {
        ObjectNode resultPayload =
                JsonNodeFactory.instance.objectNode();

        resultPayload.put("executed", true);

        ToolResult expectedResult =
                ToolResult.success(resultPayload);

        FakeAgentTool fakeTool =
                new FakeAgentTool(
                        "fakeTool",
                        schemaRequiringString("message"),
                        expectedResult
                );

        ToolRegistry registry =
                new ToolRegistry(
                        List.of(fakeTool)
                );

        ToolExecutionContext execution =
                com.gitnova.service.agent.AgentTestContexts.toolExecution(
                        createRunContext(),
                        0,
                        "call-1"
                );

        ObjectNode arguments =
                JsonNodeFactory.instance.objectNode();

        arguments.put("message", "hello");

        ToolResult actualResult =
                registry.execute(
                        execution,
                        "fakeTool",
                        arguments
                );

        assertSame(
                expectedResult,
                actualResult
        );

        assertEquals(
                1,
                fakeTool.invocationCount()
        );

        assertSame(
                execution,
                fakeTool.receivedExecution()
        );

        assertSame(
                arguments,
                fakeTool.receivedArguments()
        );
    }

    @Test
    void shouldRejectInvalidArgumentsWithoutExecutingTool() {
        FakeAgentTool fakeTool = new FakeAgentTool(
                "readFile",
                schemaRequiringString("path"),
                ToolResult.success(JsonNodeFactory.instance.objectNode())
        );
        ToolRegistry registry = new ToolRegistry(List.of(fakeTool));
        ToolExecutionContext execution = com.gitnova.service.agent.AgentTestContexts.toolExecution(
                createRunContext(),
                0,
                "call-1"
        );

        ToolResult result = registry.execute(
                execution,
                "readFile",
                JsonNodeFactory.instance.objectNode()
        );

        assertEquals(ToolStatus.INVALID_ARGUMENT, result.status());
        assertEquals("SCHEMA_VALIDATION_FAILED", result.errorCode());
        assertEquals(0, fakeTool.invocationCount());
    }

    @Test
    void shouldRejectUnknownArgumentsWithoutExecutingTool() {
        FakeAgentTool fakeTool = new FakeAgentTool(
                "listChanges",
                schemaRequiringString("path"),
                ToolResult.success(JsonNodeFactory.instance.objectNode())
        );
        ToolRegistry registry = new ToolRegistry(List.of(fakeTool));
        ToolExecutionContext execution = com.gitnova.service.agent.AgentTestContexts.toolExecution(
                createRunContext(),
                0,
                "call-1"
        );
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("path", "src/Main.java");
        arguments.put("repoKey", "model-must-not-control-this");

        ToolResult result = registry.execute(execution, "listChanges", arguments);

        assertEquals(ToolStatus.INVALID_ARGUMENT, result.status());
        assertEquals("SCHEMA_VALIDATION_FAILED", result.errorCode());
        assertEquals(0, fakeTool.invocationCount());
    }

    @Test
    void shouldFilterAndRecheckToolsByHarnessCapabilityPolicy() {
        FakeAgentTool readTool = new FakeAgentTool(
                "readFile",
                JsonNodeFactory.instance.objectNode().put("type", "object"),
                ToolResult.success(JsonNodeFactory.instance.objectNode()),
                ToolAccessMode.READ_ONLY
        );
        FakeAgentTool writeTool = new FakeAgentTool(
                "applyPatch",
                JsonNodeFactory.instance.objectNode().put("type", "object"),
                ToolResult.success(JsonNodeFactory.instance.objectNode()),
                ToolAccessMode.WORKSPACE_WRITE
        );
        ToolRegistry registry = new ToolRegistry(List.of(readTool, writeTool));
        AgentCapabilityPolicy readOnlyPolicy = new AgentCapabilityPolicy(
                Set.of(AgentCapability.CODE_READ)
        );

        assertEquals(
                List.of("readFile"),
                registry.definitions(readOnlyPolicy).stream()
                        .map(definition -> definition.name())
                        .toList()
        );

        AgentExecutionContext readOnlyContext = new AgentExecutionContext(
                "session-read-only",
                createRunContext(),
                1L,
                "Inspect without modifying",
                new WorkspaceBinding(WorkspaceId.generate()),
                readOnlyPolicy
        );
        ToolResult denied = registry.execute(
                new ToolExecutionContext(readOnlyContext, 0, "call-write"),
                "applyPatch",
                JsonNodeFactory.instance.objectNode()
        );

        assertEquals(ToolStatus.PERMISSION_DENIED, denied.status());
        assertEquals("MISSING_TOOL_CAPABILITY", denied.errorCode());
        assertEquals(0, writeTool.invocationCount());
    }

    private ObjectNode schemaRequiringString(String fieldName) {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.putObject("properties")
                .putObject(fieldName)
                .put("type", "string");
        schema.putArray("required").add(fieldName);
        schema.put("additionalProperties", false);
        return schema;
    }
}
