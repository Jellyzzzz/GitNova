package com.gitnova.agent.core.engine;

import java.nio.file.Path;
import com.gitnova.agent.core.history.SessionLog;
import com.gitnova.agent.core.history.ResultStore;
/** Shared local resources. Core can read history but can append only through an execution-bound Writer. */
public record SessionRuntime(String sessionId, Path workRoot,
 Path stateRoot, SessionLog history, ResultStore results) {}
