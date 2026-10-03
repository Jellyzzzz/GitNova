package com.gitnova.service.agent.context;

import com.gitnova.service.agent.model.ModelMessage;
import com.gitnova.service.agent.model.ModelRequest;
import com.gitnova.service.agent.model.ModelGatewayException;
import com.gitnova.service.agent.execution.AgentExecutionControl;
import com.gitnova.service.agent.journal.RunJournalScope;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.runtime.AgentExecutionContext;
import com.gitnova.service.agent.context.SessionContextService.SummaryControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** One Run's context preparation. Durable control belongs to the Session, not to this instance. */
public final class ContextAssembler {
    private static final Logger logger = LoggerFactory.getLogger(ContextAssembler.class);
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
        // Disabling summarization does not disable Session history, measurement or the hard input limit.
        if (!budget.summaryEnabled()) {
            if (measured.estimatedInputTokens() > assessment.inputLimit()) {
                throw new PreparationException("Context exceeds input limit with summarization disabled");
            }
            return request.messages();
        }
        double ratio = assessment.useRatio();

        // A changed frozen policy/system/tool set establishes a new accounting regime.
        if (control == null || !control.fixedDigest().equals(measured.fixedDigest())
                || !control.budget().equals(budget) || control.outputReserveTokens() != request.maxOutputTokens()) {
            control = new SummaryControl(measured.fixedDigest(), budget, request.maxOutputTokens(), true, true);
        }
        boolean ordinaryArmed = control.summaryArmed() || ratio < budget.summaryTriggerRatio();
        boolean urgentArmed = control.urgentArmed() || ratio < budget.compactTriggerRatio();
        boolean compactionArmed = control.compactionArmed() || ratio < budget.summaryTriggerRatio();
        var rearmed = new SummaryControl(control.fixedDigest(), budget, request.maxOutputTokens(),
                ordinaryArmed, urgentArmed, compactionArmed);
        if (!rearmed.equals(control)) {
            executionControl.requireLease();
            committed.accept(service.saveControl(scope, request.requestId() + ":context:rearm", rearmed));
            control = rearmed;
        }
        if (ratio < budget.summaryTriggerRatio()) return request.messages();

        boolean urgent = ratio >= budget.compactTriggerRatio();
        boolean tryOrdinary = urgent ? control.urgentArmed() : control.summaryArmed();
        if (!urgent && !tryOrdinary) return request.messages();

        // Two bounded phases, not a retry loop: ordinary summary first, then budget-targeted compaction.
        // Both reuse the same generator and fenced publication. Never publish an over-budget candidate
        // and then summarize that unaccepted candidate again: compact the committed source instead.
        for (int phase = 0; phase < 2; phase++) {
            boolean compact = phase == 1;
            if (!compact && !tryOrdinary) continue;
            if (compact && (budget.compactTargetRatio() == null || !control.compactionArmed())) {
                throw new PreparationException("Strong compaction is unavailable or already attempted; context retained");
            }
            String operation = compact ? "COMPACTION" : "SUMMARY";
            String attemptId = request.requestId() + (compact ? ":context:compact" : ":context:summary");
            var disarmed = new SummaryControl(control.fixedDigest(), budget, request.maxOutputTokens(),
                    false, compact ? false : !urgent, compact ? false : control.compactionArmed());
            executionControl.requireLease();
            committed.accept(service.saveControl(scope, attemptId + ":started", disarmed));
            control = disarmed;

            var snapshot = service.load(context.sessionId());
            throughSessionSequence = snapshot.throughSessionSequence();
            var selection = selectWindow(snapshot.groups(), budget.keepRecentGroups());
            if (selection.groupsToCompact().isEmpty() && (!compact || snapshot.summary() == null)) {
                if (compact) throw new PreparationException("No compressible history; protected context must be retained");
                if (!urgent) return request.messages();
                continue;
            }
            var input = snapshot.summaryInput(context.taskText(), selection.groupsToCompact());
            Long targetInputTokens = null;
            Long targetSummaryTokens = null;
            if (compact) {
                long covered = selection.groupsToCompact().isEmpty() ? snapshot.summary().throughSessionSequence()
                        : selection.groupsToCompact().get(selection.groupsToCompact().size() - 1).lastSessionSequence();
                // Pure measurement shell: count SYSTEM/tools, current Task, complete recent groups,
                // and the summary's message envelope. This placeholder is never published or sent.
                var shell = new ContextSummary(UUID.randomUUID().toString(), snapshot.sessionId(),
                        snapshot.summary() == null ? null : snapshot.summary().summaryId(), covered, "Pending compaction");
                var protectedMessages = snapshot.modelMessages(request.messages().get(0), shell,
                        scope.taskId(), context.taskText(), false);
                var protectedInput = usage.measure(new ModelRequest(request.model(), protectedMessages, request.tools(),
                        request.maxOutputTokens(), request.temperature(), request.requestId(), request.thinking()));
                var protectedBudget = budget.assess(protectedInput.estimatedInputTokens(), protectedInput.fixedTokens(), request.maxOutputTokens());
                targetInputTokens = protectedInput.fixedTokens()
                        + (long) Math.floor(protectedBudget.dynamicBudget() * budget.compactTargetRatio());
                targetSummaryTokens = Math.min(request.maxOutputTokens().longValue(),
                        targetInputTokens - protectedInput.estimatedInputTokens());
                if (targetSummaryTokens <= 0) {
                    committed.accept(service.recordSummaryResult(scope, attemptId, null, "PROTECTED_CONTEXT_TOO_LARGE",
                            measured.estimatedInputTokens(), null, operation, targetInputTokens));
                    throw new PreparationException("Protected context leaves no room for the compaction target");
                }
            }

            attemptedSummary = true;
            ContextSummarizer.SummaryOutput output;
            try {
                executionControl.requireLease();
                output = summarizer.summarize(input, targetSummaryTokens);
            } catch (ModelGatewayException | IllegalArgumentException | IllegalStateException failure) {
                if (failure instanceof ModelGatewayException gatewayFailure) {
                    logger.warn("Context model failed: runId={}, operation={}, errorCode={}, httpStatus={}, retryable={}, responseDiagnostic={}",
                            scope.runId(), operation, gatewayFailure.errorCode(), gatewayFailure.providerStatusCode(),
                            gatewayFailure.retryable(), gatewayFailure.responseDiagnostic());
                }
                executionControl.requireLease();
                String reason = "FAILED_" + failure.getClass().getSimpleName();
                if (failure instanceof ModelGatewayException gatewayFailure && gatewayFailure.responseDiagnostic() != null) {
                    reason += ":" + gatewayFailure.responseDiagnostic().reason() + "@" + gatewayFailure.responseDiagnostic().path();
                }
                committed.accept(service.recordSummaryResult(scope, attemptId, null,
                        reason, measured.estimatedInputTokens(), null, operation, targetInputTokens));
                if (compact) throw new PreparationException("Strong compaction failed; committed context retained");
                if (!urgent) return request.messages();
                continue;
            }
            executionControl.requireLease();

            var candidate = snapshot.modelMessages(request.messages().get(0), output.summary(), scope.taskId(), context.taskText());
            var candidateRequest = new ModelRequest(request.model(), candidate, request.tools(), request.maxOutputTokens(),
                    request.temperature(), request.requestId(), request.thinking());
            var after = usage.measure(candidateRequest);
            var afterBudget = budget.assess(after.estimatedInputTokens(), after.fixedTokens(), request.maxOutputTokens());
            // Compare local estimates with local estimates, not a provider count with a different tokenizer.
            var original = snapshot.modelMessages(request.messages().get(0), snapshot.summary(), scope.taskId(), context.taskText());
            long beforeLocal = usage.estimateInput(new ModelRequest(request.model(), original, request.tools(),
                    request.maxOutputTokens(), request.temperature(), request.requestId(), request.thinking()));
            long afterLocal = usage.estimateInput(candidateRequest);
            boolean smaller = afterLocal < beforeLocal;
            boolean fits = compact ? after.estimatedInputTokens() <= targetInputTokens
                    : afterBudget.useRatio() < budget.compactTriggerRatio() || budget.compactTargetRatio() == null;
            String disposition = !smaller ? "NO_REDUCTION" : fits ? "CANDIDATE_ACCEPTED" : "TARGET_NOT_REACHED";
            committed.accept(service.recordSummaryResult(scope, attemptId, output, disposition,
                    measured.estimatedInputTokens(), after.estimatedInputTokens(), operation, targetInputTokens));
            logger.info("Context candidate: runId={}, operation={}, disposition={}, beforeEstimate={}, afterEstimate={}, targetInput={}",
                    scope.runId(), operation, disposition, beforeLocal, afterLocal, targetInputTokens);
            if (!smaller || !fits) {
                if (compact) throw new PreparationException("Strong compaction did not reach its target; committed context retained");
                if (!urgent) return request.messages();
                continue;
            }

            // A failed commit propagates. Never expose a model-only candidate as durable Session history.
            committed.accept(service.publishSummary(scope, snapshot, output.summary()));
            var latest = service.load(context.sessionId());
            throughSessionSequence = latest.throughSessionSequence();
            var visible = latest.modelMessages(request.messages().get(0), latest.summary(), scope.taskId(), context.taskText());
            request = new ModelRequest(request.model(), visible, request.tools(), request.maxOutputTokens(),
                    request.temperature(), request.requestId(), request.thinking());
            measured = usage.measure(request);
            assessment = budget.assess(measured.estimatedInputTokens(), measured.fixedTokens(), request.maxOutputTokens());
            if (assessment.useRatio() < budget.compactTriggerRatio()) return visible;
            // New committed Steps arrived during the call. Keep them, and use the remaining phase once.
            urgent = true;
        }
        throw new PreparationException("New Session context exceeds the compaction threshold; no unbounded retries");
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
