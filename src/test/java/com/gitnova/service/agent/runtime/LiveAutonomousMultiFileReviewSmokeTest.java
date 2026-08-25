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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in behavioral smoke: the production prompt decides which context tools
 * to use while reviewing multiple changed files with a known test oracle.
 */
@Tag("live-model")
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = "\\S+")
class LiveAutonomousMultiFileReviewSmokeTest {

    private static final String REPO_KEY = "7/42";
    private static final String AVERAGE_PATH =
            "src/main/java/example/AverageCalculator.java";
    private static final String ACCESS_PATH =
            "src/main/java/example/AccessPolicy.java";
    private static final String SAFE_REFACTOR_PATH =
            "src/main/java/example/SumCalculator.java";
    private static final Set<String> EXPECTED_ISSUE_PATHS = Set.of(
            AVERAGE_PATH,
            ACCESS_PATH
    );

    @TempDir
    Path tempDir;

    @Test
    void shouldAutonomouslyFindTwoDefectsWithoutFlaggingSafeRefactor() {
        ObjectMapper objectMapper = new ObjectMapper();
        CanonicalGitObjectCodec codec = new CanonicalGitObjectCodec();
        ObjectStorage storage = new LocalObjectStorage(
                new RepositoryStorageProperties(tempDir.resolve("objects"))
        );
        RevisionFixture revision = writeMultiFileScenario(storage, codec);
        GitObjectReader reader = new ObjectStorageGitObjectReader(storage, codec);

        List<String> toolSequence = new ArrayList<>();
        RecordingTool listChanges = recording(
                new ListChangesTool(reader, objectMapper),
                toolSequence
        );
        RecordingTool getDiff = recording(new GetDiffTool(reader), toolSequence);
        RecordingTool readFile = recording(new ReadFileTool(reader), toolSequence);
        RecordingTool finishTask = recording(
                new FinishTaskTool(objectMapper),
                toolSequence
        );
        ToolRegistry registry = new ToolRegistry(List.of(
                listChanges,
                getDiff,
                readFile,
                finishTask
        ));

        String model = environmentOrDefault("LLM_MODEL", "deepseek-v4-flash");
        RecordingModelGateway gateway = new RecordingModelGateway(
                new OpenAiCompatibleModelGateway(
                        objectMapper,
                        System.getenv("LLM_API_KEY"),
                        environmentOrDefault(
                                "LLM_BASE_URL",
                                "https://api.deepseek.com"
                        ),
                        90,
                        environmentOrDefault("LLM_THINKING_MODE", "disabled")
                )
        );
        PromptAssembler promptAssembler = new PromptAssembler(List.of(
                new RoleSection(),
                new TaskSection(),
                new SecuritySection(),
                new RepositoryScopeSection(),
                new ToolPolicySection(),
                new QualityPolicySection(),
                new BudgetSection(),
                new OutputContractSection()
        ));
        WorkspaceGateway workspaceGateway = AgentRuntimeLiveTestSupport.workspace();
        AgentRuntime runtime = new AgentRuntime(
                gateway,
                promptAssembler,
                new MessageFactory(objectMapper),
                registry,
                workspaceGateway,
                AgentRuntimeLiveTestSupport.inspector(objectMapper, workspaceGateway),
                new AgentRuntimePolicy(model, 12, 24, 2, 2, 4096, 0.0)
        );

        AgentRunResult result = runtime.run(AgentRuntimeLiveTestSupport.execution(
                new AgentRunContext(
                        "live-autonomous-multi-file",
                        42L,
                        REPO_KEY,
                        revision.baseSha1(),
                        revision.targetSha1()
                ),
                "Review every changed file and report concrete correctness or security defects"
        ));

        Set<String> actualIssuePaths = AgentRuntimeLiveTestSupport.reviewDraft(result) == null
                ? Set.of()
                : AgentRuntimeLiveTestSupport.reviewDraft(result).issues().stream()
                        .map(ReviewIssueDraft::filePath)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> falsePositivePaths = new LinkedHashSet<>(actualIssuePaths);
        falsePositivePaths.removeAll(EXPECTED_ISSUE_PATHS);
        Set<String> missedIssuePaths = new LinkedHashSet<>(EXPECTED_ISSUE_PATHS);
        missedIssuePaths.removeAll(actualIssuePaths);

        System.out.printf(
                "LIVE_AUTONOMOUS_MULTI_RESULT model=%s status=%s reason=%s modelCalls=%d toolCalls=%d providerRequests=%d sequence=%s expected=%s actual=%s missed=%s falsePositives=%s%n",
                model,
                result.status(),
                result.terminationReason(),
                result.modelCallCount(),
                result.toolCallCount(),
                gateway.requestCount,
                toolSequence,
                EXPECTED_ISSUE_PATHS,
                actualIssuePaths,
                missedIssuePaths,
                falsePositivePaths
        );
        if (gateway.lastFailure != null) {
            ModelGatewayException failure = gateway.lastFailure;
            System.out.printf(
                    "LIVE_AUTONOMOUS_MULTI_GATEWAY_FAILURE code=%s retryable=%s providerStatus=%s providerCode=%s message=%s%n",
                    failure.errorCode(),
                    failure.retryable(),
                    failure.providerStatusCode(),
                    failure.providerErrorCode(),
                    failure.getMessage()
            );
        }
        if (AgentRuntimeLiveTestSupport.reviewDraft(result) != null) {
            System.out.printf(
                    "LIVE_AUTONOMOUS_MULTI_REVIEW summary=%s%n",
                    AgentRuntimeLiveTestSupport.reviewDraft(result).summary()
            );
            for (ReviewIssueDraft issue : AgentRuntimeLiveTestSupport.reviewDraft(result).issues()) {
                System.out.printf(
                        "LIVE_AUTONOMOUS_MULTI_ISSUE file=%s lines=%d-%d severity=%s category=%s confidence=%.2f evidence=%s explanation=%s suggestion=%s%n",
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

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(
                AgentTerminationReason.FINISH_SUCCEEDED,
                result.terminationReason()
        );
        assertNotNull(AgentRuntimeLiveTestSupport.reviewDraft(result));
        assertEquals(EXPECTED_ISSUE_PATHS, actualIssuePaths);
        assertFalse(actualIssuePaths.contains(SAFE_REFACTOR_PATH));
        assertTrue(listChanges.invocationCount >= 1);
        assertTrue(getDiff.invocationCount + readFile.invocationCount >= 1);
        assertTrue(finishTask.invocationCount >= 1);
    }

    private RevisionFixture writeMultiFileScenario(
            ObjectStorage storage,
            CanonicalGitObjectCodec codec
    ) {
        Map<String, GitObjectId> baseMapping = new LinkedHashMap<>();
        Map<String, GitObjectId> targetMapping = new LinkedHashMap<>();

        addChangedFile(
                storage,
                baseMapping,
                targetMapping,
                AVERAGE_PATH,
                """
                        package example;

                        public final class AverageCalculator {
                            public int average(int total, int count) {
                                if (count <= 0) {
                                    throw new IllegalArgumentException("count must be positive");
                                }
                                return total / count;
                            }
                        }
                        """,
                """
                        package example;

                        public final class AverageCalculator {
                            public int average(int total, int count) {
                                return total / count;
                            }
                        }
                        """
        );
        addChangedFile(
                storage,
                baseMapping,
                targetMapping,
                ACCESS_PATH,
                """
                        package example;

                        public final class AccessPolicy {
                            public boolean mayDelete(
                                    boolean authenticated,
                                    boolean ownsResource,
                                    boolean administrator
                            ) {
                                return authenticated && (ownsResource || administrator);
                            }
                        }
                        """,
                """
                        package example;

                        public final class AccessPolicy {
                            public boolean mayDelete(
                                    boolean authenticated,
                                    boolean ownsResource,
                                    boolean administrator
                            ) {
                                return authenticated || ownsResource || administrator;
                            }
                        }
                        """
        );
        addChangedFile(
                storage,
                baseMapping,
                targetMapping,
                SAFE_REFACTOR_PATH,
                """
                        package example;

                        public final class SumCalculator {
                            public int sum(int left, int right) {
                                int total = left + right;
                                return total;
                            }
                        }
                        """,
                """
                        package example;

                        public final class SumCalculator {
                            public int sum(int left, int right) {
                                return left + right;
                            }
                        }
                        """
        );

        CommitObject baseCommit = new CommitObject(
                Optional.empty(),
                Instant.parse("2026-08-20T00:00:00Z"),
                "establish calculator and access invariants",
                baseMapping
        );
        GitObjectId baseCommitId = writeObject(
                storage,
                codec.encodeCommit(baseCommit)
        );
        CommitObject targetCommit = new CommitObject(
                Optional.of(baseCommitId),
                Instant.parse("2026-08-20T00:05:00Z"),
                "simplify validation and access checks",
                targetMapping
        );
        GitObjectId targetCommitId = writeObject(
                storage,
                codec.encodeCommit(targetCommit)
        );
        return new RevisionFixture(baseCommitId.value(), targetCommitId.value());
    }

    private void addChangedFile(
            ObjectStorage storage,
            Map<String, GitObjectId> baseMapping,
            Map<String, GitObjectId> targetMapping,
            String path,
            String baseContent,
            String targetContent
    ) {
        baseMapping.put(
                path,
                writeObject(storage, baseContent.getBytes(StandardCharsets.UTF_8))
        );
        targetMapping.put(
                path,
                writeObject(storage, targetContent.getBytes(StandardCharsets.UTF_8))
        );
    }

    private GitObjectId writeObject(ObjectStorage storage, byte[] content) {
        GitObjectId id = GitObjectHasher.sha1(content);
        storage.writeObject(REPO_KEY, id.value(), content);
        return id;
    }

    private RecordingTool recording(AgentTool tool, List<String> sequence) {
        return new RecordingTool(tool, sequence);
    }

    private static String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private record RevisionFixture(String baseSha1, String targetSha1) {
    }

    private static final class RecordingTool implements AgentTool {
        private final AgentTool delegate;
        private final List<String> sequence;
        private int invocationCount;

        private RecordingTool(AgentTool delegate, List<String> sequence) {
            this.delegate = delegate;
            this.sequence = sequence;
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
            sequence.add(definition().name());
            ToolResult result = delegate.execute(execution, arguments);
            System.out.printf(
                    "LIVE_AUTONOMOUS_MULTI_TOOL turn=%d callId=%s name=%s arguments=%s status=%s errorCode=%s retryable=%s truncated=%s%n",
                    execution.turn(),
                    execution.toolCallId(),
                    definition().name(),
                    arguments,
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
