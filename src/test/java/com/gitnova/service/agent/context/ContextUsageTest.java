package com.gitnova.service.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.service.agent.model.*;
import com.gitnova.service.agent.persistence.CanonicalJsonCodec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextUsageTest {
    private final TokenEstimator estimator = spy(new TokenEstimator());
    private final CanonicalJsonCodec codec = new CanonicalJsonCodec(new ObjectMapper());
    private final List<ModelMessage> initial = List.of(
            new ModelMessage(ModelRole.SYSTEM, "Use tools safely", List.of(), null),
            new ModelMessage(ModelRole.USER, "Fix the test", List.of(), null));

    @Test
    void shouldUseLatestProviderInputAndOnlyEstimateAppendedContent() {
        var usage = new ContextUsage(estimator, codec, null);
        var first = usage.measure(request("model", initial));
        assertEquals("LOCAL_ESTIMATE", first.source());
        usage.accept(first, new ModelUsage(1000, 9000, 10000));
        var tail = new ModelMessage(ModelRole.ASSISTANT, "I will inspect the test", List.of(), null);
        var second = usage.measure(request("model", List.of(initial.get(0), initial.get(1), tail)));
        long delta = estimator.estimateRequest(request("model", List.of(tail))).total().tokens();
        assertEquals(1000 + delta, second.estimatedInputTokens());
        assertEquals("PROVIDER_USAGE_PLUS_DELTA", second.source());
        // A new provider response replaces the old anchor; usage is not a cumulative context counter.
        usage.accept(second, new ModelUsage(1200, 200, 1400));
        assertEquals(1200, usage.measure(request("model", List.of(initial.get(0), initial.get(1), tail)))
                .estimatedInputTokens());
    }

    @Test
    void shouldRestoreAcrossRunsOnlyWhenTheActualRequestPrefixMatches() {
        var first = new ContextUsage(estimator, codec, null).measure(request("model", initial));
        var restored = new ContextUsage(estimator, codec, new ContextUsage.Anchor(first, 1000));
        assertEquals(1000, restored.measure(request("model", initial)).estimatedInputTokens());
        assertEquals("LOCAL_ESTIMATE", restored.measure(request("other-model", initial)).source());
        var changedSystem = List.of(new ModelMessage(ModelRole.SYSTEM, "Different policy", List.of(), null), initial.get(1));
        assertEquals("LOCAL_ESTIMATE", restored.measure(request("model", changedSystem)).source());
        var summarized = List.of(initial.get(0), new ModelMessage(ModelRole.USER, "Summary replaces old task", List.of(), null));
        assertEquals("LOCAL_ESTIMATE", restored.measure(request("model", summarized)).source());
    }

    @Test
    void shouldReestimateWhenUsageIsMissingOrCannotCoverTheFixedEstimate() {
        var usage = new ContextUsage(estimator, codec, null);
        var measured = usage.measure(request("model", initial));
        usage.accept(measured, ModelUsage.unknown());
        assertEquals("LOCAL_ESTIMATE", usage.measure(request("model", initial)).source());
        usage.accept(measured, new ModelUsage(0, 0, 0));
        assertEquals("LOCAL_ESTIMATE", usage.measure(request("model", initial)).source());
    }

    @Test
    void shouldNotCarryAnAnchorAcrossAToolDefinitionChange() {
        var usage = new ContextUsage(estimator, codec, null);
        usage.accept(usage.measure(request("model", initial)), new ModelUsage(1000, 1, 1001));
        var tool = new com.gitnova.dto.ToolDefinition("readFile", "Read", new ObjectMapper().createObjectNode());
        assertEquals("LOCAL_ESTIMATE", usage.measure(new ModelRequest("model", initial, List.of(tool), 100, 0.0, "r2")).source());
    }

    private ModelRequest request(String model, List<ModelMessage> messages) {
        return new ModelRequest(model, messages, List.of(), 100, 0.0, "request");
    }
}
