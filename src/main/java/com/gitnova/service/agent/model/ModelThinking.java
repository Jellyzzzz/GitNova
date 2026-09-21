package com.gitnova.service.agent.model;

import java.util.Locale;

/** Explicit DeepSeek-compatible thinking controls, frozen with the execution policy. */
public record ModelThinking(String mode, String effort) {
    public ModelThinking {
        if (mode == null) throw new IllegalArgumentException("Thinking mode is required");
        mode = mode.strip().toLowerCase(Locale.ROOT);
        if (!mode.equals("enabled") && !mode.equals("disabled")) {
            throw new IllegalArgumentException("Thinking mode must be enabled or disabled");
        }
        if (effort != null && !effort.isBlank()) {
            effort = switch (effort.strip().toLowerCase(Locale.ROOT)) {
                case "minimal", "low" -> "low";
                case "medium", "high", "xhigh" -> "high";
                case "max", "ultra" -> "max";
                default -> throw new IllegalArgumentException("Unsupported thinking effort");
            };
        } else {
            effort = "high";
        }
        // The switch wins; never send a positive effort alongside thinking=disabled.
        if (mode.equals("disabled")) effort = null;
    }

    public boolean enabled() {
        return mode.equals("enabled");
    }

    public static ModelThinking disabled() {
        return new ModelThinking("disabled", null);
    }
}
