import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.entity.agent.AgentStepEntity;
import com.gitnova.mapper.agent.AgentSessionMapper;
import com.gitnova.mapper.agent.AgentStepMapper;
import com.gitnova.service.agent.context.*;
import com.gitnova.service.agent.model.*;
import com.gitnova.service.agent.persistence.AgentEventAppender;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline history replay + opt-in summary calls. No tools, service startup or database writes. */
class SummaryAccuracyProbe {
    public static void main(String[] args) throws Exception {
        Path archive = Path.of(args[0]), output = Path.of(args[1]);
        if (Files.exists(output)) throw new IllegalArgumentException("Use a fresh output directory; no automatic retries");
        Files.createDirectories(output);
        var mapper = new ObjectMapper().findAndRegisterModules().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        boolean live = "true".equals(System.getenv("SUMMARY_EVAL_LIVE"));
        String model = System.getenv().getOrDefault("LLM_MODEL", "deepseek-v4-flash");
        var provider = new OpenAiCompatibleModelGateway(mapper, System.getenv("LLM_API_KEY"),
                System.getenv().getOrDefault("LLM_BASE_URL", "https://api.deepseek.com"), 60, 60, "disabled");
        String oldPrompt = Files.readString(Path.of("evaluation/context-large-output/summary-prompt-before.txt"));
        var results = mapper.createArrayNode();
        for (String arm : List.of("C", "D")) {
            Path historyFile = archive.resolve(arm + "/followups/regression/steps.json");
            JsonNode history = mapper.readTree(historyFile.toFile());
            JsonNode published = null;
            for (JsonNode step : history) if (step.path("type").asText().equals("CONTEXT_SUMMARY_CREATED")) published = step;
            if (published == null) throw new IllegalStateException("No archived summary for " + arm);
            String session = published.path("payload").path("sessionId").asText();
            long covered = published.path("payload").path("throughSessionSequence").asLong();
            String task = null;
            for (JsonNode step : history) if (step.path("type").asText().equals("USER_MESSAGE_RECEIVED")
                    && step.path("taskId").equals(published.path("taskId"))) {
                task = step.path("payload").path("request").path("message").asText();
            }
            if (task == null || task.isBlank()) throw new IllegalStateException("Missing current task");
            var initial = snapshot(mapper, history, session, covered, false);
            var initialInput = initial.summaryInput(task, initial.groups());
            var rolling = snapshot(mapper, history, session, history.get(history.size() - 1).path("sequence").asLong(), true);
            // Use the original, uncorrected summary, then incorporate real subsequent observations.
            var rollingInput = rolling.summaryInput(Files.readString(Path.of(
                    "evaluation/context-large-output/followups/multi-03-delivery-review.md")), rolling.groups());
            String pairedSource = null;
            for (String variant : List.of("initial-before", "initial-after", "update-after")) {
                String name = arm + "-" + variant;
                var input = variant.startsWith("initial") ? initialInput : rollingInput;
                var metadata = mapper.createObjectNode().put("case", name).put("live", live)
                        .put("archive", historyFile.toString()).put("sourceThroughSequence", input.groupToCompact()
                                .get(input.groupToCompact().size() - 1).lastSessionSequence());
                if (input.previousSummary() != null) metadata.put("previousSummaryId", input.previousSummary().summaryId());
                List<ModelRequest> requests = new ArrayList<>();
                ModelGateway recording = request -> {
                    var messages = new ArrayList<>(request.messages());
                    if (variant.endsWith("before")) messages.set(0, new ModelMessage(ModelRole.SYSTEM, oldPrompt, List.of(), null));
                    var actual = new ModelRequest(request.model(), messages, request.tools(), request.maxOutputTokens(),
                            request.temperature(), request.requestId());
                    requests.add(actual);
                    long estimatedInput = new TokenEstimator().estimateRequest(actual).total().tokens();
                    metadata.put("estimatedInputTokens", estimatedInput);
                    try { mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve(name + "-request.json").toFile(), actual); }
                    catch (Exception failure) { throw new IllegalStateException("Cannot preserve request", failure); }
                    if (estimatedInput > 26880) throw new IllegalStateException("Summary input exceeds 32k minus reserves");
                    return live ? provider.complete(actual) : new ModelResponse("dry-run", "Not a generated summary",
                            List.of(), new ModelUsage(null, null, null), ModelFinishReason.STOP);
                };
                long start = System.nanoTime();
                try {
                    var result = new ContextSummarizer(recording, model, 4096, name).summarize(input);
                    if (live) {
                        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve(name + "-response.json").toFile(), result);
                        Files.writeString(output.resolve(name + "-summary.md"), result.summary().content());
                        metadata.set("usage", mapper.valueToTree(result.usage()));
                        metadata.put("summaryChars", result.summary().content().length());
                    }
                    metadata.put("status", live ? "RECEIVED" : "PREPARED");
                } catch (ModelGatewayException failure) {
                    metadata.put("status", failure.errorCode().name()).put("retryable", failure.retryable());
                } catch (IllegalStateException failure) {
                    metadata.put("status", "SUMMARY_REJECTED");
                }
                metadata.put("elapsedMillis", (System.nanoTime() - start) / 1_000_000);
                if (requests.size() != 1) throw new IllegalStateException("Expected exactly one summary request");
                var request = requests.get(0);
                String source = request.messages().get(1).content();
                if (variant.equals("initial-before")) pairedSource = source;
                if (variant.equals("initial-after") && !source.equals(pairedSource)) throw new IllegalStateException("Paired input changed");
                metadata.put("sourceSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                metadata.put("model", model).put("maxOutputTokens", 4096).put("callTimeoutSeconds", 60).put("readTimeoutSeconds", 60);
                results.add(metadata);
                mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("results.json").toFile(), results);
                System.out.println(name + ": " + metadata.path("status").asText() + " " + metadata.path("usage"));
            }
        }
    }

    static SessionContextService.Snapshot snapshot(ObjectMapper mapper, JsonNode archive, String session,
                                                    long through, boolean restoreSummary) {
        var rows = new ArrayList<AgentStepEntity>();
        var resultEvents = new HashMap<String, String>();
        AgentStepEntity summary = null;
        for (JsonNode step : archive) {
            long sequence = step.path("sequence").asLong();
            if (sequence > through) break;
            var row = new AgentStepEntity();
            row.setSessionId(session); row.setSessionSequence(sequence);
            row.setTaskId(step.path("taskId").asText(null)); row.setRunId(step.path("runId").asText(null));
            row.setStepType(step.path("type").asText()); row.setPayloadJson(step.path("payload").toString());
            row.setSchemaVersion(row.getStepType().equals("MODEL_CALL_STARTED") && step.path("payload").has("contextInput") ? 2 : 1);
            // Export lacks SQL eventId. Reconstruct only mapper linkage; model-visible payloads stay unchanged.
            row.setEventId("archive:" + sequence);
            String call = row.getRunId() + ":" + step.path("payload").path("toolCallId").asText();
            if (row.getStepType().equals("TOOL_RESULT")) resultEvents.put(call, row.getEventId());
            if (row.getStepType().equals("TOOL_OBSERVATION_PROJECTED")) {
                row.setCausationEventId(Objects.requireNonNull(resultEvents.get(call), "Missing original result"));
            }
            if (restoreSummary && row.getStepType().equals("CONTEXT_SUMMARY_CREATED")) summary = row;
            rows.add(row);
        }
        var steps = mock(AgentStepMapper.class);
        when(steps.latestSessionSequence(session)).thenReturn(through);
        when(steps.selectLatestContextSummary(session, through)).thenReturn(summary);
        when(steps.selectSessionHistory(eq(session), anyLong(), eq(through), eq(256))).thenAnswer(call -> rows.stream()
                .filter(row -> row.getSessionSequence() > (long) call.getArgument(1)).limit(256).toList());
        return new SessionContextService(steps, mock(AgentSessionMapper.class), mock(AgentEventAppender.class),
                mapper, new MessageFactory(mapper)).load(session);
    }
}
