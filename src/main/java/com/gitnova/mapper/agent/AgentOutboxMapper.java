package com.gitnova.mapper.agent;

import com.gitnova.entity.agent.AgentOutboxEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentOutboxMapper {
    @Insert("""
            INSERT INTO agent_outbox (
                event_id, event_digest,
                aggregate_type, aggregate_id, event_type,
                payload_json, payload_digest,
                status, attempt_count,
                available_at, published_at,
                created_at, updated_at
            ) VALUES (
                #{eventId}, #{eventDigest},
                #{aggregateType}, #{aggregateId}, #{eventType},
                CAST(#{payloadJson} AS JSON), #{payloadDigest},
                #{status}, #{attemptCount},
                #{availableAt}, #{publishedAt},
                #{createdAt}, #{updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "outboxId", keyColumn = "outbox_id")
    int insert(AgentOutboxEntity entity);

    @Select("""
            SELECT * FROM agent_outbox
            WHERE event_id = #{eventId}
            FOR UPDATE
            """)
    AgentOutboxEntity selectByEventId(@Param("eventId") String eventId);
}
