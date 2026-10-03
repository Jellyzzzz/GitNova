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

    /** Raw results, including inline results already covered by a context summary. */
    @Select("""
            SELECT * FROM agent_step
            WHERE session_id = #{sessionId} AND step_type = 'TOOL_RESULT'
              AND session_sequence > #{afterSequence} AND session_sequence <= #{throughSequence}
            ORDER BY session_sequence LIMIT #{limit}
            """)
    List<AgentStepEntity> selectToolResultHistory(@Param("sessionId") String sessionId,
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

    /** The public number identifies the source result, never the later projection event. */
    @Select("""
            SELECT projection.payload_json FROM agent_step source
            JOIN agent_step projection ON projection.causation_event_id = source.event_id
              AND projection.session_id = source.session_id AND projection.run_id = source.run_id
            WHERE source.session_id = #{sessionId} AND source.session_sequence = #{sourceSequence}
              AND source.step_type = 'TOOL_RESULT' AND source.schema_version = 1
              AND projection.step_type = 'TOOL_OBSERVATION_PROJECTED' AND projection.schema_version = 1
            ORDER BY projection.session_sequence LIMIT 1
            """)
    String selectArtifactProjectionBySource(@Param("sessionId") String sessionId,
                                            @Param("sourceSequence") long sourceSequence);

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

    /** The committed terminal event points to its original, immutable model answer. */
    @Select("""
            SELECT response.* FROM agent_step terminal
            JOIN agent_step response
              ON response.event_id = terminal.causation_event_id
             AND response.session_id = terminal.session_id
             AND response.task_id = terminal.task_id
             AND response.run_id = terminal.run_id
            WHERE terminal.session_id = #{sessionId} AND terminal.task_id = #{taskId}
              AND terminal.step_type = 'RUN_COMPLETED'
              AND terminal.schema_version = 1
              AND response.step_type = 'MODEL_RESPONSE'
              AND response.session_sequence < terminal.session_sequence
            ORDER BY terminal.session_sequence DESC LIMIT 1
            """)
    AgentStepEntity selectCompletedAnswer(@Param("sessionId") String sessionId,
                                         @Param("taskId") String taskId);
}
