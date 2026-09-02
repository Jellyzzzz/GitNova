package com.gitnova.service.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.gitobject.CanonicalGitObjectCodec;
import com.gitnova.gitobject.CommitObject;
import com.gitnova.gitobject.GitObjectHasher;
import com.gitnova.gitobject.GitObjectId;
import com.gitnova.gitobject.GitObjectReader;
import com.gitnova.gitobject.ObjectStorageGitObjectReader;
import com.gitnova.service.agent.model.MessageFactory;
import com.gitnova.service.agent.model.ModelGateway;
import com.gitnova.service.agent.model.ModelGatewayException;
import com.gitnova.service.agent.model.ModelRequest;
import com.gitnova.service.agent.model.ModelResponse;
import com.gitnova.service.agent.model.OpenAiCompatibleModelGateway;
import com.gitnova.service.agent.prompt.BudgetSection;
import com.gitnova.service.agent.prompt.OutputContractSection;
import com.gitnova.service.agent.prompt.PromptAssembler;
import com.gitnova.service.agent.prompt.PromptSection;
import com.gitnova.service.agent.prompt.RepositoryScopeSection;
import com.gitnova.service.agent.prompt.QualityPolicySection;
import com.gitnova.service.agent.prompt.RoleSection;
import com.gitnova.service.agent.prompt.SecuritySection;
import com.gitnova.service.agent.prompt.TaskSection;
import com.gitnova.service.agent.prompt.ToolPolicySection;
import com.gitnova.service.agent.review.ReviewIssueDraft;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tools.FinishTaskTool;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.agent.tools.GetDiffTool;
import com.gitnova.service.agent.tools.ListChangesTool;
import com.gitnova.service.agent.tools.ReadFileTool;
import com.gitnova.storage.LocalObjectStorage;
import com.gitnova.storage.ObjectStorage;
import com.gitnova.storage.config.RepositoryStorageProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in live-provider smoke that keeps repository data synthetic while using
 * the real Runtime, ToolRegistry, P0 tools, canonical codec, and local storage.
 */
@Tag("live-model")
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = "\\S+")
class LiveRealToolsAgentRuntimeSmokeTest {

    private static final String REPO_KEY = "7/42";
    private static final String FILE_PATH = "src/main/java/example/AverageCalculator.java";

    @TempDir
    Path tempDir;

    @Test
    void shouldInspectCanonicalRepositoryObjectsWithRealToolsAndFinalize() {
        ObjectMapper objectMapper = new ObjectMapper();
        CanonicalGitObjectCodec codec = new CanonicalGitObjectCodec();
        ObjectStorage storage = new LocalObjectStorage(
                new RepositoryStorageProperties(tempDir.resolve("objects"))
        );
        RevisionFixture revision = writeSyntheticRegression(storage, codec);
        GitObjectReader reader = new ObjectStorageGitObjectReader(storage, codec);

        RecordingTool listChanges = new RecordingTool(
                new ListChangesTool(reader, objectMapper)
        );
        RecordingTool getDiff = new RecordingTool(new GetDiffTool(reader));
        RecordingTool readFile = new RecordingTool(new ReadFileTool(reader));
        RecordingTool finishTask = new RecordingTool(
                new FinishTaskTool(objectMapper)
        );
        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                listChanges,
                getDiff,
                readFile,
                finishTask
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
        RecordingModelGateway modelGateway = new RecordingModelGateway(
                new OpenAiCompatibleModelGateway(
                        objectMapper,
                        System.getenv("LLM_API_KEY"),
                        baseUrl,
                        90,
                        thinkingMode
                )
        );

        PromptAssembler promptAssembler = new PromptAssembler(List.of(
                new RoleSection(),
                new TaskSection(),
                new LiveToolCoverageSection(),
                new SecuritySection(),
                new RepositoryScopeSection(),
                new ToolPolicySection(),
                new QualityPolicySection(),
                new BudgetSection(),
                new OutputContractSection()
        ));
        WorkspaceGateway workspaceGateway = AgentRuntimeLiveTestSupport.workspace();
        AgentRuntimePolicy policy = new AgentRuntimePolicy(
                model,
                8,
                12,
                2,
                2,
                2048,
                0.0
        );
        AgentExecutionConfig executionConfig =
                com.gitnova.service.agent.AgentTestExecutionConfigs.forRegistry(
                        toolRegistry,
                        policy
                );
        AgentRuntime runtime = new AgentRuntime(
                modelGateway,
                promptAssembler,
                new MessageFactory(objectMapper),
                toolRegistry,
                workspaceGateway,
                AgentRuntimeLiveTestSupport.inspector(objectMapper, workspaceGateway),
                com.gitnova.service.agent.AgentTestExecutionConfigs.resolver(toolRegistry)
        );

        AgentRunResult result = runtime.run(AgentRuntimeLiveTestSupport.execution(
                new AgentRunContext(
                        "live-real-tools-smoke",
                        42L,
                        REPO_KEY,
                        revision.baseSha1(),
                        revision.targetSha1()
                ),
                "Review the authorized change and report evidence-backed findings",
                executionConfig
        ));

        System.out.printf(
                "LIVE_REAL_TOOLS_RESULT model=%s status=%s reason=%s modelCalls=%d toolCalls=%d providerRequests=%d issues=%d%n",
                model,
                result.status(),
                result.terminationReason(),
                result.modelCallCount(),
                result.toolCallCount(),
                modelGateway.requestCount,
                AgentRuntimeLiveTestSupport.reviewDraft(result) == null
                        ? -1
                        : AgentRuntimeLiveTestSupport.reviewDraft(result).issues().size()
        );
        if (modelGateway.lastFailure != null) {
            ModelGatewayException failure = modelGateway.lastFailure;
            System.out.printf(
                    "LIVE_REAL_TOOLS_GATEWAY_FAILURE code=%s retryable=%s providerStatus=%s providerCode=%s message=%s%n",
                    failure.errorCode(),
                    failure.retryable(),
                    failure.providerStatusCode(),
                    failure.providerErrorCode(),
                    failure.getMessage()
            );
        }

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(
                AgentTerminationReason.FINISH_SUCCEEDED,
                result.terminationReason()
        );
        assertNotNull(AgentRuntimeLiveTestSupport.reviewDraft(result));
        assertTrue(listChanges.invocationCount >= 1);
        assertTrue(getDiff.invocationCount >= 1);
        assertTrue(readFile.invocationCount >= 1);
        assertTrue(finishTask.invocationCount >= 1);
        assertFalse(AgentRuntimeLiveTestSupport.reviewDraft(result).issues().isEmpty());
        assertTrue(AgentRuntimeLiveTestSupport.reviewDraft(result).issues().stream()
                .map(ReviewIssueDraft::filePath)
                .anyMatch(FILE_PATH::equals));

        System.out.printf(
                "LIVE_REAL_TOOLS_REVIEW summary=%s%n",
                AgentRuntimeLiveTestSupport.reviewDraft(result).summary()
        );
        for (ReviewIssueDraft issue : AgentRuntimeLiveTestSupport.reviewDraft(result).issues()) {
            System.out.printf(
                    "LIVE_REAL_TOOLS_ISSUE file=%s lines=%d-%d severity=%s category=%s confidence=%.2f evidence=%s explanation=%s suggestion=%s%n",
                    issue.filePath(),
                    issue.startLine(),
                    issue.endLine(),
                    issue.severity(),
                    issue.category(),
                    issue.confidence(),
                    issue.evidence(),
                    issue.explanation(),
                    issue.suggestion()
            );
        }
    }

    private RevisionFixture writeSyntheticRegression(
            ObjectStorage storage,
            CanonicalGitObjectCodec codec
    ) {
        byte[] baseFile = """
                package example;

                public final class AverageCalculator {
                    public int average(int total, int count) {
                        if (count <= 0) {
                            throw new IllegalArgumentException("count must be positive");
                        }
                        return total / count;
                    }
                }
                """.getBytes(StandardCharsets.UTF_8);
        byte[] targetFile = """
                package example;

                public final class AverageCalculator {
                    public int average(int total, int count) {
                        return total / count;
                    }
                }
                """.getBytes(StandardCharsets.UTF_8);

        GitObjectId baseBlob = writeObject(storage, baseFile);
        GitObjectId targetBlob = writeObject(storage, targetFile);

        CommitObject baseCommit = new CommitObject(
                Optional.empty(),
                Instant.parse("2026-08-20T00:00:00Z"),
                "validate average divisor",
                Map.of(FILE_PATH, baseBlob)
        );
        GitObjectId baseCommitId = writeObject(
                storage,
                codec.encodeCommit(baseCommit)
        );

        CommitObject targetCommit = new CommitObject(
                Optional.of(baseCommitId),
                Instant.parse("2026-08-20T00:05:00Z"),
                "simplify average calculation",
                Map.of(FILE_PATH, targetBlob)
        );
        GitObjectId targetCommitId = writeObject(
                storage,
                codec.encodeCommit(targetCommit)
        );
        return new RevisionFixture(baseCommitId.value(), targetCommitId.value());
    }

    private GitObjectId writeObject(ObjectStorage storage, byte[] content) {
        GitObjectId id = GitObjectHasher.sha1(content);
        storage.writeObject(REPO_KEY, id.value(), content);
        return id;
    }

    private static String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private record RevisionFixture(String baseSha1, String targetSha1) {
    }

    private static final class LiveToolCoverageSection implements PromptSection {
        @Override
        public String key() {
            return "live_tool_coverage";
        }

        @Override
        public int order() {
            return 45;
        }

        @Override
        public String render(AgentRunContext context) {
            return """
                    <live_tool_coverage>
                    This is an end-to-end capability check. Before finalizing, you must successfully call
                    listChanges, inspect every changed text file with getDiff, and read the TARGET version of
                    every changed text file with readFile. Base conclusions only on returned tool evidence.
                    </live_tool_coverage>
                    """;
        }
    }

    private static final class RecordingTool implements AgentTool {
        private final AgentTool delegate;
        private int invocationCount;

        private RecordingTool(AgentTool delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolDefinition definition() {
            return delegate.definition();
        }

        @Override
        public ToolResult execute(
                ToolExecutionContext execution,
                JsonNode arguments
        ) {
            invocationCount++;
            ToolResult result = delegate.execute(execution, arguments);
            System.out.printf(
                    "LIVE_REAL_TOOL_CALL turn=%d callId=%s name=%s status=%s errorCode=%s retryable=%s truncated=%s%n",
                    execution.turn(),
                    execution.toolCallId(),
                    definition().name(),
                    result.status(),
                    result.errorCode(),
                    result.retryable(),
                    result.truncated()
            );
            return result;
        }

        @Override
        public boolean terminal() {
            return delegate.terminal();
        }
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
}
