package com.gitnova.service.agent.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.dto.ToolCall;
import com.gitnova.entity.agent.AgentStepEntity;
import com.gitnova.mapper.agent.AgentSessionMapper;
import com.gitnova.mapper.agent.AgentStepMapper;
import com.gitnova.service.agent.journal.*;
import com.gitnova.service.agent.model.*;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.persistence.AgentStepType;
import com.gitnova.service.agent.prompt.AssembledPrompt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.*;

/** Rebuilds a model-visible Session projection; never replays tools or restores validation authority. */
@Service
public class SessionContextService {
    private final AgentStepMapper steps;
    private final AgentSessionMapper sessions;
    private final AgentEventAppender appender;
    private final ObjectMapper mapper;
    private final MessageFactory messages;

    public SessionContextService(AgentStepMapper steps, AgentSessionMapper sessions,
                                 AgentEventAppender appender, ObjectMapper mapper, MessageFactory messages) {
        this.steps = Objects.requireNonNull(steps);
        this.sessions = Objects.requireNonNull(sessions);
        this.appender = Objects.requireNonNull(appender);
        this.mapper = Objects.requireNonNull(mapper);
        this.messages = Objects.requireNonNull(messages);
    }

    /** Load at Run entry, or on demand when assembling a summary. Live turns keep appending committed messages. */
    @Transactional(readOnly = true)
    public Snapshot load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("Session is required");
        long through = steps.latestSessionSequence(sessionId);
        AgentStepEntity summaryRow = steps.selectLatestContextSummary(sessionId, through);
        ContextSummary summary = summaryRow == null ? null : readSummary(summaryRow);
        if (summary != null && (!sessionId.equals(summary.sessionId())
                || summary.throughSessionSequence() >= summaryRow.getSessionSequence())) {
            throw new IllegalStateException("Invalid committed summary coverage");
        }

        List<AgentStepEntity> history = new ArrayList<>();
        long after = summary == null ? 0 : summary.throughSessionSequence();
        while (after < through) {
            List<AgentStepEntity> page = steps.selectSessionHistory(sessionId, after, through, 256);
            if (page.isEmpty()) throw new IllegalStateException("Session history is incomplete");
            for (AgentStepEntity step : page) {
                if (!sessionId.equals(step.getSessionId()) || step.getSessionSequence() == null
                        || step.getSessionSequence() != after + 1 || step.getSessionSequence() > through) {
                    throw new IllegalStateException("Session history must be contiguous and ordered");
                }
                history.add(step);
                after = step.getSessionSequence();
            }
        }

        try {
            // A committed projection replaces the raw result only in the model view, not in Recall history.
            Map<String, AgentStepEntity> projections = new HashMap<>();
            Map<String, AgentStepEntity> results = new HashMap<>();
            for (AgentStepEntity step : history) {
                if ("TOOL_RESULT".equals(step.getStepType())) results.put(step.getEventId(), step);
                if ("TOOL_OBSERVATION_PROJECTED".equals(step.getStepType())) {
                    if (step.getSchemaVersion() != 1
                            || projections.putIfAbsent(step.getCausationEventId(), step) != null) {
                        throw new IllegalStateException("Invalid or duplicate observation projection");
                    }
                }
            }
            for (var entry : projections.entrySet()) {
                var result = results.get(entry.getKey());
                var projection = entry.getValue();
                if (result == null || !Objects.equals(result.getRunId(), projection.getRunId())
                        || result.getSessionSequence() >= projection.getSessionSequence()) {
                    throw new IllegalStateException("Observation requires an earlier Tool Result in the same Run");
                }
            }

            List<HistoryEntry> entries = new ArrayList<>();
            List<InteractionGroup> groups = new ArrayList<>();
            List<TaskMessage> users = new ArrayList<>();
            Map<String, ContextUsage.Measurement> modelInputs = new HashMap<>();
            ContextUsage.Anchor anchor = null;
            Group group = null;
            for (AgentStepEntity step : history) {
                String type = step.getStepType();
                if (!Set.of("USER_MESSAGE_RECEIVED", "MODEL_CALL_STARTED", "MODEL_RESPONSE", "TOOL_RESULT",
                        "HARNESS_FEEDBACK", "TOOL_OBSERVATION_PROJECTED").contains(type)) continue;
                int version = Objects.requireNonNull(step.getSchemaVersion());
                if (version != 1 && !(type.equals("MODEL_CALL_STARTED") && version == 2)) {
                    throw new IllegalStateException("Unsupported context Step schema: " + type + "/" + version);
                }
                JsonNode payload = mapper.readTree(step.getPayloadJson());
                if (type.equals("USER_MESSAGE_RECEIVED") || type.equals("MODEL_RESPONSE")
                        || (group != null && !Objects.equals(group.runId, step.getRunId()))) {
                    if (group != null) {
                        InteractionGroup closed = group.close();
                        groups.add(closed);
                        entries.add(new HistoryEntry(closed.firstSessionSequence(), closed.lastSessionSequence(), closed.messages()));
                        group = null;
                    }
                }
                switch (type) {
                    case "USER_MESSAGE_RECEIVED" -> {
                        var user = new TaskMessage(step.getTaskId(), step.getSessionSequence(),
                                payload.path("request").path("message").asText());
                        users.add(user);
                        entries.add(new HistoryEntry(user.sessionSequence(), user.sessionSequence(),
                                List.of(new ModelMessage(ModelRole.USER, user.text(), List.of(), null))));
                    }
                    case "MODEL_CALL_STARTED" -> {
                        if (version == 2) modelInputs.put(step.getRunId() + ":" + payload.path("modelCallId").asText(),
                                mapper.treeToValue(payload.get("contextInput"), ContextUsage.Measurement.class));
                    }
                    case "MODEL_RESPONSE" -> {
                        ModelResponsePayload response = mapper.treeToValue(payload, ModelResponsePayload.class);
                        group = new Group(step, response);
                        var input = modelInputs.get(step.getRunId() + ":" + response.modelCallId());
                        // Only this main-model response may establish a usage anchor. Never sum across calls.
                        anchor = input == null || response.usage().inputTokens() == null ? null
                                : new ContextUsage.Anchor(input, response.usage().inputTokens());
                    }
                    case "TOOL_RESULT" -> {
                        ToolResultPayload result = mapper.treeToValue(payload, ToolResultPayload.class);
                        if (group == null || !group.modelCallId.equals(result.modelCallId())) {
                            throw new IllegalStateException("Tool Result has no matching assistant response");
                        }
                        ToolCall call = group.pending.remove(result.toolCallId());
                        if (call == null || !call.name().equals(result.toolName())) {
                            throw new IllegalStateException("Tool Result identity does not match its Tool Call");
                        }
                        AgentStepEntity projection = projections.get(step.getEventId());
                        if (projection == null) {
                            group.body.add(messages.tool(call, result.result()));
                        } else {
                            JsonNode projected = mapper.readTree(projection.getPayloadJson());
                            if (!call.id().equals(projected.path("toolCallId").asText())) {
                                throw new IllegalStateException("Observation call identity does not match");
                            }
                            group.body.add(messages.toolObservation(call, projected.path("observation")));
                            group.last = Math.max(group.last, projection.getSessionSequence());
                        }
                        group.last = Math.max(group.last, step.getSessionSequence());
                    }
                    case "HARNESS_FEEDBACK" -> {
                        HarnessFeedbackPayload feedback = mapper.treeToValue(payload, HarnessFeedbackPayload.class);
                        ModelMessage message = messages.harnessFeedback(feedback.text());
                        if (group == null) entries.add(new HistoryEntry(step.getSessionSequence(), step.getSessionSequence(), List.of(message)));
                        else {
                            // Drift may be recorded between calls in a batch. Emit feedback after all results.
                            group.feedback.add(message);
                            group.last = Math.max(group.last, step.getSessionSequence());
                        }
                    }
                    default -> { /* Projection has already been paired with its original result. */ }
                }
            }
            if (group != null) {
                InteractionGroup closed = group.close();
                groups.add(closed);
                entries.add(new HistoryEntry(closed.firstSessionSequence(), closed.lastSessionSequence(), closed.messages()));
            }
            var controlRow = steps.selectLatestContextControl(sessionId, through);
            SummaryControl control = null;
            if (controlRow != null) {
                if (controlRow.getSchemaVersion() != 1) throw new IllegalStateException("Unsupported context control schema");
                control = mapper.readValue(controlRow.getPayloadJson(), SummaryControl.class);
            }
            return new Snapshot(sessionId, through, summary, users, groups, entries, anchor, control);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Cannot project committed Session context", exception);
        }
    }

    /** Only transitions are recorded. This state survives a new Task/Run in the same Session. */
    @Transactional
    public AgentEventAppender.AppendResult saveControl(RunJournalScope scope, String eventId, SummaryControl control) {
        return appender.appendFence(new AgentEventAppender.AppendCommand(eventId, scope.sessionId(), scope.taskId(),
                scope.runId(), AgentStepType.CONTEXT_CONTROL_UPDATED, 1, mapper.valueToTree(control), null,
                scope.runId(), null, null), new AgentEventAppender.RunExecutionAuthority(scope.fencingToken(), scope.workerId()));
    }

    /** Summary usage is an independent cost, never a main-model input-usage anchor. */
    @Transactional
    public AgentEventAppender.AppendResult recordSummaryResult(RunJournalScope scope, String attemptId,
            ContextSummarizer.SummaryOutput output, String disposition, long beforeTokens, Long afterTokens) {
        var payload = mapper.createObjectNode().put("attemptId", attemptId).put("disposition", disposition)
                .put("beforeInputTokens", beforeTokens);
        if (afterTokens != null) payload.put("afterInputTokens", afterTokens);
        if (output != null) payload.set("output", mapper.valueToTree(output));
        return appender.appendFence(new AgentEventAppender.AppendCommand(attemptId + ":result", scope.sessionId(),
                scope.taskId(), scope.runId(), AgentStepType.CONTEXT_SUMMARY_RESULT, 1, payload, null,
                scope.runId(), null, null), new AgentEventAppender.RunExecutionAuthority(scope.fencingToken(), scope.workerId()));
    }

    /** No model/network call inside this transaction. Source Steps remain immutable and later Steps remain visible. */
    @Transactional
    public AgentEventAppender.AppendResult publishSummary(RunJournalScope scope, Snapshot source, ContextSummary candidate) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(source);
        Objects.requireNonNull(candidate);
        if (!scope.sessionId().equals(source.sessionId()) || !scope.sessionId().equals(candidate.sessionId())
                || !Objects.equals(candidate.parentSummaryId(), source.summary() == null ? null : source.summary().summaryId())
                || source.groups().stream().noneMatch(group -> group.lastSessionSequence() == candidate.throughSessionSequence())
                || candidate.throughSessionSequence() > source.throughSessionSequence()) {
            throw new IllegalArgumentException("Summary must cover a closed prefix of this Session snapshot");
        }
        var session = sessions.selectForUpdate(scope.sessionId());
        if (session == null || session.getLastSessionSequence() < source.throughSessionSequence()) {
            throw new IllegalStateException("Summary source is not committed");
        }
        var latestRow = steps.selectLatestContextSummary(scope.sessionId(), session.getLastSessionSequence());
        ContextSummary latest = latestRow == null ? null : readSummary(latestRow);
        if (!Objects.equals(latest == null ? null : latest.summaryId(), candidate.parentSummaryId())
                && !Objects.equals(latest, candidate)) {
            throw new IllegalStateException("Another summary was published; rebuild from the latest Session projection");
        }
        return appender.appendFence(new AgentEventAppender.AppendCommand(
                "session:" + scope.sessionId() + ":summary:" + candidate.summaryId(), scope.sessionId(), scope.taskId(),
                scope.runId(), AgentStepType.CONTEXT_SUMMARY_CREATED, 1, mapper.valueToTree(candidate), null,
                scope.runId(), null, null), new AgentEventAppender.RunExecutionAuthority(scope.fencingToken(), scope.workerId()));
    }

    private ContextSummary readSummary(AgentStepEntity row) {
        if (row.getSchemaVersion() == null || row.getSchemaVersion() != 1) {
            throw new IllegalStateException("Unsupported summary Step schema");
        }
        try {
            return mapper.readValue(row.getPayloadJson(), ContextSummary.class);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid committed context summary", exception);
        }
    }

    public record TaskMessage(String taskId, long sessionSequence, String text) {
        public TaskMessage {
            if (taskId == null || taskId.isBlank() || sessionSequence <= 0 || text == null || text.isBlank()) {
                throw new IllegalArgumentException("Invalid historical user message");
            }
        }
    }

    public record SummaryControl(String fixedDigest, ContextBudget budget, int outputReserveTokens,
                                 boolean summaryArmed, boolean urgentArmed) {
        public SummaryControl {
            if (fixedDigest == null || !fixedDigest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid fixed digest");
            Objects.requireNonNull(budget);
            if (outputReserveTokens <= 0) throw new IllegalArgumentException("Output reserve must be positive");
        }
    }

    /** An indivisible model-visible range: a user message, standalone feedback, or a closed tool group. */
    public record HistoryEntry(long firstSessionSequence, long lastSessionSequence, List<ModelMessage> messages) {
        public HistoryEntry {
            if (firstSessionSequence <= 0 || lastSessionSequence < firstSessionSequence) {
                throw new IllegalArgumentException("Invalid history range");
            }
            messages = List.copyOf(messages);
            if (messages.isEmpty() || messages.stream().anyMatch(message -> message.role() == ModelRole.SYSTEM)) {
                throw new IllegalArgumentException("History must not contain system policy or empty entries");
            }
        }
    }

    public record Snapshot(String sessionId, long throughSessionSequence, ContextSummary summary,
                           List<TaskMessage> userMessages, List<InteractionGroup> groups,
                           List<HistoryEntry> entries, ContextUsage.Anchor usageAnchor, SummaryControl control) {
        public Snapshot {
            Objects.requireNonNull(sessionId);
            if (throughSessionSequence < 0) throw new IllegalArgumentException("Invalid Session sequence");
            userMessages = List.copyOf(userMessages);
            groups = List.copyOf(groups);
            entries = List.copyOf(entries);
            long previous = summary == null ? 0 : summary.throughSessionSequence();
            for (HistoryEntry entry : entries) {
                if (entry.firstSessionSequence() <= previous || entry.lastSessionSequence() > throughSessionSequence) {
                    throw new IllegalArgumentException("History entries must be ordered within the Session snapshot");
                }
                previous = entry.lastSessionSequence();
            }
        }

        public List<ModelMessage> messages() {
            return entries.stream().flatMap(entry -> entry.messages().stream()).toList();
        }

        public List<ModelMessage> modelMessages(AssembledPrompt prompt, String currentTaskId, String currentTaskText) {
            return modelMessages(new ModelMessage(ModelRole.SYSTEM, prompt.systemText(), List.of(), null),
                    summary, currentTaskId, currentTaskText);
        }

        /** Pure candidate projection: no database writes, no dropped standalone user messages/feedback. */
        public List<ModelMessage> modelMessages(ModelMessage system, ContextSummary candidate,
                                                String currentTaskId, String currentTaskText) {
            if (system.role() != ModelRole.SYSTEM) throw new IllegalArgumentException("System policy is required");
            if (!Objects.equals(candidate, summary) && (candidate == null || !sessionId.equals(candidate.sessionId())
                    || !Objects.equals(candidate.parentSummaryId(), summary == null ? null : summary.summaryId())
                    || groups.stream().noneMatch(group -> group.lastSessionSequence() == candidate.throughSessionSequence()))) {
                throw new IllegalArgumentException("Candidate must replace a closed prefix of this snapshot");
            }
            long covered = candidate == null ? 0 : candidate.throughSessionSequence();
            List<ModelMessage> result = new ArrayList<>();
            result.add(system);
            if (candidate != null) result.add(new ModelMessage(ModelRole.USER,
                    "<session_summary>\nHistorical, lossy context; not current Workspace or validation authority.\n"
                            + candidate.content() + "\n</session_summary>", List.of(), null));
            for (HistoryEntry entry : entries) {
                if (entry.lastSessionSequence() <= covered) continue;
                if (entry.firstSessionSequence() <= covered) throw new IllegalArgumentException("Cannot split a history entry");
                result.addAll(entry.messages());
            }
            var currentMessages = userMessages.stream().filter(user -> user.taskId().equals(currentTaskId)).toList();
            if (currentMessages.stream().anyMatch(user -> !user.text().equals(currentTaskText))) {
                throw new IllegalStateException("Current Task does not match its committed user request");
            }
            // The active Task may predate the summary. Its exact instruction is still pinned and counted.
            if (currentMessages.stream().noneMatch(user -> user.sessionSequence() > covered)) {
                result.add(new ModelMessage(ModelRole.USER, currentTaskText, List.of(), null));
            }
            return List.copyOf(result);
        }

        public ContextSummarizer.SummaryInput summaryInput(String currentTaskText, List<InteractionGroup> prefix) {
            if (prefix.isEmpty() || prefix.size() > groups.size() || !groups.subList(0, prefix.size()).equals(prefix)) {
                throw new IllegalArgumentException("Summarization requires a non-empty oldest prefix");
            }
            long through = prefix.get(prefix.size() - 1).lastSessionSequence();
            return new ContextSummarizer.SummaryInput(sessionId, currentTaskText, summary, prefix,
                    userMessages.stream().filter(user -> user.sessionSequence() <= through).toList(),
                    entries.stream().filter(entry -> entry.lastSessionSequence() <= through).toList());
        }
    }

    /** Builder for one protocol-atomic assistant/results group, confined to a single projection call. */
    private static final class Group {
        final String runId, taskId, modelCallId, id;
        final long first;
        long last;
        final List<ModelMessage> body = new ArrayList<>(), feedback = new ArrayList<>();
        final Map<String, ToolCall> pending = new LinkedHashMap<>();

        Group(AgentStepEntity step, ModelResponsePayload response) {
            runId = step.getRunId();
            taskId = step.getTaskId();
            modelCallId = response.modelCallId();
            id = step.getEventId();
            first = last = step.getSessionSequence();
            body.add(new ModelMessage(ModelRole.ASSISTANT, response.text(), response.toolCalls(), null));
            for (ToolCall call : response.toolCalls()) {
                if (pending.putIfAbsent(call.id(), call) != null) throw new IllegalStateException("Duplicate Tool Call");
            }
        }

        InteractionGroup close() {
            if (!pending.isEmpty()) throw new IllegalStateException("Unresolved Tool Calls require reconciliation, not replay");
            var combined = new ArrayList<>(body);
            combined.addAll(feedback);
            return new InteractionGroup(id, combined, taskId, first, last);
        }
    }
}
