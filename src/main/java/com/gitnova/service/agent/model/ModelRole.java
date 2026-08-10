package com.gitnova.service.agent.model;

/**
 * A message's protocol role in a model conversation. This is not an AgentRuntime state
 * and must not be used as a trust classification for the message content.
 */
public enum ModelRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}
