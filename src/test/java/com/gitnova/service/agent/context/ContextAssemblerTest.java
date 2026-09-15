package com.gitnova.service.agent.context;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.gitnova.dto.ToolCall;
import com.gitnova.service.agent.model.ModelMessage;
import com.gitnova.service.agent.model.ModelRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextAssemblerTest {

    private final ContextAssembler assembler = new ContextAssembler();

    @Test
    void shouldKeepAllGroupsWhenHistoryIsSmallerThanWindow() {
        List<InteractionGroup> groups = List.of(group("g1", 1), group("g2", 3));

        ContextAssembler.WindowSelection result = assembler.selectWindow(groups, 3);

        assertTrue(result.groupsToCompact().isEmpty());
        assertEquals(groups, result.groupsToKeep());
    }

    @Test
    void shouldKeepAllGroupsWhenHistoryExactlyFillsWindow() {
        List<InteractionGroup> groups = List.of(group("g1", 1), group("g2", 3));

        ContextAssembler.WindowSelection result = assembler.selectWindow(groups, 2);

        assertTrue(result.groupsToCompact().isEmpty());
        assertEquals(groups, result.groupsToKeep());
    }

    @Test
    void shouldCompactOlderGroupsAndKeepExactlyTheNewestGroupsInOrder() {
        InteractionGroup g1 = group("g1", 1);
        InteractionGroup g2 = group("g2", 3);
        InteractionGroup g3 = group("g3", 5);
        InteractionGroup g4 = group("g4", 7);
        InteractionGroup g5 = group("g5", 9);

        ContextAssembler.WindowSelection result = assembler.selectWindow(
                List.of(g1, g2, g3, g4, g5),
                3
        );

        assertEquals(List.of(g1, g2), result.groupsToCompact());
        assertEquals(List.of(g3, g4, g5), result.groupsToKeep());
    }

    @Test
    void shouldAcceptEmptyHistory() {
        ContextAssembler.WindowSelection result = assembler.selectWindow(List.of(), 3);

        assertTrue(result.groupsToCompact().isEmpty());
        assertTrue(result.groupsToKeep().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void shouldRequirePositiveWindowSize(int keepRecentGroups) {
        assertThrows(
                IllegalArgumentException.class,
                () -> assembler.selectWindow(List.of(), keepRecentGroups)
        );
    }

    @Test
    void shouldRequireGroups() {
        List<InteractionGroup> groupsWithNull = new ArrayList<>();
        groupsWithNull.add(group("g1", 1));
        groupsWithNull.add(null);

        assertThrows(NullPointerException.class, () -> assembler.selectWindow(null, 3));
        assertThrows(NullPointerException.class,
                () -> assembler.selectWindow(groupsWithNull, 3));
    }

    @Test
    void shouldRejectOpenGroup() {
        InteractionGroup open = new InteractionGroup(
                "g1",
                List.of(assistant("call1")),
                "task1",
                1,
                1
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> assembler.selectWindow(List.of(open), 3)
        );
    }

    @Test
    void shouldRejectOutOfOrderOrOverlappingGroups() {
        InteractionGroup first = group("g1", 3);
        InteractionGroup earlier = group("g2", 1);
        InteractionGroup overlapping = new InteractionGroup(
                "g3",
                List.of(assistant("g3-call"), result("g3-call")),
                "task1",
                4,
                5
        );

        assertThrows(IllegalArgumentException.class,
                () -> assembler.selectWindow(List.of(first, earlier), 2));
        assertThrows(IllegalArgumentException.class,
                () -> assembler.selectWindow(List.of(first, overlapping), 2));
    }

    @Test
    void shouldAllowSequenceGaps() {
        InteractionGroup first = group("g1", 1);
        InteractionGroup second = group("g2", 10);

        ContextAssembler.WindowSelection result = assembler.selectWindow(
                List.of(first, second),
                1
        );

        assertEquals(List.of(first), result.groupsToCompact());
        assertEquals(List.of(second), result.groupsToKeep());
    }

    @Test
    void shouldReturnImmutableListsIndependentFromInputList() {
        List<InteractionGroup> input = new ArrayList<>(
                List.of(group("g1", 1), group("g2", 3))
        );

        ContextAssembler.WindowSelection result = assembler.selectWindow(input, 1);
        input.clear();

        assertEquals(1, result.groupsToCompact().size());
        assertEquals(1, result.groupsToKeep().size());
        assertThrows(UnsupportedOperationException.class, result.groupsToCompact()::clear);
        assertThrows(UnsupportedOperationException.class, result.groupsToKeep()::clear);
    }

    private InteractionGroup group(String id, long firstSequence) {
        String callId = id + "-call";
        return new InteractionGroup(
                id,
                List.of(assistant(callId), result(callId)),
                "task1",
                firstSequence,
                firstSequence + 1
        );
    }

    private ModelMessage assistant(String... callIds) {
        List<ToolCall> calls = new ArrayList<>();
        for (String callId : callIds) {
            calls.add(new ToolCall(
                    callId,
                    "readFile",
                    JsonNodeFactory.instance.objectNode()
            ));
        }
        return new ModelMessage(ModelRole.ASSISTANT, "Inspect files", calls, null);
    }

    private ModelMessage result(String callId) {
        return new ModelMessage(
                ModelRole.TOOL,
                "{\"status\":\"SUCCESS\"}",
                List.of(),
                callId
        );
    }
}
