package com.gitnova.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolResult;

final class FakeAgentTool implements AgentTool {

    private final ToolDefinition definition;
    private final ToolResult resultToReturn;

    private ToolExecutionContext receivedExecution;
    private JsonNode receivedArguments;
    private int invocationCount;

    FakeAgentTool(
            String name,
            ToolResult resultToReturn
    ) {
        this(name, defaultInputSchema(), resultToReturn);
    }

    FakeAgentTool(
            String name,
            ObjectNode inputSchema,
            ToolResult resultToReturn
    ) {
        this.definition = new ToolDefinition(
                name,
                "Fake tool used for ToolRegistry tests",
                inputSchema
        );

        this.resultToReturn = resultToReturn;
    }

    private static ObjectNode defaultInputSchema() {
        ObjectNode inputSchema =
                JsonNodeFactory.instance.objectNode();

        inputSchema.put("type", "object");
        return inputSchema;
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(
            ToolExecutionContext execution,
            JsonNode arguments
    ) {
        this.receivedExecution = execution;
        this.receivedArguments = arguments;
        this.invocationCount++;

        return resultToReturn;
    }

    ToolExecutionContext receivedExecution() {
        return receivedExecution;
    }

    JsonNode receivedArguments() {
        return receivedArguments;
    }

    int invocationCount() {
        return invocationCount;
    }
}
