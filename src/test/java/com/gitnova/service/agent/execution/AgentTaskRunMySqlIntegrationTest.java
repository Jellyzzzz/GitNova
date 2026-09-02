package com.gitnova.service.agent.execution;

import com.gitnova.gitobject.GitObjectId;
import com.gitnova.service.agent.workspace.SnapshotScope;
import com.gitnova.service.agent.runtime.AgentCapability;
import com.gitnova.service.agent.runtime.AgentExecutionConfig;
import com.gitnova.service.session.AgentSessionStore;
import com.gitnova.service.session.CreateSessionCommand;
import com.gitnova.storage.RepoKey;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real MySQL contract for Task/Run ordering, dispatch, lease recovery and fencing. */
@SpringBootTest
@Tag("mysql-it")
@Transactional
class AgentTaskRunMySqlIntegrationTest {

    @Autowired
    AgentSessionStore sessionStore;

    @Autowired
    AgentTaskRunStore taskRunStore;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void shouldPersistClaimHeartbeatTakeoverAndFailedTerminalTransactionChain() {
        long ownerId = 7101L;
        long repoId = insertRepository(ownerId);
        RepoKey repoKey = RepoKey.of(ownerId, repoId);
        CreateSessionCommand sessionCommand = CreateSessionCommand.prepare(
                "task-run-mysql-it-" + UUID.randomUUID(),
                9101L,
                repoKey,
                new SnapshotScope(GitObjectId.of("a".repeat(40)))
        );
        AgentSessionStore.CreateResult session = sessionStore.createProvisioning(sessionCommand);
        sessionStore.activate(new AgentSessionStore.WorkspaceActivation(
                "workspace:materialized:" + session.session().workspaceId(),
                session.session().sessionId(),
                "mysql-it",
                "workspace://" + session.session().workspaceId(),
                null,
                "b".repeat(64)
        ));

        AgentExecutionConfig executionConfig =
                com.gitnova.service.agent.AgentTestExecutionConfigs.minimal(
                        Set.of(AgentCapability.CODE_READ)
                );
        CreateTaskCommand create = CreateTaskCommand.prepare(
                "task-mysql-it-" + UUID.randomUUID(),
                session.session().sessionId(),
                9101L,
                new AgentTaskRequest("verify persistence"),
                executionConfig
        );
        AgentTaskRunStore.CreateResult created = taskRunStore.createTaskWithInitialRun(create);

        assertTrue(created.created());
        assertEquals(AgentTask.Status.ACTIVE, created.task().status());
        assertEquals("verify persistence", created.task().request().message());
        assertEquals(AgentRun.Status.QUEUED, created.initialRun().status());
        assertEquals(executionConfig, created.initialRun().executionConfig());
        assertEquals(
                executionConfig,
                taskRunStore.findRun(create.initialRunId()).orElseThrow().executionConfig()
        );
        assertEquals(
                "verify persistence",
                jdbcTemplate.queryForObject(
                        "SELECT JSON_UNQUOTE(JSON_EXTRACT(request_json, '$.message')) "
                                + "FROM agent_task WHERE task_id = ?",
                        String.class,
                        create.taskId()
                )
        );
        assertEquals(1L, countOutbox(create.initialRunId()));

        AgentTaskRunStore.ClaimResult claimed = taskRunStore.claimRun(
                new AgentTaskRunStore.ClaimCommand(
                        "run:claimed:" + create.initialRunId() + ":1",
                        create.sessionId(),
                        create.taskId(),
                        create.initialRunId(),
                        "worker-a",
                        30
                )
        );
        assertEquals(AgentTaskRunStore.ClaimDisposition.CLAIMED, claimed.disposition());
        assertEquals(1L, claimed.run().currentFencingToken());

        long stepsBeforeHeartbeat = countSteps(create.sessionId());
        assertEquals(
                AgentTaskRunStore.HeartbeatResult.EXTENDED,
                taskRunStore.heartbeat(new AgentTaskRunStore.HeartbeatCommand(
                        create.initialRunId(),
                        "worker-a",
                        1L,
                        30
                ))
        );
        assertEquals(stepsBeforeHeartbeat, countSteps(create.sessionId()));

        jdbcTemplate.update(
                "UPDATE agent_run SET lease_until = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) "
                        + "WHERE run_id = ?",
                create.initialRunId()
        );
        AgentTaskRunStore.LeaseExpiryResult expired = taskRunStore.recordLeaseExpired(
                new AgentTaskRunStore.LeaseExpiryCommand(
                        "run:lease-expired:" + create.initialRunId() + ":1",
                        create.sessionId(),
                        create.taskId(),
                        create.initialRunId(),
                        1L
                )
        );
        assertTrue(expired.recorded());
        assertEquals(2L, countOutbox(create.initialRunId()));

        AgentTaskRunStore.TakeoverResult takeover = taskRunStore.takeoverRun(
                new AgentTaskRunStore.TakeoverCommand(
                        "run:taken-over:" + create.initialRunId() + ":2",
                        create.sessionId(),
                        create.taskId(),
                        create.initialRunId(),
                        "worker-b",
                        1L,
                        30
                )
        );
        assertEquals(AgentTaskRunStore.TakeoverDisposition.TAKEN_OVER, takeover.disposition());
        assertEquals(2L, takeover.run().currentFencingToken());

        AgentTaskRunStore.TerminalResult terminal = taskRunStore.terminateRun(
                new AgentTaskRunStore.TerminalCommand(
                        "run:failed:" + create.initialRunId(),
                        "task:run-failed:" + create.taskId() + ":" + create.initialRunId(),
                        create.sessionId(),
                        create.taskId(),
                        create.initialRunId(),
                        "worker-b",
                        2L,
                        AgentTaskRunStore.TerminalOutcome.FAILED,
                        "MYSQL_IT_FAILURE"
                )
        );

        assertEquals(AgentRun.Status.FAILED, terminal.run().status());
        assertEquals(AgentTask.Status.ACTIVE, terminal.task().status());
        assertNull(terminal.task().currentRunId());
        assertEquals(10L, countSteps(create.sessionId()));
        assertEquals(5L, terminal.run().lastRunStepSequence());
        assertEquals(
                3L,
                jdbcTemplate.queryForObject(
                        "SELECT last_accepted_fencing_token FROM agent_workspace WHERE session_id = ?",
                        Long.class,
                        create.sessionId()
                )
        );
        assertNull(jdbcTemplate.queryForObject(
                "SELECT writer_run_id FROM agent_workspace WHERE session_id = ?",
                String.class,
                create.sessionId()
        ));
    }

    private long countSteps(String sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_step WHERE session_id = ?",
                Long.class,
                sessionId
        );
    }

    private long countOutbox(String runId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_outbox WHERE aggregate_id = ?",
                Long.class,
                runId
        );
    }

    private long insertRepository(long ownerId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO repository (name, owner_id, is_private) VALUES (?, ?, 1)",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, "task-run-it-" + UUID.randomUUID());
            statement.setLong(2, ownerId);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("MySQL did not return the repository id");
        }
        return key.longValue();
    }
}
