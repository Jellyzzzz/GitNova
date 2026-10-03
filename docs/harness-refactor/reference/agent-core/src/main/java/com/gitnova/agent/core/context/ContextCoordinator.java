package com.gitnova.agent.core.context;

import java.util.List;
import com.gitnova.agent.core.model.ModelTypes;
import com.gitnova.agent.core.engine.*;
public interface ContextCoordinator {
 ModelTypes.Request prepare(TaskInput input, SessionRuntime session, ExecutionControl control,
   List<ModelTypes.ToolDefinition> tools);
 void observeResponse(ModelTypes.Response response);
}
