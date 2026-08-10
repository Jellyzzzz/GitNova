package com.gitnova.service.agent.model;

/**
 * Stable error categories exposed by a ModelGateway implementation.
 */
public enum ModelGatewayErrorCode {
    /** Local API key, endpoint, or model configuration is absent or invalid. */
    CONFIGURATION_ERROR,
    /** The constructed provider request is invalid, excluding context-length overflow. */
    INVALID_REQUEST,
    /** The request is valid but exceeds the model context window. */
    CONTEXT_LENGTH_EXCEEDED,
    /** The provider rejected the API key or other authentication credential. */
    AUTHENTICATION_FAILED,
    /** The authenticated credential is not allowed to access the requested model. */
    PERMISSION_DENIED,
    /** The configured model name does not exist for the selected provider. */
    MODEL_NOT_FOUND,
    /** The provider requested backoff, normally through HTTP 429. */
    RATE_LIMITED,
    /** The individual HTTP call reached its timeout before a valid response arrived. */
    TIMEOUT,
    /** DNS, connection, TLS, or socket failures prevented a provider response. */
    NETWORK_ERROR,
    /** The provider or its upstream dependency is temporarily unavailable. */
    PROVIDER_UNAVAILABLE,
    /** A successful provider response did not satisfy the OpenAI-compatible protocol. */
    INVALID_RESPONSE,
    /** A provider failure that does not fit a more actionable category. */
    PROVIDER_FAILURE
}
