package com.gitnova.service.agent.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolCall;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.model.FakeModelGateway;
import com.gitnova.service.agent.model.MessageFactory;
import com.gitnova.service.agent.model.ModelFinishReason;
import com.gitnova.service.agent.model.ModelMessage;
import com.gitnova.service.agent.model.ModelRequest;
import com.gitnova.service.agent.model.ModelResponse;
import com.gitnova.service.agent.model.ModelRole;
import com.gitnova.service.agent.model.ModelUsage;
import com.gitnova.service.agent.prompt.PromptAssembler;
import com.gitnova.service.agent.prompt.PromptSection;
import com.gitnova.service.agent.review.ReviewVerifier;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.tools.FinalizeReviewTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeFinalDraftCorrectionTest {

    private static final String ISSUE_PATH = "src/Risky.java";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldReturnInvalidDraftObservationAndCompleteAfterModelCorrection()
            throws JsonProcessingException {
        FakeModelGateway gateway = new FakeModelGateway()
                .enqueueResponse(toolResponse(
                        "response-list",
                        call("call-list", "listChanges", objectMapper.createObjectNode())
                ))
                .enqueueResponse(toolResponse(
                        "response-invalid-finalize",
                        call("call-finalize-invalid", "finalizeReview", invalidDraftArguments())
                ))
                .enqueueResponse(toolResponse(
                        "response-valid-finalize",
                        call("call-finalize-valid", "finalizeReview", emptyValidDraftArguments())
                ));

        AgentRunResult result = runtime(gateway, 1, List.of(
                listChangesTool(),
                new FinalizeReviewTool(objectMapper)
        )).run(context());

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(AgentTerminationReason.FINALIZE_SUCCEEDED, result.terminationReason());
        assertNotNull(result.reviewDraft());
        assertEquals(3, result.modelCallCount());
        assertEquals(3, result.toolCallCount());

        ModelRequest correctionRequest = gateway.receivedRequests().get(2);
        List<ModelMessage> messages = correctionRequest.messages();
        assertEquals(7, messages.size());

        ModelMessage rejectedCall = messages.get(4);
        assertEquals(ModelRole.ASSISTANT, rejectedCall.role());
        assertEquals("call-finalize-invalid", rejectedCall.toolCalls().get(0).id());

        ModelMessage rejection = messages.get(5);
        assertEquals(ModelRole.TOOL, rejection.role());
        assertEquals("call-finalize-invalid", rejection.toolCallId());
        JsonNode rejectionJson = objectMapper.readTree(rejection.content());
        assertEquals(ToolStatus.INVALID_ARGUMENT.name(), rejectionJson.path("status").asText());
        assertEquals("INVALID_REVIEW_DRAFT", rejectionJson.path("errorCode").asText());

        ModelMessage feedback = messages.get(6);
        assertEquals(ModelRole.USER, feedback.role());
        assertTrue(feedback.content().contains("Correct the draft"));
        assertTrue(feedback.content().contains("finalizeReview alone"));
    }

    @Test
    void shouldTerminatePartialWhenFinalDraftCorrectionBudgetIsExhausted() {
        FakeModelGateway gateway = new FakeModelGateway()
                .enqueueResponse(toolResponse(
                        "response-list",
                        call("call-list", "listChanges", objectMapper.createObjectNode())
                ))
                .enqueueResponse(toolResponse(
                        "response-invalid-finalize-1",
                        call("call-finalize-invalid-1", "finalizeReview", invalidDraftArguments())
                ))
                .enqueueResponse(toolResponse(
                        "response-invalid-finalize-2",
                        call("call-finalize-invalid-2", "finalizeReview", invalidDraftArguments())
                ));

        AgentRunResult result = runtime(gateway, 1, List.of(
                listChangesTool(),
                new FinalizeReviewTool(objectMapper)
        )).run(context());

        assertEquals(AgentRunStatus.PARTIAL, result.status());
        assertEquals(AgentTerminationReason.INVALID_FINAL_DRAFT, result.terminationReason());
        assertNull(result.reviewDraft());
        assertTrue(result.coverage().changesListed());
        assertEquals(3, result.modelCallCount());
        assertEquals(3, result.toolCallCount());
        assertEquals(0, gateway.remainingOutcomes());
    }

    @Test
    void shouldAllowEvidenceGatheringAfterVerifierRejectsUninspectedIssue() {
        ObjectNode issueDraft = issueDraftArguments(ISSUE_PATH);
        FakeModelGateway gateway = new FakeModelGateway()
                .enqueueResponse(toolResponse(
                        "response-list",
                        call("call-list", "listChanges", objectMapper.createObjectNode())
                ))
                .enqueueResponse(toolResponse(
                        "response-unverified-finalize",
                        call("call-finalize-unverified", "finalizeReview", issueDraft)
                ))
                .enqueueResponse(toolResponse(
                        "response-read-evidence",
                        call("call-read", "readFile", readArguments(ISSUE_PATH))
                ))
                .enqueueResponse(toolResponse(
                        "response-verified-finalize",
                        call("call-finalize-verified", "finalizeReview", issueDraft.deepCopy())
                ));

        AgentRunResult result = runtime(gateway, 1, List.of(
                listChangesTool(),
                readFileTool(),
                new FinalizeReviewTool(objectMapper)
        )).run(context());

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(AgentTerminationReason.FINALIZE_SUCCEEDED, result.terminationReason());
        assertNotNull(result.reviewDraft());
        assertEquals(1, result.reviewDraft().issues().size());
        assertTrue(result.coverage().readFiles().contains(ISSUE_PATH));
        assertEquals(4, result.modelCallCount());
        assertEquals(4, result.toolCallCount());

        ModelRequest evidenceRequest = gateway.receivedRequests().get(2);
        ModelMessage feedback = evidenceRequest.messages()
                .get(evidenceRequest.messages().size() - 1);
        assertEquals(ModelRole.USER, feedback.role());
        assertTrue(feedback.content().contains("issue file was not inspected"));
        assertTrue(feedback.content().contains("Gather any missing tool evidence"));
        assertFalse(feedback.content().contains("Correct the draft and call finalizeReview alone"));
    }

    private AgentRuntime runtime(
            FakeModelGateway gateway,
            int maxFinalDraftCorrections,
            List<AgentTool> tools
    ) {
        PromptSection section = new PromptSection() {
            @Override
            public String key() {
                return "final-draft-correction-test";
            }

            @Override
            public int order() {
                return 10;
            }

            @Override
            public String render(AgentRunContext context) {
                return "Inspect the trusted change and finish through finalizeReview.";
            }
        };
        return new AgentRuntime(
                gateway,
                new PromptAssembler(List.of(section)),
                new MessageFactory(objectMapper),
                new ToolRegistry(tools),
                new ReviewVerifier(),
                objectMapper,
                new AgentRuntimePolicy(
                        "fake-model",
                        8,
                        8,
                        1,
                        maxFinalDraftCorrections,
                        1024,
                        0.0
                )
        );
    }

    private ModelResponse toolResponse(String responseId, ToolCall call) {
        return new ModelResponse(
                responseId,
                null,
                List.of(call),
                new ModelUsage(10, 5, 15),
                ModelFinishReason.TOOL_CALLS
        );
    }

    private ToolCall call(String id, String name, ObjectNode arguments) {
        return new ToolCall(id, name, arguments);
    }

    private ObjectNode invalidDraftArguments() {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("summary", "");
        arguments.putArray("issues");
        return arguments;
    }

    private ObjectNode emptyValidDraftArguments() {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("summary", "No actionable defects were found.");
        arguments.putArray("issues");
        return arguments;
    }

    private ObjectNode issueDraftArguments(String filePath) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("summary", "Found one correctness issue.");
        ObjectNode issue = arguments.putArray("issues").addObject();
        issue.put("filePath", filePath);
        issue.put("startLine", 10);
        issue.put("endLine", 10);
        issue.put("severity", "error");
        issue.put("category", "correctness");
        issue.put("evidence", "The changed branch returns the wrong value.");
        issue.put("explanation", "The returned value violates the method contract.");
        issue.put("suggestion", "Restore the contract-preserving branch.");
        issue.put("confidence", 0.95);
        return arguments;
    }

    private ObjectNode readArguments(String filePath) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("filePath", filePath);
        return arguments;
    }

    private AgentTool listChangesTool() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        schema.put("additionalProperties", false);
        return new SuccessfulTool(
                new ToolDefinition("listChanges", "List changed files", schema),
                objectMapper.createObjectNode().put("totalFiles", 1)
        );
    }

    private AgentTool readFileTool() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("filePath").put("type", "string");
        schema.putArray("required").add("filePath");
        schema.put("additionalProperties", false);
        return new SuccessfulTool(
                new ToolDefinition("readFile", "Read one changed file", schema),
                objectMapper.createObjectNode().put("content", "return wrongValue;")
        );
    }

    private AgentRunContext context() {
        return new AgentRunContext(
                "run-final-draft-correction",
                42L,
                "7/42",
                "base-sha",
                "target-sha"
        );
    }

    private record SuccessfulTool(
            ToolDefinition definition,
            JsonNode payload
    ) implements AgentTool {
        @Override
        public ToolResult execute(
                ToolExecutionContext execution,
                JsonNode arguments
        ) {
            return ToolResult.success(payload);
        }
    }
}
