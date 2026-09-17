package com.gitnova.service.agent.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class ContextBudgetTest {
    private final ContextBudget budget = new ContextBudget(100, 5);

    @Test
    void shouldRejectInvalidTriggerConfigurationWithoutImplementingTriggerActions() {
        assertThrows(IllegalArgumentException.class, () -> new ContextBudget(100, 5, 0.9, 0.8, 4));
        assertThrows(IllegalArgumentException.class, () -> new ContextBudget(100, 5, 0, 0.9, 4));
        assertThrows(IllegalArgumentException.class, () -> new ContextBudget(100, 5, 0.8, 1, 4));
        assertThrows(IllegalArgumentException.class, () -> new ContextBudget(100, 5, Double.NaN, 0.9, 4));
        assertThrows(IllegalArgumentException.class, () -> new ContextBudget(100, 5, 0.8, 0.9, 0));
    }

    @Test
    void shouldSubtractFixedTokensFromBothDynamicCapacityAndUsage() {
        assertEquals(new ContextBudget.Assessment(75, 60, 48, 0.8),
                budget.assess(63, 15, 20));
    }

    @ParameterizedTest
    @CsvSource({"15, 0.0", "75, 1.0", "87, 1.2"})
    void shouldAllowEmptyFullAndOverBudgetContext(long inputTokens, double expectedRatio) {
        assertEquals(expectedRatio, budget.assess(inputTokens, 15, 20).useRatio(), 0.000001);
    }

    @Test
    void shouldUseEachRequestsMeasurementsWithoutChangingConfiguration() {
        var first = budget.assess(63, 15, 20);
        assertEquals(new ContextBudget.Assessment(85, 65, 13, 0.2),
                budget.assess(33, 20, 10));
        assertEquals(first, budget.assess(63, 15, 20));
        assertEquals(new ContextBudget(100, 5), budget);
    }

    @Test
    void shouldAllowZeroSafetyMarginAndZeroFixedTokens() {
        assertEquals(new ContextBudget.Assessment(80, 80, 40, 0.5),
                new ContextBudget(100, 0).assess(40, 0, 20));
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "-1, 0", "100, -1", "100, 100", "100, 101"})
    void shouldRejectInvalidConfiguredCapacity(long window, long margin) {
        assertThrows(IllegalArgumentException.class, () -> new ContextBudget(window, margin));
    }

    @ParameterizedTest
    @CsvSource({"-1, 0, 20", "14, 15, 20", "63, -1, 20",
            "75, 75, 20", "76, 76, 20", "63, 15, 0", "63, 15, -1",
            "63, 15, 95", "63, 15, 100"})
    void shouldRejectInvalidMeasurementsAndMissingDynamicCapacity(long input, long fixed, long output) {
        assertThrows(IllegalArgumentException.class, () -> budget.assess(input, fixed, output));
    }

    @Test
    void shouldNotOverflowWhenReservesExceedTheWindow() {
        var largeMargin = new ContextBudget(Long.MAX_VALUE, Long.MAX_VALUE - 1);
        assertThrows(IllegalArgumentException.class, () -> largeMargin.assess(0, 0, Long.MAX_VALUE));
        assertEquals(1.0, new ContextBudget(Long.MAX_VALUE, 0)
                .assess(Long.MAX_VALUE - 1, 0, 1).useRatio());
    }
}
