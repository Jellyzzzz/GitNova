package com.gitnova.service.agent.context;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.gitnova.dto.ToolCall;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.model.ModelMessage;
import com.gitnova.service.agent.model.ModelRequest;
import com.gitnova.service.agent.model.ModelRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TokenEstimatorTest {
    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    void shouldCountBpeTokensRatherThanCharactersOrBytes() {
        assertEquals(0, estimator.estimateText("").tokens());
        assertEquals(2, estimator.estimateText("hello world").tokens());
        assertEquals(11, "hello world".length());
    }

    @Test
    void shouldDistinguishEqualLengthTextWithDifferentTokenization() {
        String repetitive = "aaaaaaaaaaaaaaaa";
        String mixed = "a 1!b 2?c 3$d 4%";
        assertEquals(repetitive.length(), mixed.length());
        assertTrue(estimator.estimateText(repetitive).tokens() < estimator.estimateText(mixed).tokens());
    }

    @Test
    void shouldHandleChineseCodeEmojiAndSpecialTokenLikeTextAsOrdinaryContent() {
        for (String content : new String[]{"检查工作区的代码", "public int add(int a, int b) { return a+b; }",
                "🧪测试结果✅", "<|endoftext|>", "{\"stdout\":\"a\\nb\"}"}) {
            TokenEstimate estimate = estimator.estimateText(content);
            assertTrue(estimate.tokens() > 0);
            assertEquals(TokenEstimate.Quality.REFERENCE_TOKENIZER, estimate.quality());
            assertEquals("o200k_base", estimate.source());
            assertEquals(estimate, estimator.estimateText(content));
        }
        assertThrows(NullPointerException.class, () -> estimator.estimateText(null));
    }

    @Test
    void shouldIncludeAllRequestComponentsWithoutDoubleEscapingToolContent() {
        var args = JsonNodeFactory.instance.objectNode().put("filePath", "src/Main.java");
        var schema = JsonNodeFactory.instance.objectNode().put("type", "object");
        String observation = "{\"stdout\":\"路径\\nline\\t2\",\"exitCode\":0}";
        var request = new ModelRequest("deepseek-v4-flash", List.of(
                new ModelMessage(ModelRole.SYSTEM, "Follow the policy", List.of(), null),
                new ModelMessage(ModelRole.USER, "Read the file", List.of(), null),
                new ModelMessage(ModelRole.ASSISTANT, null,
                        List.of(new ToolCall("call-1", "readFile", args)), null),
                new ModelMessage(ModelRole.TOOL, observation, List.of(), "call-1")),
                List.of(new ToolDefinition("readFile", "Read current file", schema)),
                4096, 0.2, "request-1");

        var cost = estimator.estimateRequest(request);
        assertEquals(estimator.estimateText("Follow the policy").tokens()
                        + estimator.estimateText("Read the file").tokens()
                        + estimator.estimateText(observation).tokens(), cost.contentTokens());
        assertEquals(estimator.estimateText("readFile").tokens()
                + estimator.estimateText(args.toString()).tokens(), cost.toolCallTokens());
        assertEquals(estimator.estimateText("readFile").tokens()
                + estimator.estimateText("Read current file").tokens()
                + estimator.estimateText(schema.toString()).tokens(), cost.toolDefinitionTokens());
        assertTrue(cost.protocolTokens() > 0);
        assertEquals(cost.contentTokens() + cost.toolCallTokens()
                + cost.toolDefinitionTokens() + cost.protocolTokens(), cost.total().tokens());
        assertEquals(TokenEstimate.Quality.HEURISTIC, cost.total().quality());
        assertEquals(request.model(), cost.model());
    }

    @Test
    void shouldExcludeOutputBudgetTemperatureAndRequestIdentityFromInputCost() {
        var messages = List.of(new ModelMessage(ModelRole.USER, "hello", List.of(), null));
        var first = new ModelRequest("model-a", messages, List.of(), 32, 0.0, "short-id");
        var second = new ModelRequest("model-a", messages, List.of(), 8192, 1.0, "long-id".repeat(100));
        assertEquals(estimator.estimateRequest(first), estimator.estimateRequest(second));
    }

    @Test
    void shouldCountToolDefinitionsEvenWhenNoToolHasBeenCalledYet() {
        var messages = List.of(new ModelMessage(ModelRole.USER, "hello", List.of(), null));
        var first = new ModelRequest("model-a", messages, List.of(), 32, 0.0, "request-1");
        var schema = JsonNodeFactory.instance.objectNode().put("type", "object");
        var second = new ModelRequest("model-a", messages,
                List.of(new ToolDefinition("readFile", "Read a file", schema)), 32, 0.0, "request-2");
        var withoutTools = estimator.estimateRequest(first);
        var withTools = estimator.estimateRequest(second);
        assertEquals(0, withoutTools.toolDefinitionTokens());
        assertTrue(withTools.toolDefinitionTokens() > 0);
        assertEquals(0, withTools.toolCallTokens());
        assertTrue(withTools.total().tokens() > withoutTools.total().tokens());
    }

    @Test
    void shouldNotGuessAProviderTokenizerFromTheModelName() {
        var messages = List.of(new ModelMessage(ModelRole.USER, "hello", List.of(), null));
        var cost = estimator.estimateRequest(new ModelRequest("unknown-provider-model",
                messages, List.of(), 32, 0.0, "request-1"));
        assertEquals(TokenEstimate.Quality.HEURISTIC, cost.total().quality());
        assertTrue(cost.total().source().contains("json-frame-proxy"));
    }

    @Test
    void shouldRejectInvalidEstimateMetadataAndMismatchedBreakdown() {
        assertThrows(IllegalArgumentException.class, () -> new TokenEstimate(-1,
                TokenEstimate.Quality.HEURISTIC, "source"));
        assertThrows(IllegalArgumentException.class, () -> new TokenEstimate(0,
                TokenEstimate.Quality.HEURISTIC, " "));
        assertThrows(IllegalArgumentException.class, () -> new TokenEstimator.RequestEstimate("model",
                new TokenEstimate(10, TokenEstimate.Quality.HEURISTIC, "source"), 1, 1, 1, 1));
    }
}
