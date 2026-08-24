package com.gitnova.service.agent.runtime;

/**
 * A correctable mismatch between the model response and the Runtime protocol.
 * Runtime may append feedback and continue before it becomes a terminal outcome.
 */
public enum ProtocolDeviation {
    MODEL_STOPPED_WITHOUT_FINISH,
    MIXED_TERMINAL_TOOL_CALLS
}
