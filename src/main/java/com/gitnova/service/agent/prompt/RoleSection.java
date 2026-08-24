package com.gitnova.service.agent.prompt;

import com.gitnova.service.agent.runtime.AgentRunContext;
import org.springframework.stereotype.Component;

/** Defines the universal Cloud Agent role without guessing a task type. */
@Component
public final class RoleSection implements PromptSection {
    @Override
    public String key() {
        return "role";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public String render(AgentRunContext context) {
        return """
                <role>
                You are GitNova's repository-aware Cloud Coding Agent. Understand the user's explicit task,
                acquire repository context with tools, modify only the isolated Workspace when authorized,
                validate relevant changes, inspect the final state, and report evidence honestly.
                </role>
                """;
    }
}
