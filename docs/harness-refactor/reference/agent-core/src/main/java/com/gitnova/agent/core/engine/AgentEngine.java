package com.gitnova.agent.core.engine;

import java.util.List;
import com.gitnova.agent.core.history.SessionLog;
import com.gitnova.agent.core.model.ModelTypes;

public interface AgentEngine {
 AgentOutcome run(ModelTypes.Message input, SessionRuntime session,
                  ExecutionControl control, SessionLog.Writer events);

 /** Local text entry uses the same message execution path; it does not trim user text. */
 default AgentOutcome run(String userText, SessionRuntime session,
                          ExecutionControl control, SessionLog.Writer events) {
  return run(new ModelTypes.Message("user", userText, List.of(), null, null),
             session, control, events);
 }
}
