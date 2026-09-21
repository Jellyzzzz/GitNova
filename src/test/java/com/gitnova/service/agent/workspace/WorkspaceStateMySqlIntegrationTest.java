package com.gitnova.service.agent.workspace;

import com.gitnova.entity.agent.AgentStepEntity;
import com.gitnova.mapper.agent.AgentStepMapper;
import com.gitnova.mapper.agent.AgentWorkspaceMapper;
import com.gitnova.service.agent.AgentTestExecutionConfigs;
import com.gitnova.service.agent.dispatch.OutboxPublisher;
import com.gitnova.service.agent.execution.*;
import com.gitnova.service.agent.model.ModelGateway;
import com.gitnova.service.session.AgentSessionStore;
import com.gitnova.service.session.CreateSessionCommand;
import com.gitnova.storage.RepoKey;
import com.gitnova.storage.config.WorkspaceStorageProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.gitnova.service.agent.runtime.AgentCapability.CODE_READ;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/** Real MySQL commits and real file mutations; no LLM, MQ dispatch or enclosing test transaction. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/gitnova_workspace_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "gitnova.agent.runtime.context.context-window-tokens=128000",
        "gitnova.agent.runtime.context.safety-margin-tokens=1024"
})
@Tag("mysql-it")
class WorkspaceStateMySqlIntegrationTest {
    @Autowired AgentSessionStore sessions;
    @Autowired AgentTaskRunStore tasks;
    @Autowired AgentWorkspaceMapper workspaces;
    @Autowired JdbcTemplate jdbc;
    @SpyBean AgentStepMapper steps;
    @MockBean ModelGateway model;
    @MockBean OutboxPublisher publisher;
    @MockBean AgentRunRecoveryScanner scanner;
    @TempDir Path directory;
    Long repoId;
    String sessionId;
    WorkspaceId workspaceId;
    WorkspaceExecutionPermit permit;
    Path root;
    LocalWorkspaceRegistry registry;

    @BeforeEach
    void createClaimedWorkspace() throws Exception {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO repository(name, owner_id, is_private) VALUES (?, 8401, 1)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "workspace-state-it-" + UUID.randomUUID());
            return statement;
        }, key);
        repoId = key.getKey().longValue();
        var session = sessions.createProvisioning(CreateSessionCommand.prepare(UUID.randomUUID().toString(),
                8401L, RepoKey.of(8401L, repoId), SnapshotScope.of("a".repeat(40)))).session();
        sessionId = session.sessionId();
        workspaceId = session.workspaceId();
        root = Files.createDirectories(directory.resolve(workspaceId.toString()));
        Files.writeString(root.resolve("README.md"), "initial\n");
        sessions.activate(new AgentSessionStore.WorkspaceActivation("activate:" + workspaceId,
                sessionId, "local-filesystem", root.toString(), null, WorkspaceTreeFingerprint.capture(root)));
        var task = tasks.createTaskWithInitialRun(CreateTaskCommand.prepare(UUID.randomUUID().toString(),
                sessionId, 8401L, new AgentTaskRequest("workspace state test"),
                AgentTestExecutionConfigs.minimal(Set.of(CODE_READ))));
        var claimed = tasks.claimRun(new AgentTaskRunStore.ClaimCommand("claim:" + workspaceId,
                sessionId, task.task().taskId(), task.initialRun().runId(), "workspace-state-it", 120));
        permit = new WorkspaceExecutionPermit(claimed.run().runId(), workspaceId,
                claimed.run().currentFencingToken());
        registry = new LocalWorkspaceRegistry(workspaces, new WorkspaceStorageProperties(directory), sessions);
    }

    @AfterEach
    void removeOnlyThisTestData() {
        reset(steps);
        if (sessionId != null) {
            jdbc.update("DELETE FROM agent_outbox WHERE session_id = ?", sessionId);
            jdbc.update("DELETE FROM agent_step WHERE session_id = ?", sessionId);
            jdbc.update("UPDATE agent_task SET current_run_id = NULL WHERE session_id = ?", sessionId);
            jdbc.update("DELETE FROM agent_run WHERE session_id = ?", sessionId);
            jdbc.update("DELETE FROM agent_task WHERE session_id = ?", sessionId);
            jdbc.update("DELETE FROM agent_workspace WHERE session_id = ?", sessionId);
            jdbc.update("DELETE FROM agent_session WHERE session_id = ?", sessionId);
        }
        if (repoId != null) jdbc.update("DELETE FROM repository WHERE id = ?", repoId);
    }

    @Test
    void patchPartialCommandAndExternalEditsSurviveColdRegistryWithoutResettingGeneration() throws Exception {
        var gateway = new LocalWorkspaceGateway(registry, null, (workspace, cwd, argv, timeout) -> {
            Files.writeString(workspace.resolve("command.txt"), "command side effect\n");
            return new WorkspaceCommandExecutor.ProcessResult(true, 1, 10, "failed test", "", false, false);
        });
        assertEquals(0, gateway.refreshWorkspace(workspaceId).generationAfter());
        var created = gateway.applyPatch(workspaceId, permit, new WorkspaceMutationCommand(0,
                List.of(PatchOperation.create(0, "first.txt", "first\n"))));
        assertEquals(1, created.generationAfter());
        assertEquals(1, workspaces.selectById(workspaceId.toString()).getGeneration());

        var partial = gateway.applyPatch(workspaceId, permit, new WorkspaceMutationCommand(1, List.of(
                PatchOperation.create(0, "second.txt", "second\n"),
                PatchOperation.delete(1, "missing.txt"))));
        assertEquals(PatchBatchStatus.PARTIAL_SUCCESS, partial.status());
        assertEquals(2, workspaces.selectById(workspaceId.toString()).getGeneration());
        var rejected = gateway.applyPatch(workspaceId, permit, new WorkspaceMutationCommand(2,
                List.of(PatchOperation.create(0, "first.txt", "duplicate"))));
        assertEquals(PatchBatchStatus.FAILED, rejected.status());
        assertEquals(2, workspaces.selectById(workspaceId.toString()).getGeneration());

        var command = gateway.runCommand(workspaceId, permit,
                new WorkspaceGateway.CommandRequest(2, List.of("test"), ".", 1, "validate"));
        assertEquals(WorkspaceGateway.CommandStatus.TIMED_OUT, command.status());
        assertEquals(3, command.generationAfter());
        assertEquals(3, workspaces.selectById(workspaceId.toString()).getGeneration());
        Files.writeString(root.resolve("user.txt"), "user edit\n");
        assertEquals(4, gateway.refreshWorkspace(workspaceId).generationAfter());
        assertFalse(gateway.refreshWorkspace(workspaceId).changed());

        // Discard all in-memory state, as a new JVM would, keeping only files and MySQL.
        var restarted = new LocalWorkspaceGateway(new LocalWorkspaceRegistry(
                workspaces, new WorkspaceStorageProperties(directory), sessions));
        assertEquals(new WorkspaceGateway.WorkspaceRefresh(4, 4, false), restarted.refreshWorkspace(workspaceId));
        assertEquals(WorkspaceTreeFingerprint.capture(root),
                workspaces.selectById(workspaceId.toString()).getContentFingerprint());
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM agent_step WHERE session_id = ? "
                + "AND step_type = 'WORKSPACE_STATE_OBSERVED'", Integer.class, sessionId));
        Files.writeString(root.resolve("after-restart.txt"), "external\n");
        assertEquals(new WorkspaceGateway.WorkspaceRefresh(4, 5, true), restarted.refreshWorkspace(workspaceId));
        assertEquals(5, workspaces.selectById(workspaceId.toString()).getGeneration());
    }

    @Test
    void unverifiedCommandStateRetainsGenerationAndReestablishesFingerprintAfterRestart() throws Exception {
        var gateway = new LocalWorkspaceGateway(registry, null, (workspace, cwd, argv, timeout) -> {
            Files.writeString(workspace.resolve("side-effect.txt"), "effect\n");
            Files.writeString(WorkspaceCommandExecutor.pendingCommandFile(workspace), "unreconciled");
            throw new IllegalStateException("simulated uncertain executor failure");
        });
        var result = gateway.runCommand(workspaceId, permit,
                new WorkspaceGateway.CommandRequest(0, List.of("test"), ".", 1, "validate"));
        assertEquals("WORKSPACE_STATE_UNVERIFIED", result.errorCode());
        assertEquals(1, workspaces.selectById(workspaceId.toString()).getGeneration());
        assertNull(workspaces.selectById(workspaceId.toString()).getContentFingerprint());
        // Simulated operator reconciliation: no command is running in this deterministic test.
        Files.delete(WorkspaceCommandExecutor.pendingCommandFile(root));
        var restarted = new LocalWorkspaceGateway(new LocalWorkspaceRegistry(
                workspaces, new WorkspaceStorageProperties(directory), sessions));
        assertEquals(new WorkspaceGateway.WorkspaceRefresh(1, 1, false), restarted.refreshWorkspace(workspaceId));
        assertEquals(WorkspaceTreeFingerprint.capture(root),
                workspaces.selectById(workspaceId.toString()).getContentFingerprint());
    }

    @Test
    void failedStepInsertRollsBackProjectionAndRetryDoesNotLoseConfirmedFiles() throws Exception {
        doThrow(new IllegalStateException("injected Step insert failure")).when(steps)
                .insert(argThat((AgentStepEntity step) -> "WORKSPACE_STATE_OBSERVED".equals(step.getStepType())));
        var gateway = new LocalWorkspaceGateway(registry);
        var failure = assertThrows(AgentExecutionPersistenceException.class,
                () -> gateway.applyPatch(workspaceId, permit, new WorkspaceMutationCommand(0,
                        List.of(PatchOperation.create(0, "confirmed.txt", "keep this\n")))));
        assertEquals(AgentExecutionPersistenceException.Code.PERSISTENCE_FAILURE, failure.code());
        assertEquals("keep this\n", Files.readString(root.resolve("confirmed.txt")));
        assertEquals(0, workspaces.selectById(workspaceId.toString()).getGeneration());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_step WHERE session_id = ? "
                + "AND step_type = 'WORKSPACE_STATE_OBSERVED'", Integer.class, sessionId));
        reset(steps);
        assertEquals(1, gateway.refreshWorkspace(workspaceId).generationAfter());
        assertEquals(1, workspaces.selectById(workspaceId.toString()).getGeneration());
    }

    @Test
    void duplicateObservationIsIdempotentAndStaleCoordinatesOrWriterCannotOverwrite() {
        var before = workspaces.selectById(workspaceId.toString());
        var change = new AgentSessionStore.WorkspaceStateChange(sessionId, workspaceId.toString(), 0,
                0, before.getContentFingerprint(), 1, "b".repeat(64), permit);
        sessions.recordWorkspaceState(change);
        sessions.recordWorkspaceState(change);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_step WHERE session_id = ? "
                + "AND step_type = 'WORKSPACE_STATE_OBSERVED'", Integer.class, sessionId));
        var stale = new AgentSessionStore.WorkspaceStateChange(sessionId, workspaceId.toString(), 0,
                0, before.getContentFingerprint(), 2, "c".repeat(64), permit);
        assertEquals(AgentExecutionPersistenceException.Code.STATE_CONFLICT,
                assertThrows(AgentExecutionPersistenceException.class, () -> sessions.recordWorkspaceState(stale)).code());
        var wrongEpoch = new AgentSessionStore.WorkspaceStateChange(sessionId, workspaceId.toString(), 1,
                1, "b".repeat(64), 2, "c".repeat(64), permit);
        assertThrows(AgentExecutionPersistenceException.class, () -> sessions.recordWorkspaceState(wrongEpoch));
        jdbc.update("UPDATE agent_workspace SET last_accepted_fencing_token = last_accepted_fencing_token + 1 "
                + "WHERE workspace_id = ?", workspaceId.toString());
        assertEquals(AgentExecutionPersistenceException.Code.LEASE_LOST,
                assertThrows(AgentExecutionPersistenceException.class, () -> sessions.recordWorkspaceState(change)).code());
        assertEquals(1, workspaces.selectById(workspaceId.toString()).getGeneration());
    }
}
