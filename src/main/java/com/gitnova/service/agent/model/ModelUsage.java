package com.gitnova.service.agent.model;

/**
 * Token accounting reported by a provider. A null value means that metric was not
 * supplied by that provider, rather than zero tokens having been used.
 */
public record ModelUsage(Integer inputTokens, Integer outputTokens, Integer totalTokens) {
    public ModelUsage {
        validateNonNegative(inputTokens, "inputTokens");
        validateNonNegative(outputTokens, "outputTokens");
        validateNonNegative(totalTokens, "totalTokens");
    }

    public static ModelUsage unknown() {
        return new ModelUsage(null, null, null);
    }

    private static void validateNonNegative(Integer value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
