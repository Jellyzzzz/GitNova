package com.gitnova.agent.core.engine;

import java.time.Instant;
import com.gitnova.agent.protocol.command.TaskMode;
public record TaskInput(String sessionId, String taskId, String attemptId,
 long runnerEpoch, TaskMode mode, String message, String expectedPublishedHead, Instant deadlineAt) {}
