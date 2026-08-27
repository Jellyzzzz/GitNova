package com.gitnova.mapper.agent;

import com.gitnova.entity.agent.AgentStepEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Append-only Mapper: deliberately exposes no update or delete methods. */
@Mapper
public interface AgentStepMapper {

    @Insert("""
            INSERT INTO agent_step (
                event_id, event_digest,
                session_id, session_sequence,
                task_id, run_id, run_step_sequence,
                step_type, schema_version,
                payload_json, persisted_payload_digest,
                causation_event_id, correlation_id,
                workspace_epoch, workspace_generation,
                created_at
            ) VALUES (
                #{eventId}, #{eventDigest},
                #{sessionId}, #{sessionSequence},
                #{taskId}, #{runId}, #{runStepSequence},
                #{stepType}, #{schemaVersion},
                CAST(#{payloadJson} AS JSON), #{persistedPayloadDigest},
                #{causationEventId}, #{correlationId},
                #{workspaceEpoch}, #{workspaceGeneration},
                #{createdAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "stepId", keyColumn = "step_id")
    int insert(AgentStepEntity step);

    @Select("""
            SELECT *
            FROM agent_step
            WHERE event_id = #{eventId}
            FOR UPDATE
            """)
    AgentStepEntity selectByEventId(@Param("eventId") String eventId);
}
