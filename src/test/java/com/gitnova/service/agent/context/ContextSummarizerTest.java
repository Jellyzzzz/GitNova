package com.gitnova.service.agent.context;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.gitnova.dto.ToolCall;
import com.gitnova.service.agent.context.ContextSummarizer.SummaryInput;
import com.gitnova.service.agent.context.ContextSummarizer.SummaryOutput;
import com.gitnova.service.agent.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextSummarizerTest {

    private final ModelUsage usage = new ModelUsage(80, 20, 100);
    private final FakeModelGateway gateway = new FakeModelGateway().enqueueResponse(
            new ModelResponse("response-1", "Inspect the failing test next.", List.of(),
                    usage, ModelFinishReason.STOP));
    private final ContextSummarizer summarizer = new ContextSummarizer(
            gateway, "test-model", 512, "summary-request-1");
    private final ModelMessage assistant = new ModelMessage(
            ModelRole.ASSISTANT, "The test failed; investigate the cause.", List.of(), null);
    private final InteractionGroup group = new InteractionGroup(
            "group-1", List.of(assistant), "task-1", 11, 17);
    private final ContextSummary previous = new ContextSummary(
            "summary-0", "session-1", null, 10, "Investigating a test failure.");

    @Test
    void shouldCreateFirstSummaryWithoutParent() {
        SummaryOutput output = summarizer.summarize(
                new SummaryInput("session-1", "Fix the test", null, List.of(group)));

        assertNull(output.summary().parentSummaryId());
        assertEquals("session-1", output.summary().sessionId());
        assertEquals(17, output.summary().throughSessionSequence());
        assertEquals("Inspect the failing test next.", output.summary().content());
        assertEquals(usage, output.usage());
        assertEquals("summary-request-1", output.requestId());

        ModelRequest request = gateway.receivedRequests().get(0);
        assertTrue(request.tools().isEmpty());
        assertEquals(List.of(ModelRole.SYSTEM, ModelRole.USER),
                request.messages().stream().map(ModelMessage::role).toList());
        assertTrue(request.messages().get(1).content().contains("11..17"));
        assertFalse(request.messages().get(1).content().contains("PREVIOUS SUMMARY"));
    }

    @Test
    void shouldUseLastGroupSequenceRatherThanGroupCountAndAllowSequenceGaps() {
        InteractionGroup later = new InteractionGroup(
                "group-2", List.of(assistant), "task-1", 30, 40);

        SummaryOutput output = summarizer.summarize(new SummaryInput(
                "session-1", "Fix the test", previous, List.of(group, later)));

        assertEquals("summary-0", output.summary().parentSummaryId());
        assertEquals(40, output.summary().throughSessionSequence());
        assertEquals(10, previous.throughSessionSequence());
        String source = gateway.receivedRequests().get(0).messages().get(1).content();
        assertTrue(source.contains(previous.content()));
        assertTrue(source.contains("11..17"));
        assertTrue(source.contains("30..40"));
    }

    @Test
    void shouldRejectNullInputBeforeCallingModel() {
        assertThrows(NullPointerException.class, () -> summarizer.summarize(null));
        assertTrue(gateway.receivedRequests().isEmpty());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n"})
    void shouldRequireSessionIdentityAndTaskText(String value) {
        Class<? extends RuntimeException> exceptionType = value == null
                ? NullPointerException.class : IllegalArgumentException.class;

        assertThrows(exceptionType,
                () -> new SummaryInput(value, "Fix the test", null, List.of(group)));
        assertThrows(exceptionType,
                () -> new SummaryInput("session-1", value, null, List.of(group)));
        assertTrue(gateway.receivedRequests().isEmpty());
    }

    @Test
    void shouldRejectNullListAndNullGroup() {
        assertThrows(NullPointerException.class,
                () -> new SummaryInput("session-1", "Fix the test", null, null));
        assertThrows(NullPointerException.class,
                () -> new SummaryInput("session-1", "Fix the test", null,
                        Arrays.asList(group, null)));
        assertTrue(gateway.receivedRequests().isEmpty());
    }

    @Test
    void shouldRejectEmptyGroupsBeforeCallingModel() {
        SummaryInput input = new SummaryInput("session-1", "Fix the test", null, List.of());

        assertThrows(IllegalArgumentException.class, () -> summarizer.summarize(input));
        assertTrue(gateway.receivedRequests().isEmpty());
    }

    @Test
    void shouldRejectSummaryFromAnotherSession() {
        SummaryInput input = new SummaryInput("session-2", "Fix the test", previous, List.of(group));

        assertThrows(IllegalArgumentException.class, () -> summarizer.summarize(input));
        assertTrue(gateway.receivedRequests().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"5, 9", "9, 12", "10, 12"})
    void shouldRejectGroupsInsideOrTouchingPreviousSummary(long start, long end) {
        InteractionGroup overlapping = new InteractionGroup(
                "old-group", List.of(assistant), "task-1", start, end);
        SummaryInput input = new SummaryInput(
                "session-1", "Fix the test", previous, List.of(overlapping));

        assertThrows(IllegalArgumentException.class, () -> summarizer.summarize(input));
        assertTrue(gateway.receivedRequests().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"2, 5", "11, 17", "15, 20", "17, 20"})
    void shouldRejectUnorderedOverlappingOrRepeatedGroups(long start, long end) {
        InteractionGroup invalidNext = new InteractionGroup(
                "group-2", List.of(assistant), "task-1", start, end);
        SummaryInput input = new SummaryInput(
                "session-1", "Fix the test", null, List.of(group, invalidNext));

        assertThrows(IllegalArgumentException.class, () -> summarizer.summarize(input));
        assertTrue(gateway.receivedRequests().isEmpty());
    }

    @Test
    void shouldRejectUnclosedGroupEvenAfterValidGroups() {
        ToolCall call = new ToolCall("call-1", "readFile", JsonNodeFactory.instance.objectNode());
        InteractionGroup open = new InteractionGroup("open-group", List.of(
                new ModelMessage(ModelRole.ASSISTANT, null, List.of(call), null)
        ), "task-1", 18, 18);
        SummaryInput input = new SummaryInput(
                "session-1", "Fix the test", previous, List.of(group, open));

        assertThrows(IllegalArgumentException.class, () -> summarizer.summarize(input));
        assertTrue(gateway.receivedRequests().isEmpty());
    }

    @Test
    void shouldSnapshotInputList() {
        List<InteractionGroup> original = new ArrayList<>(List.of(group));
        SummaryInput input = new SummaryInput("session-1", "Fix the test", null, original);
        original.clear();

        assertEquals(List.of(group), input.groupToCompact());
        assertThrows(UnsupportedOperationException.class, () -> input.groupToCompact().clear());
        assertEquals(17, summarizer.summarize(input).summary().throughSessionSequence());
    }

    @Test
    void shouldRejectNullResponse() {
        ContextSummarizer invalid = new ContextSummarizer(
                request -> null, "test-model", 512, "summary-request-1");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> invalid.summarize(new SummaryInput(
                        "session-1", "Fix the test", previous, List.of(group))));

        assertEquals("Summary model returned no response", exception.getMessage());
        assertEquals(10, previous.throughSessionSequence());
    }

    @ParameterizedTest
    @EnumSource(value = ModelFinishReason.class, names = {"LENGTH", "CONTENT_FILTER", "UNKNOWN"})
    void shouldRejectNonStopResponseEvenWithNonBlankText(ModelFinishReason reason) {
        FakeModelGateway invalidGateway = new FakeModelGateway().enqueueResponse(
                new ModelResponse("response-1", "Potentially incomplete summary", List.of(), usage, reason));
        ContextSummarizer invalid = new ContextSummarizer(
                invalidGateway, "test-model", 512, "summary-request-1");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> invalid.summarize(new SummaryInput(
                        "session-1", "Fix the test", previous, List.of(group))));

        assertEquals("Summary response must finish with STOP, received " + reason, exception.getMessage());
        assertEquals(1, invalidGateway.receivedRequests().size());
        assertEquals(10, previous.throughSessionSequence());
    }

    @Test
    void shouldRejectToolCallsEvenWhenResponseAlsoContainsSummaryText() {
        ToolCall call = new ToolCall("call-1", "readFile", JsonNodeFactory.instance.objectNode());
        FakeModelGateway invalidGateway = new FakeModelGateway().enqueueResponse(
                new ModelResponse("response-1", "I will read more code.", List.of(call),
                        usage, ModelFinishReason.TOOL_CALLS));
        ContextSummarizer invalid = new ContextSummarizer(
                invalidGateway, "test-model", 512, "summary-request-1");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> invalid.summarize(new SummaryInput(
                        "session-1", "Fix the test", previous, List.of(group))));

        assertEquals("Summary response must not contain tool calls", exception.getMessage());
        assertEquals(1, invalidGateway.receivedRequests().size());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void shouldRejectMissingOrBlankResponseText(String text) {
        FakeModelGateway invalidGateway = new FakeModelGateway().enqueueResponse(
                new ModelResponse("response-1", text, List.of(), usage, ModelFinishReason.STOP));
        ContextSummarizer invalid = new ContextSummarizer(
                invalidGateway, "test-model", 512, "summary-request-1");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> invalid.summarize(new SummaryInput(
                        "session-1", "Fix the test", previous, List.of(group))));

        assertEquals("Summary response must contain non-blank text", exception.getMessage());
        assertEquals(1, invalidGateway.receivedRequests().size());
    }

    @Test
    void shouldPreserveUnknownUsageWithoutAddingTokenValidation() {
        FakeModelGateway unknownUsageGateway = new FakeModelGateway().enqueueResponse(
                new ModelResponse("response-1", "Continue investigating.", List.of(),
                        ModelUsage.unknown(), ModelFinishReason.STOP));
        ContextSummarizer unknownUsageSummarizer = new ContextSummarizer(
                unknownUsageGateway, "test-model", 512, "summary-request-1");

        SummaryOutput output = unknownUsageSummarizer.summarize(new SummaryInput(
                "session-1", "Fix the test", previous, List.of(group)));

        assertEquals("Continue investigating.", output.summary().content());
        assertEquals(ModelUsage.unknown(), output.usage());
    }

    @Test
    void shouldPropagateGatewayFailureWithoutRetryingOrChangingPreviousSummary() {
        ModelGatewayException failure = new ModelGatewayException(
                ModelGatewayErrorCode.TIMEOUT, "Model request timed out", true, null);
        FakeModelGateway failingGateway = new FakeModelGateway().enqueueFailure(failure);
        ContextSummarizer failing = new ContextSummarizer(
                failingGateway, "test-model", 512, "summary-request-1");

        ModelGatewayException actual = assertThrows(ModelGatewayException.class,
                () -> failing.summarize(new SummaryInput(
                        "session-1", "Fix the test", previous, List.of(group))));

        assertSame(failure, actual);
        assertEquals(1, failingGateway.receivedRequests().size());
        assertEquals(10, previous.throughSessionSequence());
        assertEquals("Investigating a test failure.", previous.content());
    }
}
