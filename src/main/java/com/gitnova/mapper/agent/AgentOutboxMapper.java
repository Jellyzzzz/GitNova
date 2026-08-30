package com.gitnova.mapper.agent;

import com.gitnova.entity.agent.AgentOutboxEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

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

    @Select("""
            SELECT *
            FROM agent_outbox
            WHERE status = 'PENDING'
              AND aggregate_type = 'RUN'
              AND event_type = 'RUN_DISPATCH_REQUESTED'
              AND available_at <= UTC_TIMESTAMP(6)
            ORDER BY outbox_id
            LIMIT #{limit}
            """)
    List<AgentOutboxEntity> findPublishable(@Param("limit") int limit);

    @Update("""
            UPDATE agent_outbox
            SET status = 'PUBLISHED',
                published_at = UTC_TIMESTAMP(6),
                updated_at = UTC_TIMESTAMP(6)
            WHERE outbox_id = #{outboxId}
              AND status = 'PENDING'
            """)
    int markPublished(@Param("outboxId") long outboxId);

    @Update("""
            UPDATE agent_outbox
            SET attempt_count = attempt_count + 1,
                available_at = #{nextAvailableAt},
                updated_at = UTC_TIMESTAMP(6)
            WHERE outbox_id = #{outboxId}
              AND status = 'PENDING'
            """)
    int recordFailure(
            @Param("outboxId") long outboxId,
            @Param("nextAvailableAt") LocalDateTime nextAvailableAt
    );
}
