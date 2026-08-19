package com.gitnova.service.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.model.MessageFactory;
import com.gitnova.service.agent.model.ModelGateway;
import com.gitnova.service.agent.model.ModelGatewayException;
import com.gitnova.service.agent.model.ModelRequest;
import com.gitnova.service.agent.model.ModelResponse;
import com.gitnova.service.agent.model.OpenAiCompatibleModelGateway;
import com.gitnova.service.agent.prompt.BudgetSection;
import com.gitnova.service.agent.prompt.OutputContractSection;
import com.gitnova.service.agent.prompt.PromptAssembler;
import com.gitnova.service.agent.prompt.RepositoryScopeSection;
import com.gitnova.service.agent.prompt.ReviewPolicySection;
import com.gitnova.service.agent.prompt.RoleSection;
import com.gitnova.service.agent.prompt.SecuritySection;
import com.gitnova.service.agent.prompt.TaskSection;
import com.gitnova.service.agent.prompt.ToolPolicySection;
import com.gitnova.service.agent.review.ReviewVerifier;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tools.FinalizeReviewTool;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("live-model")
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = "\\S+")
class LiveAgentRuntimeSmokeTest {

    @Test
    void shouldCompleteReviewThroughRealModelToolCalls() {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingListChangesTool listChanges = new RecordingListChangesTool(objectMapper);
        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                listChanges,
                new FinalizeReviewTool(objectMapper)
        ));

        String baseUrl = environmentOrDefault(
                "LLM_BASE_URL",
                "https://api.deepseek.com"
        );
        String model = environmentOrDefault(
                "LLM_MODEL",
                "deepseek-v4-flash"
        );
        String thinkingMode = environmentOrDefault(
                "LLM_THINKING_MODE",
                "disabled"
        );

        OpenAiCompatibleModelGateway providerGateway =
                new OpenAiCompatibleModelGateway(
                        objectMapper,
                        System.getenv("LLM_API_KEY"),
                        baseUrl,
                        90,
                        thinkingMode
                );
        RecordingModelGateway modelGateway =
                new RecordingModelGateway(providerGateway);

        PromptAssembler promptAssembler = new PromptAssembler(List.of(
                new RoleSection(),
                new TaskSection(),
                new SecuritySection(),
                new RepositoryScopeSection(),
                new ToolPolicySection(),
                new ReviewPolicySection(),
                new BudgetSection(),
                new OutputContractSection()
        ));

        AgentRuntime runtime = new AgentRuntime(
                modelGateway,
                promptAssembler,
                new MessageFactory(objectMapper),
                toolRegistry,
                new ReviewVerifier(),
                objectMapper,
                new AgentRuntimePolicy(
                        model,
                        6,
                        6,
                        1,
                        1,
                        1024,
                        0.0
                )
        );

        AgentRunResult result = runtime.run(new AgentRunContext(
                "live-runtime-smoke",
                42L,
                "7/42",
                "base-smoke-sha",
                "target-smoke-sha"
        ));

        System.out.printf(
                "LIVE_AGENT_TRACE model=%s status=%s reason=%s modelCalls=%d toolCalls=%d providerRequests=%d%n",
                model,
                result.status(),
                result.terminationReason(),
                result.modelCallCount(),
                result.toolCallCount(),
                modelGateway.requestCount
        );
        if (modelGateway.lastFailure != null) {
            ModelGatewayException failure = modelGateway.lastFailure;
            System.out.printf(
                    "LIVE_GATEWAY_FAILURE code=%s retryable=%s providerStatus=%s providerCode=%s message=%s%n",
                    failure.errorCode(),
                    failure.retryable(),
                    failure.providerStatusCode(),
                    failure.providerErrorCode(),
                    failure.getMessage()
            );
        }

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(
                AgentTerminationReason.FINALIZE_SUCCEEDED,
                result.terminationReason()
        );
        assertNotNull(result.reviewDraft());
        assertTrue(result.coverage().changesListed());
        assertEquals(1, listChanges.invocationCount);
        assertTrue(result.modelCallCount() >= 2);
        assertTrue(result.toolCallCount() >= 2);

        System.out.printf(
                "LIVE_AGENT_RESULT model=%s status=%s reason=%s modelCalls=%d toolCalls=%d summary=%s%n",
                model,
                result.status(),
                result.terminationReason(),
                result.modelCallCount(),
                result.toolCallCount(),
                result.reviewDraft().summary()
        );
    }

    private static final class RecordingModelGateway implements ModelGateway {
        private final ModelGateway delegate;
        private int requestCount;
        private ModelGatewayException lastFailure;

        private RecordingModelGateway(ModelGateway delegate) {
            this.delegate = delegate;
        }

        @Override
        public ModelResponse complete(ModelRequest request) {
            requestCount++;
            try {
                return delegate.complete(request);
            } catch (ModelGatewayException exception) {
                lastFailure = exception;
                throw exception;
            }
        }
    }

    private static String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final class RecordingListChangesTool implements AgentTool {
        private final ToolDefinition definition;
        private final ObjectMapper objectMapper;
        private int invocationCount;

        private RecordingListChangesTool(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            schema.set("properties", objectMapper.createObjectNode());
            schema.put("additionalProperties", false);
            this.definition = new ToolDefinition(
                    "listChanges",
                    "List the files changed by the server-authorized revision range.",
                    schema
            );
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
            invocationCount++;
            ObjectNode payload = objectMapper.createObjectNode();
            payload.putArray("files");
            payload.put("totalFiles", 0);
            payload.put("totalHunks", 0);
            payload.put("totalAddedLines", 0);
            payload.put("totalDeletedLines", 0);
            payload.put("containsBinary", false);
            return ToolResult.success(payload);
        }
    }
}
