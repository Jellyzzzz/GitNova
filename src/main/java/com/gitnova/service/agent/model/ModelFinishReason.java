package com.gitnova.service.agent.model;

/**
 * Provider-neutral completion reason. Provider adapters translate their native values
 * (for example {@code tool_calls} or {@code tool_use}) to this enum.
 */
public enum ModelFinishReason {
    STOP,
    TOOL_CALLS,
    LENGTH,
    CONTENT_FILTER,
    UNKNOWN
}
