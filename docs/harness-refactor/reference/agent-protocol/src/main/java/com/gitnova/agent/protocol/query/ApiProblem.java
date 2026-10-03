package com.gitnova.agent.protocol.query;

public record ApiProblem(String code, String message, boolean retryable, String requestId) {}
