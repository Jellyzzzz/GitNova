package com.gitnova.service.agent.runtime;

/**
 * A correctable mismatch between the model response and this review protocol.
 * Runtime may append feedback and continue before it becomes a terminal outcome.
 */
public enum ProtocolDeviation {
    MODEL_STOPPED_WITHOUT_FINALIZE,
    MIXED_TERMINAL_TOOL_CALLS
}
