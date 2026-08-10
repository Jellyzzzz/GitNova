package com.gitnova.dto;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolCallTest {

    @Test
    void shouldRejectMissingArguments() {
        assertThrows(
                NullPointerException.class,
                () -> new ToolCall("call-1", "readFile", null)
        );
    }

    @Test
    void shouldRejectBlankIdentifiers() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolCall(" ", "readFile", JsonNodeFactory.instance.objectNode())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolCall("call-1", " ", JsonNodeFactory.instance.objectNode())
        );
    }
}
