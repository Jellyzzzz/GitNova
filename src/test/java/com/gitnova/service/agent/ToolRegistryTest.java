package com.gitnova.service.agent;



import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

    private AgentRunContext createRunContext() {
        return new AgentRunContext(
                "run-1",
                10L,
                "1/10",
                null,
                "target-sha"
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
                new ToolExecutionContext(
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
        ToolExecutionContext execution = new ToolExecutionContext(
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
        ToolExecutionContext execution = new ToolExecutionContext(
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
