package com.gitnova.service.agent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.AgentTestExecutionConfigs;
import com.gitnova.service.agent.context.ContextBudget;
import com.gitnova.service.agent.context.ObservationPolicy;
import com.gitnova.service.agent.execution.AgentTaskRunStore;
import com.gitnova.service.agent.execution.CreateTaskCommand;
import com.gitnova.service.agent.runtime.ToolSetSnap;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolSetSnapFactory;
import com.gitnova.service.agent.tools.FinishTaskTool;
import com.gitnova.service.session.AgentSession;
import com.gitnova.service.session.AgentSessionService;
import com.gitnova.storage.RepoKey;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskServiceTest {
    @Test
    void newTaskFreezesOnlyNonTerminalTools() {
        AgentSessionService sessions = mock(AgentSessionService.class);
        AgentTaskRunStore runs = mock(AgentTaskRunStore.class);
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolSetSnapFactory snapshots = mock(ToolSetSnapFactory.class);
        AgentSession session = mock(AgentSession.class);
        RepoKey repo = RepoKey.of(7L, 42L);
        String sessionId = "10000000-0000-0000-0000-000000000001";
        ToolDefinition finish = new FinishTaskTool(new ObjectMapper()).definition();
        ToolDefinition read = new ToolDefinition("readFile", "Read", new ObjectMapper().createObjectNode());
        when(sessions.require(sessionId)).thenReturn(session);
        when(session.acceptsNewTasks()).thenReturn(true);
        when(session.repoKey()).thenReturn(repo);
        when(session.createdByActorId()).thenReturn(7L);
        when(registry.definitions(any())).thenReturn(List.of(finish, read));
        when(snapshots.create(any())).thenReturn(new ToolSetSnap(1, List.of("readFile"), "a".repeat(64)));
        AgentTaskService service = new AgentTaskService(sessions, runs, AgentTestExecutionConfigs.defaultPolicy(),
                registry, snapshots, mock(ObservationPolicy.class), mock(ContextBudget.class));

        service.create(repo, sessionId, 7L, "Inspect the code", "request-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ToolDefinition>> visible = ArgumentCaptor.forClass(List.class);
        verify(snapshots).create(visible.capture());
        assertEquals(List.of("readFile"), visible.getValue().stream().map(ToolDefinition::name).toList());
        ArgumentCaptor<CreateTaskCommand> created = ArgumentCaptor.forClass(CreateTaskCommand.class);
        verify(runs).createTaskWithInitialRun(created.capture());
        assertEquals(List.of("readFile"), created.getValue().executionConfig().toolSet().enabledDefinitionNames());
    }
}
