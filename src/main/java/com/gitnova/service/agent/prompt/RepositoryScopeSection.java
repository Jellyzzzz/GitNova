package com.gitnova.service.agent.prompt;

import com.gitnova.service.agent.runtime.AgentRunContext;
import org.springframework.stereotype.Component;

/** Describes server-enforced scope without disclosing trusted execution values. */
@Component
public final class RepositoryScopeSection implements PromptSection {
    @Override
    public String key() {
        return "repository_scope";
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public String render(AgentRunContext context) {
        return """
                <scope>
                Inspect only the current repository and only the server-authorized BASE or TARGET revisions.
                The Harness selects the repository and revisions; do not request raw commit hashes, repository
                keys, absolute paths, environment variables, direct network access, or shell execution.
                </scope>
                """;
    }
}
