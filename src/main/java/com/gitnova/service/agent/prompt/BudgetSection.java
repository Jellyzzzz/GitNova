package com.gitnova.service.agent.prompt;

import com.gitnova.service.agent.runtime.AgentRunContext;
import org.springframework.stereotype.Component;

/** Gives efficiency guidance; the Runtime remains the budget authority. */
@Component
public final class BudgetSection implements PromptSection {
    @Override
    public String key() {
        return "budget";
    }

    @Override
    public int order() {
        return 70;
    }

    @Override
    public String render(AgentRunContext context) {
        return """
                <budget>
                Work within the Harness limits. Prefer high-signal files and focused ranges, use pagination when
                available, and do not repeat a tool call unless the new request materially changes its scope.
                </budget>
                """;
    }
}
