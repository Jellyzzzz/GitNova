package com.gitnova.service.agent.workspace;

/** Overall outcome of one ordered Workspace mutation batch. */
public enum PatchBatchStatus {
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED,
    CONFLICT
}
