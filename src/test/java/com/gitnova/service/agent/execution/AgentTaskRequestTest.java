package com.gitnova.service.agent.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentTaskRequestTest {

    @Test
    void shouldExposeTheValidatedMessage() {
        AgentTaskRequest request = new AgentTaskRequest("Review the repository");

        assertEquals("Review the repository", request.message());
    }

    @Test
    void shouldRejectMissingOrBlankMessages() {
        assertThrows(NullPointerException.class, () -> new AgentTaskRequest(null));
        assertThrows(IllegalArgumentException.class, () -> new AgentTaskRequest("  "));
    }
}
