package com.gitnova.service.agent.context;

import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Immutable budget configuration; request measurements are never stored here. */
public record ContextBudget(long contextWindowTokens, long safetyMarginTokens,
                            double summaryTriggerRatio, double compactTriggerRatio, int keepRecentGroups) {
    public ContextBudget(long contextWindowTokens, long safetyMarginTokens) {
        this(contextWindowTokens, safetyMarginTokens, 0.8, 0.9, 4);
    }

    @ConstructorBinding
    public ContextBudget {
        if (contextWindowTokens <= 0) {
            throw new IllegalArgumentException("contextWindowTokens must be positive");
        }
        if (safetyMarginTokens < 0 || safetyMarginTokens >= contextWindowTokens) {
            throw new IllegalArgumentException("safetyMarginTokens must be non-negative and less than contextWindowTokens");
        }
        if (!Double.isFinite(summaryTriggerRatio) || !Double.isFinite(compactTriggerRatio)
                || summaryTriggerRatio <= 0 || summaryTriggerRatio >= compactTriggerRatio
                || compactTriggerRatio >= 1) {
            throw new IllegalArgumentException("Trigger ratios must satisfy 0 < summary < compact < 1");
        }
        if (keepRecentGroups <= 0) throw new IllegalArgumentException("keepRecentGroups must be positive");
    }

    /** estimatedInputTokens includes fixedTokens; output reserve comes from the current request policy. */
    public Assessment assess(long estimatedInputTokens, long fixedTokens, long outputReserveTokens) {
        if (fixedTokens < 0) {
            throw new IllegalArgumentException("fixedTokens must not be negative");
        }
        if (estimatedInputTokens < fixedTokens) {
            throw new IllegalArgumentException("estimatedInputTokens must include fixedTokens");
        }
        // Validate before subtracting the output reserve, including for very large long values.
        long inputLimit = contextWindowTokens - safetyMarginTokens;
        if (outputReserveTokens <= 0 || outputReserveTokens >= inputLimit) {
            throw new IllegalArgumentException("outputReserveTokens must be positive and leave room for input");
        }
        inputLimit -= outputReserveTokens;
        if (fixedTokens >= inputLimit) {
            throw new IllegalArgumentException("fixedTokens must leave room for dynamic context");
        }

        long dynamicBudget = inputLimit - fixedTokens;
        long dynamicUsed = estimatedInputTokens - fixedTokens;
        double useRatio = (double) dynamicUsed / dynamicBudget;
        return new Assessment(inputLimit, dynamicBudget, dynamicUsed, useRatio);
    }

    public record Assessment(long inputLimit, long dynamicBudget, long dynamicUsed, double useRatio) {
    }

}
