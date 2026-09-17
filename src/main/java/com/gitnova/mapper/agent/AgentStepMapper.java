package com.gitnova.mapper.agent;

import com.gitnova.entity.agent.AgentStepEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/** Append-only Mapper: deliberately exposes no update or delete methods. */
@Mapper
public interface AgentStepMapper {
    @Select("SELECT COALESCE(MAX(session_sequence), 0) FROM agent_step WHERE session_id = #{sessionId}")
    long latestSessionSequence(@Param("sessionId") String sessionId);

    /** A fixed upper bound prevents concurrent appends from changing a multi-page history read. */
    @Select("""
            SELECT * FROM agent_step
            WHERE session_id = #{sessionId} AND session_sequence > #{afterSequence}
              AND session_sequence <= #{throughSequence}
            ORDER BY session_sequence LIMIT #{limit}
            """)
    List<AgentStepEntity> selectSessionHistory(@Param("sessionId") String sessionId,
                                              @Param("afterSequence") long afterSequence,
                                              @Param("throughSequence") long throughSequence,
                                              @Param("limit") int limit);

    @Select("""
            SELECT * FROM agent_step WHERE session_id = #{sessionId}
              AND step_type = 'CONTEXT_SUMMARY_CREATED' AND session_sequence <= #{throughSequence}
            ORDER BY session_sequence DESC LIMIT 1
            """)
    AgentStepEntity selectLatestContextSummary(@Param("sessionId") String sessionId,
                                              @Param("throughSequence") long throughSequence);

    @Select("""
            SELECT * FROM agent_step WHERE session_id = #{sessionId}
              AND step_type = 'CONTEXT_CONTROL_UPDATED' AND session_sequence <= #{throughSequence}
            ORDER BY session_sequence DESC LIMIT 1
            """)
    AgentStepEntity selectLatestContextControl(@Param("sessionId") String sessionId,
                                              @Param("throughSequence") long throughSequence);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM agent_step
                WHERE run_id = #{runId} AND step_type = 'MODEL_CALL_STARTED')
            """)
    boolean hasModelCallStarted(@Param("runId") String runId);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM agent_step
                WHERE session_id = #{sessionId} AND run_id = #{runId}
                  AND event_id = #{eventId} AND step_type = 'TOOL_RESULT')
            """)
    boolean hasToolResult(@Param("sessionId") String sessionId,
                          @Param("runId") String runId, @Param("eventId") String eventId);

    /** Only committed projections in this Session may authorize Artifact reads. */
    @Select("""
            SELECT payload_json FROM agent_step
            WHERE session_id = #{sessionId}
              AND step_type = 'TOOL_OBSERVATION_PROJECTED'
              AND schema_version = 1
              AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,
                  '$.observation.externalization.artifact.artifactId')) = #{artifactId}
            ORDER BY session_sequence DESC
            LIMIT 1
            """)
    String selectArtifactProjection(@Param("sessionId") String sessionId,
                                    @Param("artifactId") String artifactId);

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
