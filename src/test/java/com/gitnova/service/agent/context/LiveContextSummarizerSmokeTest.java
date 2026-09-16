package com.gitnova.service.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.dto.ToolCall;
import com.gitnova.service.agent.context.ContextSummarizer.SummaryInput;
import com.gitnova.service.agent.context.ContextSummarizer.SummaryOutput;
import com.gitnova.service.agent.model.ModelGateway;
import com.gitnova.service.agent.model.ModelMessage;
import com.gitnova.service.agent.model.ModelRequest;
import com.gitnova.service.agent.model.ModelRole;
import com.gitnova.service.agent.model.OpenAiCompatibleModelGateway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in provider test. All history is synthetic; no repository files or tools are executed. */
@Tag("live-model")
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = "\\S+")
class LiveContextSummarizerSmokeTest {

    @Test
    @Timeout(240)
    void shouldGenerateInitialAndRollingSummaryWithRealModel() {
        ObjectMapper mapper = new ObjectMapper();
        String model = System.getenv("LLM_MODEL");
        if (model == null || model.isBlank()) model = "deepseek-v4-flash";
        String baseUrl = System.getenv("LLM_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://api.deepseek.com";
        String thinkingMode = System.getenv("LLM_THINKING_MODE");
        if (thinkingMode == null || thinkingMode.isBlank()) thinkingMode = "disabled";

        ModelGateway provider = new OpenAiCompatibleModelGateway(
                mapper, System.getenv("LLM_API_KEY"), baseUrl, 90, thinkingMode);
        List<ModelRequest> requests = new ArrayList<>();
        ModelGateway recordingGateway = request -> {
            requests.add(request);
            return provider.complete(request);
        };
        String sessionId = "synthetic-summary-" + UUID.randomUUID();
        String taskText = "修复 src/main/java/demo/PriceCalculator.java 的负数数量处理，"
                + "要求抛出 IllegalArgumentException，补测试且不改变公开方法签名。";
        String sourcePath = "src/main/java/demo/PriceCalculator.java";

        ToolCall read = new ToolCall("read-1", "readFile",
                mapper.createObjectNode().put("filePath", sourcePath));
        InteractionGroup readGroup = new InteractionGroup("read-source", List.of(
                new ModelMessage(ModelRole.ASSISTANT, "先检查计算逻辑。", List.of(read), null),
                new ModelMessage(ModelRole.TOOL, """
                        {"generation":7,"filePath":"src/main/java/demo/PriceCalculator.java",
                         "content":"public int total(int unitPrice, int quantity) { return unitPrice * quantity; }"}
                        """, List.of(), read.id())
        ), "task-price", 11, 12);

        StringBuilder testLog = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            testLog.append("[INFO] Fixture scan ").append(i)
                    .append(": unchanged generated test resource inspected; no additional diagnostic.\n");
        }
        testLog.append("[ERROR] PriceCalculatorTest.shouldRejectNegativeQuantity FAILED\n")
                .append("Expected IllegalArgumentException, but total(100, -1) returned -100.\n")
                .append("Tests run: 3, Failures: 1, Errors: 0.\n");
        ToolCall failedTest = new ToolCall("test-1", "runCommand",
                mapper.createObjectNode().put("command", "mvn -q -Dtest=PriceCalculatorTest test")
                        .put("expectedGeneration", 7));
        InteractionGroup failedTestGroup = new InteractionGroup("failed-validation", List.of(
                new ModelMessage(ModelRole.ASSISTANT, "用回归测试确认缺陷。", List.of(failedTest), null),
                new ModelMessage(ModelRole.TOOL, mapper.createObjectNode()
                        .put("generationBefore", 7).put("generationAfter", 7)
                        .put("exitCode", 1).put("stdout", testLog.toString()).toString(),
                        List.of(), failedTest.id())
        ), "task-price", 13, 14);
        InteractionGroup hypothesisGroup = new InteractionGroup("unverified-hypothesis", List.of(
                new ModelMessage(ModelRole.ASSISTANT,
                        "负数输入缺少校验已被工具证实。另外怀疑大整数乘法可能溢出，但尚未测试，不能当作已确认缺陷。",
                        List.of(), null)
        ), "task-price", 15, 15);

        String firstRequestId = sessionId + ":summary:1";
        long started = System.nanoTime();
        SummaryOutput first = new ContextSummarizer(recordingGateway, model, 1024, firstRequestId)
                .summarize(new SummaryInput(sessionId, taskText, null,
                        List.of(readGroup, failedTestGroup, hypothesisGroup)));
        long firstElapsedMs = (System.nanoTime() - started) / 1_000_000;
        int firstSourceChars = requests.get(0).messages().get(1).content().length();
        System.out.printf(Locale.ROOT,
                "LIVE_SUMMARY phase=initial model=%s elapsedMs=%d inputTokens=%s outputTokens=%s totalTokens=%s sourceChars=%d summaryChars=%d summaryToSourceCharRatio=%.3f throughSessionSequence=%d%n%s%n",
                model, firstElapsedMs, first.usage().inputTokens(), first.usage().outputTokens(),
                first.usage().totalTokens(), firstSourceChars, first.summary().content().length(),
                (double) first.summary().content().length() / firstSourceChars,
                first.summary().throughSessionSequence(), first.summary().content());

        assertEquals(sessionId, first.summary().sessionId());
        assertNull(first.summary().parentSummaryId());
        assertEquals(15, first.summary().throughSessionSequence());
        assertEquals(firstRequestId, first.requestId());
        assertTrue(first.summary().content().contains("PriceCalculator.java"), "Keep the affected file identity");
        assertTrue(first.summary().content().contains("shouldRejectNegativeQuantity"), "Keep the failing test identity");
        assertTrue(first.summary().content().contains("IllegalArgumentException"), "Keep the expected behavior");
        assertTrue(first.summary().content().length() < firstSourceChars,
                "This deliberately verbose fixture should produce a shorter summary");

        ToolCall patch = new ToolCall("patch-1", "applyPatch", mapper.createObjectNode()
                .put("expectedGeneration", 7).put("filePath", sourcePath)
                .put("change", "if (quantity < 0) throw new IllegalArgumentException(\"quantity\");"));
        InteractionGroup patchGroup = new InteractionGroup("applied-fix", List.of(
                new ModelMessage(ModelRole.ASSISTANT, "只补充负数检查，不改方法签名。", List.of(patch), null),
                new ModelMessage(ModelRole.TOOL, """
                        {"status":"SUCCESS","generationBefore":7,"generationAfter":8,
                         "modifiedFiles":["src/main/java/demo/PriceCalculator.java"]}
                        """, List.of(), patch.id())
        ), "task-price", 21, 22);
        ToolCall successfulTest = new ToolCall("test-2", "runCommand",
                mapper.createObjectNode().put("command", "mvn -q -Dtest=PriceCalculatorTest test")
                        .put("expectedGeneration", 8));
        InteractionGroup successfulTestGroup = new InteractionGroup("passed-validation", List.of(
                new ModelMessage(ModelRole.ASSISTANT, "校验修复后的工作区。", List.of(successfulTest), null),
                new ModelMessage(ModelRole.TOOL, """
                        {"generationBefore":8,"generationAfter":8,"exitCode":0,
                         "stdout":"PriceCalculatorTest: Tests run: 3, Failures: 0, Errors: 0"}
                        """, List.of(), successfulTest.id())
        ), "task-price", 23, 24);
        ToolCall reread = new ToolCall("read-2", "readFile",
                mapper.createObjectNode().put("filePath", sourcePath));
        InteractionGroup driftGroup = new InteractionGroup("external-drift", List.of(
                new ModelMessage(ModelRole.ASSISTANT, "结束前再次确认当前代码。", List.of(reread), null),
                new ModelMessage(ModelRole.TOOL, """
                        {"generation":9,"filePath":"src/main/java/demo/PriceCalculator.java",
                         "content":"public int total(int unitPrice, int quantity) { return unitPrice * quantity; }"}
                        """, List.of(), reread.id()),
                new ModelMessage(ModelRole.USER,
                        "<runtime_feedback>用户在测试后移除了负数检查，Workspace 从 generation=8 变为 generation=9。"
                                + "generation=8 的通过结果仅是历史，generation=9 尚未验证。需重新检查并测试，不能宣告当前任务完成。"
                                + "</runtime_feedback>", List.of(), null)
        ), "task-price", 25, 27);

        // A request-scoped instance supplies a distinct logical request ID without changing production code.
        String rollingRequestId = sessionId + ":summary:2";
        started = System.nanoTime();
        SummaryOutput rolling = new ContextSummarizer(recordingGateway, model, 1024, rollingRequestId)
                .summarize(new SummaryInput(sessionId, taskText, first.summary(),
                        List.of(patchGroup, successfulTestGroup, driftGroup)));
        long rollingElapsedMs = (System.nanoTime() - started) / 1_000_000;
        int rollingSourceChars = requests.get(1).messages().get(1).content().length();
        System.out.printf(Locale.ROOT,
                "LIVE_SUMMARY phase=rolling model=%s elapsedMs=%d inputTokens=%s outputTokens=%s totalTokens=%s sourceChars=%d summaryChars=%d summaryToSourceCharRatio=%.3f throughSessionSequence=%d%n%s%n",
                model, rollingElapsedMs, rolling.usage().inputTokens(), rolling.usage().outputTokens(),
                rolling.usage().totalTokens(), rollingSourceChars, rolling.summary().content().length(),
                (double) rolling.summary().content().length() / rollingSourceChars,
                rolling.summary().throughSessionSequence(), rolling.summary().content());

        assertEquals(first.summary().summaryId(), rolling.summary().parentSummaryId());
        assertNotEquals(first.summary().summaryId(), rolling.summary().summaryId());
        assertEquals(27, rolling.summary().throughSessionSequence());
        assertEquals(15, first.summary().throughSessionSequence());
        assertEquals(sessionId, rolling.summary().sessionId());
        assertEquals(rollingRequestId, rolling.requestId());
        assertNotEquals(first.requestId(), rolling.requestId());
        assertTrue(rolling.summary().content().contains("PriceCalculator.java"));
        assertTrue(rolling.summary().content().contains("IllegalArgumentException"));
        assertEquals(2, requests.size());
        for (ModelRequest request : requests) {
            assertTrue(request.tools().isEmpty());
            assertEquals(List.of(ModelRole.SYSTEM, ModelRole.USER),
                    request.messages().stream().map(ModelMessage::role).toList());
            assertEquals(1024, request.maxOutputTokens());
        }
        String rollingSource = requests.get(1).messages().get(1).content();
        assertTrue(rollingSource.contains(first.summary().content()));
        assertTrue(rollingSource.contains("throughSessionSequence=15"));
        assertTrue(rollingSource.contains("GROUP applied-fix"));
        assertFalse(rollingSource.contains("GROUP failed-validation"), "Do not resend compacted raw groups");
        assertFalse(rollingSource.contains("[INFO] Fixture scan"), "Do not resend discarded bulk logs");
        // Stale validation and uncertainty preservation are also reviewed in the printed summaries;
        // keyword checks alone cannot prove semantic correctness.
    }
}
