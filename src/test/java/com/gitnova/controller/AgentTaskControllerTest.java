package com.gitnova.controller;

import com.gitnova.common.UserContext;
import com.gitnova.entity.Repository;
import com.gitnova.service.RepositoryAccessService;
import com.gitnova.service.agent.execution.AgentTask;
import com.gitnova.service.agent.runtime.AgentAnswer;
import com.gitnova.service.agent.task.AgentTaskService;
import com.gitnova.storage.RepoKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentTaskControllerTest {
    @AfterEach
    void clearActor() {
        UserContext.clear();
    }

    @Test
    void completedTaskExposesCommittedFinalTextAsAnswerContent() throws Exception {
        RepositoryAccessService access = mock(RepositoryAccessService.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        Repository repo = mock(Repository.class);
        AgentTask task = mock(AgentTask.class);
        UserContext.setUserId(7L);
        when(access.requireReadAccess(42L, 7L)).thenReturn(repo);
        when(repo.getOwnerId()).thenReturn(7L);
        when(repo.getId()).thenReturn(42L);
        when(task.taskId()).thenReturn("task-1");
        when(task.sessionId()).thenReturn("session-1");
        when(task.status()).thenReturn(AgentTask.Status.COMPLETED);
        when(task.terminalReason()).thenReturn("ANSWER_DELIVERED");
        when(tasks.find(RepoKey.of(7L, 42L), "session-1", 7L, "task-1"))
                .thenReturn(new AgentTaskService.TaskView(task,
                        Optional.of(new AgentAnswer("The change is ready; tests were not run.", "call-1"))));

        MockMvcBuilders.standaloneSetup(new AgentTaskController(access, tasks)).build()
                .perform(get("/api/repos/42/agent/sessions/session-1/tasks/task-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.answer.content").value("The change is ready; tests were not run."))
                .andExpect(jsonPath("$.data.answer.modelCallId").doesNotExist());

        verify(tasks).find(RepoKey.of(7L, 42L), "session-1", 7L, "task-1");
    }
}
