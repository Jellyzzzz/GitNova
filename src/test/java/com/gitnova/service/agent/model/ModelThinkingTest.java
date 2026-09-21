package com.gitnova.service.agent.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ModelThinkingTest {
    @ParameterizedTest
    @CsvSource({"minimal,low", "low,low", "medium,high", "high,high", "xhigh,high", "max,max", "ultra,max"})
    void mapsCompatibilityLevelsToTheThreeProviderEfforts(String requested, String mapped) {
        assertEquals(mapped, new ModelThinking("enabled", requested).effort());
    }

    @Test
    void switchIsExplicitAndDisabledNeverCarriesAPositiveEffort() {
        assertEquals("high", new ModelThinking("enabled", null).effort());
        assertEquals("high", new ModelThinking(" ENABLED ", " HIGH ").effort());
        assertFalse(new ModelThinking("disabled", "max").enabled());
        assertNull(new ModelThinking("disabled", "max").effort());
        assertEquals(ModelThinking.disabled(), new ModelThinking("disabled", "high"));
        assertThrows(IllegalArgumentException.class, () -> new ModelThinking(null, "high"));
        assertThrows(IllegalArgumentException.class, () -> new ModelThinking("auto", "high"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"highest", "none", "1024"})
    void rejectsUnsupportedEffortInsteadOfSilentlyFallingBack(String effort) {
        assertThrows(IllegalArgumentException.class, () -> new ModelThinking("enabled", effort));
    }
}
