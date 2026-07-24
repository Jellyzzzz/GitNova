package com.gitnova.service.agent;



import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.service.agent.tools.ToolExecutionContext;
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
}
