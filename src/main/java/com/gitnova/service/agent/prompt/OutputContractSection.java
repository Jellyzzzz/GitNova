package com.gitnova.service.agent.prompt;

import com.gitnova.service.agent.runtime.AgentRunContext;
import org.springframework.stereotype.Component;

/** States the only successful completion path for the first review workflow. */
@Component
public final class OutputContractSection implements PromptSection {
    @Override
    public String key() {
        return "output_contract";
    }

    @Override
    public int order() {
        return 80;
    }

    @Override
    public String render(AgentRunContext context) {
        return """
                <completion>
                Call finalizeReview alone only after gathering sufficient evidence. The review is not complete until
                finalizeReview succeeds. Use an empty issue list when no actionable defect is found. Do not provide
                the final review only as plain assistant text.
                </completion>
                """;
    }
}
