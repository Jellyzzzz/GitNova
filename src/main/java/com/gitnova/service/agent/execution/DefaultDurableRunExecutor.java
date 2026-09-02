package com.gitnova.service.agent.execution;

import com.gitnova.service.agent.runtime.AgentExecutionContext;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.runtime.AgentRunResult;
import com.gitnova.service.agent.runtime.AgentRuntime;
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
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;

@Service
@ConditionalOnBean(AgentRuntime.class)
public class DefaultDurableRunExecutor implements DurableRunExecutor {
    private static final int LEASE_SECONDS = 30;
    private static final int HEARTBEAT_SECONDS = 10;
    private static final Logger logger = LoggerFactory.getLogger(
            DefaultDurableRunExecutor.class
    );

    private final AgentTaskRunStore agentTaskRunStore;
    private final AgentSessionStore agentSessionStore;
    private final AgentRuntime runtime;
    private final TaskScheduler heartbeatScheduler;

    public DefaultDurableRunExecutor(
            AgentTaskRunStore agentTaskRunStore,
            AgentSessionStore agentSessionStore,
            AgentRuntime runtime,
            @Qualifier("agentHeartbeatScheduler")
            TaskScheduler heartbeatScheduler
    ) {
        this.agentTaskRunStore = Objects.requireNonNull(
                agentTaskRunStore,
                "agentTaskRunStore must not be null"
        );
        this.agentSessionStore = Objects.requireNonNull(
                agentSessionStore,
                "agentSessionStore must not be null"
        );
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.heartbeatScheduler = Objects.requireNonNull(
                heartbeatScheduler,
                "heartbeatScheduler must not be null"
        );
    }

    @Override
    public void execute(String runId, String workerId, long fencingToken) {
        AgentRun run = agentTaskRunStore.findRun(runId).orElseThrow();
        AgentTask task = agentTaskRunStore.findTask(run.taskId()).orElseThrow();
        AgentSession session = agentSessionStore.findById(run.sessionId()).orElseThrow();

        if (run.status() != AgentRun.Status.RUNNING) {
            return;
        }
        if (!workerId.equals(run.leaseOwner())) {
            return;
        }
        if (run.currentFencingToken() != fencingToken) {
            return;
        }
        if (task.status() != AgentTask.Status.ACTIVE) {
            return;
        }
        if (session.status() != AgentSession.Status.ACTIVE) {
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

        AgentExecutionContext executionContext = new AgentExecutionContext(
                session.sessionId(),
                agentRunContext,
                task.createdByActorId(),
                task.request().message(),
                workspace,
                executionPermit,
                run.executionConfig()
        );

        AgentExecutionControl executionControl = new AgentExecutionControl();
        ScheduledFuture<?> heartbeat = startHeartbeat(
                runId,
                workerId,
                fencingToken,
                executionControl
        );
        try {
            AgentRunResult result = runtime.run(executionContext, executionControl);
            executionControl.requireLease();

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
        } catch (AgentExecutionControl.LeaseLostException exception) {
            logger.info(
                    "Agent Run stopped after lease loss: runId={}, workerId={}, fence={}",
                    runId,
                    workerId,
                    fencingToken
            );
        } finally {
            heartbeat.cancel(false);
        }
    }

    private final class HeartbeatTask implements Runnable {
        private final String runId;
        private final String workerId;
        private final long fencingToken;
        private final AgentExecutionControl executionControl;

        private HeartbeatTask(
                String runId,
                String workerId,
                long fencingToken,
                AgentExecutionControl executionControl
        ) {
            this.runId = runId;
            this.workerId = workerId;
            this.fencingToken = fencingToken;
            this.executionControl = executionControl;
        }

        @Override
        public void run() {
            try {
                AgentTaskRunStore.HeartbeatResult result = agentTaskRunStore.heartbeat(
                        new AgentTaskRunStore.HeartbeatCommand(
                                runId,
                                workerId,
                                fencingToken,
                                LEASE_SECONDS
                        )
                );
                if (result == AgentTaskRunStore.HeartbeatResult.LEASE_LOST) {
                    executionControl.markLeaseLost();
                    logger.warn(
                            "Agent Run heartbeat lost lease: runId={}, workerId={}, fence={}",
                            runId,
                            workerId,
                            fencingToken
                    );
                }
            } catch (RuntimeException exception) {
                logger.warn(
                        "Agent Run heartbeat failed: runId={}, workerId={}, fence={}",
                        runId,
                        workerId,
                        fencingToken,
                        exception
                );
            }
        }
    }

    private ScheduledFuture<?> startHeartbeat(
            String runId,
            String workerId,
            long fencingToken,
            AgentExecutionControl executionControl
    ) {
        HeartbeatTask task = new HeartbeatTask(
                runId,
                workerId,
                fencingToken,
                executionControl
        );
        return heartbeatScheduler.scheduleAtFixedRate(
                task,
                Duration.ofSeconds(HEARTBEAT_SECONDS)
        );
    }
}
