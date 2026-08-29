package com.gitnova.mapper.agent;

import com.gitnova.entity.agent.AgentTaskEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AgentTaskMapper {
    @Insert("""
            INSERT INTO agent_task (
                task_id, session_id,
                creation_idempotency_key, created_by_actor_id,
                status, request_json, request_digest,
                current_run_id, last_run_number,
                terminal_reason, version,
                created_at, updated_at, terminal_at
            ) VALUES (
                #{taskId}, #{sessionId},
                #{creationIdempotencyKey}, #{createdByActorId},
                #{status}, CAST(#{requestJson} AS JSON), #{requestDigest},
                #{currentRunId}, #{lastRunNumber},
                #{terminalReason}, #{version},
                #{createdAt}, #{updatedAt}, #{terminalAt}
            )
            ON DUPLICATE KEY UPDATE task_id = task_id
            """)
    int claimCreationIdentity(AgentTaskEntity task);

    @Select("SELECT * FROM agent_task WHERE task_id = #{taskId}")
    AgentTaskEntity selectById(@Param("taskId") String taskId);

    @Select("""
            SELECT * FROM agent_task
            WHERE task_id = #{taskId}
            FOR UPDATE
            """)
    AgentTaskEntity selectForUpdate(@Param("taskId") String taskId);

    @Select("""
            SELECT * FROM agent_task
            WHERE session_id = #{sessionId}
              AND creation_idempotency_key = #{creationIdempotencyKey}
            FOR UPDATE
            """)
    AgentTaskEntity selectForUpdateByCreationIdentity(
            @Param("sessionId") String sessionId,
            @Param("creationIdempotencyKey") String creationIdempotencyKey
    );

    @Update("""
            UPDATE agent_task
            SET status = 'ACTIVE',
                current_run_id = #{runId},
                last_run_number = #{nextRunNumber},
                version = version + 1,
                updated_at = UTC_TIMESTAMP(6)
            WHERE task_id = #{taskId}
              AND session_id = #{sessionId}
              AND current_run_id IS NULL
              AND last_run_number = #{expectedRunNumber}
              AND status IN ('ACTIVE', 'WAITING_USER')
            """)
    int attachRun(
            @Param("taskId") String taskId,
            @Param("sessionId") String sessionId,
            @Param("runId") String runId,
            @Param("expectedRunNumber") long expectedRunNumber,
            @Param("nextRunNumber") long nextRunNumber
    );

    @Update("""
            UPDATE agent_task
            SET status = #{nextStatus},
                current_run_id = NULL,
                terminal_reason = #{terminalReason},
                terminal_at = #{terminalAt},
                version = version + 1,
                updated_at = UTC_TIMESTAMP(6)
            WHERE task_id = #{taskId}
              AND session_id = #{sessionId}
              AND status = 'ACTIVE'
              AND current_run_id = #{runId}
            """)
    int transitionAfterRun(
            @Param("taskId") String taskId,
            @Param("sessionId") String sessionId,
            @Param("runId") String runId,
            @Param("nextStatus") String nextStatus,
            @Param("terminalReason") String terminalReason,
            @Param("terminalAt") LocalDateTime terminalAt
    );
}
