package com.gitnova.service.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.gitnova.dto.ToolCall;
import com.gitnova.service.agent.prompt.AssembledPrompt;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MessageFactory factory = new MessageFactory(objectMapper);

    @Test
    void shouldCreateSystemAndPreserveExplicitUserTask() {
        List<ModelMessage> messages = factory.initialMessages(
                new AssembledPrompt("prompt-1", "Operate only in the authorized Workspace."),
                "Explain how generation works"
        );

        assertEquals(2, messages.size());
        assertEquals(ModelRole.SYSTEM, messages.get(0).role());
        assertEquals("Operate only in the authorized Workspace.", messages.get(0).content());
        assertEquals(ModelRole.USER, messages.get(1).role());
        assertEquals("Explain how generation works", messages.get(1).content());
        assertTrue(messages.stream().allMatch(message -> message.toolCalls().isEmpty()));
        assertTrue(messages.stream().allMatch(message -> message.toolCallId() == null));
    }

    @Test
    void shouldPreserveToolOnlyAssistantResponse() {
        ToolCall call = toolCall("call-1", "listChanges");
        ModelResponse response = new ModelResponse(
                "response-1",
                null,
                List.of(call),
                ModelUsage.unknown(),
                ModelFinishReason.TOOL_CALLS
        );

        ModelMessage message = factory.assistant(response);

        assertEquals(ModelRole.ASSISTANT, message.role());
        assertNull(message.content());
        assertEquals(List.of(call), message.toolCalls());
        assertNull(message.toolCallId());
    }

    @Test
    void shouldSerializeCompleteSuccessfulToolResultAndPreserveCallId() throws Exception {
        JsonNode payload = JsonNodeFactory.instance.objectNode().put("totalFiles", 2);

        ModelMessage message = factory.tool(
                toolCall("call-2", "listChanges"),
                ToolResult.success(payload)
        );

        JsonNode observation = objectMapper.readTree(message.content());
        assertEquals(ModelRole.TOOL, message.role());
        assertEquals("call-2", message.toolCallId());
        assertTrue(message.toolCalls().isEmpty());
        assertEquals(ToolStatus.SUCCESS.name(), observation.path("status").asText());
        assertEquals(2, observation.path("payload").path("totalFiles").asInt());
        assertFalse(observation.path("retryable").asBoolean());
        assertFalse(observation.path("truncated").asBoolean());
    }

    @Test
    void shouldSerializeToolErrorMetadata() throws Exception {
        ModelMessage message = factory.tool(
                toolCall("call-3", "readFile"),
                ToolResult.error(ToolStatus.NOT_FOUND, "FILE_NOT_FOUND", "File does not exist", false)
        );

        JsonNode observation = objectMapper.readTree(message.content());
        assertEquals(ToolStatus.NOT_FOUND.name(), observation.path("status").asText());
        assertEquals("FILE_NOT_FOUND", observation.path("errorCode").asText());
        assertEquals("File does not exist", observation.path("message").asText());
        assertFalse(observation.path("retryable").asBoolean());
    }

    @Test
    void committedInlineResultCarriesRealSourceWithoutChangingTheStoredResult() throws Exception {
        var result = ToolResult.success(objectMapper.createObjectNode().put("exitCode", 1)
                .put("generationBefore", 3).put("generationAfter", 3)
                .put("stdout", "FAIL exact original line\n").put("stderr", "").put("stdoutTruncated", true), true);
        JsonNode before = objectMapper.valueToTree(result);
        var call = toolCall("command", "runCommand");
        var message = factory.tool(call, result, "session-a", 24);
        var observation = objectMapper.readTree(message.content());

        assertEquals(ModelRole.TOOL, message.role());
        assertEquals("command", message.toolCallId());
        assertEquals(before.path("payload"), observation.path("payload"));
        assertTrue(observation.path("truncated").asBoolean());
        assertEquals("session-a", observation.at("/source/sourceSessionId").asText());
        assertEquals(24, observation.at("/source/sourceStepSequence").asLong());
        assertEquals("artifact://tool-results/24/result.json", observation.at("/source/resources/result.json").asText());
        assertEquals("artifact://tool-results/24/stdout.txt", observation.at("/source/resources/stdout.txt").asText());
        assertEquals("artifact://tool-results/24/stderr.txt", observation.at("/source/resources/stderr.txt").asText());
        assertEquals(3, observation.at("/source/resources").size());
        assertFalse(observation.has("externalization"));
        assertEquals(before, objectMapper.valueToTree(result));
        assertFalse(objectMapper.readTree(factory.tool(call, result).content()).has("source"));
        assertThrows(IllegalArgumentException.class, () -> factory.tool(call, result, null, 24));
        assertThrows(IllegalArgumentException.class, () -> factory.tool(call, result, "session-a", -1));
    }

    @Test
    void inlinePathsUseTheReadersActualViewRegistry() throws Exception {
        var payload = objectMapper.createObjectNode().put("unifiedDiff", "--- old\n+++ new\n");
        payload.putArray("files").add("Main.java");
        payload.putArray("hunks");
        var observation = objectMapper.readTree(factory.tool(toolCall("diff", "getWorkspaceDiff"),
                ToolResult.success(payload), "session-a", 25).content());
        assertEquals("artifact://tool-results/25/diff.patch", observation.at("/source/resources/diff.patch").asText());
        assertEquals("artifact://tool-results/25/files.jsonl", observation.at("/source/resources/files.jsonl").asText());
        assertEquals("artifact://tool-results/25/hunks.jsonl", observation.at("/source/resources/hunks.jsonl").asText());
        assertFalse(observation.path("source").path("resources").has("stdout.txt"));

        var error = objectMapper.readTree(factory.tool(toolCall("missing", "readFile"),
                ToolResult.error(ToolStatus.NOT_FOUND, "FILE_NOT_FOUND", "Missing file", false), "session-a", 26).content());
        assertEquals("FILE_NOT_FOUND", error.path("errorCode").asText());
        assertEquals(1, error.at("/source/resources").size());
        assertEquals("artifact://tool-results/26/result.json", error.at("/source/resources/result.json").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"readFile", "searchText", "readArtifact"})
    void historyReadsDoNotAcquireAnotherSourceAddress(String toolName) throws Exception {
        var arguments = objectMapper.createObjectNode();
        if (toolName.equals("readFile")) arguments.put("filePath", "artifact://tool-results/24/stdout.txt");
        if (toolName.equals("searchText")) arguments.put("path", "artifact://tool-results/");
        var call = new ToolCall("read", toolName, arguments);
        var payload = objectMapper.createObjectNode().put("historical", true)
                .put("originRef", "artifact://tool-results/24/stdout.txt").put("sourceStepSequence", 24);
        var observation = objectMapper.readTree(factory.tool(call, ToolResult.success(payload), "session-a", 99).content());
        assertFalse(observation.has("source"));
        assertEquals(payload, observation.path("payload"));
        // Failed reads have no historical payload either; they must not advertise a copy-of-a-read.
        var error = objectMapper.readTree(factory.tool(call,
                ToolResult.error(ToolStatus.NOT_FOUND, "ARTIFACT_NOT_FOUND", "Unknown source", false), "session-a", 100).content());
        assertFalse(error.has("source"));
    }

    @Test
    void shouldSerializePartialStateAndPreserveCallId() throws Exception {
        JsonNode payload = JsonNodeFactory.instance.objectNode()
                .put("generationBefore", 3)
                .put("generationAfter", 4);

        ModelMessage message = factory.tool(
                toolCall("call-patch", "applyPatch"),
                ToolResult.partialSuccess(
                        payload,
                        "PATCH_OPERATION_FAILED",
                        "A confirmed prefix was applied before the patch failed"
                )
        );

        JsonNode observation = objectMapper.readTree(message.content());
        assertEquals(ModelRole.TOOL, message.role());
        assertEquals("call-patch", message.toolCallId());
        assertEquals(
                ToolStatus.PARTIAL_SUCCESS.name(),
                observation.path("status").asText()
        );
        assertEquals(4, observation.path("payload").path("generationAfter").asInt());
        assertEquals(
                "PATCH_OPERATION_FAILED",
                observation.path("errorCode").asText()
        );
        assertFalse(observation.path("retryable").asBoolean());
    }

    @Test
    void shouldCreateTaggedHarnessFeedbackWithoutToolBinding() {
        ModelMessage message = factory.harnessFeedback("Call finishTask alone after evidence gathering.");

        assertEquals(ModelRole.USER, message.role());
        assertTrue(message.content().startsWith("<runtime_feedback>"));
        assertTrue(message.content().contains("Call finishTask alone"));
        assertTrue(message.content().endsWith("</runtime_feedback>"));
        assertTrue(message.toolCalls().isEmpty());
        assertNull(message.toolCallId());
    }

    private static ToolCall toolCall(String id, String name) {
        return new ToolCall(id, name, JsonNodeFactory.instance.objectNode());
    }
}
