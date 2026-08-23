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
import java.util.Set;

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

    @Test
    void shouldExposeOnlyToolsAllowedByTaskProfile() {
        FakeAgentTool readTool = new FakeAgentTool(
                "readFile",
                ToolResult.success(JsonNodeFactory.instance.objectNode())
        );
        FakeAgentTool writeTool = new FakeAgentTool(
                "applyPatch",
                ToolResult.success(JsonNodeFactory.instance.objectNode())
        );
        ToolRegistry registry = new ToolRegistry(List.of(readTool, writeTool));

        assertEquals(
                List.of("readFile"),
                registry.definitions(Set.of("readFile"))
                        .stream()
                        .map(definition -> definition.name())
                        .toList()
        );
    }

    @Test
    void shouldRejectForgedScopedCallWithoutExecutingTool() {
        FakeAgentTool writeTool = new FakeAgentTool(
                "applyPatch",
                ToolResult.success(JsonNodeFactory.instance.objectNode())
        );
        ToolRegistry registry = new ToolRegistry(List.of(writeTool));

        ToolResult result = registry.executeScoped(
                new ToolExecutionContext(createRunContext(), 0, "call-write"),
                Set.of(),
                "applyPatch",
                JsonNodeFactory.instance.objectNode()
        );

        assertEquals(ToolStatus.PERMISSION_DENIED, result.status());
        assertEquals("TOOL_NOT_ALLOWED", result.errorCode());
        assertEquals(0, writeTool.invocationCount());
    }

    @Test
    void shouldExecuteScopedCallWhenToolIsAllowed() {
        ToolResult expected = ToolResult.success(
                JsonNodeFactory.instance.objectNode()
        );
        FakeAgentTool writeTool = new FakeAgentTool("applyPatch", expected);
        ToolRegistry registry = new ToolRegistry(List.of(writeTool));

        ToolResult result = registry.executeScoped(
                new ToolExecutionContext(createRunContext(), 0, "call-write"),
                Set.of("applyPatch"),
                "applyPatch",
                JsonNodeFactory.instance.objectNode()
        );

        assertSame(expected, result);
        assertEquals(1, writeTool.invocationCount());
    }

    @Test
    void shouldFailFastWhenProfileReferencesUnregisteredTool() {
        ToolRegistry registry = new ToolRegistry(List.of(
                new FakeAgentTool(
                        "readFile",
                        ToolResult.success(JsonNodeFactory.instance.objectNode())
                )
        ));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> registry.definitions(Set.of("applyPatch"))
        );

        assertEquals(
                "Allowed tool is not registered: applyPatch",
                exception.getMessage()
        );
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
