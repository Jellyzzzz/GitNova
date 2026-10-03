package com.gitnova.agent.core.engine;

import java.nio.file.Path;
import com.gitnova.agent.core.history.SessionLog;
import com.gitnova.agent.core.history.ResultStore;
public record SessionRuntime(String sessionId, long runnerEpoch, Path workRoot,
 Path stateRoot, SessionLog log, ResultStore results) {}
