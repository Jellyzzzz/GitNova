package com.gitnova.agent.worker.control;

import com.gitnova.agent.core.engine.AgentOutcome;
import com.gitnova.agent.core.engine.TaskInput;
import com.gitnova.agent.protocol.command.AgentCommand;
public interface TaskFinalizer {
 void finish(TaskInput input, AgentOutcome outcome);
 void acknowledge(AgentCommand.Ack ack);
 void sealIdle(String commandId, AgentCommand.Seal seal);
 void preview(String commandId, AgentCommand.Preview preview);
}
