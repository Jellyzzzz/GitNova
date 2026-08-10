package com.gitnova.service.agent.model;

/**
 * Boundary between AgentRuntime and a model-provider protocol.
 *
 * <p>Implementations translate provider-specific request, response, and error details.
 * The runtime only sees the model types in this package.</p>
 */
public interface ModelGateway {

    ModelResponse complete(ModelRequest request);
}
