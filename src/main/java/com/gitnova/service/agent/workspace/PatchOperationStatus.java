package com.gitnova.service.agent.workspace;

/** Outcome of one requested operation in an ordered fail-fast batch. */
public enum PatchOperationStatus {
    APPLIED,
    FAILED,
    NOT_ATTEMPTED
}
