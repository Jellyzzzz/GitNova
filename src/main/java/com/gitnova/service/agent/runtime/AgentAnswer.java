package com.gitnova.service.agent.runtime;

/** Model-authored delivery text and the Model Call that produced it. */
public record AgentAnswer(String content, String modelCallId) {
    public AgentAnswer {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("answer content must not be blank");
        }
        if (modelCallId == null || modelCallId.isBlank()) {
            throw new IllegalArgumentException("answer modelCallId must not be blank");
        }
    }
}
