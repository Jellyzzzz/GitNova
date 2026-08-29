package com.gitnova.service.agent.persistence.mybatis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.entity.agent.AgentOutboxEntity;
import com.gitnova.mapper.agent.AgentOutboxMapper;
import com.gitnova.service.agent.execution.AgentExecutionPersistenceException;
import com.gitnova.service.agent.persistence.AgentOutboxWriter;
import com.gitnova.service.agent.persistence.CanonicalJsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MyBatisAgentOutboxWriterTest {

    @Mock
    AgentOutboxMapper outboxMapper;

    @Test
    void shouldPersistCanonicalPendingDispatchIntent() {
        when(outboxMapper.selectByEventId("dispatch-1")).thenReturn(null);
        when(outboxMapper.insert(any())).thenAnswer(invocation -> {
            AgentOutboxEntity inserted = invocation.getArgument(0);
            inserted.setOutboxId(11L);
            return 1;
        });

        AgentOutboxWriter.EnqueueResult result = writer().enqueue(command(payload(2, 1)));

        assertEquals(11L, result.outboxId());
        assertFalse(result.alreadyEnqueued());
        ArgumentCaptor<AgentOutboxEntity> row = ArgumentCaptor.forClass(AgentOutboxEntity.class);
        verify(outboxMapper).insert(row.capture());
        assertEquals("PENDING", row.getValue().getStatus());
        assertEquals(0, row.getValue().getAttemptCount());
        assertEquals("{\"a\":1,\"z\":2}", row.getValue().getPayloadJson());
        assertEquals(64, row.getValue().getPayloadDigest().length());
        assertEquals(64, row.getValue().getEventDigest().length());
    }

    @Test
    void shouldReturnExistingRowForSameEventSemantics() {
        AtomicReference<AgentOutboxEntity> inserted = new AtomicReference<>();
        when(outboxMapper.selectByEventId("dispatch-1"))
                .thenReturn(null)
                .thenAnswer(invocation -> inserted.get());
        when(outboxMapper.insert(any())).thenAnswer(invocation -> {
            AgentOutboxEntity row = invocation.getArgument(0);
            row.setOutboxId(11L);
            inserted.set(row);
            return 1;
        });
        MyBatisAgentOutboxWriter writer = writer();

        AgentOutboxWriter.EnqueueResult first = writer.enqueue(command(payload(2, 1)));
        AgentOutboxWriter.EnqueueResult retry = writer.enqueue(command(payload(2, 1)));

        assertFalse(first.alreadyEnqueued());
        assertTrue(retry.alreadyEnqueued());
        assertEquals(first.outboxId(), retry.outboxId());
        verify(outboxMapper, times(1)).insert(any());
    }

    @Test
    void shouldRejectEventIdReusedForDifferentPayload() {
        AgentOutboxEntity existing = new AgentOutboxEntity();
        existing.setOutboxId(11L);
        existing.setEventDigest("0".repeat(64));
        when(outboxMapper.selectByEventId("dispatch-1")).thenReturn(existing);

        AgentExecutionPersistenceException error = assertThrows(
                AgentExecutionPersistenceException.class,
                () -> writer().enqueue(command(payload(2, 1)))
        );

        assertEquals(AgentExecutionPersistenceException.Code.IDEMPOTENCY_KEY_CONFLICT, error.code());
    }

    private MyBatisAgentOutboxWriter writer() {
        return new MyBatisAgentOutboxWriter(
                outboxMapper,
                new CanonicalJsonCodec(new ObjectMapper())
        );
    }

    private AgentOutboxWriter.EnqueueCommand command(ObjectNode payload) {
        return new AgentOutboxWriter.EnqueueCommand(
                "dispatch-1",
                "RUN",
                "run-1",
                "RUN_DISPATCH_REQUESTED",
                payload,
                Instant.parse("2026-08-29T12:00:00Z")
        );
    }

    private ObjectNode payload(int z, int a) {
        ObjectNode payload = new ObjectMapper().createObjectNode();
        payload.put("z", z);
        payload.put("a", a);
        return payload;
    }
}
