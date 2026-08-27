package com.gitnova.service.agent.persistence;

/** Stable semantic type of one append-only durable Agent event. */
public enum AgentStepType {
    SESSION_CREATED,
    WORKSPACE_MATERIALIZED,
    WORKSPACE_PROVISIONING_FAILED
}
