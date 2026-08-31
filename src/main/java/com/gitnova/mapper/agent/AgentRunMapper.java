package com.gitnova.mapper.agent;

import com.gitnova.entity.agent.AgentRunEntity;
import com.gitnova.service.agent.execution.AgentRun;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AgentRunMapper {
    @Insert("""
            INSERT INTO agent_run (
                run_id, session_id, task_id,
                run_number, predecessor_run_id, status,
                last_run_step_sequence,
                lease_owner, lease_until, current_fencing_token,
                execution_config_json, execution_config_digest,
                termination_reason, version,
                created_at, claimed_at, last_heartbeat_at,
                finished_at, updated_at
            ) VALUES (
                #{runId}, #{sessionId}, #{taskId},
                #{runNumber}, #{predecessorRunId}, #{status},
                #{lastRunStepSequence},
                #{leaseOwner}, #{leaseUntil}, #{currentFencingToken},
                CAST(#{executionConfigJson} AS JSON), #{executionConfigDigest},
                #{terminationReason}, #{version},
                #{createdAt}, #{claimedAt}, #{lastHeartbeatAt},
                #{finishedAt}, #{updatedAt}
            )
            """)
    int insert(AgentRunEntity run);

    @Select("SELECT * FROM agent_run WHERE run_id = #{runId}")
    AgentRunEntity selectById(@Param("runId") String runId);

    @Select("""
            SELECT * FROM agent_run
            WHERE run_id = #{runId}
            FOR UPDATE
            """)
    AgentRunEntity selectForUpdate(@Param("runId") String runId);

    @Select("""
            SELECT * FROM agent_run
            WHERE task_id = #{taskId}
              AND run_number = #{runNumber}
            """)
    AgentRunEntity selectByTaskAndRunNumber(
            @Param("taskId") String taskId,
            @Param("runNumber") long runNumber
    );

    @Select("""
            SELECT * FROM agent_run
            WHERE run_id = #{runId}
              AND status = 'RUNNING'
              AND current_fencing_token = #{expectedFencingToken}
              AND lease_until <= UTC_TIMESTAMP(6)
            FOR UPDATE
            """)
    AgentRunEntity selectExpiredForUpdate(
            @Param("runId") String runId,
            @Param("expectedFencingToken") long expectedFencingToken
    );

    @Select("""
            SELECT COUNT(*) FROM agent_run
            WHERE run_id = #{runId}
              AND status = 'RUNNING'
              AND lease_owner = #{workerId}
              AND current_fencing_token = #{fencingToken}
              AND lease_until > UTC_TIMESTAMP(6)
            """)
    int hasValidLease(
            @Param("runId") String runId,
            @Param("workerId") String workerId,
            @Param("fencingToken") long fencingToken
    );
    @Select("""
            SELECT * FROM agent_run
            WHERE status='RUNNIG'
            AND   lease_until<=UTC_TIMESTAMP(6)
            ORDER BY lease_until
            LIMIT #{limit}
            """)
    List<AgentRunEntity>selectExpiredRuns(@Param("limit") int limit);

    @Update("""
            UPDATE agent_run
            SET last_run_step_sequence = #{nextSequence},
                version = version + 1,
                updated_at = UTC_TIMESTAMP(6)
            WHERE run_id = #{runId}
              AND last_run_step_sequence = #{expectedSequence}
            """)
    int advanceStepSequence(
            @Param("runId") String runId,
            @Param("expectedSequence") long expectedSequence,
            @Param("nextSequence") long nextSequence
    );

    @Update("""
            UPDATE agent_run
            SET status = 'RUNNING',
                lease_owner = #{workerId},
                lease_until = TIMESTAMPADD(SECOND, #{leaseSeconds}, UTC_TIMESTAMP(6)),
                current_fencing_token = #{fencingToken},
                claimed_at = UTC_TIMESTAMP(6),
                last_heartbeat_at = UTC_TIMESTAMP(6),
                version = version + 1,
                updated_at = UTC_TIMESTAMP(6)
            WHERE run_id = #{runId}
              AND status = 'QUEUED'
              AND lease_owner IS NULL
              AND lease_until IS NULL
              AND current_fencing_token IS NULL
            """)
    int claim(
            @Param("runId") String runId,
            @Param("workerId") String workerId,
            @Param("fencingToken") long fencingToken,
            @Param("leaseSeconds") int leaseSeconds
    );

    @Update("""
            UPDATE agent_run
            SET lease_until = TIMESTAMPADD(SECOND, #{leaseSeconds}, UTC_TIMESTAMP(6)),
                last_heartbeat_at = UTC_TIMESTAMP(6),
                version = version + 1,
                updated_at = UTC_TIMESTAMP(6)
            WHERE run_id = #{runId}
              AND status = 'RUNNING'
              AND lease_owner = #{workerId}
              AND current_fencing_token = #{fencingToken}
              AND lease_until > UTC_TIMESTAMP(6)
            """)
    int heartbeat(
            @Param("runId") String runId,
            @Param("workerId") String workerId,
            @Param("fencingToken") long fencingToken,
            @Param("leaseSeconds") int leaseSeconds
    );

    @Update("""
            UPDATE agent_run
            SET lease_owner = #{workerId},
                lease_until = TIMESTAMPADD(SECOND, #{leaseSeconds}, UTC_TIMESTAMP(6)),
                current_fencing_token = #{nextFencingToken},
                last_heartbeat_at = UTC_TIMESTAMP(6),
                version = version + 1,
                updated_at = UTC_TIMESTAMP(6)
            WHERE run_id = #{runId}
              AND status = 'RUNNING'
              AND current_fencing_token = #{expectedFencingToken}
              AND lease_until <= UTC_TIMESTAMP(6)
            """)
    int takeover(
            @Param("runId") String runId,
            @Param("workerId") String workerId,
            @Param("expectedFencingToken") long expectedFencingToken,
            @Param("nextFencingToken") long nextFencingToken,
            @Param("leaseSeconds") int leaseSeconds
    );

    @Update("""
            UPDATE agent_run
            SET status = #{terminalStatus},
                lease_owner = NULL,
                lease_until = NULL,
                termination_reason = #{terminationReason},
                finished_at = UTC_TIMESTAMP(6),
                version = version + 1,
                updated_at = UTC_TIMESTAMP(6)
            WHERE run_id = #{runId}
              AND status = 'RUNNING'
              AND lease_owner = #{workerId}
              AND current_fencing_token = #{fencingToken}
              AND lease_until > UTC_TIMESTAMP(6)
            """)
    int terminate(
            @Param("runId") String runId,
            @Param("workerId") String workerId,
            @Param("fencingToken") long fencingToken,
            @Param("terminalStatus") String terminalStatus,
            @Param("terminationReason") String terminationReason
    );
}
