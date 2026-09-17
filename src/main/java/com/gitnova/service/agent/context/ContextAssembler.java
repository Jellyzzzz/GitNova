package com.gitnova.service.agent.context;

import com.gitnova.service.agent.model.ModelMessage;
import com.gitnova.service.agent.model.ModelRequest;
import com.gitnova.service.agent.model.ModelGatewayException;
import com.gitnova.service.agent.execution.AgentExecutionControl;
import com.gitnova.service.agent.journal.RunJournalScope;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.runtime.AgentExecutionContext;
import com.gitnova.service.agent.context.SessionContextService.SummaryControl;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** One Run's context preparation. Durable control belongs to the Session, not to this instance. */
public final class ContextAssembler {
    private final SessionContextService service;
    private final ContextSummarizer summarizer;
    private final RunJournalScope scope;
    private final AgentExecutionControl executionControl;
    private final Consumer<AgentEventAppender.AppendResult> committed;
    private SummaryControl control;
    private boolean attemptedSummary;
    private long throughSessionSequence;

    public ContextAssembler(SessionContextService service, ContextSummarizer summarizer, RunJournalScope scope,
                            AgentExecutionControl executionControl, Consumer<AgentEventAppender.AppendResult> committed,
                            SummaryControl restoredControl) {
        this.service = Objects.requireNonNull(service);
        this.summarizer = Objects.requireNonNull(summarizer);
        this.scope = Objects.requireNonNull(scope);
        this.executionControl = Objects.requireNonNull(executionControl);
        this.committed = Objects.requireNonNull(committed);
        this.control = restoredControl;
    }

    public List<ModelMessage> assemble(ModelRequest request, ContextBudget budget, ContextUsage usage,
                                       AgentExecutionContext context) {
        attemptedSummary = false;
        if (!scope.sessionId().equals(context.sessionId()) || !scope.runId().equals(context.context().runId())) {
            throw new IllegalArgumentException("Context preparation scope does not match this Run");
        }
        if (request.maxOutputTokens() == null) throw new PreparationException("Output reserve is required");
        var measured = usage.measure(request);
        var assessment = budget.assess(measured.estimatedInputTokens(), measured.fixedTokens(), request.maxOutputTokens());
        double ratio = assessment.useRatio();

        // A changed frozen policy/system/tool set establishes a new accounting regime.
        if (control == null || !control.fixedDigest().equals(measured.fixedDigest())
                || !control.budget().equals(budget) || control.outputReserveTokens() != request.maxOutputTokens()) {
            control = new SummaryControl(measured.fixedDigest(), budget, request.maxOutputTokens(), true, true);
        }
        boolean ordinaryArmed = control.summaryArmed() || ratio < budget.summaryTriggerRatio();
        boolean urgentArmed = control.urgentArmed() || ratio < budget.compactTriggerRatio();
        var rearmed = new SummaryControl(control.fixedDigest(), budget, request.maxOutputTokens(), ordinaryArmed, urgentArmed);
        if (!rearmed.equals(control)) {
            executionControl.requireLease();
            committed.accept(service.saveControl(scope, request.requestId() + ":context:rearm", rearmed));
            control = rearmed;
        }
        if (ratio < budget.summaryTriggerRatio()) return request.messages();

        boolean urgent = ratio >= budget.compactTriggerRatio();
        if (!(urgent ? control.urgentArmed() : control.summaryArmed())) {
            if (urgent) throw new PreparationException("Stronger compaction is required; automatic repeated summaries are disabled");
            return request.messages();
        }

        // Disarm durably BEFORE a potentially slow/failed call. No database transaction spans the LLM call.
        var disarmed = new SummaryControl(control.fixedDigest(), budget, request.maxOutputTokens(), false, !urgent);
        executionControl.requireLease();
        String attemptId = request.requestId() + ":context:summary";
        committed.accept(service.saveControl(scope, attemptId + ":started", disarmed));
        control = disarmed;

        var snapshot = service.load(context.sessionId());
        throughSessionSequence = snapshot.throughSessionSequence();
        var selection = selectWindow(snapshot.groups(), budget.keepRecentGroups());
        if (selection.groupsToCompact().isEmpty()) {
            if (urgent) throw new PreparationException("Protected recent context cannot fit the configured compaction threshold");
            return request.messages();
        }

        attemptedSummary = true;
        ContextSummarizer.SummaryOutput output;
        try {
            executionControl.requireLease();
            output = summarizer.summarize(snapshot.summaryInput(context.taskText(), selection.groupsToCompact()));
        } catch (ModelGatewayException | IllegalArgumentException | IllegalStateException failure) {
            executionControl.requireLease();
            committed.accept(service.recordSummaryResult(scope, attemptId, null,
                    "FAILED_" + failure.getClass().getSimpleName(), measured.estimatedInputTokens(), null));
            if (urgent) throw new PreparationException("Summary failed and stronger compaction is required");
            return request.messages();
        }
        executionControl.requireLease();

        List<ModelMessage> candidate = snapshot.modelMessages(request.messages().get(0), output.summary(),
                scope.taskId(), context.taskText());
        var candidateRequest = new ModelRequest(request.model(), candidate, request.tools(), request.maxOutputTokens(),
                request.temperature(), request.requestId());
        var after = usage.measure(candidateRequest);
        var afterBudget = budget.assess(after.estimatedInputTokens(), after.fixedTokens(), request.maxOutputTokens());
        // Compare like for like: provider usage and a local estimate can have different biases.
        var originalProjection = snapshot.modelMessages(request.messages().get(0), snapshot.summary(), scope.taskId(), context.taskText());
        long beforeLocal = usage.estimateInput(new ModelRequest(request.model(), originalProjection, request.tools(),
                request.maxOutputTokens(), request.temperature(), request.requestId()));
        long afterLocal = usage.estimateInput(candidateRequest);
        boolean smaller = afterLocal < beforeLocal;
        committed.accept(service.recordSummaryResult(scope, attemptId, output,
                smaller ? "CANDIDATE_ACCEPTED" : "NO_REDUCTION", measured.estimatedInputTokens(), after.estimatedInputTokens()));
        if (!smaller) {
            if (urgent) throw new PreparationException("Summary did not reduce context; stronger compaction is required");
            return request.messages();
        }

        // Publication is fenced; only then may Runtime replace its model-visible projection.
        committed.accept(service.publishSummary(scope, snapshot, output.summary()));
        if (afterBudget.useRatio() >= budget.compactTriggerRatio()) {
            throw new PreparationException("Summary committed, but stronger compaction is still required");
        }
        // Preserve Steps appended while the summary was being generated, not just the earlier snapshot.
        var latest = service.load(context.sessionId());
        throughSessionSequence = latest.throughSessionSequence();
        var visible = latest.modelMessages(request.messages().get(0), latest.summary(), scope.taskId(), context.taskText());
        var finalMeasurement = usage.measure(new ModelRequest(request.model(), visible, request.tools(),
                request.maxOutputTokens(), request.temperature(), request.requestId()));
        var finalBudget = budget.assess(finalMeasurement.estimatedInputTokens(), finalMeasurement.fixedTokens(), request.maxOutputTokens());
        if (finalBudget.useRatio() >= budget.compactTriggerRatio()) {
            throw new PreparationException("New Session context requires stronger compaction");
        }
        return visible;
    }

    public boolean attemptedSummary() {
        return attemptedSummary;
    }

    public long throughSessionSequence() {
        return throughSessionSequence;
    }

    /** Context-policy failure, distinct from a failed durable commit (which must propagate). */
    public static final class PreparationException extends RuntimeException {
        public PreparationException(String message) { super(message); }
    }

    public static WindowSelection selectWindow(
            List<InteractionGroup> groups,
            int keepRecentGroups
    ) {
        Objects.requireNonNull(groups, "groups must not be null");
        if (keepRecentGroups <= 0) {
            throw new IllegalArgumentException("keepRecentGroups must be positive");
        }

        List<InteractionGroup> orderedGroups = List.copyOf(groups);
        InteractionGroup previous = null;
        for (InteractionGroup group : orderedGroups) {
            if (!group.closed()) {
                throw new IllegalArgumentException("Interaction groups must be closed");
            }
            if (previous != null
                    && previous.lastSessionSequence() >= group.firstSessionSequence()) {
                throw new IllegalArgumentException(
                        "Interaction groups must be ordered and must not overlap"
                );
            }
            previous = group;
        }

        int splitIndex = Math.max(0, orderedGroups.size() - keepRecentGroups);
        return new WindowSelection(
                orderedGroups.subList(0, splitIndex),
                orderedGroups.subList(splitIndex, orderedGroups.size())
        );
    }

    public record WindowSelection(
            List<InteractionGroup> groupsToCompact,
            List<InteractionGroup> groupsToKeep
    ) {
        public WindowSelection {
            Objects.requireNonNull(groupsToCompact, "groupsToCompact must not be null");
            Objects.requireNonNull(groupsToKeep, "groupsToKeep must not be null");
            groupsToCompact = List.copyOf(groupsToCompact);
            groupsToKeep = List.copyOf(groupsToKeep);
        }
    }
}
