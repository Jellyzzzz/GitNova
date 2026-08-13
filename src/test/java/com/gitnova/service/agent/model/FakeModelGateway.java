package com.gitnova.service.agent.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Scripted in-memory {@link ModelGateway} for AgentRuntime tests.
 *
 * <p>Each call consumes exactly one queued outcome. This lets a test describe a
 * deterministic model conversation such as response -> response -> failure
 * without making a network request or depending on a provider implementation.</p>
 */
public final class FakeModelGateway implements ModelGateway {

    private final Deque<Outcome> scriptedOutcomes = new ArrayDeque<>();
    private final List<ModelRequest> receivedRequests = new ArrayList<>();

    public FakeModelGateway enqueueResponse(ModelResponse response) {
        scriptedOutcomes.addLast(new ResponseOutcome(
                Objects.requireNonNull(response, "response must not be null")
        ));
        return this;
    }

    public FakeModelGateway enqueueFailure(ModelGatewayException exception) {
        scriptedOutcomes.addLast(new FailureOutcome(
                Objects.requireNonNull(exception, "exception must not be null")
        ));
        return this;
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        receivedRequests.add(Objects.requireNonNull(request, "request must not be null"));

        Outcome outcome = scriptedOutcomes.pollFirst();
        if (outcome == null) {
            throw new IllegalStateException(
                    "FakeModelGateway has no scripted outcome for request " + request.requestId()
            );
        }
        return outcome.complete();
    }

    public List<ModelRequest> receivedRequests() {
        return List.copyOf(receivedRequests);
    }

    public int remainingOutcomes() {
        return scriptedOutcomes.size();
    }

    private sealed interface Outcome permits ResponseOutcome, FailureOutcome {
        ModelResponse complete();
    }

    private record ResponseOutcome(ModelResponse response) implements Outcome {
        @Override
        public ModelResponse complete() {
            return response;
        }
    }

    private record FailureOutcome(ModelGatewayException exception) implements Outcome {
        @Override
        public ModelResponse complete() {
            throw exception;
        }
    }
}
