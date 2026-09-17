package com.gitnova.service.agent.context;

/** Content token estimates, not bytes: full ToolResult inline, full preview plus reference after externalization. */
public record ObservationPolicy(
        int maxInlineTokens,
        int maxPreviewTokens
) {
    public ObservationPolicy {
        if (maxInlineTokens <= 0) {
            throw new IllegalArgumentException("maxInlineTokens must be positive");
        }
        if (maxPreviewTokens <= 0
                || maxPreviewTokens >= maxInlineTokens) {
            throw new IllegalArgumentException(
                    "maxPreviewTokens must be positive and less than maxInlineTokens"
            );
        }
    }
}
