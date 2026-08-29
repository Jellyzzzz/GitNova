package com.gitnova.service.agent.persistence.mybatis;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.entity.agent.AgentOutboxEntity;
import com.gitnova.mapper.agent.AgentOutboxMapper;
import com.gitnova.service.agent.execution.AgentExecutionPersistenceException;
import com.gitnova.service.agent.persistence.AgentOutboxWriter;
import com.gitnova.service.agent.persistence.CanonicalJsonCodec;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/** MySQL transactional Outbox writer; transport publication is a later adapter. */
@Repository
public class MyBatisAgentOutboxWriter implements AgentOutboxWriter {
    private final AgentOutboxMapper outboxMapper;
    private final CanonicalJsonCodec canonicalJson;

    public MyBatisAgentOutboxWriter(
            AgentOutboxMapper outboxMapper,
            CanonicalJsonCodec canonicalJson
    ) {
        this.outboxMapper = Objects.requireNonNull(outboxMapper, "outboxMapper must not be null");
        this.canonicalJson = Objects.requireNonNull(canonicalJson, "canonicalJson must not be null");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public EnqueueResult enqueue(EnqueueCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        CanonicalJsonCodec.EncodedJson payload = canonicalJson.encode(command.payload());
        String eventDigest = eventDigest(command, payload.digest());

        AgentOutboxEntity existing = outboxMapper.selectByEventId(command.eventId());
        if (existing != null) {
            if (!eventDigest.equals(existing.getEventDigest())) {
                throw new AgentExecutionPersistenceException(
                        AgentExecutionPersistenceException.Code.IDEMPOTENCY_KEY_CONFLICT,
                        "Outbox eventId is already bound to different semantics: " + command.eventId()
                );
            }
            return new EnqueueResult(existing.getOutboxId(), true);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        AgentOutboxEntity entity = new AgentOutboxEntity();
        entity.setEventId(command.eventId());
        entity.setEventDigest(eventDigest);
        entity.setAggregateType(command.aggregateType());
        entity.setAggregateId(command.aggregateId());
        entity.setEventType(command.eventType());
        entity.setPayloadJson(payload.json());
        entity.setPayloadDigest(payload.digest());
        entity.setStatus("PENDING");
        entity.setAttemptCount(0);
        entity.setAvailableAt(LocalDateTime.ofInstant(command.availableAt(), ZoneOffset.UTC));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        if (outboxMapper.insert(entity) != 1 || entity.getOutboxId() == null) {
            throw new AgentExecutionPersistenceException(
                    AgentExecutionPersistenceException.Code.PERSISTENCE_FAILURE,
                    "Could not enqueue Agent Outbox event"
            );
        }
        return new EnqueueResult(entity.getOutboxId(), false);
    }

    private String eventDigest(EnqueueCommand command, String payloadDigest) {
        // availableAt is delivery scheduling metadata. The first committed row wins so a
        // later retry of the same semantic event remains idempotent.
        ObjectNode identity = canonicalJson.objectNode();
        identity.put("aggregateId", command.aggregateId());
        identity.put("aggregateType", command.aggregateType());
        identity.put("eventType", command.eventType());
        identity.put("payloadDigest", payloadDigest);
        return canonicalJson.encode(identity).digest();
    }
}
