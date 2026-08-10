package com.gitnova.service.agent.prompt;

import com.gitnova.service.agent.runtime.AgentRunContext;

public interface PromptSection {
    String key();
    int order();
    String render(AgentRunContext context);
}
