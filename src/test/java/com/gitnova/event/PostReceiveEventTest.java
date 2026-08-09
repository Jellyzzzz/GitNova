package com.gitnova.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostReceiveEventTest {

    @Test
    void shouldCarryBaseAndNonNullTargetIntoReviewConstructionPath() {
        PostReceiveEvent event = new PostReceiveEvent(
                this,
                10L,
                "base-sha",
                "target-sha",
                20L,
                true
        );

        assertEquals("base-sha", event.getBaseSha1());
        assertEquals("target-sha", event.getTargetSha1());
    }

    @Test
    void shouldRejectNullTargetBeforeAgentRunContextCanBeConstructed() {
        assertThrows(
                NullPointerException.class,
                () -> new PostReceiveEvent(
                        this,
                        10L,
                        "base-sha",
                        null,
                        20L,
                        true
                )
        );
    }
}
