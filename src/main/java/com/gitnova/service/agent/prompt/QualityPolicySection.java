package com.gitnova.service.agent.prompt;

import com.gitnova.service.agent.runtime.AgentRunContext;
import org.springframework.stereotype.Component;

/** Defines the quality bar for analysis, changes, and optional findings. */
@Component
public final class QualityPolicySection implements PromptSection {
    @Override
    public String key() {
        return "quality_policy";
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public String render(AgentRunContext context) {
        return """
                <quality_policy>
                Prefer minimal, task-scoped changes. Preserve existing architecture unless the task requires a
                change. Treat tests as finite evidence, not proof of semantic correctness. Any reported finding
                must include a repository-relative path, line range, evidence, explanation, and suggestion.
                Never claim a file change or validation that the tools did not actually produce.
                </quality_policy>
                """;
    }
}
