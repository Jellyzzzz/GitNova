package com.gitnova.service.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.entity.agent.AgentSessionEntity;
import com.gitnova.entity.agent.AgentStepEntity;
import com.gitnova.mapper.agent.AgentSessionMapper;
import com.gitnova.mapper.agent.AgentStepMapper;
import com.gitnova.service.agent.AgentTestExecutionConfigs;
import com.gitnova.service.agent.execution.AgentExecutionControl;
import com.gitnova.service.agent.journal.*;
import com.gitnova.service.agent.model.*;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.runtime.*;
import com.gitnova.service.agent.workspace.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real projection/summary/assembly; in-memory committed rows emulate the database boundary, not a MySQL test. */
class ContextPreparationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentStepMapper steps = mock(AgentStepMapper.class);
    private final AgentSessionMapper sessions = mock(AgentSessionMapper.class);
    private final AgentEventAppender appender = mock(AgentEventAppender.class);
    private final ModelGateway model = mock(ModelGateway.class);
    private final ContextUsage usage = mock(ContextUsage.class);
    private final AgentExecutionControl execution = new AgentExecutionControl();
    private final List<AgentStepEntity> history = new ArrayList<>();
    private final List<AgentEventAppender.AppendResult> commits = new ArrayList<>();
    private final SessionContextService service = new SessionContextService(steps, sessions, appender, mapper, new MessageFactory(mapper));
    private final RunJournalScope scope = new RunJournalScope("session", "current", "run", "worker", 1, "a".repeat(64));
    private final ContextBudget budget = new ContextBudget(1200, 0, .8, .9, 1);
    private final ContextBudget compactionBudget = new ContextBudget(1200, 0, .8, .9, 1, true, .6);
    private final ModelMessage system = new ModelMessage(ModelRole.SYSTEM, "Trusted system", List.of(), null);
    private AgentExecutionContext context;
    private ContextAssembler assembler;
    private ModelRequest request;
    private long originalTokens = 900; // (900 - fixed 100) / dynamic budget 1000 == a
    private long candidateTokens = 300;
    private long beforeLocalTokens = 1000;
    private long compactedTokens = 400;
    private long protectedTokens = 200;

    @BeforeEach
    void setUp() {
        when(steps.latestSessionSequence("session")).thenAnswer(call -> (long) history.size());
        when(steps.selectSessionHistory(eq("session"), anyLong(), anyLong(), eq(256))).thenAnswer(call -> history.stream()
                .filter(row -> row.getSessionSequence() > (long) call.getArgument(1)
                        && row.getSessionSequence() <= (long) call.getArgument(2)).limit(256).toList());
        when(steps.selectLatestContextSummary(eq("session"), anyLong())).thenAnswer(call -> latest("CONTEXT_SUMMARY_CREATED"));
        when(steps.selectLatestContextControl(eq("session"), anyLong())).thenAnswer(call -> latest("CONTEXT_CONTROL_UPDATED"));
        when(sessions.selectForUpdate("session")).thenAnswer(call -> {
            var row = new AgentSessionEntity();
            row.setLastSessionSequence((long) history.size());
            return row;
        });
        when(appender.appendFence(any(), any())).thenAnswer(call -> {
            AgentEventAppender.AppendCommand command = call.getArgument(0);
            assertEquals(new AgentEventAppender.RunExecutionAuthority(1, "worker"), call.getArgument(1));
            var row = add(command.stepType().name(), command.taskId(), command.runId(), command.persistedPayload());
            row.setSchemaVersion(command.schemaVersion());
            row.setEventId(command.eventId());
            return new AgentEventAppender.AppendResult(history.size(), history.size(), (long) history.size(), false);
        });
        when(usage.measure(any())).thenAnswer(call -> {
            ModelRequest input = call.getArgument(0);
            boolean summarized = input.messages().stream().anyMatch(message -> message.content() != null
                    && message.content().contains("<session_summary>"));
            String text = input.messages().toString();
            long count = text.contains("authority.\n\n</session_summary>") ? protectedTokens
                    : text.contains("Compact checkpoint") ? compactedTokens
                    : text.contains("Previous durable summary") ? originalTokens : summarized ? candidateTokens : originalTokens;
            return new ContextUsage.Measurement("model", "a".repeat(64), "b".repeat(64), input.messages().size(),
                    count, 100, "LOCAL_ESTIMATE");
        });
        when(usage.estimateInput(any())).thenAnswer(call -> {
            ModelRequest input = call.getArgument(0);
            String text = input.messages().toString();
            return text.contains("Compact checkpoint") ? compactedTokens : text.contains("Previous durable summary")
                    ? beforeLocalTokens : text.contains("<session_summary>") ? candidateTokens : beforeLocalTokens;
        });
        when(model.complete(any())).thenReturn(new ModelResponse("summary-response", "Keep the public API unchanged.",
                List.of(), new ModelUsage(200, 30, 230), ModelFinishReason.STOP));
        var id = WorkspaceId.generate();
        context = new AgentExecutionContext("session", new AgentRunContext("run", 1L, "1/1", SnapshotScope.of("a".repeat(40))),
                1L, "Add tests", new WorkspaceBinding(id), new WorkspaceExecutionPermit("run", id, 1), AgentTestExecutionConfigs.minimal());
        user("old", "Do not change the public API");
        add("HARNESS_FEEDBACK", "old", "old-run", new HarnessFeedbackPayload("f", HarnessFeedbackKind.WORKSPACE_DRIFT, "Old standalone feedback"));
        assistant("old", "old-run", "Old detailed investigation");
        user("current", "Add tests");
        add("HARNESS_FEEDBACK", "current", "run", new HarnessFeedbackPayload("f2", HarnessFeedbackKind.WORKSPACE_DRIFT, "Current generation is 7"));
        assistant("current", "run", "Recent exact investigation");
        request = new ModelRequest("model", service.load("session").modelMessages(system, null, "current", "Add tests"),
                List.of(), 100, null, "request-1");
        assembler = assembler(null);
    }

    @Test
    void belowThresholdDoesNotReloadOrSummarize() {
        originalTokens = 899;
        clearInvocations(steps);
        assertEquals(request.messages(), assembler.assemble(request, budget, usage, context));
        verifyNoInteractions(model, appender);
        verifyNoInteractions(steps);
    }

    @Test
    void disablingSummaryRetainsSessionHistoryAndStillEnforcesInputLimit() {
        var disabled = new ContextBudget(1200, 0, .8, .9, 1, false);
        originalTokens = 1100; // Above both triggers, but fits the input limit.
        assertSame(request.messages(), assembler.assemble(request, disabled, usage, context));
        assertTrue(request.messages().toString().contains("Old detailed investigation"));
        assertFalse(assembler.attemptedSummary());
        verifyNoInteractions(model, appender);
        originalTokens = 1101;
        assertThrows(ContextAssembler.PreparationException.class,
                () -> assembler.assemble(request, disabled, usage, context));
        verifyNoInteractions(model, appender);
    }

    @ParameterizedTest
    @ValueSource(longs = {900, 1000, 1101}) // exactly a, exactly b, and above the input limit: summary first
    void thresholdsSummarizeOnceAndPreserveSystemTaskAndStandaloneFeedback(long inputTokens) {
        originalTokens = inputTokens;
        var result = assembler.assemble(request, budget, usage, context);
        assertSame(system, result.get(0));
        assertEquals(1, result.stream().filter(m -> m.role() == ModelRole.SYSTEM).count());
        assertTrue(result.get(1).content().contains("<session_summary>"));
        assertEquals(1, result.stream().filter(m -> "Add tests".equals(m.content())).count());
        assertTrue(result.toString().contains("Current generation is 7"));
        assertTrue(result.toString().contains("Recent exact investigation"));
        assertFalse(result.toString().contains("Old detailed investigation"));
        var modelRequest = org.mockito.ArgumentCaptor.forClass(ModelRequest.class);
        verify(model).complete(modelRequest.capture());
        assertTrue(modelRequest.getValue().messages().toString().contains("Do not change the public API"));
        assertTrue(modelRequest.getValue().messages().toString().contains("Old standalone feedback"));
        assertTrue(modelRequest.getValue().tools().isEmpty());
        assertNotNull(latest("CONTEXT_SUMMARY_CREATED"));
        assertTrue(latest("CONTEXT_SUMMARY_RESULT").getPayloadJson().contains("230"));
        verify(usage, never()).accept(any(), any()); // Summary tokens never become main usage.
        assertEquals(3, commits.size()); // disarm, summary result, summary publication
        assertEquals(history.size(), assembler.throughSessionSequence());
    }

    @Test
    void candidateProjectionIsPureAndPinsCurrentTaskEvenWhenItsRequestWasSummarized() {
        var source = service.load("session");
        var summary = new ContextSummary("candidate", "session", null, 6, "Condensed");
        var preview = source.modelMessages(system, summary, "current", "Add tests");
        assertEquals(List.of(ModelRole.SYSTEM, ModelRole.USER, ModelRole.USER), preview.stream().map(ModelMessage::role).toList());
        assertEquals("Add tests", preview.get(2).content());
        assertNull(service.load("session").summary());
        verifyNoInteractions(appender);
    }

    @Test
    void newMessagesDuringSummaryAreNotLost() {
        doAnswer(call -> {
            user("next", "Do not rename tests");
            return new ModelResponse("r", "Condensed", List.of(), ModelUsage.unknown(), ModelFinishReason.STOP);
        }).when(model).complete(any());
        var messages = assembler.assemble(request, budget, usage, context);
        assertEquals("Do not rename tests", messages.get(messages.size() - 1).content());
    }

    @Test
    void summaryFailureKeepsOriginalAndIsNotRepeatedByANewRunInstance() {
        doThrow(new IllegalStateException("Bad summary response")).when(model).complete(any());
        assertEquals(request.messages(), assembler.assemble(request, budget, usage, context));
        var restored = service.load("session").control();
        assertFalse(restored.summaryArmed());
        assertTrue(restored.urgentArmed());
        var nextRun = assembler(restored);
        assertEquals(request.messages(), nextRun.assemble(nextRequest(), budget, usage, context));
        verify(model, times(1)).complete(any());
        assertNull(latest("CONTEXT_SUMMARY_CREATED"));
    }

    @Test
    void summaryGatewayFailureKeepsRawHistoryAndBoundsRetriesAcrossRuns() {
        doThrow(new ModelGatewayException(ModelGatewayErrorCode.PROVIDER_UNAVAILABLE,
                "Temporary provider failure", true, null)).when(model).complete(any());

        assertEquals(request.messages(), assembler.assemble(request, budget, usage, context));
        assertTrue(latest("CONTEXT_SUMMARY_RESULT").getPayloadJson().contains("FAILED_ModelGatewayException"));
        var restored = assembler(service.load("session").control());
        assertEquals(request.messages(), restored.assemble(nextRequest(), budget, usage, context));
        verify(model, times(1)).complete(any());

        originalTokens = 1000; // The urgent threshold permits one further attempt, then stops.
        assertThrows(ContextAssembler.PreparationException.class,
                () -> restored.assemble(nextRequest(), budget, usage, context));
        var afterFailure = assembler(service.load("session").control());
        assertThrows(ContextAssembler.PreparationException.class,
                () -> afterFailure.assemble(nextRequest(), budget, usage, context));
        verify(model, times(2)).complete(any());
        assertNull(latest("CONTEXT_SUMMARY_CREATED"));
        assertTrue(service.load("session").modelMessages(system, null, "current", "Add tests").toString()
                .contains("Old detailed investigation"));
    }

    @Test
    void failedOrdinarySummaryCanTryOnceWhenUrgentButCannotLoop() {
        doThrow(new IllegalStateException("Bad summary")).when(model).complete(any());
        assembler.assemble(request, budget, usage, context);
        originalTokens = 1000;
        assertThrows(ContextAssembler.PreparationException.class, () -> assembler.assemble(nextRequest(), budget, usage, context));
        var restored = service.load("session").control();
        assertFalse(restored.urgentArmed());
        assertThrows(ContextAssembler.PreparationException.class, () -> assembler(restored).assemble(nextRequest(), budget, usage, context));
        verify(model, times(2)).complete(any());
    }

    @Test
    void droppingBelowThresholdRearmsAndPersistsTheChange() {
        var control = new SessionContextService.SummaryControl("a".repeat(64), budget, 100, false, false);
        var resumed = assembler(control);
        originalTokens = 899;
        resumed.assemble(request, budget, usage, context);
        assertTrue(service.load("session").control().summaryArmed());
        originalTokens = 900;
        resumed.assemble(nextRequest(), budget, usage, context);
        verify(model).complete(any());
    }

    @Test
    void noReductionIsNotPublishedAndUrgentRemainderDoesNotSilentlyDropMessages() {
        candidateTokens = beforeLocalTokens;
        assertEquals(request.messages(), assembler.assemble(request, budget, usage, context));
        assertNull(latest("CONTEXT_SUMMARY_CREATED"));
        originalTokens = 1100;
        beforeLocalTokens = 1200;
        candidateTokens = 1000; // smaller, but still exactly b
        assertThrows(ContextAssembler.PreparationException.class, () -> assembler.assemble(nextRequest(), budget, usage, context));
        assertNotNull(latest("CONTEXT_SUMMARY_CREATED")); // Useful summary is durable even if main request must stop.
    }

    @Test
    void noEligibleOldGroupsDoesNotSummarizeAndStopsIfUrgent() {
        var keepAll = new ContextBudget(1200, 0, .8, .9, 4);
        assertEquals(request.messages(), assembler.assemble(request, keepAll, usage, context));
        originalTokens = 1000;
        assertThrows(ContextAssembler.PreparationException.class, () -> assembler.assemble(nextRequest(), keepAll, usage, context));
        verifyNoInteractions(model);
    }

    @Test
    void disarmOrPublicationCommitFailureCannotExposeAnUncommittedProjection() {
        doThrow(new IllegalStateException("DB unavailable")).when(appender).appendFence(any(), any());
        assertThrows(IllegalStateException.class, () -> assembler.assemble(request, budget, usage, context));
        verifyNoInteractions(model);
        assertNull(service.load("session").summary());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publicationFailureRetainsOriginalHistoryAndDoesNotReturnCandidate(boolean compact) {
        if (compact) {
            originalTokens = 1000;
            candidateTokens = 1000;
            when(model.complete(any())).thenReturn(
                    new ModelResponse("ordinary", "Still detailed", List.of(), ModelUsage.unknown(), ModelFinishReason.STOP),
                    new ModelResponse("compact", "Compact checkpoint", List.of(), ModelUsage.unknown(), ModelFinishReason.STOP));
        }
        doAnswer(call -> {
            AgentEventAppender.AppendCommand command = call.getArgument(0);
            if (command.stepType().name().equals("CONTEXT_SUMMARY_CREATED")) throw new IllegalStateException("Commit failed");
            add(command.stepType().name(), command.taskId(), command.runId(), command.persistedPayload());
            return new AgentEventAppender.AppendResult(history.size(), history.size(), (long) history.size(), false);
        }).when(appender).appendFence(any(), any());
        assertThrows(IllegalStateException.class, () -> assembler.assemble(request, compact ? compactionBudget : budget, usage, context));
        verify(model, times(compact ? 2 : 1)).complete(any());
        assertNull(service.load("session").summary());
        assertTrue(service.load("session").messages().toString().contains("Old detailed investigation"));
    }

    @Test
    void newerPolicyCanReconsiderADisarmedSession() {
        var previousBudget = new ContextBudget(2000, 0, .8, .9, 1);
        var restored = new SessionContextService.SummaryControl("a".repeat(64), previousBudget, 100, false, false);
        assembler(restored).assemble(request, budget, usage, context);
        verify(model).complete(any());
    }

    @Test
    void futureTailCrossingUrgentThresholdStopsWithoutLosingItsSteps() {
        doAnswer(call -> {
            user("next", "More task context");
            return new ModelResponse("r", "Condensed", List.of(), ModelUsage.unknown(), ModelFinishReason.STOP);
        }).when(model).complete(any());
        doAnswer(call -> {
            ModelRequest input = call.getArgument(0);
            long tokens = input.messages().toString().contains("More task context") ? 1000
                    : input.messages().toString().contains("session_summary") ? 300 : 900;
            return new ContextUsage.Measurement("model", "a".repeat(64), "b".repeat(64), input.messages().size(), tokens, 100, "LOCAL_ESTIMATE");
        }).when(usage).measure(any());
        assertThrows(ContextAssembler.PreparationException.class, () -> assembler.assemble(request, budget, usage, context));
        assertTrue(service.load("session").messages().toString().contains("More task context"));
    }

    @Test
    void leaseLossDuringSummaryStopsBeforePublishing() {
        doAnswer(call -> {
            execution.markLeaseLost();
            return new ModelResponse("r", "Condensed", List.of(), ModelUsage.unknown(), ModelFinishReason.STOP);
        }).when(model).complete(any());
        assertThrows(AgentExecutionControl.LeaseLostException.class, () -> assembler.assemble(request, budget, usage, context));
        assertNull(latest("CONTEXT_SUMMARY_CREATED"));
        assertNull(latest("CONTEXT_SUMMARY_RESULT"));
    }

    @ParameterizedTest
    @ValueSource(longs = {400, 700})
    void urgentSummaryFallsBackToTargetedCompactionAndOnlyPublishesTheAcceptedCandidate(long finalTokens) throws Exception {
        compactedTokens = finalTokens;
        originalTokens = 1100;
        beforeLocalTokens = 1200;
        candidateTokens = 1000; // Ordinary summary shrinks, but still reaches b.
        when(model.complete(any())).thenReturn(
                new ModelResponse("ordinary", "Still detailed", List.of(), new ModelUsage(200, 30, 230), ModelFinishReason.STOP),
                new ModelResponse("compact", "Compact checkpoint", List.of(), new ModelUsage(220, 20, 240), ModelFinishReason.STOP));

        var messages = assembler.assemble(request, compactionBudget, usage, context);

        assertSame(system, messages.get(0));
        assertTrue(messages.toString().contains("Add tests"));
        assertTrue(messages.toString().contains("Current generation is 7"));
        assertTrue(messages.toString().contains("Recent exact investigation"));
        assertTrue(messages.toString().contains("Compact checkpoint"));
        assertEquals(1, history.stream().filter(row -> row.getStepType().equals("CONTEXT_SUMMARY_CREATED")).count());
        assertEquals(2, history.stream().filter(row -> row.getStepType().equals("CONTEXT_SUMMARY_RESULT")).count());
        assertTrue(history.stream().anyMatch(row -> row.getPayloadJson().contains("TARGET_NOT_REACHED")));
        var result = mapper.readTree(latest("CONTEXT_SUMMARY_RESULT").getPayloadJson());
        assertEquals("COMPACTION", result.path("operation").asText());
        assertEquals(700, result.path("targetInputTokens").asLong()); // 100 fixed + 1000 dynamic * .6
        assertEquals(240, result.at("/output/usage/totalTokens").asLong());
        var captured = org.mockito.ArgumentCaptor.forClass(ModelRequest.class);
        verify(model, times(2)).complete(captured.capture());
        assertFalse(captured.getAllValues().get(1).messages().toString().contains("Still detailed"));
        assertTrue(captured.getAllValues().get(1).messages().get(0).content().contains("强压缩"));
        assertEquals(history.size(), assembler.throughSessionSequence());
        verify(usage, never()).accept(any(), any());
    }

    @Test
    void compactExistingSummaryWithoutNewOldGroupsPreservesItsCoverageAndRecentProtocol() {
        var previous = new ContextSummary("previous", "session", null, 3, "Previous durable summary");
        add("CONTEXT_SUMMARY_CREATED", "current", "run", previous);
        originalTokens = 1000;
        when(model.complete(any())).thenReturn(new ModelResponse("compact", "Compact checkpoint", List.of(),
                new ModelUsage(200, 20, 220), ModelFinishReason.STOP));
        var originalRows = history.stream().map(AgentStepEntity::getPayloadJson).toList();
        var source = service.load("session");
        var prepared = new ModelRequest("model", source.modelMessages(system, previous, "current", "Add tests"),
                List.of(), 100, null, "rewrite-only");

        var messages = assembler.assemble(prepared, compactionBudget, usage, context);

        var restored = service.load("session");
        assertEquals(3, restored.summary().throughSessionSequence());
        assertEquals("previous", restored.summary().parentSummaryId());
        assertNotEquals(previous.summaryId(), restored.summary().summaryId());
        assertTrue(messages.toString().contains("Recent exact investigation"));
        assertEquals(originalRows, history.subList(0, originalRows.size()).stream().map(AgentStepEntity::getPayloadJson).toList());
        assertEquals(messages, restored.modelMessages(system, restored.summary(), "current", "Add tests"));
        verify(model, times(1)).complete(any());
    }

    @ParameterizedTest
    @ValueSource(longs = {701, 1300}) // Reduced but misses the target; or grows beyond the original.
    void rejectedCompactionDoesNotReplaceHistoryAndCannotRepeatAfterRestart(long tooLarge) {
        originalTokens = 1000;
        candidateTokens = 1000;
        compactedTokens = tooLarge;
        when(model.complete(any())).thenReturn(
                new ModelResponse("ordinary", "Still detailed", List.of(), ModelUsage.unknown(), ModelFinishReason.STOP),
                new ModelResponse("compact", "Compact checkpoint", List.of(), ModelUsage.unknown(), ModelFinishReason.STOP));
        assertThrows(ContextAssembler.PreparationException.class,
                () -> assembler.assemble(request, compactionBudget, usage, context));
        assertNull(latest("CONTEXT_SUMMARY_CREATED"));
        assertFalse(service.load("session").control().compactionArmed());
        assertThrows(ContextAssembler.PreparationException.class,
                () -> assembler(service.load("session").control()).assemble(nextRequest(), compactionBudget, usage, context));
        verify(model, times(2)).complete(any());
        assertTrue(service.load("session").messages().toString().contains("Old detailed investigation"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void compactionGatewayFailureOrTruncationIsRecordedWithoutReplacingTheOldSummary(boolean truncated) {
        originalTokens = 1000;
        candidateTokens = 1000;
        doAnswer(call -> {
            ModelRequest input = call.getArgument(0);
            if (!input.requestId().contains(":compact:")) {
                return new ModelResponse("ordinary", "Still detailed", List.of(), ModelUsage.unknown(), ModelFinishReason.STOP);
            }
            if (truncated) return new ModelResponse("cut", "Cut-off checkpoint", List.of(), ModelUsage.unknown(), ModelFinishReason.LENGTH);
            throw new ModelGatewayException(ModelGatewayErrorCode.TIMEOUT, "Timed out", true, null);
        }).when(model).complete(any());
        assertThrows(ContextAssembler.PreparationException.class,
                () -> assembler.assemble(request, compactionBudget, usage, context));
        assertTrue(latest("CONTEXT_SUMMARY_RESULT").getPayloadJson().contains("FAILED_"));
        assertNull(latest("CONTEXT_SUMMARY_CREATED"));
        assertThrows(ContextAssembler.PreparationException.class,
                () -> assembler(service.load("session").control()).assemble(nextRequest(), compactionBudget, usage, context));
        verify(model, times(2)).complete(any());
    }

    @Test
    void protectedTailCannotBeSilentlyRemovedToReachTheCompactionTarget() {
        originalTokens = 1000;
        protectedTokens = 700;
        var previous = new ContextSummary("previous", "session", null, 3, "Previous durable summary");
        add("CONTEXT_SUMMARY_CREATED", "current", "run", previous);
        assertThrows(ContextAssembler.PreparationException.class,
                () -> assembler.assemble(request, compactionBudget, usage, context));
        assertEquals(previous, service.load("session").summary());
        assertTrue(latest("CONTEXT_SUMMARY_RESULT").getPayloadJson().contains("PROTECTED_CONTEXT_TOO_LARGE"));
        verifyNoInteractions(model);
    }

    @Test
    void newCommittedMessagesDuringCompactionRemainVisible() {
        originalTokens = 1000;
        candidateTokens = 1000;
        doAnswer(call -> {
            ModelRequest input = call.getArgument(0);
            boolean compact = input.requestId().contains(":compact:");
            if (compact) user("next", "Keep traditional loops; do not use lambda");
            return new ModelResponse("r", compact ? "Compact checkpoint" : "Still detailed",
                    List.of(), ModelUsage.unknown(), ModelFinishReason.STOP);
        }).when(model).complete(any());
        var result = assembler.assemble(request, compactionBudget, usage, context);
        assertEquals("Keep traditional loops; do not use lambda", result.get(result.size() - 1).content());
        assertEquals(history.size(), assembler.throughSessionSequence());
        verify(model, times(2)).complete(any());
    }

    @Test
    void aLargeAppendDuringCompactionIsRetainedButCannotStartAnUnboundedThirdModelAttempt() {
        originalTokens = 1000;
        candidateTokens = 1000;
        doAnswer(call -> {
            ModelRequest input = call.getArgument(0);
            boolean compact = input.requestId().contains(":compact:");
            if (compact) user("next", "Large concurrent task");
            return new ModelResponse("r", compact ? "Compact checkpoint" : "Still detailed",
                    List.of(), ModelUsage.unknown(), ModelFinishReason.STOP);
        }).when(model).complete(any());
        doReturn(new ContextUsage.Measurement("model", "a".repeat(64), "b".repeat(64), 10, 1000, 100, "LOCAL_ESTIMATE"))
                .when(usage).measure(argThat(input -> input.messages().toString().contains("Large concurrent task")));
        assertThrows(ContextAssembler.PreparationException.class,
                () -> assembler.assemble(request, compactionBudget, usage, context));
        var latest = service.load("session");
        assertEquals("Compact checkpoint", latest.summary().content());
        assertTrue(latest.messages().toString().contains("Large concurrent task"));
        var nextRequest = new ModelRequest("model", latest.modelMessages(system, latest.summary(), "current", "Add tests"),
                List.of(), 100, null, "after-large-append");
        assertThrows(ContextAssembler.PreparationException.class,
                () -> assembler(latest.control()).assemble(nextRequest, compactionBudget, usage, context));
        verify(model, times(2)).complete(any());
    }

    @Test
    void lostLeaseDuringCompactionCannotPublishItsCandidate() {
        originalTokens = 1000;
        candidateTokens = 1000;
        doAnswer(call -> {
            ModelRequest input = call.getArgument(0);
            boolean compact = input.requestId().contains(":compact:");
            if (compact) execution.markLeaseLost();
            return new ModelResponse("r", compact ? "Compact checkpoint" : "Still detailed",
                    List.of(), ModelUsage.unknown(), ModelFinishReason.STOP);
        }).when(model).complete(any());
        assertThrows(AgentExecutionControl.LeaseLostException.class,
                () -> assembler.assemble(request, compactionBudget, usage, context));
        assertNull(latest("CONTEXT_SUMMARY_CREATED"));
        assertEquals(1, history.stream().filter(row -> row.getStepType().equals("CONTEXT_SUMMARY_RESULT")).count());
    }

    private ContextAssembler assembler(SessionContextService.SummaryControl control) {
        return new ContextAssembler(service, new ContextSummarizer(model, "model", 100, "summary"), scope, execution, commits::add, control);
    }

    private ModelRequest nextRequest() {
        return new ModelRequest(request.model(), request.messages(), request.tools(), 100, null, "request-" + history.size());
    }

    private AgentStepEntity latest(String type) {
        return history.stream().filter(row -> row.getStepType().equals(type)).reduce((left, right) -> right).orElse(null);
    }

    private void user(String task, String text) {
        var payload = mapper.createObjectNode();
        payload.putObject("request").put("message", text);
        add("USER_MESSAGE_RECEIVED", task, null, payload);
    }

    private void assistant(String task, String run, String text) {
        add("MODEL_RESPONSE", task, run, new ModelResponsePayload("m-" + history.size(), "r", text, List.of(), ModelUsage.unknown(), ModelFinishReason.STOP));
    }

    private AgentStepEntity add(String type, String task, String run, Object payload) {
        var row = new AgentStepEntity();
        row.setEventId("e-" + history.size());
        row.setSessionId("session");
        row.setTaskId(task);
        row.setRunId(run);
        row.setSessionSequence((long) history.size() + 1);
        row.setSchemaVersion(1);
        row.setStepType(type);
        row.setPayloadJson(mapper.valueToTree(payload).toString());
        history.add(row);
        return row;
    }
}
