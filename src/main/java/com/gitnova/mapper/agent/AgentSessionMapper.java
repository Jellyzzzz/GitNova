package com.gitnova.mapper.agent;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gitnova.entity.agent.AgentSessionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentSessionMapper extends BaseMapper<AgentSessionEntity> {
    @Insert("""
            INSERT INTO agent_session (
                session_id,
                creation_idempotency_key,
                created_by_actor_id,
                repo_id,
                repo_key,
                status,
                last_session_sequence,
                version,
                created_at,
                updated_at,
                closed_at
            ) VALUES (
                #{sessionId},
                #{creationIdempotencyKey},
                #{createdByActorId},
                #{repoId},
                #{repoKey},
                #{status},
                #{lastSessionSequence},
                #{version},
                #{createdAt},
                #{updatedAt},
                #{closedAt}
            )
            ON DUPLICATE KEY UPDATE session_id = session_id
            """)
    int claimCreationIdentity(AgentSessionEntity entity);

    @Select("""
            SELECT * FROM agent_session
            WHERE session_id = #{sessionId}
            FOR UPDATE
            """)
    AgentSessionEntity selectForUpdate(@Param("sessionId") String sessionId);

    @Select("""
            SELECT * FROM agent_session
            WHERE creation_idempotency_key = #{creationIdempotencyKey}
            """)
    AgentSessionEntity selectByCreationIdempotencyKey(
            @Param("creationIdempotencyKey") String creationIdempotencyKey
    );

    @Select("""
            SELECT * FROM agent_session
            WHERE creation_idempotency_key = #{creationIdempotencyKey}
            FOR UPDATE
            """)
    AgentSessionEntity selectForUpdateByCreationIdempotencyKey(
            @Param("creationIdempotencyKey") String creationIdempotencyKey
    );

    @Update("""
            UPDATE agent_session
            SET last_session_sequence = #{nextSequence},
                version = version + 1,
                updated_at = UTC_TIMESTAMP(6)
            WHERE session_id = #{sessionId}
              AND last_session_sequence = #{expectedSequence}
            """)
    int advanceSequence(
            @Param("sessionId") String sessionId,
            @Param("expectedSequence") long expectedSequence,
            @Param("nextSequence") long nextSequence
    );

    @Update("""
            UPDATE agent_session
            SET status = 'ACTIVE',
                version = version + 1,
                updated_at = UTC_TIMESTAMP(6)
            WHERE session_id = #{sessionId}
              AND status = 'PROVISIONING'
            """)
    int activate(@Param("sessionId") String sessionId);

    @Update("""
            UPDATE agent_session
            SET status = 'FAILED',
                version = version + 1,
                updated_at = UTC_TIMESTAMP(6)
            WHERE session_id = #{sessionId}
              AND status = 'PROVISIONING'
            """)
    int failProvisioning(@Param("sessionId") String sessionId);
}
