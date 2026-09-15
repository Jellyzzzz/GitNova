package com.gitnova.service.agent.context;

import java.util.List;
import java.util.Objects;

/** Selects the closed interaction groups used to build the next model context. */
public final class ContextAssembler {

    public WindowSelection selectWindow(
            List<InteractionGroup> groups,
            int keepRecentGroups
    ) {
        Objects.requireNonNull(groups, "groups must not be null");
        if (keepRecentGroups <= 0) {
            throw new IllegalArgumentException("keepRecentGroups must be positive");
        }

        List<InteractionGroup> orderedGroups = List.copyOf(groups);
        InteractionGroup previous = null;
        for (InteractionGroup group : orderedGroups) {
            if (!group.closed()) {
                throw new IllegalArgumentException("Interaction groups must be closed");
            }
            if (previous != null
                    && previous.lastSessionSequence() >= group.firstSessionSequence()) {
                throw new IllegalArgumentException(
                        "Interaction groups must be ordered and must not overlap"
                );
            }
            previous = group;
        }

        int splitIndex = Math.max(0, orderedGroups.size() - keepRecentGroups);
        return new WindowSelection(
                orderedGroups.subList(0, splitIndex),
                orderedGroups.subList(splitIndex, orderedGroups.size())
        );
    }

    public record WindowSelection(
            List<InteractionGroup> groupsToCompact,
            List<InteractionGroup> groupsToKeep
    ) {
        public WindowSelection {
            Objects.requireNonNull(groupsToCompact, "groupsToCompact must not be null");
            Objects.requireNonNull(groupsToKeep, "groupsToKeep must not be null");
            groupsToCompact = List.copyOf(groupsToCompact);
            groupsToKeep = List.copyOf(groupsToKeep);
        }
    }
}
