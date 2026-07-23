package com.gitnova.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.service.agent.tools.ToolExecutionContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutionContextTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    private AgentRunContext createRunContext() {
        return new AgentRunContext(
                "run-1",
                10L,
                "1/10",
                null,
                "target-sha"
        );
    }

    @Test
    void shouldCreateValidExecutionContext() {
        AgentRunContext run = createRunContext();

        ToolExecutionContext execution =
                new ToolExecutionContext(
                        run,
                        0,
                        "call-1"
                );

        assertSame(run, execution.run());
        assertEquals(0, execution.turn());
        assertEquals("call-1", execution.toolCallId());
    }

    @Test
    void shouldPreserveRunContextInformation() {
        ToolExecutionContext execution =
                new ToolExecutionContext(
                        createRunContext(),
                        2,
                        "call-3"
                );

        assertEquals(
                "run-1",
                execution.run().runId()
        );

        assertEquals(
                10L,
                execution.run().repoId()
        );

        assertEquals(
                "1/10",
                execution.run().repoKey()
        );

        assertEquals(
                "target-sha",
                execution.run().targetSha1()
        );

        assertNull(
                execution.run().baseSha1()
        );
    }

    @Test
    void shouldAllowZeroTurn() {
        ToolExecutionContext execution =
                new ToolExecutionContext(
                        createRunContext(),
                        0,
                        "call-1"
                );

        assertEquals(0, execution.turn());
    }

    @Test
    void shouldRejectNullRunContext() {
        NullPointerException exception =
                assertThrows(
                        NullPointerException.class,
                        () -> new ToolExecutionContext(
                                null,
                                0,
                                "call-1"
                        )
                );

        assertEquals(
                "run must not be null",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectNegativeTurn() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new ToolExecutionContext(
                                createRunContext(),
                                -1,
                                "call-1"
                        )
                );

        assertEquals(
                "turn must not be negative",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectNullToolCallId() {
        NullPointerException exception =
                assertThrows(
                        NullPointerException.class,
                        () -> new ToolExecutionContext(
                                createRunContext(),
                                0,
                                null
                        )
                );

        assertEquals(
                "toolCallId must not be null",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectEmptyToolCallId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolExecutionContext(
                        createRunContext(),
                        0,
                        ""
                )
        );
    }

    @Test
    void shouldRejectBlankToolCallId() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new ToolExecutionContext(
                                createRunContext(),
                                0,
                                "   "
                        )
                );

        assertEquals(
                "toolCallId must not be blank",
                exception.getMessage()
        );
    }

    @Test
    void shouldSerializeAsStructuredJson() throws Exception {
        ToolExecutionContext execution =
                new ToolExecutionContext(
                        createRunContext(),
                        3,
                        "call-4"
                );

        String json =
                objectMapper.writeValueAsString(execution);

        JsonNode root =
                objectMapper.readTree(json);

        assertEquals(
                3,
                root.get("turn").asInt()
        );

        assertEquals(
                "call-4",
                root.get("toolCallId").asText()
        );

        assertEquals(
                "run-1",
                root.get("run").get("runId").asText()
        );

        assertEquals(
                10L,
                root.get("run").get("repoId").asLong()
        );

        assertEquals(
                "1/10",
                root.get("run").get("repoKey").asText()
        );
    }
}
