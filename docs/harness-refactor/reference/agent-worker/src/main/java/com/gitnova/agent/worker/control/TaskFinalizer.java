package com.gitnova.agent.worker.control;
import com.gitnova.agent.core.engine.AgentOutcome;
import com.gitnova.agent.protocol.command.AgentCommand;
/** Local task cleanup only. Must not wait for checkpoint upload or repository publication. */
public interface TaskFinalizer {
 void finish(AgentCommand acceptedCommand, AgentOutcome outcome);
}
