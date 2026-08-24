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
                Operate only on the current server-authorized repository, revisions, and isolated Workspace.
                The Harness selects their identities. Never invent or request repository keys, raw revisions,
                Workspace IDs, host paths, environment secrets, network access, or external write access.
                </scope>
                """;
    }
}
