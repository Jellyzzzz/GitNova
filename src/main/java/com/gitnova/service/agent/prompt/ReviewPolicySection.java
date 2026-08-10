package com.gitnova.service.agent.prompt;

import com.gitnova.service.agent.runtime.AgentRunContext;
import org.springframework.stereotype.Component;

/** Defines the quality bar for findings, independently of the final tool schema. */
@Component
public final class ReviewPolicySection implements PromptSection {
    @Override
    public String key() {
        return "review_policy";
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public String render(AgentRunContext context) {
        return """
                <review_policy>
                Prioritize correctness, security, concurrency, data consistency, resource management, and
                breaking changes. Do not report purely stylistic preferences unless they create a concrete risk.
                Every issue must include a repository-relative file path, line range, evidence, explanation,
                and a practical suggestion.
                </review_policy>
                """;
    }
}
