package com.gitnova.service.agent.prompt;

import com.gitnova.service.agent.runtime.AgentRunContext;
import org.springframework.stereotype.Component;

/** Documents the current P0 workflow; schemas remain in ModelRequest.tools. */
@Component
public final class ToolPolicySection implements PromptSection {
    @Override
    public String key() {
        return "tool_policy";
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public String render(AgentRunContext context) {
        return """
                <workflow>
                Use only registered tools and only for their declared purpose. Start with listChanges.
                Use getDiff for relevant changed files and readFile only for focused ranges when more context is needed.
                Do not invent tools, parameters, repositories, revisions, or direct network access.
                </workflow>
                """;
    }
}
