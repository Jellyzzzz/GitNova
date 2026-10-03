package com.gitnova.agent.core.model;

import com.gitnova.agent.core.engine.ExecutionControl;
public interface ModelGateway {
 ModelTypes.Response complete(ModelTypes.Request request, ExecutionControl control);
}
