package com.gitnova.service.agent.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.service.agent.runtime.*;
import com.gitnova.service.agent.workspace.WorkspaceBinding;
import com.gitnova.service.agent.workspace.WorkspaceExecutionPermit;
import com.gitnova.service.session.AgentSession;
import com.gitnova.service.session.AgentSessionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnBean(AgentRuntime.class)
public class DefaultDurableRunExecutor implements DurableRunExecutor {
    private final AgentTaskRunStore agentTaskRunStore;
    private final AgentRuntime agentRuntime;
    private final AgentSessionStore agentSessionStore;
    private final ObjectMapper objectMapper;
    public DefaultDurableRunExecutor(
            AgentTaskRunStore agentTaskRunStore,
            AgentRuntime agentRuntime,
            AgentSessionStore agentSessionStore, ObjectMapper objectMapper
    ) {
        this.agentRuntime = agentRuntime;
        this.agentTaskRunStore = agentTaskRunStore;
        this.agentSessionStore = agentSessionStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public void execute(String runId, String workerId, long fencingToken) throws JsonProcessingException {
        AgentRun run = agentTaskRunStore.findRun(runId).orElseThrow();
        AgentTask task = agentTaskRunStore.findTask(run.taskId()).orElseThrow();
        AgentSession session = agentSessionStore.findById(run.sessionId()).orElseThrow();

        if (!AgentRun.Status.RUNNING.equals(run.status())) {
            return;
        }
        if (!workerId.equals(run.leaseOwner())) {
            return;
        }
        if (!run.currentFencingToken().equals(fencingToken)) {
            return;
        }
        if (!AgentTask.Status.ACTIVE.equals(task.status())) {
            return;
        }
        if (!AgentSession.Status.ACTIVE.equals(session.status())) {
            return;
        }

        if (!task.sessionId().equals(run.sessionId())) {
            throw new IllegalStateException("Run/Task session mismatch");
        }

        if (!run.runId().equals(task.currentRunId())) {
            throw new IllegalStateException("Run is not Task currentRun");
        }
        WorkspaceBinding workspace = new WorkspaceBinding(session.workspaceId());
        WorkspaceExecutionPermit executionPermit = new WorkspaceExecutionPermit(
                run.runId(),
                session.workspaceId(),
                run.currentFencingToken()
        );

        AgentRunContext agentRunContext = new AgentRunContext(
                run.runId(),
                session.repoKey().repoId(),
                session.repoKey().value(),
                session.source()
        );

        String taskText = task.request().message();
        AgentExecutionConfig config=objectMapper.readValue(run.executionConfigJson(),AgentExecutionConfig.class);
        AgentCapabilityPolicy capabilities=AgentCapabilityPolicy.cloudAgent().restrictTo(config.capabilities());

        AgentExecutionContext executionContext=new AgentExecutionContext(session.sessionId(),agentRunContext,session.createdByActorId(),taskText,workspace,executionPermit,capabilities);
    }
}
