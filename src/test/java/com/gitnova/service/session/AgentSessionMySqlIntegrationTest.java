package com.gitnova.service.session;

import com.gitnova.gitobject.GitObjectId;
import com.gitnova.service.agent.workspace.SnapshotScope;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Actual MySQL contract test; excluded from the default unit lane. */
@SpringBootTest
@Tag("mysql-it")
@Transactional
class AgentSessionMySqlIntegrationTest {

    @Autowired
    AgentSessionStore sessionStore;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void shouldPersistIdempotentSessionWorkspaceAndOrderedSteps() {
        long ownerId = 7001L;
        long repoId = insertRepository(ownerId);
        RepoKey repoKey = RepoKey.of(ownerId, repoId);
        String idempotencyKey = "mysql-it-" + UUID.randomUUID();
        CreateSessionCommand firstAttempt = CreateSessionCommand.prepare(
                idempotencyKey,
                9001L,
                repoKey,
                new SnapshotScope(GitObjectId.of("a".repeat(40)))
        );

        AgentSessionStore.CreateResult created = sessionStore.createProvisioning(firstAttempt);
        CreateSessionCommand requestRetry = CreateSessionCommand.prepare(
                idempotencyKey,
                9001L,
                repoKey,
                firstAttempt.source()
        );
        AgentSessionStore.CreateResult existing = sessionStore.createProvisioning(requestRetry);

        assertTrue(created.created());
        assertFalse(existing.created());
        assertEquals(created.session().sessionId(), existing.session().sessionId());
        assertEquals(created.session().workspaceId(), existing.session().workspaceId());
        assertEquals(1L, existing.session().lastSessionSequence());

        AgentSession active = sessionStore.activate(new AgentSessionStore.WorkspaceActivation(
                "workspace:materialized:" + created.session().workspaceId(),
                created.session().sessionId(),
                "mysql-it",
                "workspace://" + created.session().workspaceId(),
                null,
                "b".repeat(64)
        ));

        assertEquals(AgentSession.Status.ACTIVE, active.status());
        assertEquals(2L, active.lastSessionSequence());
        assertEquals(
                2L,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM agent_step WHERE session_id = ?",
                        Long.class,
                        active.sessionId()
                )
        );
        assertEquals(
                "READY",
                jdbcTemplate.queryForObject(
                        "SELECT status FROM agent_workspace WHERE session_id = ?",
                        String.class,
                        active.sessionId()
                )
        );
    }

    private long insertRepository(long ownerId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO repository (name, owner_id, is_private) VALUES (?, ?, 1)",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, "session-it-" + UUID.randomUUID());
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
