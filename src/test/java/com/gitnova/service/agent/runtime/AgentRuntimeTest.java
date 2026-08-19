package com.gitnova.service.agent.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolCall;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.model.*;
import com.gitnova.service.agent.prompt.PromptAssembler;
import com.gitnova.service.agent.prompt.PromptSection;
import com.gitnova.service.agent.review.ReviewVerifier;
import com.gitnova.service.agent.tool.*;
import com.gitnova.service.agent.tools.FinalizeReviewTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeTest {
    @Test
    void shouldCompleteTwoTurnReviewThroughToolObservation() throws JsonProcessingException {
        AgentRunContext context=new AgentRunContext("run-runtime-1",42L,"7/42","base-sha","target-sha");

        ObjectMapper objectMapper=new ObjectMapper();
        RecordingListChangesTool listChanges =new RecordingListChangesTool(objectMapper);
        FinalizeReviewTool finalizeReview =new FinalizeReviewTool(objectMapper);
        ToolRegistry toolRegistry=new ToolRegistry(List.of(listChanges, finalizeReview));

        ObjectNode listArguments=objectMapper.createObjectNode();
        ToolCall listCall=new ToolCall("call-list-1","listChanges",listArguments);
        ModelUsage firstUsage=new ModelUsage(100,10,110);
        ModelResponse firstResponse=new ModelResponse("response-1",null,List.of(listCall),firstUsage, ModelFinishReason.TOOL_CALLS);

        ObjectNode finalizeArguments = objectMapper.createObjectNode();
        finalizeArguments.put(
                "summary",
                "No actionable defects were found."
        );
        finalizeArguments.putArray("issues");
        ToolCall finalizeCall=new ToolCall("call-finalize-1","finalizeReview",finalizeArguments);
        ModelUsage secondUsage=new ModelUsage(140,20,160);
        ModelResponse secondResponse=new ModelResponse("response-2",null,List.of(finalizeCall),secondUsage,ModelFinishReason.TOOL_CALLS);

        FakeModelGateway fakeModelGateway=new FakeModelGateway();
        fakeModelGateway.enqueueResponse(firstResponse).enqueueResponse(secondResponse);

        PromptSection section = new PromptSection() {
            @Override
            public String key() {
                return "runtime-test";
            }

            @Override
            public int order() {
                return 10;
            }

            @Override
            public String render(AgentRunContext context) {
                return """
                <runtime_test>
                Inspect changes and finish through finalizeReview.
                </runtime_test>
                """;
            }
        };

        PromptAssembler promptAssembler=new PromptAssembler(List.of(section));
        AgentRuntimePolicy policy = new AgentRuntimePolicy(
                "fake-model",
                4,      // maxModelCalls
                4,      // maxToolCalls
                1,      // maxProtocolCorrections
                1,      // maxFinalDraftCorrections
                1024,   // maxOutputTokens
                0.0     // temperature
        );
        AgentRuntime runtime=new AgentRuntime(fakeModelGateway,promptAssembler,new MessageFactory(objectMapper),toolRegistry,new ReviewVerifier(),objectMapper,policy);
        AgentRunResult result = runtime.run(context);

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(
                AgentTerminationReason.FINALIZE_SUCCEEDED,
                result.terminationReason()
        );

        assertNotNull(result.reviewDraft());
        assertEquals(
                "No actionable defects were found.",
                result.reviewDraft().summary()
        );
        assertTrue(result.reviewDraft().issues().isEmpty());

        assertTrue(result.coverage().changesListed());
        assertEquals(2, result.modelCallCount());
        assertEquals(2, result.toolCallCount());
        assertEquals(
                List.of(firstUsage, secondUsage),
                result.modelUsages()
        );

        assertEquals(0, fakeModelGateway.remainingOutcomes());
        assertEquals(2, fakeModelGateway.receivedRequests().size());

        assertEquals(1, listChanges.invocationCount);

        assertSame(
                context,
                listChanges.receivedExecution.run()
        );

        assertEquals(
                "call-list-1",
                listChanges.receivedExecution.toolCallId()
        );

        assertEquals(
                0,
                listChanges.receivedExecution.turn()
        );

        assertEquals(
                listArguments,
                listChanges.receivedArguments
        );

        ModelRequest secondRequest =
                fakeModelGateway.receivedRequests().get(1);

        assertEquals(4, secondRequest.messages().size());

        ModelMessage assistantCall =
                secondRequest.messages().get(2);

        assertEquals(ModelRole.ASSISTANT, assistantCall.role());
        assertEquals(1, assistantCall.toolCalls().size());
        assertEquals(
                "call-list-1",
                assistantCall.toolCalls().get(0).id()
        );
        assertEquals(
                "listChanges",
                assistantCall.toolCalls().get(0).name()
        );

        ModelMessage observation =
                secondRequest.messages().get(3);

        assertEquals(ModelRole.TOOL, observation.role());
        assertEquals("call-list-1", observation.toolCallId());
        JsonNode observationJson =
                objectMapper.readTree(observation.content());

        assertEquals(
                ToolStatus.SUCCESS.name(),
                observationJson.path("status").asText()
        );

        assertEquals(
                0,
                observationJson
                        .path("payload")
                        .path("totalFiles")
                        .asInt()
        );

        assertFalse(
                observationJson.path("retryable").asBoolean()
        );

        assertFalse(
                observationJson.path("truncated").asBoolean()
        );
    }
    private static final class RecordingListChangesTool implements AgentTool {
        private final ToolDefinition toolDefinition;
        private ToolExecutionContext receivedExecution;
        private JsonNode receivedArguments;
        private int invocationCount;

        public RecordingListChangesTool(ObjectMapper objectMapper){
            ObjectNode schema=objectMapper.createObjectNode();
            schema.put("type","object");
            schema.set("properties",objectMapper.createObjectNode());
            schema.put("additionalProperties",false);
            this.toolDefinition=new ToolDefinition("listChanges","Returns changed files for this runtime test",schema);
        }

        @Override
        public ToolDefinition definition() {
            return this.toolDefinition;
        }

        @Override
        public ToolResult execute(ToolExecutionContext execution, JsonNode arguments) {
            this.receivedExecution =execution;
            this.receivedArguments=arguments;
            invocationCount++;

            ObjectNode payload= JsonNodeFactory.instance.objectNode();
            payload.putArray("files");
            payload.put("totalFiles",0);
            return ToolResult.success(payload);
        }

    }
}
