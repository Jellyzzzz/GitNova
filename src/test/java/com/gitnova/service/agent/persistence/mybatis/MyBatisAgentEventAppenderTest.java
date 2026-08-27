package com.gitnova.service.agent.persistence.mybatis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.entity.agent.AgentSessionEntity;
import com.gitnova.entity.agent.AgentStepEntity;
import com.gitnova.mapper.agent.AgentSessionMapper;
import com.gitnova.mapper.agent.AgentStepMapper;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.persistence.AgentStepType;
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
                IllegalStateException.class,
                () -> appender().append(command(payload(2, 1)))
        );
    }

    private MyBatisAgentEventAppender appender() {
        return new MyBatisAgentEventAppender(
                sessionMapper,
                stepMapper,
                new ObjectMapper()
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

    private AgentSessionEntity session(long sequence) {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId("session-1");
        session.setLastSessionSequence(sequence);
        return session;
    }
}
