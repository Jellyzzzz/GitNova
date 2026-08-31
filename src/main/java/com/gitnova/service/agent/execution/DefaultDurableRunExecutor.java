package com.gitnova.service.agent.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.service.agent.runtime.*;
import com.gitnova.service.agent.workspace.WorkspaceBinding;
import com.gitnova.service.agent.workspace.WorkspaceExecutionPermit;
import com.gitnova.service.session.AgentSession;
import com.gitnova.service.session.AgentSessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;

@Service
@ConditionalOnBean(AgentRuntime.class)
public class DefaultDurableRunExecutor implements DurableRunExecutor {
    private final AgentTaskRunStore agentTaskRunStore;
    private final AgentSessionStore agentSessionStore;
    private final AgentRuntime runtime;
    private final ObjectMapper objectMapper;
    private final TaskScheduler heartbeatScheduler;
    private final int LEASE_SECONDS=30;
    private final int HEARTBEAT_SECONDS=10;
    private static final Logger logger =
            LoggerFactory.getLogger(DefaultDurableRunExecutor.class);
    public DefaultDurableRunExecutor(
            AgentTaskRunStore agentTaskRunStore,
            AgentRuntime agentRuntime,
            AgentSessionStore agentSessionStore, AgentRuntime runtime, ObjectMapper objectMapper,
            @Qualifier("agentHeartbeatScheduler")
            TaskScheduler heartbeatScheduler
    ) {
        this.agentTaskRunStore = agentTaskRunStore;
        this.agentSessionStore = agentSessionStore;
        this.runtime = runtime;
        this.objectMapper = objectMapper;
        this.heartbeatScheduler=heartbeatScheduler;
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

        AgentExecutionContext executionContext=new AgentExecutionContext(session.sessionId(),agentRunContext,task.createdByActorId(),taskText,workspace,executionPermit,capabilities);

        ScheduledFuture<?>scheduledFuture=startHeartbeat(runId,workerId,fencingToken);
        try {
            AgentRunResult result = runtime.run(executionContext);

            AgentTaskRunStore.TerminalOutcome outcome = switch (result.status()) {
                case COMPLETED -> AgentTaskRunStore.TerminalOutcome.COMPLETED;
                case PARTIAL -> AgentTaskRunStore.TerminalOutcome.PARTIAL;
                case FAILED -> AgentTaskRunStore.TerminalOutcome.FAILED;
            };
            agentTaskRunStore.terminateRun(
                    new AgentTaskRunStore.TerminalCommand(
                            "run:terminal:" + run.runId(),
                            "task:terminal:" + task.taskId() + ":" + run.runId(),
                            run.sessionId(),
                            run.taskId(),
                            run.runId(),
                            workerId,
                            fencingToken,
                            outcome,
                            result.terminationReason().name()
                    ));
        }finally {
            scheduledFuture.cancel(false);
        }
    }

    private final class HeartbeatTask implements Runnable{
        private final String runId;
        private final String workerId;
        private final long fencingToken;
        private HeartbeatTask(String runId,String workerId,long fencingToken){
            this.runId=runId;
            this.workerId=workerId;
            this.fencingToken=fencingToken;
        }
        @Override
        public void run() {
            try {
                AgentTaskRunStore.HeartbeatResult result = agentTaskRunStore.heartbeat(new AgentTaskRunStore.HeartbeatCommand(
                        runId, workerId, fencingToken, LEASE_SECONDS
                ));
                logger.warn(
                        "Agent Run heartbeat lost lease: runId={}, workerId={}, fence={}",
                        runId,
                        workerId,
                        fencingToken
                );
            }catch (RuntimeException e){
                logger.warn(
                        "Agent Run heartbeat failed: runId={}, workerId={}, fence={}",
                        runId,
                        workerId,
                        fencingToken,
                        e
                );
            }
        }
    }
    private ScheduledFuture<?>startHeartbeat(String runId,String workerId,long fencingToken){
        HeartbeatTask task=new HeartbeatTask(runId,workerId,fencingToken);
        return heartbeatScheduler.scheduleAtFixedRate(
                task,
                Duration.ofSeconds(HEARTBEAT_SECONDS)
        );
    }
}
