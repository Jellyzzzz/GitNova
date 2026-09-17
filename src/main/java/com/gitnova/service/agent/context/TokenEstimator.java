package com.gitnova.service.agent.context;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.gitnova.service.agent.model.ModelRequest;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Locale;

/**
 * Local text estimate using a fixed reference BPE encoding, not Provider-reported usage.
 * In particular, o200k_base is NOT DeepSeek's tokenizer and is not a guaranteed upper bound.
 * Counts content only; whole-request accounting must also consider tools and message framing.
 */
@Component
public final class TokenEstimator {
    public static final String REFERENCE_ENCODING = "o200k_base";
    // JTokkit encodings are thread-safe. Reuse the packaged vocabulary; no per-call network I/O.
    private static final Encoding ENCODING = Encodings.newLazyEncodingRegistry()
            .getEncoding(EncodingType.O200K_BASE);

    public TokenEstimate estimateText(String content) {
        Objects.requireNonNull(content, "content");
        // Repository text resembling a special token remains ordinary content.
        return new TokenEstimate(ENCODING.countTokensOrdinary(content),
                TokenEstimate.Quality.REFERENCE_TOKENIZER, REFERENCE_ENCODING);
    }

    /**
     * Estimates all currently represented model input, not the HTTP body or output budget.
     * Text is counted unescaped exactly once. Empty-content JSON frames are an explicit proxy
     * for role/call/schema framing, NOT the provider's hidden chat template or an upper bound.
     */
    public RequestEstimate estimateRequest(ModelRequest request) {
        Objects.requireNonNull(request, "request");
        long contentTokens = 0;
        long toolCallTokens = 0;
        long toolDefinitionTokens = 0;
        var frames = JsonNodeFactory.instance.objectNode();
        var messages = frames.putArray("messages");

        for (var message : request.messages()) {
            var frame = messages.addObject().put("role", message.role().name().toLowerCase(Locale.ROOT));
            if (message.content() != null) {
                contentTokens = Math.addExact(contentTokens, estimateText(message.content()).tokens());
                frame.put("content", "");
            } else {
                frame.putNull("content");
            }
            if (message.toolCallId() != null) frame.put("tool_call_id", message.toolCallId());
            if (!message.toolCalls().isEmpty()) {
                var calls = frame.putArray("tool_calls");
                for (var call : message.toolCalls()) {
                    toolCallTokens = Math.addExact(toolCallTokens, estimateText(call.name()).tokens());
                    toolCallTokens = Math.addExact(toolCallTokens, estimateText(call.arguments().toString()).tokens());
                    calls.addObject().put("id", call.id()).put("type", "function")
                            .putObject("function").put("name", "").put("arguments", "");
                }
            }
        }
        if (!request.tools().isEmpty()) {
            var tools = frames.putArray("tools");
            for (var tool : request.tools()) {
                toolDefinitionTokens = Math.addExact(toolDefinitionTokens, estimateText(tool.name()).tokens());
                toolDefinitionTokens = Math.addExact(toolDefinitionTokens, estimateText(tool.description()).tokens());
                toolDefinitionTokens = Math.addExact(toolDefinitionTokens, estimateText(tool.inputSchema().toString()).tokens());
                tools.addObject().put("type", "function").putObject("function")
                        .put("name", "").put("description", "").putObject("parameters");
            }
        }
        long protocolTokens = estimateText(frames.toString()).tokens();
        long total = Math.addExact(Math.addExact(contentTokens, toolCallTokens),
                Math.addExact(toolDefinitionTokens, protocolTokens));
        return new RequestEstimate(request.model(),
                new TokenEstimate(total, TokenEstimate.Quality.HEURISTIC,
                        REFERENCE_ENCODING + "+json-frame-proxy"),
                contentTokens, toolCallTokens, toolDefinitionTokens, protocolTokens);
    }

    /** Each input component is accounted for once; provider usage must be compared separately. */
    public record RequestEstimate(
            String model,
            TokenEstimate total,
            long contentTokens,
            long toolCallTokens,
            long toolDefinitionTokens,
            long protocolTokens
    ) {
        public RequestEstimate {
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(total, "total");
            if (model.isBlank() || contentTokens < 0 || toolCallTokens < 0
                    || toolDefinitionTokens < 0 || protocolTokens < 0) {
                throw new IllegalArgumentException("Invalid request estimate");
            }
            long sum = Math.addExact(Math.addExact(contentTokens, toolCallTokens),
                    Math.addExact(toolDefinitionTokens, protocolTokens));
            if (total.tokens() != sum) {
                throw new IllegalArgumentException("Request estimate must equal its component sum");
            }
        }
    }
}
