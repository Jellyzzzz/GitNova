package com.gitnova.mapper.agent;

import com.gitnova.entity.agent.AgentOutboxEntity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@Tag("mysql-it")
@Transactional
class AgentOutboxMapperMySqlIntegrationTest {

    @Autowired
    AgentOutboxMapper outboxMapper;

    @Test
    void shouldSelectOnlyDueRunDispatchRowsAndPersistPublishAttempts() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        AgentOutboxEntity first = insert("RUN", "RUN_DISPATCH_REQUESTED", now.minusSeconds(2));
        AgentOutboxEntity second = insert("RUN", "RUN_DISPATCH_REQUESTED", now.minusSeconds(1));
        insert("SESSION", "RUN_DISPATCH_REQUESTED", now.minusSeconds(1));
        insert("RUN", "RUN_LEASE_EXPIRED", now.minusSeconds(1));
        insert("RUN", "RUN_DISPATCH_REQUESTED", now.plusMinutes(1));

        assertEquals(
                List.of(first.getOutboxId(), second.getOutboxId()),
                outboxMapper.findPublishable(10).stream()
                        .map(AgentOutboxEntity::getOutboxId)
                        .toList()
        );
        assertEquals(
                List.of(first.getOutboxId()),
                outboxMapper.findPublishable(1).stream()
                        .map(AgentOutboxEntity::getOutboxId)
                        .toList()
        );

        LocalDateTime nextAvailableAt = now.plusSeconds(30);
        assertEquals(1, outboxMapper.recordFailure(first.getOutboxId(), nextAvailableAt));
        AgentOutboxEntity failed = outboxMapper.selectByEventId(first.getEventId());
        assertEquals(1, failed.getAttemptCount());
        assertEquals(nextAvailableAt, failed.getAvailableAt());
        assertEquals(
                List.of(second.getOutboxId()),
                outboxMapper.findPublishable(10).stream()
                        .map(AgentOutboxEntity::getOutboxId)
                        .toList()
        );

        assertEquals(1, outboxMapper.markPublished(first.getOutboxId()));
        AgentOutboxEntity published = outboxMapper.selectByEventId(first.getEventId());
        assertEquals("PUBLISHED", published.getStatus());
        assertNotNull(published.getPublishedAt());
        assertEquals(0, outboxMapper.markPublished(first.getOutboxId()));
        assertEquals(0, outboxMapper.recordFailure(first.getOutboxId(), now.plusMinutes(1)));
    }

    private AgentOutboxEntity insert(
            String aggregateType,
            String eventType,
            LocalDateTime availableAt
    ) {
        String id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        AgentOutboxEntity entity = new AgentOutboxEntity();
        entity.setEventId("test:" + id);
        entity.setEventDigest("a".repeat(64));
        entity.setAggregateType(aggregateType);
        entity.setAggregateId(id);
        entity.setEventType(eventType);
        entity.setPayloadJson("{\"runId\":\"" + id + "\"}");
        entity.setPayloadDigest("b".repeat(64));
        entity.setStatus("PENDING");
        entity.setAttemptCount(0);
        entity.setAvailableAt(availableAt);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        assertEquals(1, outboxMapper.insert(entity));
        assertNotNull(entity.getOutboxId());
        return entity;
    }
}
