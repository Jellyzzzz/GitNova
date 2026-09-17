package com.gitnova.service.agent.context;

import com.gitnova.service.agent.model.ModelRequest;
import com.gitnova.service.agent.model.ModelRole;
import com.gitnova.service.agent.model.ModelUsage;
import com.gitnova.service.agent.persistence.CanonicalJsonCodec;

import java.util.Map;
import java.util.Objects;

/** Per-execution cache of a Session's durable usage anchor, not a shared Spring singleton. */
public final class ContextUsage {
    private final TokenEstimator estimator;
    private final CanonicalJsonCodec codec;
    private Anchor anchor;
    private String fixedDigest;
    private long fixedTokens;

    public ContextUsage(TokenEstimator estimator, CanonicalJsonCodec codec, Anchor restoredAnchor) {
        this.estimator = Objects.requireNonNull(estimator);
        this.codec = Objects.requireNonNull(codec);
        this.anchor = restoredAnchor;
    }

    public Measurement measure(ModelRequest request) {
        var system = request.messages().stream().filter(message -> message.role() == ModelRole.SYSTEM).toList();
        if (system.size() != 1 || request.messages().get(0).role() != ModelRole.SYSTEM) {
            throw new IllegalArgumentException("Context requires exactly one leading SYSTEM message");
        }
        String currentFixedDigest = codec.encodeValue(Map.of(
                "model", request.model(), "system", system, "tools", request.tools())).digest();
        if (!currentFixedDigest.equals(fixedDigest)) {
            fixedDigest = currentFixedDigest;
            fixedTokens = estimator.estimateRequest(new ModelRequest(request.model(), system, request.tools(),
                    request.maxOutputTokens(), request.temperature(), request.requestId())).total().tokens();
        }

        long inputTokens;
        String source = "LOCAL_ESTIMATE";
        boolean extendsAnchor = anchor != null && anchor.measurement().model().equals(request.model())
                && anchor.measurement().fixedDigest().equals(fixedDigest)
                && request.messages().size() >= anchor.measurement().messageCount()
                && codec.encodeValue(request.messages().subList(0, anchor.measurement().messageCount()))
                        .digest().equals(anchor.measurement().messagesDigest())
                && anchor.inputTokens() >= fixedTokens;
        if (extendsAnchor) {
            var added = request.messages().subList(anchor.measurement().messageCount(), request.messages().size());
            // Count only retained new messages, not completion_tokens (which may include hidden reasoning).
            long delta = added.isEmpty() ? 0 : estimator.estimateRequest(new ModelRequest(request.model(), added,
                    java.util.List.of(), request.maxOutputTokens(), request.temperature(), request.requestId()))
                    .total().tokens();
            inputTokens = Math.addExact(anchor.inputTokens(), delta);
            source = "PROVIDER_USAGE_PLUS_DELTA";
        } else {
            // First call, missing usage, changed model/system/tools, or history replacement after summarization.
            inputTokens = estimator.estimateRequest(request).total().tokens();
        }
        return new Measurement(request.model(), fixedDigest, codec.encodeValue(request.messages()).digest(),
                request.messages().size(), inputTokens, fixedTokens, source);
    }

    /** Used only for before/after comparison when a summary replaces history; does not change the anchor. */
    public long estimateInput(ModelRequest request) {
        return estimator.estimateRequest(request).total().tokens();
    }

    /** Called only after MODEL_RESPONSE has committed. Summary-model usage must never be passed here. */
    public void accept(Measurement measurement, ModelUsage usage) {
        Objects.requireNonNull(usage);
        anchor = usage.inputTokens() == null ? null : new Anchor(measurement, usage.inputTokens());
    }

    public record Anchor(Measurement measurement, long inputTokens) {
        public Anchor {
            Objects.requireNonNull(measurement);
            if (inputTokens < 0) throw new IllegalArgumentException("inputTokens must not be negative");
        }
    }

    /** Persisted with the model-call intent; digests identify the actual projection, not just its raw history. */
    public record Measurement(String model, String fixedDigest, String messagesDigest, int messageCount,
                              long estimatedInputTokens, long fixedTokens, String source) {
        public Measurement {
            Objects.requireNonNull(model);
            if (model.isBlank() || fixedDigest == null || !fixedDigest.matches("[0-9a-f]{64}")
                    || messagesDigest == null || !messagesDigest.matches("[0-9a-f]{64}")
                    || messageCount < 1 || fixedTokens < 0 || estimatedInputTokens < fixedTokens
                    || !("LOCAL_ESTIMATE".equals(source) || "PROVIDER_USAGE_PLUS_DELTA".equals(source))) {
                throw new IllegalArgumentException("Invalid context measurement");
            }
        }
    }
}
