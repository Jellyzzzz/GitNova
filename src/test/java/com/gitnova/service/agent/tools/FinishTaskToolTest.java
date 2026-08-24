package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.service.agent.runtime.AgentCapabilityPolicy;
import com.gitnova.service.agent.runtime.AgentExecutionContext;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.workspace.SnapshotScope;
import com.gitnova.service.agent.workspace.WorkspaceBinding;
import com.gitnova.service.agent.workspace.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinishTaskToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FinishTaskTool tool = new FinishTaskTool(objectMapper);

    @Test
    void shouldAcceptReadOnlyCompletionWithoutInventingCodeChanges() {
        ObjectNode arguments = emptyCompletion();

        ToolResult result = tool.execute(execution(), arguments);

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals(3, result.payload().path("expectedGeneration").asLong());
        assertEquals("Explained how generation protects mutations", result.payload().path("summary").asText());
        assertTrue(result.payload().path("findings").isEmpty());
        assertTrue(result.payload().path("claimedChangedFiles").isEmpty());
        assertTrue(result.payload().path("claimedValidations").isEmpty());
        assertTrue(tool.terminal());
        assertTrue(tool.concurrencySafe());
        assertEquals("finishTask", tool.definition().name());
    }

    @Test
    void shouldReturnAllModelClaimsAsStructuredUntrustedDraft() {
        ObjectNode arguments = emptyCompletion();
        arguments.put("summary", "Fixed stale Workspace mutations");
        ObjectNode finding = ((ArrayNode) arguments.path("findings")).addObject();
        finding.put("filePath", "src/main/java/WorkspaceGateway.java");
        finding.put("startLine", 40);
        finding.put("endLine", 45);
        finding.put("severity", "error");
        finding.put("category", "concurrency");
        finding.put("evidence", "The mutation did not compare expectedGeneration");
        finding.put("explanation", "A stale run could overwrite newer Workspace state");
        finding.put("suggestion", "Reject the mutation unless both generations match");
        finding.put("confidence", 0.97);
        ((ArrayNode) arguments.path("claimedChangedFiles"))
                .add("src/main/java/WorkspaceGateway.java")
                .add("src/test/java/WorkspaceGatewayTest.java");
        ObjectNode validation = ((ArrayNode) arguments.path("claimedValidations")).addObject();
        validation.putArray("argv").add("mvn").add("-q").add("test");
        validation.put("result", "passed");
        ((ArrayNode) arguments.path("risks")).add("Cross-JVM ownership is not covered yet");
        ((ArrayNode) arguments.path("followUps")).add("Add durable generation CAS");

        ToolResult result = tool.execute(execution(), arguments);

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals("ERROR", result.payload().path("findings").get(0).path("severity").asText());
        assertEquals(2, result.payload().path("claimedChangedFiles").size());
        assertEquals("mvn", result.payload().path("claimedValidations").get(0).path("argv").get(0).asText());
        assertEquals("passed", result.payload().path("claimedValidations").get(0).path("result").asText());
        assertFalse(result.retryable());
    }

    @Test
    void shouldRejectUnsafeOrDuplicateClaimedPaths() {
        ObjectNode unsafe = emptyCompletion();
        ((ArrayNode) unsafe.path("claimedChangedFiles")).add("../outside.java");

        ToolResult unsafeResult = tool.execute(execution(), unsafe);

        assertEquals(ToolStatus.INVALID_ARGUMENT, unsafeResult.status());
        assertEquals("INVALID_COMPLETION_DRAFT", unsafeResult.errorCode());

        ObjectNode duplicate = emptyCompletion();
        ((ArrayNode) duplicate.path("claimedChangedFiles"))
                .add("src/App.java")
                .add("src/App.java");

        ToolResult duplicateResult = tool.execute(execution(), duplicate);

        assertEquals(ToolStatus.INVALID_ARGUMENT, duplicateResult.status());
        assertEquals("INVALID_COMPLETION_DRAFT", duplicateResult.errorCode());
    }

    @Test
    void shouldRejectMalformedFindingAndValidationClaims() {
        ObjectNode malformedFinding = emptyCompletion();
        ObjectNode finding = ((ArrayNode) malformedFinding.path("findings")).addObject();
        finding.put("filePath", "src/App.java");
        finding.put("startLine", 8);
        finding.put("endLine", 4);
        finding.put("severity", "warning");
        finding.put("category", "correctness");
        finding.put("evidence", "line range is reversed");
        finding.put("explanation", "invalid evidence location");
        finding.put("suggestion", "provide a valid range");
        finding.put("confidence", 0.8);

        ToolResult findingResult = tool.execute(execution(), malformedFinding);

        assertEquals(ToolStatus.INVALID_ARGUMENT, findingResult.status());
        assertEquals("INVALID_COMPLETION_DRAFT", findingResult.errorCode());

        ObjectNode malformedValidation = emptyCompletion();
        ObjectNode validation = ((ArrayNode) malformedValidation.path("claimedValidations"))
                .addObject();
        validation.putArray("argv");
        validation.put("result", "passed");

        ToolResult validationResult = tool.execute(execution(), malformedValidation);

        assertEquals(ToolStatus.INVALID_ARGUMENT, validationResult.status());
        assertEquals("INVALID_COMPLETION_DRAFT", validationResult.errorCode());
    }

    @Test
    void shouldRejectModelSuppliedTrustedIdentityAtRegistryBoundary() {
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        ObjectNode arguments = emptyCompletion();
        arguments.put("workspaceId", WorkspaceId.generate().toString());

        ToolResult result = registry.execute(
                execution(),
                "finishTask",
                arguments
        );

        assertEquals(ToolStatus.INVALID_ARGUMENT, result.status());
        assertEquals("SCHEMA_VALIDATION_FAILED", result.errorCode());
    }

    @Test
    void shouldRejectNegativeGenerationBecauseClaimsMustBindOneWorkspaceVersion() {
        ObjectNode arguments = emptyCompletion();
        arguments.put("expectedGeneration", -1);

        ToolResult result = tool.execute(execution(), arguments);

        assertEquals(ToolStatus.INVALID_ARGUMENT, result.status());
        assertEquals("INVALID_COMPLETION_DRAFT", result.errorCode());
    }

    private ObjectNode emptyCompletion() {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("expectedGeneration", 3);
        arguments.put("summary", "Explained how generation protects mutations");
        arguments.putArray("findings");
        arguments.putArray("claimedChangedFiles");
        arguments.putArray("claimedValidations");
        arguments.putArray("risks");
        arguments.putArray("followUps");
        return arguments;
    }

    private ToolExecutionContext execution() {
        AgentRunContext run = new AgentRunContext(
                "run-1",
                10L,
                "1/10",
                SnapshotScope.of("a".repeat(40))
        );
        AgentExecutionContext agent = new AgentExecutionContext(
                "session-1",
                run,
                7L,
                "Inspect generation handling and fix it if necessary",
                new WorkspaceBinding(WorkspaceId.generate()),
                AgentCapabilityPolicy.cloudAgent()
        );
        return com.gitnova.service.agent.AgentTestContexts.toolExecution(agent, 2, "call-finish-1");
    }
}
