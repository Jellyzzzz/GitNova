import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.gitnova.service.agent.context.ObservationPolicy;
import com.gitnova.service.agent.context.TokenEstimator;
import com.gitnova.service.agent.context.ToolObservationPreview;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.storage.artifact.ArtifactRef;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Local output calibration using production rendering/counting; not a model or artifact E2E test. */
class ScenarioProbe {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        var mapper = JsonMapper.builder().defaultPropertyInclusion(JsonInclude.Value.construct(
                JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL)).build();
        var estimator = new TokenEstimator();
        var renderer = new ToolObservationPreview(mapper, estimator);
        var policy = new ObservationPolicy(4096, 1024);
        var execution = mapper.readTree(directory.resolve("report.json").toFile())
                .path("variants").path("baseline").path("public");
        String stdout = Files.readString(directory.resolve("baseline-public.stdout"));
        String stderr = Files.readString(directory.resolve("baseline-public.stderr"));
        if (Files.size(directory.resolve("baseline-public.stdout")) >= WorkspaceGateway.MAX_COMMAND_STREAM_BYTES
                || Files.size(directory.resolve("baseline-public.stderr")) >= WorkspaceGateway.MAX_COMMAND_STREAM_BYTES) {
            throw new AssertionError("Command output would hit the Gateway capture limit");
        }
        var payload = mapper.createObjectNode().put("status", "COMPLETED")
                .put("stdout", stdout).put("stderr", stderr).put("exitCode", execution.path("exitCode").asInt())
                .put("durationMillis", execution.path("durationMillis").asLong())
                .put("expectedGeneration", 0).put("generationBefore", 0).put("generationAfter", 0)
                .put("stdoutTruncated", false).put("stderrTruncated", false);
        // Exit 1 is a completed command with failed validation, not a failed tool invocation.
        var result = ToolResult.success(payload);
        byte[] raw = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        Path artifact = directory.resolve(digest + ".json");
        Files.write(artifact, raw);
        var reference = new ArtifactRef(digest, digest, raw.length, "application/json");
        var preview = renderer.preview("runCommand", result, reference, policy.maxPreviewTokens());
        long originalTokens = renderer.estimateTokens(result);
        long previewTokens = renderer.estimateTokens(preview);
        if (!renderer.exceedsInlineBudget(result, policy) || previewTokens > policy.maxPreviewTokens()) {
            throw new AssertionError("Fixture does not exercise the intended externalization branch");
        }
        for (String order : new String[]{"FAIL [65,", "FAIL [69,", "FAIL [73,"}) {
            if (!stdout.contains(order) || preview.toString().contains(order)) {
                throw new AssertionError("Middle diagnostic must be outside the preview: " + order);
            }
        }
        var restored = mapper.readTree(Files.readAllBytes(artifact));
        if (!restored.path("payload").path("stdout").asText().equals(stdout)) {
            throw new AssertionError("Calibration artifact did not preserve the complete captured log");
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("preview.json").toFile(), preview);
        var metrics = mapper.createObjectNode().put("referenceEncoding", TokenEstimator.REFERENCE_ENCODING)
                .put("providerUsageMeasured", false).put("inlineBudget", 4096).put("previewBudget", 1024)
                .put("fullResultEstimatedTokens", originalTokens).put("previewEstimatedTokens", previewTokens)
                .put("stdoutBytes", Files.size(directory.resolve("baseline-public.stdout")))
                .put("executorOutputTruncated", false).put("middleDiagnosticsHiddenByPreview", true)
                .put("artifactCapturedLogRoundTrip", true).put("artifactStoreEndToEndTested", false);
        mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("calibration.json").toFile(), metrics);
        System.out.println(metrics.toPrettyString());
    }
}
