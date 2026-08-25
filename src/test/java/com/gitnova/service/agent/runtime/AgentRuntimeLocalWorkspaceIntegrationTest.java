package com.gitnova.service.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolCall;
import com.gitnova.gitobject.GitObjectHasher;
import com.gitnova.gitobject.GitObjectReader;
import com.gitnova.service.agent.completion.CompletionDisposition;
import com.gitnova.service.agent.completion.CompletionInspector;
import com.gitnova.service.agent.model.FakeModelGateway;
import com.gitnova.service.agent.model.MessageFactory;
import com.gitnova.service.agent.model.ModelFinishReason;
import com.gitnova.service.agent.model.ModelGateway;
import com.gitnova.service.agent.model.ModelGatewayException;
import com.gitnova.service.agent.model.ModelMessage;
import com.gitnova.service.agent.model.ModelRequest;
import com.gitnova.service.agent.model.ModelResponse;
import com.gitnova.service.agent.model.ModelRole;
import com.gitnova.service.agent.model.ModelUsage;
import com.gitnova.service.agent.model.OpenAiCompatibleModelGateway;
import com.gitnova.service.agent.prompt.BudgetSection;
import com.gitnova.service.agent.prompt.OutputContractSection;
import com.gitnova.service.agent.prompt.PromptAssembler;
import com.gitnova.service.agent.prompt.PromptSection;
import com.gitnova.service.agent.prompt.QualityPolicySection;
import com.gitnova.service.agent.prompt.RepositoryScopeSection;
import com.gitnova.service.agent.prompt.RoleSection;
import com.gitnova.service.agent.prompt.SecuritySection;
import com.gitnova.service.agent.prompt.TaskSection;
import com.gitnova.service.agent.prompt.ToolPolicySection;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.tools.ApplyPatchTool;
import com.gitnova.service.agent.tools.FinishTaskTool;
import com.gitnova.service.agent.tools.FindFilesTool;
import com.gitnova.service.agent.tools.GetWorkspaceDiffTool;
import com.gitnova.service.agent.tools.ListFilesTool;
import com.gitnova.service.agent.tools.ReadFileTool;
import com.gitnova.service.agent.tools.RunCommandTool;
import com.gitnova.service.agent.tools.SearchTextTool;
import com.gitnova.service.agent.workspace.LocalWorkspaceGateway;
import com.gitnova.service.agent.workspace.LocalWorkspaceProvider;
import com.gitnova.service.agent.workspace.LocalWorkspaceRegistry;
import com.gitnova.service.agent.workspace.SnapshotScope;
import com.gitnova.service.agent.workspace.WorkspaceBinding;
import com.gitnova.service.agent.workspace.WorkspaceCommandExecutor;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.agent.workspace.WorkspaceHandle;
import com.gitnova.service.agent.workspace.WorkspaceId;
import com.gitnova.service.agent.workspace.WorkspaceMaterializer;
import com.gitnova.service.agent.workspace.WorkspaceSpec;
import com.gitnova.storage.FakeObjectStorage;
import com.gitnova.storage.RepoKey;
import com.gitnova.storage.config.WorkspaceStorageProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gitnova.gitobject.GitObjectTestFixtures.objectId;
import static com.gitnova.gitobject.GitObjectTestFixtures.reader;
import static com.gitnova.gitobject.GitObjectTestFixtures.writeCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vertical-slice test for the local Coding Agent path.
 *
 * <p>Only the model provider and command sandbox are deterministic fakes. Workspace
 * materialization, tool validation/dispatch, mutation, diffing, Runtime observations,
 * validation evidence, and completion inspection all use production implementations.</p>
 */
class AgentRuntimeLocalWorkspaceIntegrationTest {

    private static final String REPO_KEY = "1/10";
    private static final String BASE_SHA = objectId('a');
    private static final String CALCULATOR_PATH = "src/Calculator.java";
    private static final String README_PATH = "README.md";
    private static final String USER_NOTE_PATH = "USER_NOTE.md";
    private static final String USER_NOTE_CONTENT = "owner-note: preserve this concurrent edit\n";
    private static final List<String> VALIDATION_COMMAND =
            List.of("test-runner", "CalculatorTest");
    private static final String PRICE_PATH = "src/services/PriceService.java";
    private static final String DISCOUNT_PATH = "src/services/DiscountService.java";
    private static final String ACCESS_PATH = "src/services/AccessPolicy.java";
    private static final String CLAMP_PATH = "src/services/ClampService.java";
    private static final String RETRY_PATH = "src/services/RetryPolicy.java";
    private static final String FORMATTER_PATH = "src/services/NameFormatter.java";
    private static final String REGRESSION_SPEC_PATH = "src/test/ServiceRegressionSpec.java";
    private static final String STATUS_PATH = "STATUS.md";
    private static final List<String> BATCH_VALIDATION_COMMAND =
            List.of("test-runner", "ServiceRegressionSpec");
    private static final Set<String> EXPECTED_BATCH_CHANGED_FILES = Set.of(
            STATUS_PATH,
            ACCESS_PATH,
            DISCOUNT_PATH,
            PRICE_PATH
    );

    private static final String BROKEN_CALCULATOR = """
            class Calculator {
                int add(int left, int right) {
                    return left - right;
                }
            }
            """;

    private static final String FIXED_CALCULATOR = """
            class Calculator {
                int add(int left, int right) {
                    return left + right;
                }
            }
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void shouldReadPatchValidateDiffAndFinishAgainstOneRealLocalWorkspace() throws Exception {
        Fixture fixture = provisionWorkspace();
        FakeModelGateway modelGateway = scriptedCodingConversation();
        AgentRuntime runtime = runtime(modelGateway, fixture);

        AgentRunResult result = runtime.run(executionContext(fixture.handle().workspaceId()));

        // Final business outcome: the terminal claim was accepted only after canonical inspection.
        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(AgentTerminationReason.FINISH_SUCCEEDED, result.terminationReason());
        assertEquals(CompletionDisposition.CHANGES_READY, result.completionOutcome().disposition());
        assertEquals(5, result.modelCallCount());
        assertEquals(6, result.toolCallCount());
        assertEquals(6, result.successfulToolCallCount());
        assertEquals(5, result.modelUsages().size());

        // Real Workspace state: one two-file patch advanced generation exactly once.
        assertEquals(
                FIXED_CALCULATOR,
                Files.readString(fixture.handle().root().resolve(CALCULATOR_PATH))
        );
        assertEquals(
                "status: passing\n",
                Files.readString(fixture.handle().root().resolve(README_PATH))
        );
        WorkspaceGateway.WorkspaceRefresh refresh = fixture.gateway().refreshWorkspace(
                fixture.handle().workspaceId()
        );
        assertEquals(1, refresh.generationAfter());
        assertFalse(refresh.changed());

        // The model's file claims were not trusted; CompletionInspector recomputed this diff.
        assertEquals(
                List.of(README_PATH, CALCULATOR_PATH),
                result.completionOutcome().canonicalDiff().files().stream()
                        .map(WorkspaceGateway.DiffFile::filePath)
                        .toList()
        );
        assertTrue(result.completionOutcome().canonicalDiff().unifiedDiff()
                .contains("+        return left + right;"));
        assertEquals(VALIDATION_COMMAND, result.completionOutcome().validation().argv());
        assertEquals(1, result.completionOutcome().validation().generation());

        // Protocol evidence: both read observations retain their original provider call ids.
        assertEquals(0, modelGateway.remainingOutcomes());
        ModelRequest patchRequest = modelGateway.receivedRequests().get(1);
        List<ModelMessage> patchMessages = patchRequest.messages();
        assertEquals(5, patchMessages.size());
        assertEquals(ModelRole.TOOL, patchMessages.get(3).role());
        assertEquals("call-read-code", patchMessages.get(3).toolCallId());
        assertEquals(ModelRole.TOOL, patchMessages.get(4).role());
        assertEquals("call-read-readme", patchMessages.get(4).toolCallId());
        assertObservationSucceeded(patchMessages.get(3));
        assertObservationSucceeded(patchMessages.get(4));

        ModelRequest finishRequest = modelGateway.receivedRequests().get(4);
        ModelMessage diffObservation = finishRequest.messages()
                .get(finishRequest.messages().size() - 1);
        assertEquals(ModelRole.TOOL, diffObservation.role());
        assertEquals("call-diff", diffObservation.toolCallId());
        assertObservationSucceeded(diffObservation);
    }

    @Test
    @Tag("live-model")
    @EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = "\\S+")
    void shouldLetRealModelAutonomouslyCompleteTheLocalCodingTask() throws Exception {
        Fixture fixture = provisionWorkspace();
        String model = environmentOrDefault("LLM_MODEL", "deepseek-v4-flash");
        RecordingModelGateway modelGateway = new RecordingModelGateway(
                new OpenAiCompatibleModelGateway(
                        objectMapper,
                        System.getenv("LLM_API_KEY"),
                        environmentOrDefault("LLM_BASE_URL", "https://api.deepseek.com"),
                        90,
                        environmentOrDefault("LLM_THINKING_MODE", "disabled")
                ),
                fixture
        );
        AgentRuntime runtime = runtime(
                modelGateway,
                fixture,
                productionPromptAssembler(),
                new AgentRuntimePolicy(model, 12, 24, 2, 2, 4096, 0.0)
        );

        AgentRunResult result = runtime.run(executionContext(fixture.handle().workspaceId()));

        System.out.printf(
                "LIVE_LOCAL_CODING_RESULT model=%s status=%s reason=%s "
                        + "modelCalls=%d toolCalls=%d sequence=%s%n",
                model,
                result.status(),
                result.terminationReason(),
                result.modelCallCount(),
                result.toolCallCount(),
                modelGateway.toolSequence
        );
        if (modelGateway.lastFailure != null) {
            ModelGatewayException failure = modelGateway.lastFailure;
            System.out.printf(
                    "LIVE_LOCAL_CODING_GATEWAY_FAILURE code=%s retryable=%s "
                            + "providerStatus=%s providerCode=%s message=%s%n",
                    failure.errorCode(),
                    failure.retryable(),
                    failure.providerStatusCode(),
                    failure.providerErrorCode(),
                    failure.getMessage()
            );
        }

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(AgentTerminationReason.FINISH_SUCCEEDED, result.terminationReason());
        assertEquals(CompletionDisposition.CHANGES_READY, result.completionOutcome().disposition());
        assertEquals(FIXED_CALCULATOR, Files.readString(
                fixture.handle().root().resolve(CALCULATOR_PATH)
        ));
        assertEquals("status: passing\n", Files.readString(
                fixture.handle().root().resolve(README_PATH)
        ));
        assertEquals(
                List.of(README_PATH, CALCULATOR_PATH),
                result.completionOutcome().canonicalDiff().files().stream()
                        .map(WorkspaceGateway.DiffFile::filePath)
                        .toList()
        );
        assertEquals(1, result.completionOutcome().canonicalDiff().generation());
        assertEquals(VALIDATION_COMMAND, result.completionOutcome().validation().argv());
        assertEquals(1, result.completionOutcome().validation().generation());
        assertTrue(modelGateway.toolSequence.contains("readFile"));
        assertTrue(modelGateway.toolSequence.contains("applyPatch"));
        assertTrue(modelGateway.toolSequence.contains("runCommand"));
        assertTrue(modelGateway.toolSequence.contains("getWorkspaceDiff"));
        assertEquals(
                FinishTaskTool.NAME,
                modelGateway.toolSequence.get(modelGateway.toolSequence.size() - 1)
        );
    }

    @Test
    @Tag("live-model")
    @EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = "\\S+")
    void shouldLetRealModelRecoverFromConcurrentUserWorkspaceEdit() throws Exception {
        Fixture fixture = provisionWorkspace();
        String model = environmentOrDefault("LLM_MODEL", "deepseek-v4-flash");
        ModelGateway provider = new OpenAiCompatibleModelGateway(
                objectMapper,
                System.getenv("LLM_API_KEY"),
                environmentOrDefault("LLM_BASE_URL", "https://api.deepseek.com"),
                90,
                environmentOrDefault("LLM_THINKING_MODE", "disabled")
        );
        RecordingModelGateway modelGateway = new RecordingModelGateway(
                new DriftInjectingModelGateway(
                        provider,
                        fixture.handle().root().resolve(USER_NOTE_PATH),
                        USER_NOTE_CONTENT
                ),
                fixture
        );
        AgentRuntime runtime = runtime(
                modelGateway,
                fixture,
                productionPromptAssembler(),
                new AgentRuntimePolicy(model, 16, 32, 2, 3, 4096, 0.0)
        );

        AgentRunResult result = runtime.run(concurrentEditExecutionContext(
                fixture.handle().workspaceId()
        ));

        System.out.printf(
                "LIVE_WORKSPACE_DRIFT_RESULT model=%s status=%s reason=%s "
                        + "modelCalls=%d toolCalls=%d sequence=%s%n",
                model,
                result.status(),
                result.terminationReason(),
                result.modelCallCount(),
                result.toolCallCount(),
                modelGateway.toolSequence
        );
        if (modelGateway.lastFailure != null) {
            ModelGatewayException failure = modelGateway.lastFailure;
            System.out.printf(
                    "LIVE_WORKSPACE_DRIFT_GATEWAY_FAILURE code=%s retryable=%s "
                            + "providerStatus=%s providerCode=%s message=%s%n",
                    failure.errorCode(),
                    failure.retryable(),
                    failure.providerStatusCode(),
                    failure.providerErrorCode(),
                    failure.getMessage()
            );
        }

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(AgentTerminationReason.FINISH_SUCCEEDED, result.terminationReason());
        assertEquals(USER_NOTE_CONTENT, Files.readString(
                fixture.handle().root().resolve(USER_NOTE_PATH)
        ));
        assertEquals(FIXED_CALCULATOR, Files.readString(
                fixture.handle().root().resolve(CALCULATOR_PATH)
        ));
        assertEquals("status: passing\n", Files.readString(
                fixture.handle().root().resolve(README_PATH)
        ));
        assertEquals(
                Set.of(CALCULATOR_PATH, README_PATH, USER_NOTE_PATH),
                Set.copyOf(result.completionOutcome().canonicalDiff().files().stream()
                        .map(WorkspaceGateway.DiffFile::filePath)
                        .toList())
        );
        assertEquals(
                Set.of(CALCULATOR_PATH, README_PATH),
                Set.copyOf(result.completionOutcome().draft().agentModifiedFiles())
        );
        assertTrue(result.completionOutcome().canonicalDiff().generation() >= 2);
        assertEquals(
                result.completionOutcome().canonicalDiff().generation(),
                result.completionOutcome().validation().generation()
        );
        assertTrue(modelGateway.receivedRequests.stream()
                .flatMap(request -> request.messages().stream())
                .anyMatch(message -> message.role() == ModelRole.USER
                        && message.content().contains("authoritative at generation 1")));
        assertTrue(modelGateway.toolSequence.contains("applyPatch"));
        assertTrue(modelGateway.toolSequence.contains("runCommand"));
        assertEquals(
                FinishTaskTool.NAME,
                modelGateway.toolSequence.get(modelGateway.toolSequence.size() - 1)
        );
    }

    @Test
    @Tag("live-model")
    @EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = "\\S+")
    void shouldLetRealModelFixOnlyTheDefectiveFilesInAMediumBatch() throws Exception {
        Fixture fixture = provisionMediumBatchWorkspace();
        String model = environmentOrDefault("LLM_MODEL", "deepseek-v4-flash");
        RecordingModelGateway modelGateway = new RecordingModelGateway(
                new OpenAiCompatibleModelGateway(
                        objectMapper,
                        System.getenv("LLM_API_KEY"),
                        environmentOrDefault("LLM_BASE_URL", "https://api.deepseek.com"),
                        90,
                        environmentOrDefault("LLM_THINKING_MODE", "disabled")
                ),
                fixture
        );
        AgentRuntime runtime = runtime(
                modelGateway,
                fixture,
                productionPromptAssembler(),
                new AgentRuntimePolicy(model, 18, 48, 2, 2, 4096, 0.0)
        );

        AgentRunResult result = runtime.run(mediumBatchExecutionContext(
                fixture.handle().workspaceId()
        ));

        System.out.printf(
                "LIVE_MEDIUM_BATCH_RESULT model=%s status=%s reason=%s "
                        + "modelCalls=%d toolCalls=%d sequence=%s%n",
                model,
                result.status(),
                result.terminationReason(),
                result.modelCallCount(),
                result.toolCallCount(),
                modelGateway.toolSequence
        );
        if (modelGateway.lastFailure != null) {
            ModelGatewayException failure = modelGateway.lastFailure;
            System.out.printf(
                    "LIVE_MEDIUM_BATCH_GATEWAY_FAILURE code=%s retryable=%s "
                            + "providerStatus=%s providerCode=%s message=%s%n",
                    failure.errorCode(),
                    failure.retryable(),
                    failure.providerStatusCode(),
                    failure.providerErrorCode(),
                    failure.getMessage()
            );
        }

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(AgentTerminationReason.FINISH_SUCCEEDED, result.terminationReason());
        assertEquals(CompletionDisposition.CHANGES_READY, result.completionOutcome().disposition());
        assertTrue(Files.readString(fixture.handle().root().resolve(PRICE_PATH))
                .contains("return subtotal + tax;"));
        assertTrue(Files.readString(fixture.handle().root().resolve(DISCOUNT_PATH))
                .contains("return price - discount;"));
        assertTrue(Files.readString(fixture.handle().root().resolve(ACCESS_PATH))
                .contains("return authenticated && ownsResource;"));
        assertEquals("regressions fixed: 3/3\n", Files.readString(
                fixture.handle().root().resolve(STATUS_PATH)
        ));

        Map<String, String> originalFiles = mediumBatchFiles();
        assertEquals(originalFiles.get(CLAMP_PATH), Files.readString(
                fixture.handle().root().resolve(CLAMP_PATH)
        ));
        assertEquals(originalFiles.get(RETRY_PATH), Files.readString(
                fixture.handle().root().resolve(RETRY_PATH)
        ));
        assertEquals(originalFiles.get(FORMATTER_PATH), Files.readString(
                fixture.handle().root().resolve(FORMATTER_PATH)
        ));
        assertEquals(originalFiles.get(REGRESSION_SPEC_PATH), Files.readString(
                fixture.handle().root().resolve(REGRESSION_SPEC_PATH)
        ));

        Set<String> actualChangedFiles = Set.copyOf(
                result.completionOutcome().canonicalDiff().files().stream()
                        .map(WorkspaceGateway.DiffFile::filePath)
                        .toList()
        );
        assertEquals(EXPECTED_BATCH_CHANGED_FILES, actualChangedFiles);
        assertEquals(
                result.completionOutcome().canonicalDiff().generation(),
                result.completionOutcome().validation().generation()
        );
        assertEquals(BATCH_VALIDATION_COMMAND, result.completionOutcome().validation().argv());
        assertTrue(modelGateway.toolSequence.stream().anyMatch(name ->
                name.equals("listFiles")
                        || name.equals("findFiles")
                        || name.equals("searchText")
                        || name.equals("readFile")
        ));
        assertTrue(modelGateway.toolSequence.contains("applyPatch"));
        assertTrue(modelGateway.toolSequence.contains("runCommand"));
        assertTrue(modelGateway.toolSequence.contains("getWorkspaceDiff"));
        assertEquals(
                FinishTaskTool.NAME,
                modelGateway.toolSequence.get(modelGateway.toolSequence.size() - 1)
        );
    }

    private Fixture provisionWorkspace() {
        Map<String, String> files = Map.of(
                CALCULATOR_PATH, BROKEN_CALCULATOR,
                README_PATH, "status: failing\n",
                "src/CalculatorTest.java", "class CalculatorTest {}\n"
        );
        WorkspaceCommandExecutor commandExecutor = (workingDirectory, argv, timeout) -> {
            boolean passed = VALIDATION_COMMAND.equals(argv)
                    && FIXED_CALCULATOR.equals(Files.readString(
                    workingDirectory.resolve(CALCULATOR_PATH)
            ))
                    && "status: passing\n".equals(Files.readString(
                    workingDirectory.resolve(README_PATH)
            ));
            return commandResult(passed, "1 focused test passed");
        };
        return provisionWorkspace(files, "broken calculator", commandExecutor);
    }

    private Fixture provisionMediumBatchWorkspace() {
        Map<String, String> files = mediumBatchFiles();
        WorkspaceCommandExecutor commandExecutor = (workingDirectory, argv, timeout) -> {
            boolean passed = BATCH_VALIDATION_COMMAND.equals(argv)
                    && Files.readString(workingDirectory.resolve(PRICE_PATH))
                    .contains("return subtotal + tax;")
                    && Files.readString(workingDirectory.resolve(DISCOUNT_PATH))
                    .contains("return price - discount;")
                    && Files.readString(workingDirectory.resolve(ACCESS_PATH))
                    .contains("return authenticated && ownsResource;")
                    && "regressions fixed: 3/3\n".equals(Files.readString(
                    workingDirectory.resolve(STATUS_PATH)
            ))
                    && files.get(CLAMP_PATH).equals(Files.readString(
                    workingDirectory.resolve(CLAMP_PATH)
            ))
                    && files.get(RETRY_PATH).equals(Files.readString(
                    workingDirectory.resolve(RETRY_PATH)
            ))
                    && files.get(FORMATTER_PATH).equals(Files.readString(
                    workingDirectory.resolve(FORMATTER_PATH)
            ))
                    && files.get(REGRESSION_SPEC_PATH).equals(Files.readString(
                    workingDirectory.resolve(REGRESSION_SPEC_PATH)
            ));
            return commandResult(passed, "3 service regressions passed");
        };
        return provisionWorkspace(files, "three service regressions", commandExecutor);
    }

    private Fixture provisionWorkspace(
            Map<String, String> files,
            String commitMessage,
            WorkspaceCommandExecutor commandExecutor
    ) {
        FakeObjectStorage storage = new FakeObjectStorage();
        Map<String, String> mapping = new LinkedHashMap<>();
        files.forEach((path, content) -> {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            String blobId = GitObjectHasher.sha1(bytes).value();
            storage.writeObject(REPO_KEY, blobId, bytes);
            mapping.put(path, blobId);
        });
        writeCommit(
                storage,
                REPO_KEY,
                BASE_SHA,
                null,
                commitMessage,
                mapping
        );

        GitObjectReader objectReader = reader(storage);
        WorkspaceId workspaceId = WorkspaceId.generate();
        WorkspaceSpec spec = new WorkspaceSpec(
                workspaceId,
                RepoKey.parseCanonical(REPO_KEY),
                SnapshotScope.of(BASE_SHA)
        );
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                new WorkspaceStorageProperties(tempDir.resolve("workspaces")),
                new WorkspaceMaterializer(objectReader)
        );
        WorkspaceHandle handle = provider.provision(spec);

        LocalWorkspaceRegistry registry = new LocalWorkspaceRegistry();
        registry.register(handle);
        LocalWorkspaceGateway gateway = new LocalWorkspaceGateway(
                registry,
                objectReader,
                commandExecutor
        );
        return new Fixture(handle, gateway, objectReader);
    }

    private WorkspaceCommandExecutor.ProcessResult commandResult(
            boolean passed,
            String successMessage
    ) {
        return new WorkspaceCommandExecutor.ProcessResult(
                false,
                passed ? 0 : 1,
                40,
                passed ? successMessage : "",
                passed ? "" : "regression validation failed",
                false,
                false
        );
    }

    private Map<String, String> mediumBatchFiles() {
        return Map.ofEntries(
                Map.entry(PRICE_PATH, """
                        package services;

                        final class PriceService {
                            int total(int subtotal, int tax) {
                                return subtotal - tax;
                            }
                        }
                        """),
                Map.entry(DISCOUNT_PATH, """
                        package services;

                        final class DiscountService {
                            int discounted(int price, int discount) {
                                return price + discount;
                            }
                        }
                        """),
                Map.entry(ACCESS_PATH, """
                        package services;

                        final class AccessPolicy {
                            boolean mayDelete(boolean authenticated, boolean ownsResource) {
                                return authenticated || ownsResource;
                            }
                        }
                        """),
                Map.entry(CLAMP_PATH, """
                        package services;

                        final class ClampService {
                            int clamp(int value, int minimum, int maximum) {
                                return Math.max(minimum, Math.min(maximum, value));
                            }
                        }
                        """),
                Map.entry(RETRY_PATH, """
                        package services;

                        final class RetryPolicy {
                            boolean shouldRetry(int attempt, int maxAttempts) {
                                return attempt < maxAttempts;
                            }
                        }
                        """),
                Map.entry(FORMATTER_PATH, """
                        package services;

                        final class NameFormatter {
                            String normalize(String value) {
                                return value.trim();
                            }
                        }
                        """),
                Map.entry(REGRESSION_SPEC_PATH, """
                        package test;

                        final class ServiceRegressionSpec {
                            // PriceService.total(100, 20) must be 120.
                            // DiscountService.discounted(100, 20) must be 80.
                            // AccessPolicy.mayDelete(false, true) must be false.
                        }
                        """),
                Map.entry(STATUS_PATH, "regressions fixed: 0/3\n")
        );
    }

    private FakeModelGateway scriptedCodingConversation() {
        return new FakeModelGateway()
                .enqueueResponse(toolResponse(
                        "response-read",
                        call("call-read-code", "readFile", readArguments(CALCULATOR_PATH, 1, 20)),
                        call("call-read-readme", "readFile", readArguments(README_PATH, 1, 10))
                ))
                .enqueueResponse(toolResponse(
                        "response-patch",
                        call("call-patch", "applyPatch", patchArguments())
                ))
                .enqueueResponse(toolResponse(
                        "response-test",
                        call("call-test", "runCommand", commandArguments())
                ))
                .enqueueResponse(toolResponse(
                        "response-diff",
                        call("call-diff", "getWorkspaceDiff", objectMapper.createObjectNode())
                ))
                .enqueueResponse(toolResponse(
                        "response-finish",
                        call("call-finish", FinishTaskTool.NAME, finishArguments())
                ));
    }

    private AgentRuntime runtime(ModelGateway modelGateway, Fixture fixture) {
        return runtime(
                modelGateway,
                fixture,
                promptAssembler(),
                new AgentRuntimePolicy("fake-model", 8, 12, 1, 1, 2048, 0.0)
        );
    }

    private AgentRuntime runtime(
            ModelGateway modelGateway,
            Fixture fixture,
            PromptAssembler promptAssembler,
            AgentRuntimePolicy policy
    ) {
        List<AgentTool> tools = List.of(
                new ListFilesTool(fixture.gateway(), objectMapper),
                new FindFilesTool(fixture.gateway(), objectMapper),
                new SearchTextTool(fixture.gateway(), objectMapper),
                new ReadFileTool(fixture.objectReader(), fixture.gateway()),
                new ApplyPatchTool(fixture.gateway(), objectMapper),
                new RunCommandTool(fixture.gateway(), objectMapper),
                new GetWorkspaceDiffTool(fixture.gateway(), objectMapper),
                new FinishTaskTool(objectMapper)
        );
        return new AgentRuntime(
                modelGateway,
                promptAssembler,
                new MessageFactory(objectMapper),
                new ToolRegistry(tools),
                fixture.gateway(),
                new CompletionInspector(objectMapper, fixture.gateway()),
                policy
        );
    }

    private PromptAssembler promptAssembler() {
        PromptSection section = new PromptSection() {
            @Override
            public String key() {
                return "local-coding-integration";
            }

            @Override
            public int order() {
                return 10;
            }

            @Override
            public String render(AgentRunContext context) {
                return "Inspect the Workspace, make the requested changes, validate them, "
                        + "inspect the diff, and call finishTask alone.";
            }
        };
        return new PromptAssembler(List.of(section));
    }

    private PromptAssembler productionPromptAssembler() {
        return new PromptAssembler(List.of(
                new RoleSection(),
                new TaskSection(),
                new SecuritySection(),
                new RepositoryScopeSection(),
                new ToolPolicySection(),
                new QualityPolicySection(),
                new BudgetSection(),
                new OutputContractSection()
        ));
    }

    private AgentExecutionContext executionContext(WorkspaceId workspaceId) {
        return new AgentExecutionContext(
                "session-local-coding",
                new AgentRunContext(
                        "run-local-coding",
                        10L,
                        REPO_KEY,
                        SnapshotScope.of(BASE_SHA)
                ),
                7L,
                "Fix Calculator.add so it returns the sum, update README.md from status: failing "
                        + "to status: passing, validate the result by running argv "
                        + "[\"test-runner\", \"CalculatorTest\"] from working directory '.', "
                        + "inspect the final Workspace diff, and finish the task.",
                new WorkspaceBinding(workspaceId),
                AgentCapabilityPolicy.cloudAgent()
        );
    }

    private AgentExecutionContext mediumBatchExecutionContext(WorkspaceId workspaceId) {
        return new AgentExecutionContext(
                "session-medium-batch",
                new AgentRunContext(
                        "run-medium-batch",
                        10L,
                        REPO_KEY,
                        SnapshotScope.of(BASE_SHA)
                ),
                7L,
                "Inspect every Java file under src/services and the regression specification at "
                        + REGRESSION_SPEC_PATH + ". Fix exactly the three defective service "
                        + "implementations required by that specification without changing the "
                        + "already-correct services or the specification. Then update "
                        + STATUS_PATH + " from regressions fixed: 0/3 to regressions fixed: 3/3. "
                        + "Validate with argv [\"test-runner\", \"ServiceRegressionSpec\"] "
                        + "from working directory '.', inspect the final Workspace diff, and finish.",
                new WorkspaceBinding(workspaceId),
                AgentCapabilityPolicy.cloudAgent()
        );
    }

    private AgentExecutionContext concurrentEditExecutionContext(WorkspaceId workspaceId) {
        return new AgentExecutionContext(
                "session-workspace-drift",
                new AgentRunContext(
                        "run-workspace-drift",
                        10L,
                        REPO_KEY,
                        SnapshotScope.of(BASE_SHA)
                ),
                7L,
                "Fix Calculator.add so it returns the sum, update README.md from status: failing "
                        + "to status: passing, and preserve every concurrent user-created file. "
                        + "If the Workspace generation changes while you reason, accept the latest "
                        + "Workspace as authoritative, refresh your evidence, and retry stale writes. "
                        + "Validate with argv [\"test-runner\", \"CalculatorTest\"] from working "
                        + "directory '.', inspect the final Workspace diff, and finish the task.",
                new WorkspaceBinding(workspaceId),
                AgentCapabilityPolicy.cloudAgent()
        );
    }

    private ObjectNode readArguments(String path, int startLine, int endLine) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("revision", "WORKSPACE");
        arguments.put("filePath", path);
        arguments.put("startLine", startLine);
        arguments.put("endLine", endLine);
        return arguments;
    }

    private ObjectNode patchArguments() {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("expectedGeneration", 0);
        var operations = arguments.putArray("operations");
        operations.addObject()
                .put("type", "UPDATE")
                .put("filePath", CALCULATOR_PATH)
                .put(
                        "patch",
                        "@@ -1,5 +1,5 @@\n"
                                + " class Calculator {\n"
                                + "     int add(int left, int right) {\n"
                                + "-        return left - right;\n"
                                + "+        return left + right;\n"
                                + "     }\n"
                                + " }\n"
                );
        operations.addObject()
                .put("type", "UPDATE")
                .put("filePath", README_PATH)
                .put("patch", "@@ -1 +1 @@\n-status: failing\n+status: passing\n");
        return arguments;
    }

    private ObjectNode commandArguments() {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("expectedGeneration", 1);
        VALIDATION_COMMAND.forEach(arguments.putArray("argv")::add);
        arguments.put("workingDirectory", ".");
        arguments.put("timeoutSeconds", 30);
        arguments.put("purpose", "run the focused calculator test");
        return arguments;
    }

    private ObjectNode finishArguments() {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("expectedGeneration", 1);
        arguments.put("summary", "Fixed Calculator.add and updated the status document");
        arguments.putArray("findings");
        arguments.putArray("agentModifiedFiles")
                .add(CALCULATOR_PATH)
                .add(README_PATH);
        ObjectNode validation = arguments.putArray("claimedValidations").addObject();
        VALIDATION_COMMAND.forEach(validation.putArray("argv")::add);
        validation.put("result", "passed");
        arguments.putArray("risks");
        arguments.putArray("followUps");
        return arguments;
    }

    private ModelResponse toolResponse(String responseId, ToolCall... calls) {
        return new ModelResponse(
                responseId,
                null,
                List.of(calls),
                ModelUsage.unknown(),
                ModelFinishReason.TOOL_CALLS
        );
    }

    private ToolCall call(String id, String name, JsonNode arguments) {
        return new ToolCall(id, name, arguments);
    }

    private void assertObservationSucceeded(ModelMessage observation) throws Exception {
        JsonNode result = objectMapper.readTree(observation.content());
        assertEquals(ToolStatus.SUCCESS.name(), result.path("status").asText());
    }

    private static String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final class RecordingModelGateway implements ModelGateway {
        private final ModelGateway delegate;
        private final Fixture fixture;
        private final List<String> toolSequence = new java.util.ArrayList<>();
        private final List<ModelRequest> receivedRequests = new java.util.ArrayList<>();
        private ModelGatewayException lastFailure;

        private RecordingModelGateway(ModelGateway delegate, Fixture fixture) {
            this.delegate = delegate;
            this.fixture = fixture;
        }

        @Override
        public ModelResponse complete(ModelRequest request) {
            WorkspaceGateway.WorkspaceDiff canonicalDiff = fixture.gateway()
                    .getWorkspaceDiff(fixture.handle().workspaceId());
            long generationAtRequest = canonicalDiff.generation();
            request.messages().stream()
                    .filter(message -> message.role() == ModelRole.USER)
                    .map(ModelMessage::content)
                    .filter(content -> content.contains("The completion draft was rejected:"))
                    .reduce((first, second) -> second)
                    .ifPresent(feedback -> System.out.printf(
                            "LIVE_COMPLETION_CORRECTION request=%s currentGeneration=%d "
                                    + "canonicalFiles=%s feedback=%s%n",
                            request.requestId(),
                            generationAtRequest,
                            canonicalDiff.files().stream()
                                    .map(WorkspaceGateway.DiffFile::filePath)
                                    .toList(),
                            feedback.replace('\n', ' ')
                    ));
            receivedRequests.add(request);
            try {
                ModelResponse response = delegate.complete(request);
                response.toolCalls().forEach(call -> toolSequence.add(call.name()));
                response.toolCalls().stream()
                        .filter(call -> FinishTaskTool.NAME.equals(call.name()))
                        .forEach(call -> System.out.printf(
                                "LIVE_FINISH_DRAFT request=%s toolCallId=%s "
                                        + "currentGeneration=%d expectedGeneration=%s "
                                        + "agentModifiedFiles=%s claimedValidations=%s%n",
                                request.requestId(),
                                call.id(),
                                generationAtRequest,
                                call.arguments().path("expectedGeneration"),
                                call.arguments().path("agentModifiedFiles"),
                                call.arguments().path("claimedValidations")
                        ));
                System.out.printf(
                        "LIVE_LOCAL_CODING_TURN request=%s generationAtRequest=%d "
                                + "finish=%s tools=%s usage=%s%n",
                        request.requestId(),
                        generationAtRequest,
                        response.finishReason(),
                        response.toolCalls().stream().map(ToolCall::name).toList(),
                        response.usage()
                );
                return response;
            } catch (ModelGatewayException exception) {
                lastFailure = exception;
                throw exception;
            }
        }
    }

    private static final class DriftInjectingModelGateway implements ModelGateway {
        private final ModelGateway delegate;
        private final Path userFile;
        private final String content;
        private boolean injected;

        private DriftInjectingModelGateway(
                ModelGateway delegate,
                Path userFile,
                String content
        ) {
            this.delegate = delegate;
            this.userFile = userFile;
            this.content = content;
        }

        @Override
        public ModelResponse complete(ModelRequest request) {
            ModelResponse response = delegate.complete(request);
            if (!injected) {
                try {
                    Files.writeString(userFile, content);
                    injected = true;
                } catch (java.io.IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            }
            return response;
        }
    }

    private record Fixture(
            WorkspaceHandle handle,
            LocalWorkspaceGateway gateway,
            GitObjectReader objectReader
    ) {
    }
}
