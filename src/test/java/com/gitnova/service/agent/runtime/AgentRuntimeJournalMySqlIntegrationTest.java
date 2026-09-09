package com.gitnova.service.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.dto.ToolCall;
import com.gitnova.service.agent.AgentTestExecutionConfigs;
import com.gitnova.service.agent.completion.AgentCompletionDraft;
import com.gitnova.service.agent.dispatch.OutboxPublisher;
import com.gitnova.service.agent.execution.*;
import com.gitnova.service.agent.model.*;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.workspace.*;
import com.gitnova.service.session.*;
import com.gitnova.storage.RepoKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Real commits (no enclosing test transaction), real production Runtime/Journal/terminal wiring. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/gitnova_journal_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@Tag("mysql-it")
class AgentRuntimeJournalMySqlIntegrationTest {
    @Autowired AgentSessionStore sessions;
    @Autowired AgentTaskRunStore tasks;
    @Autowired DefaultDurableRunExecutor executor;
    @Autowired ToolRegistry tools;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockBean ModelGateway model;
    @MockBean WorkspaceGateway workspace;
    @MockBean OutboxPublisher outboxPublisher;
    @MockBean AgentRunRecoveryScanner recoveryScanner;
    private Long repoId;
    private String sessionId;
    private String runId;

    @Test
    void productionExecutorCommitsObservationsBeforeNextModelCallAndRetainsFinalOutcome() throws Exception {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO repository(name, owner_id, is_private) VALUES (?, 7301, 1)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "runtime-journal-it-" + UUID.randomUUID());
            return statement;
        }, keys);
        repoId = keys.getKey().longValue();
        var createSession = CreateSessionCommand.prepare(UUID.randomUUID().toString(), 7301L,
                RepoKey.of(7301L, repoId), SnapshotScope.of("a".repeat(40)));
        var created = sessions.createProvisioning(createSession).session();
        sessionId = created.sessionId();
        sessions.activate(new AgentSessionStore.WorkspaceActivation("workspace:" + created.workspaceId(),
                sessionId, "journal-it", "workspace://" + created.workspaceId(), null, "b".repeat(64)));
        var command = CreateTaskCommand.prepare(UUID.randomUUID().toString(), sessionId, 7301L,
                new AgentTaskRequest("Explain the current empty workspace without modifying files"),
                AgentTestExecutionConfigs.forRegistry(tools, AgentTestExecutionConfigs.defaultPolicy()));
        var task = tasks.createTaskWithInitialRun(command);
        runId = task.initialRun().runId();
        var claim = tasks.claimRun(new AgentTaskRunStore.ClaimCommand("claim:" + runId, sessionId,
                task.task().taskId(), runId, "journal-it-worker", 120));
        when(workspace.refreshWorkspace(any())).thenReturn(new WorkspaceGateway.WorkspaceRefresh(0, 0, false));
        when(workspace.getWorkspaceDiff(any())).thenReturn(new WorkspaceGateway.WorkspaceDiff(0, List.of(), 0, 0, 0, false, ""));
        AtomicInteger calls = new AtomicInteger();
        when(model.complete(any())).thenAnswer(invocation -> {
            int call = calls.incrementAndGet();
            // JdbcTemplate opens a different connection: these rows must already be committed.
            assertEquals(call, count("MODEL_CALL_STARTED"));
            if (call == 1) return new ModelResponse("text-only", "Ready to finish", List.of(),
                    ModelUsage.unknown(), ModelFinishReason.STOP);
            assertEquals(1, count("MODEL_RESPONSE"));
            assertEquals(1, count("HARNESS_FEEDBACK"));
            var draft = new AgentCompletionDraft(0, "Durably retained final summary", List.of(), List.of(), List.of(), List.of(), List.of());
            return new ModelResponse("finished", "", List.of(new ToolCall("finish-call", "finishTask", mapper.valueToTree(draft))),
                    new ModelUsage(100, 10, 110), ModelFinishReason.TOOL_CALLS);
        });

        executor.execute(runId, "journal-it-worker", claim.run().currentFencingToken());

        assertEquals(AgentRun.Status.COMPLETED, tasks.findRun(runId).orElseThrow().status());
        assertEquals(2, count("MODEL_RESPONSE"));
        assertEquals(1, count("TOOL_RESULT"));
        assertEquals(1, count("COMPLETION_DECISION"));
        String payload = jdbc.queryForObject("SELECT payload_json FROM agent_step WHERE run_id=? AND step_type='COMPLETION_DECISION'",
                String.class, runId);
        assertEquals("Durably retained final summary", mapper.readTree(payload).path("decision").path("outcome").path("draft").path("summary").asText());
        List<Long> sequences = jdbc.queryForList("SELECT run_step_sequence FROM agent_step WHERE run_id=? ORDER BY run_step_sequence", Long.class, runId);
        for (int i = 0; i < sequences.size(); i++) assertEquals(i + 1L, sequences.get(i));
        System.out.println("Journal MySQL: " + jdbc.queryForList(
                "SELECT step_type FROM agent_step WHERE run_id=? ORDER BY run_step_sequence", String.class, runId));
    }

    private int count(String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM agent_step WHERE run_id=? AND step_type=?", Integer.class, runId, type);
    }

    @AfterEach
    void removeOnlyThisTestFixture() {
        if (sessionId != null) {
            jdbc.update("DELETE FROM agent_outbox WHERE aggregate_id=?", runId);
            jdbc.update("DELETE FROM agent_step WHERE session_id=?", sessionId);
            jdbc.update("UPDATE agent_workspace SET writer_run_id=NULL WHERE session_id=?", sessionId);
            jdbc.update("UPDATE agent_task SET current_run_id=NULL WHERE session_id=?", sessionId);
            jdbc.update("DELETE FROM agent_run WHERE session_id=?", sessionId);
            jdbc.update("DELETE FROM agent_task WHERE session_id=?", sessionId);
            jdbc.update("DELETE FROM agent_workspace WHERE session_id=?", sessionId);
            jdbc.update("DELETE FROM agent_session WHERE session_id=?", sessionId);
        }
        if (repoId != null) jdbc.update("DELETE FROM repository WHERE id=?", repoId);
    }
}
