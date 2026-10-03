package com.gitnova.agent.protocol.transport;

import java.net.URI;
import java.util.Map;
/** Base includes provider route prefix. Headers may contain secrets: never log this record. */
public record WorkerEndpoint(URI baseUri, Map<String,String> requiredHeaders) {}
