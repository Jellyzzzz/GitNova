package com.gitnova.service.agent.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ContextSummaryTest {

    @Test
    void shouldAllowFirstSummaryWithoutParent() {
        ContextSummary summary = new ContextSummary("s1", "session1", null, 10, "Investigate login failure");
        assertNull(summary.parentSummaryId());
        assertEquals(10, summary.throughSessionSequence());
    }

    @Test
    void shouldRetainParentAndSourceWatermarkForRollingSummary() {
        ContextSummary summary = new ContextSummary("s2", "session1", "s1", 20, "Cache ruled out; inspect null handling");
        assertEquals("s1", summary.parentSummaryId());
        assertEquals(20, summary.throughSessionSequence());
        assertEquals("Cache ruled out; inspect null handling", summary.content());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\n"})
    void shouldRejectBlankParentWhenProvided(String parentId) {
        assertThrows(IllegalArgumentException.class,
                () -> new ContextSummary("s1", "session1", parentId, 10, "Summary"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void shouldRequirePositiveSourceWatermark(long throughSequence) {
        assertThrows(IllegalArgumentException.class,
                () -> new ContextSummary("s1", "session1", null, throughSequence, "Summary"));
    }

    @Test
    void shouldRequireSummaryAndSessionIdentity() {
        assertThrows(NullPointerException.class,
                () -> new ContextSummary(null, "session1", null, 10, "Summary"));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextSummary(" ", "session1", null, 10, "Summary"));
        assertThrows(NullPointerException.class,
                () -> new ContextSummary("s1", null, null, 10, "Summary"));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextSummary("s1", " ", null, 10, "Summary"));
    }

    @Test
    void shouldRequireSummaryContent() {
        assertThrows(NullPointerException.class,
                () -> new ContextSummary("s1", "session1", null, 10, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextSummary("s1", "session1", null, 10, " "));
    }
}
