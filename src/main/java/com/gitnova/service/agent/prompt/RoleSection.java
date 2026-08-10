package com.gitnova.service.agent.prompt;

import com.gitnova.service.agent.runtime.AgentRunContext;
import org.springframework.stereotype.Component;

/** Defines the agent's purpose without exposing repository identifiers. */
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
                You are GitNova's repository-aware code review agent.
                Identify actionable defects introduced by the server-authorized change.
                </role>
                """;
    }
}
