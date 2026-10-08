package com.gitnova.agent.protocol.command;

public enum CommandType {
    INITIALIZE,
    SUBMIT_TASK,
    STEER_TASK,
    CANCEL_TASK,
    PREVIEW_CHANGES,
    CHECKPOINT_SESSION,
    ACK_CHECKPOINT,
    STOP_WORKER
}
