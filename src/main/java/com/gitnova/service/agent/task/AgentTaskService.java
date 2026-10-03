package com.gitnova.service.agent.task;

import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.context.ObservationPolicy;
import com.gitnova.service.agent.context.ContextBudget;
import com.gitnova.service.agent.execution.AgentExecutionPersistenceException;
import com.gitnova.service.agent.execution.AgentTaskRequest;
import com.gitnova.service.agent.execution.AgentTaskRunStore;
import com.gitnova.service.agent.execution.AgentTask;
import com.gitnova.service.agent.execution.CreateTaskCommand;
import com.gitnova.service.agent.runtime.AgentCapabilityPolicy;
import com.gitnova.service.agent.runtime.AgentExecutionConfig;
import com.gitnova.service.agent.runtime.AgentRuntimePolicy;
import com.gitnova.service.agent.runtime.AgentAnswer;
import com.gitnova.service.agent.runtime.ToolSetSnap;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolSetSnapFactory;
import com.gitnova.service.agent.tools.FinishTaskTool;
import com.gitnova.service.session.AgentSession;
import com.gitnova.service.session.AgentSessionService;
import com.gitnova.storage.RepoKey;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class AgentTaskService {
    private static final String CONTEXT_POLICY_VERSION="context-v1";

    private final AgentSessionService sessionService;
    private final AgentTaskRunStore agentTaskRunStore;
    private final AgentRuntimePolicy policy;
    private final ToolRegistry toolRegistry;
    private final ToolSetSnapFactory toolSetSnapFactory;
    private final ObservationPolicy observationPolicy;
    private final ContextBudget contextBudget;

    public AgentTaskService(AgentSessionService sessionService, AgentTaskRunStore agentTaskRunStore, AgentRuntimePolicy policy, ToolRegistry toolRegistry, ToolSetSnapFactory toolSetSnapFactory, ObservationPolicy observationPolicy, ContextBudget contextBudget) {
        this.sessionService = Objects.requireNonNull(sessionService);
        this.agentTaskRunStore =Objects.requireNonNull(agentTaskRunStore);
        this.policy = Objects.requireNonNull(policy);
        this.toolRegistry =Objects.requireNonNull(toolRegistry);
        this.toolSetSnapFactory =Objects.requireNonNull(toolSetSnapFactory);
        this.observationPolicy =Objects.requireNonNull(observationPolicy);
        this.contextBudget = Objects.requireNonNull(contextBudget);
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
        // Keep the registered legacy tool for frozen Runs, but never advertise it to new Tasks.
        List<ToolDefinition>definitions=toolRegistry.definitions(capabilities).stream()
                .filter(definition -> !FinishTaskTool.NAME.equals(definition.name()))
                .toList();
        ToolSetSnap toolSet=toolSetSnapFactory.create(definitions);

        AgentExecutionConfig config=new AgentExecutionConfig(policy,capabilities.granted(),toolSet,CONTEXT_POLICY_VERSION,observationPolicy,contextBudget);

        CreateTaskCommand command=CreateTaskCommand.prepare(idempotencyKey,sessionId,actorId,request,config);
        return agentTaskRunStore.createTaskWithInitialRun(command);
    }

    public TaskView find(RepoKey repoKey, String sessionId, long actorId, String taskId) {
        AgentSession session = sessionService.require(sessionId);
        if (!session.repoKey().equals(repoKey) || session.createdByActorId() != actorId) {
            throw new AgentExecutionPersistenceException(
                    AgentExecutionPersistenceException.Code.STATE_CONFLICT,
                    "Session is not available for the current Task scope");
        }
        AgentTask task = agentTaskRunStore.findTask(taskId)
                .filter(found -> sessionId.equals(found.sessionId()))
                .orElseThrow(() -> new AgentExecutionPersistenceException(
                        AgentExecutionPersistenceException.Code.UNKNOWN_TASK, "Unknown Task"));
        return new TaskView(task, agentTaskRunStore.findAnswer(sessionId, taskId));
    }

    public record TaskView(AgentTask task, Optional<AgentAnswer> answer) {}
}
