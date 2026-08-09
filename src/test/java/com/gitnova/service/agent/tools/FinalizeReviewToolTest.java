package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FinalizeReviewToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FinalizeReviewTool tool = new FinalizeReviewTool(objectMapper);

    @Test
    void shouldReturnStructuredReviewDraftWithoutSideEffects() {
        ObjectNode arguments = validArguments();

        ToolResult result = tool.execute(execution(), arguments);

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals("Found one concurrency issue", result.payload().path("summary").asText());
        assertEquals(1, result.payload().path("issues").size());
        assertEquals("ERROR", result.payload().path("issues").get(0).path("severity").asText());
        assertEquals(0.96, result.payload().path("issues").get(0).path("confidence").asDouble());
    }

    @Test
    void shouldRejectMalformedIssueInsteadOfInventingDefaults() {
        ObjectNode arguments = validArguments();
        ((ObjectNode) arguments.path("issues").get(0)).put("confidence", 1.5);

        ToolResult result = tool.execute(execution(), arguments);

        assertEquals(ToolStatus.INVALID_ARGUMENT, result.status());
        assertEquals("INVALID_REVIEW_DRAFT", result.errorCode());
    }

    private ObjectNode validArguments() {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("summary", "Found one concurrency issue");
        ArrayNode issues = arguments.putArray("issues");
        ObjectNode issue = issues.addObject();
        issue.put("filePath", "src/UserService.java");
        issue.put("startLine", 103);
        issue.put("endLine", 107);
        issue.put("severity", "error");
        issue.put("category", "concurrency");
        issue.put("evidence", "Mutable singleton state is overwritten");
        issue.put("explanation", "Concurrent reviews can observe another request");
        issue.put("suggestion", "Pass immutable request context explicitly");
        issue.put("confidence", 0.96);
        return arguments;
    }

    private ToolExecutionContext execution() {
        return new ToolExecutionContext(
                new AgentRunContext(
                        "run-1",
                        10L,
                        "1/10",
                        "base-sha",
                        "target-sha"
                ),
                0,
                "call-1"
        );
    }
}
