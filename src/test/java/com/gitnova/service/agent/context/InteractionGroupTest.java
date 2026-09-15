package com.gitnova.service.agent.context;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.gitnova.dto.ToolCall;
import com.gitnova.service.agent.model.ModelMessage;
import com.gitnova.service.agent.model.ModelRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InteractionGroupTest {

    @Test
    void shouldRejectNullAndEmptyMessagesBeforeReadingFirstElement() {
        assertThrows(NullPointerException.class,
                () -> new InteractionGroup("g1", null, "task1", 1, 1));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new InteractionGroup("g1", List.of(), "task1", 1, 1));
        assertEquals("messages must not be empty", error.getMessage());
    }

    @Test
    void shouldRequireGroupAndTaskIdentity() {
        assertThrows(NullPointerException.class,
                () -> new InteractionGroup(null, List.of(assistant()), "task1", 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new InteractionGroup(" ", List.of(assistant()), "task1", 1, 1));
        assertThrows(NullPointerException.class,
                () -> new InteractionGroup("g1", List.of(assistant()), null, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new InteractionGroup("g1", List.of(assistant()), " ", 1, 1));
    }

    @ParameterizedTest
    @EnumSource(value = ModelRole.class, names = {"SYSTEM", "USER", "TOOL"})
    void shouldRequireAssistantAsFirstMessage(ModelRole role) {
        ModelMessage message = new ModelMessage(role, "text", List.of(),
                role == ModelRole.TOOL ? "call1" : null);
        assertThrows(IllegalArgumentException.class,
                () -> new InteractionGroup("g1", List.of(message), "task1", 1, 1));
    }

    @ParameterizedTest
    @EnumSource(value = ModelRole.class, names = {"ASSISTANT", "SYSTEM"})
    void shouldRejectAdditionalAssistantOrSystemMessage(ModelRole role) {
        ModelMessage second = new ModelMessage(role, "text", List.of(), null);
        assertThrows(IllegalArgumentException.class,
                () -> new InteractionGroup("g1", List.of(assistant(), second), "task1", 1, 2));
    }

    @ParameterizedTest
    @CsvSource({"0,1", "-1,1", "2,1"})
    void shouldRejectInvalidSequenceRange(long first, long last) {
        assertThrows(IllegalArgumentException.class,
                () -> new InteractionGroup("g1", List.of(assistant()), "task1", first, last));
    }

    @ParameterizedTest
    @CsvSource({"1,1", "2,9"})
    void shouldAllowSingleEventAndGapsInSequenceRange(long first, long last) {
        InteractionGroup group = new InteractionGroup(
                "g1", List.of(assistant()), "task1", first, last);
        assertEquals(first, group.firstSessionSequence());
        assertEquals(last, group.lastSessionSequence());
        assertTrue(group.closed());
    }

    @Test
    void shouldCopyMessagesAndExposeUnmodifiableList() {
        List<ModelMessage> messages = new ArrayList<>(List.of(assistant()));
        InteractionGroup group = new InteractionGroup("g1", messages, "task1", 1, 1);
        messages.clear();
        assertEquals(1, group.messages().size());
        assertThrows(UnsupportedOperationException.class, () -> group.messages().clear());
    }

    @Test
    void shouldMatchMultipleCallsByIdRegardlessOfResultOrderOrSuccess() {
        ModelMessage failedResult = new ModelMessage(ModelRole.TOOL,
                "{\"status\":\"FAILED\"}", List.of(), "call2");
        assertTrue(group(assistant("call1", "call2"), failedResult, result("call1")).closed());
    }

    @Test
    void shouldRemainOpenWhenAResultIsMissing() {
        assertFalse(group(assistant("call1", "call2"), result("call1")).closed());
    }

    @Test
    void shouldRejectDuplicateCallIds() {
        assertFalse(group(assistant("call1", "call1"), result("call1")).closed());
    }

    @Test
    void shouldRejectUnknownOrDuplicateResults() {
        assertFalse(group(assistant("call1"), result("unknown")).closed());
        assertFalse(group(assistant("call1"), result("call1"), result("call1")).closed());
        assertFalse(group(assistant(), result("call1")).closed());
    }

    @Test
    void shouldNotAllowFeedbackToInterruptPendingToolResults() {
        ModelMessage feedback = new ModelMessage(ModelRole.USER, "Retry after reading results", List.of(), null);
        assertFalse(group(assistant("call1", "call2"), result("call1"), feedback, result("call2")).closed());
    }

    @Test
    void shouldAllowTrailingFeedbackAfterTextOrCompleteToolResults() {
        ModelMessage feedback = new ModelMessage(ModelRole.USER, "Please call finishTask", List.of(), null);
        assertTrue(group(assistant(), feedback).closed());
        assertTrue(group(assistant("call1"), result("call1"), feedback).closed());
    }

    private InteractionGroup group(ModelMessage... messages) {
        return new InteractionGroup("g1", List.of(messages), "task1", 1, messages.length);
    }

    private ModelMessage assistant(String... callIds) {
        List<ToolCall> calls = new ArrayList<>();
        for (String id : callIds) {
            calls.add(new ToolCall(id, "readFile", JsonNodeFactory.instance.objectNode()));
        }
        return new ModelMessage(ModelRole.ASSISTANT, "Inspect the files", calls, null);
    }

    private ModelMessage result(String callId) {
        return new ModelMessage(ModelRole.TOOL, "{\"status\":\"SUCCESS\"}", List.of(), callId);
    }
}
