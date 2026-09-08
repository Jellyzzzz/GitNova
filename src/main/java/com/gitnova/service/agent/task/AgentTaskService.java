package com.gitnova.service.agent.task;

import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.execution.AgentExecutionPersistenceException;
import com.gitnova.service.agent.execution.AgentTaskRequest;
import com.gitnova.service.agent.execution.AgentTaskRunStore;
import com.gitnova.service.agent.execution.CreateTaskCommand;
import com.gitnova.service.agent.runtime.AgentCapabilityPolicy;
import com.gitnova.service.agent.runtime.AgentExecutionConfig;
import com.gitnova.service.agent.runtime.AgentRuntimePolicy;
import com.gitnova.service.agent.runtime.ToolSetSnap;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolSetSnapFactory;
import com.gitnova.service.session.AgentSession;
import com.gitnova.service.session.AgentSessionService;
import com.gitnova.storage.RepoKey;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class AgentTaskService {
    private static final String CONTEXT_POLICY_VERSION="context-v1";

    private final AgentSessionService sessionService;
    private final AgentTaskRunStore agentTaskRunStore;
    private final AgentRuntimePolicy policy;
    private final ToolRegistry toolRegistry;
    private final ToolSetSnapFactory toolSetSnapFactory;

    public AgentTaskService(AgentSessionService sessionService, AgentTaskRunStore agentTaskRunStore, AgentRuntimePolicy policy, ToolRegistry toolRegistry, ToolSetSnapFactory toolSetSnapFactory) {
        this.sessionService = Objects.requireNonNull(sessionService);
        this.agentTaskRunStore =Objects.requireNonNull(agentTaskRunStore);
        this.policy = Objects.requireNonNull(policy);
        this.toolRegistry =Objects.requireNonNull(toolRegistry);
        this.toolSetSnapFactory =Objects.requireNonNull(toolSetSnapFactory);
    }

    public AgentTaskRunStore.CreateResult create(RepoKey repoKey,String sessionId,long actorId,String message,String idempotencyKey){
        AgentSession session=sessionService.require(sessionId);
        if(!session.acceptsNewTasks()){
            throw new AgentExecutionPersistenceException(AgentExecutionPersistenceException.Code.STATE_CONFLICT,"Session does not accept new Tasks");
        }
        if(!session.repoKey().equals(repoKey)||session.createdByActorId()!=actorId){
            throw new AgentExecutionPersistenceException(AgentExecutionPersistenceException.Code.STATE_CONFLICT,"Session is not available for the current Task scope");
        }
        AgentTaskRequest request=new AgentTaskRequest(message);

        AgentCapabilityPolicy capabilities=AgentCapabilityPolicy.cloudAgent();
        List<ToolDefinition>definitions=toolRegistry.definitions(capabilities);
        ToolSetSnap toolSet=toolSetSnapFactory.create(definitions);

        AgentExecutionConfig config=new AgentExecutionConfig(policy,capabilities.granted(),toolSet,CONTEXT_POLICY_VERSION);

        CreateTaskCommand command=CreateTaskCommand.prepare(idempotencyKey,sessionId,actorId,request,config);
        return agentTaskRunStore.createTaskWithInitialRun(command);
    }
}
