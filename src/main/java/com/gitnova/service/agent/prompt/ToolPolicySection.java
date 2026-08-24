package com.gitnova.service.agent.prompt;

import com.gitnova.service.agent.runtime.AgentRunContext;
import org.springframework.stereotype.Component;

/** Describes progressive context acquisition and controlled Workspace actions. */
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
                Use only the tools exposed in the current request and only for their declared purpose.
                Acquire context progressively: explore paths, search, read focused ranges, inspect diffs, then act.
                Before a Workspace mutation, base expectedGeneration on fresh tool evidence. After modifications,
                inspect the canonical Workspace diff and run relevant validation before finishing.
                </workflow>
                """;
    }
}
