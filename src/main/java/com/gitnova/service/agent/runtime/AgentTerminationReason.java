package com.gitnova.service.agent.runtime;

/** The final reason a Run stopped, not an intermediate model protocol event. */
public enum AgentTerminationReason {
    FINALIZE_SUCCEEDED,
    INVALID_FINAL_DRAFT,
    MODEL_OUTPUT_LENGTH,
    MODEL_CONTENT_FILTERED,
    MAX_MODEL_CALLS_REACHED,
    MAX_TOOL_CALLS_REACHED,
    MODEL_GATEWAY_FAILURE,
    INVALID_MODEL_PROTOCOL,
    PROTOCOL_CORRECTION_EXHAUSTED
}
