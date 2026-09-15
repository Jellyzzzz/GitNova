package com.gitnova.service.agent.context;

import com.gitnova.dto.ToolCall;
import com.gitnova.service.agent.model.ModelMessage;
import com.gitnova.service.agent.model.ModelRole;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One assistant response, its tool results, and optional trailing Harness feedback. */
public record InteractionGroup(
        String groupId,
        List<ModelMessage> messages,
        String taskId,
        long firstSessionSequence,
        long lastSessionSequence
) {
    public InteractionGroup {
        Objects.requireNonNull(messages, "messages must not be null");
        messages = List.copyOf(messages);
        if (messages.isEmpty()) throw new IllegalArgumentException("messages must not be empty");
        if (messages.get(0).role() != ModelRole.ASSISTANT) {
            throw new IllegalArgumentException("First message must be assistant");
        }
        requireNonBlank(groupId, "groupId");
        requireNonBlank(taskId, "taskId");
        if (firstSessionSequence <= 0 || lastSessionSequence < firstSessionSequence) {
            throw new IllegalArgumentException("Invalid Session sequence range");
        }
        int assistantCount = 0;
        int systemCount = 0;
        for (ModelMessage message : messages) {
            if (message.role() == ModelRole.ASSISTANT) assistantCount += 1;
            if (message.role() == ModelRole.SYSTEM) systemCount += 1;
        }
        if (assistantCount > 1) throw new IllegalArgumentException("Assistant message must be only 1");
        if (systemCount > 0) throw new IllegalArgumentException("System message must be 0");
    }

    public boolean closed() {
        Set<String> pendingCallIds = new HashSet<>();
        for (ModelMessage message : messages) {
            if (message.role() == ModelRole.ASSISTANT) {
                for (ToolCall toolCall : message.toolCalls()) {
                    if (!pendingCallIds.add(toolCall.id())) return false;
                }
            } else if (message.role() == ModelRole.TOOL) {
                if (!pendingCallIds.remove(message.toolCallId())) return false;
            } else if (message.role() == ModelRole.USER && !pendingCallIds.isEmpty()) {
                // The assembler supplies Harness feedback, not a new user Task, here.
                return false;
            }
        }
        return pendingCallIds.isEmpty();
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
