package com.gitnova.service.agent.context;

import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Content token estimates, not bytes: full ToolResult inline, full preview plus reference after externalization. */
public record ObservationPolicy(
        int maxInlineTokens,
        int maxPreviewTokens,
        @DefaultValue("true") boolean externalizationEnabled
) {
    public ObservationPolicy(int maxInlineTokens, int maxPreviewTokens) {
        this(maxInlineTokens, maxPreviewTokens, true);
    }

    @ConstructorBinding
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
