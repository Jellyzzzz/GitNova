package com.gitnova.service.agent.prompt;

import com.gitnova.service.agent.runtime.AgentRunContext;
import org.springframework.stereotype.Component;

/** States the review task while keeping concrete revision identifiers in the Harness. */
@Component
public final class TaskSection implements PromptSection {
    @Override
    public String key() {
        return "task";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public String render(AgentRunContext context) {
        return """
                <task>
                Review the server-authorized change from BASE to TARGET.
                Focus on defects that are specific, evidence-backed, and actionable for the author.
                </task>
                """;
    }
}
