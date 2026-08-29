package com.gitnova.service.agent.persistence.mybatis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.entity.agent.AgentSessionEntity;
import com.gitnova.entity.agent.AgentStepEntity;
import com.gitnova.entity.agent.AgentTaskEntity;
import com.gitnova.entity.agent.AgentRunEntity;
import com.gitnova.mapper.agent.AgentRunMapper;
import com.gitnova.mapper.agent.AgentSessionMapper;
import com.gitnova.mapper.agent.AgentStepMapper;
import com.gitnova.mapper.agent.AgentTaskMapper;
import com.gitnova.service.agent.execution.AgentExecutionPersistenceException;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.persistence.AgentStepType;
import com.gitnova.service.agent.persistence.CanonicalJsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MyBatisAgentEventAppenderTest {

    @Mock
    AgentSessionMapper sessionMapper;

    @Mock
    AgentTaskMapper taskMapper;

    @Mock
    AgentRunMapper runMapper;

    @Mock
    AgentStepMapper stepMapper;

    @Test
    void shouldAppendCanonicalVersionedStepOnTheSessionTimeline() {
        AgentSessionEntity session = session(7L);
        when(sessionMapper.selectForUpdate("session-1")).thenReturn(session);
        when(stepMapper.selectByEventId("event-1")).thenReturn(null);
        when(sessionMapper.advanceSequence("session-1", 7L, 8L)).thenReturn(1);
        when(stepMapper.insert(any())).thenAnswer(invocation -> {
            AgentStepEntity inserted = invocation.getArgument(0);
            inserted.setStepId(42L);
            return 1;
        });

        AgentEventAppender.AppendResult result = appender().append(command(payload(2, 1)));

        assertEquals(42L, result.stepId());
        assertEquals(8L, result.sessionSequence());
        assertFalse(result.alreadyCommitted());
        ArgumentCaptor<AgentStepEntity> step = ArgumentCaptor.forClass(AgentStepEntity.class);
        verify(stepMapper).insert(step.capture());
        assertEquals("{\"a\":1,\"z\":2}", step.getValue().getPayloadJson());
        assertEquals(64, step.getValue().getPersistedPayloadDigest().length());
        assertEquals(64, step.getValue().getEventDigest().length());
        assertEquals("SESSION_CREATED", step.getValue().getStepType());
        assertEquals(1, step.getValue().getSchemaVersion());
    }

    @Test
    void shouldReturnTheCommittedStepWhenTheSameEventIsRetried() {
        AgentSessionEntity session = session(7L);
        AtomicReference<AgentStepEntity> inserted = new AtomicReference<>();
        when(sessionMapper.selectForUpdate("session-1")).thenReturn(session);
        when(stepMapper.selectByEventId("event-1"))
                .thenReturn(null)
                .thenAnswer(invocation -> inserted.get());
        when(sessionMapper.advanceSequence("session-1", 7L, 8L)).thenReturn(1);
        when(stepMapper.insert(any())).thenAnswer(invocation -> {
            AgentStepEntity step = invocation.getArgument(0);
            step.setStepId(42L);
            inserted.set(step);
            return 1;
        });
        MyBatisAgentEventAppender appender = appender();
        AgentEventAppender.AppendCommand command = command(payload(2, 1));

        AgentEventAppender.AppendResult first = appender.append(command);
        AgentEventAppender.AppendResult retry = appender.append(command);

        assertFalse(first.alreadyCommitted());
        assertTrue(retry.alreadyCommitted());
        assertEquals(first.stepId(), retry.stepId());
        assertEquals(first.sessionSequence(), retry.sessionSequence());
        verify(sessionMapper, times(1)).advanceSequence(eq("session-1"), anyLong(), anyLong());
        verify(stepMapper, times(1)).insert(any());
    }

    @Test
    void shouldRejectAnEventIdReusedWithDifferentSemantics() {
        AgentSessionEntity session = session(7L);
        AgentStepEntity existing = new AgentStepEntity();
        existing.setStepId(42L);
        existing.setSessionSequence(8L);
        existing.setEventDigest("0".repeat(64));
        when(sessionMapper.selectForUpdate("session-1")).thenReturn(session);
        when(stepMapper.selectByEventId("event-1")).thenReturn(existing);

        assertThrows(
                AgentExecutionPersistenceException.class,
                () -> appender().append(command(payload(2, 1)))
        );
    }

    @Test
    void shouldAllocateSessionAndRunSequencesForOneRunStep() {
        AgentSessionEntity session = session(7L);
        AgentTaskEntity task = task();
        AgentRunEntity run = run(3L);
        when(sessionMapper.selectForUpdate("session-1")).thenReturn(session);
        when(taskMapper.selectForUpdate("task-1")).thenReturn(task);
        when(runMapper.selectForUpdate("run-1")).thenReturn(run);
        when(stepMapper.selectByEventId("run-event-1")).thenReturn(null);
        when(sessionMapper.advanceSequence("session-1", 7L, 8L)).thenReturn(1);
        when(runMapper.advanceStepSequence("run-1", 3L, 4L)).thenReturn(1);
        when(stepMapper.insert(any())).thenAnswer(invocation -> {
            AgentStepEntity inserted = invocation.getArgument(0);
            inserted.setStepId(43L);
            return 1;
        });

        AgentEventAppender.AppendResult result = appender().append(runCommand());

        assertEquals(8L, result.sessionSequence());
        assertEquals(4L, result.runStepSequence());
        ArgumentCaptor<AgentStepEntity> step = ArgumentCaptor.forClass(AgentStepEntity.class);
        verify(stepMapper).insert(step.capture());
        assertEquals("task-1", step.getValue().getTaskId());
        assertEquals("run-1", step.getValue().getRunId());
        assertEquals(4L, step.getValue().getRunStepSequence());
    }

    @Test
    void shouldStopBeforeInsertWhenRunSequenceCasIsLost() {
        when(sessionMapper.selectForUpdate("session-1")).thenReturn(session(7L));
        when(taskMapper.selectForUpdate("task-1")).thenReturn(task());
        when(runMapper.selectForUpdate("run-1")).thenReturn(run(3L));
        when(stepMapper.selectByEventId("run-event-1")).thenReturn(null);
        when(sessionMapper.advanceSequence("session-1", 7L, 8L)).thenReturn(1);
        when(runMapper.advanceStepSequence("run-1", 3L, 4L)).thenReturn(0);

        AgentExecutionPersistenceException error = assertThrows(
                AgentExecutionPersistenceException.class,
                () -> appender().append(runCommand())
        );

        assertEquals(AgentExecutionPersistenceException.Code.STATE_CONFLICT, error.code());
        verify(stepMapper, times(0)).insert(any());
    }

    private MyBatisAgentEventAppender appender() {
        return new MyBatisAgentEventAppender(
                sessionMapper,
                taskMapper,
                runMapper,
                stepMapper,
                new CanonicalJsonCodec(new ObjectMapper())
        );
    }

    private AgentEventAppender.AppendCommand command(ObjectNode payload) {
        return AgentEventAppender.AppendCommand.sessionEvent(
                "event-1",
                "session-1",
                AgentStepType.SESSION_CREATED,
                payload,
                0L,
                0L
        );
    }

    private ObjectNode payload(int z, int a) {
        ObjectNode payload = new ObjectMapper().createObjectNode();
        payload.put("z", z);
        payload.put("a", a);
        return payload;
    }

    private AgentEventAppender.AppendCommand runCommand() {
        return new AgentEventAppender.AppendCommand(
                "run-event-1",
                "session-1",
                "task-1",
                "run-1",
                AgentStepType.RUN_CLAIMED,
                1,
                payload(2, 1),
                null,
                "task-1",
                0L,
                0L
        );
    }

    private AgentTaskEntity task() {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setTaskId("task-1");
        task.setSessionId("session-1");
        return task;
    }

    private AgentRunEntity run(long sequence) {
        AgentRunEntity run = new AgentRunEntity();
        run.setRunId("run-1");
        run.setTaskId("task-1");
        run.setSessionId("session-1");
        run.setLastRunStepSequence(sequence);
        return run;
    }

    private AgentSessionEntity session(long sequence) {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId("session-1");
        session.setLastSessionSequence(sequence);
        return session;
    }
}
