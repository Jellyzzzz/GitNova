package com.gitnova.mapper.agent;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gitnova.entity.agent.AgentWorkspaceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentWorkspaceMapper
        extends BaseMapper<AgentWorkspaceEntity> {

    @Select("""
            SELECT *
            FROM agent_workspace
            WHERE session_id = #{sessionId}
            """)
    AgentWorkspaceEntity selectBySessionId(
            @Param("sessionId") String sessionId
    );

    @Select("""
            SELECT *
            FROM agent_workspace
            WHERE workspace_id = #{workspaceId}
            FOR UPDATE
            """)
    AgentWorkspaceEntity selectForUpdate(
            @Param("workspaceId") String workspaceId
    );

    @Select("""
            SELECT workspace.*, session.repo_key AS repo_key
            FROM agent_workspace workspace
            JOIN agent_session session ON session.session_id = workspace.session_id
            WHERE workspace.workspace_id = #{workspaceId}
              AND workspace.status = 'READY'
              AND session.status = 'ACTIVE'
            """)
    @Results(
            id = "readyWorkspaceRegistration",
            value = @Result(column = "repo_key", property = "repoKey")
    )
    AgentWorkspaceEntity selectReadyForRegistration(
            @Param("workspaceId") String workspaceId
    );

    @Update("""
            UPDATE agent_workspace
            SET status = 'READY',
                provider_type = #{providerType},
                provider_ref = #{providerRef},
                manifest_digest = #{manifestDigest},
                content_fingerprint = #{fingerprint},
                version = version + 1,
                updated_at = UTC_TIMESTAMP(6)
            WHERE workspace_id = #{workspaceId}
              AND status = 'PROVISIONING'
              AND workspace_epoch = 0
              AND generation = 0
            """)
    int activate(
            @Param("workspaceId") String workspaceId,
            @Param("providerType") String providerType,
            @Param("providerRef") String providerRef,
            @Param("manifestDigest") String manifestDigest,
            @Param("fingerprint") String fingerprint
    );

    @Update("""
            UPDATE agent_workspace
            SET status = 'FAILED',
                version = version + 1,
                updated_at = UTC_TIMESTAMP(6)
            WHERE workspace_id = #{workspaceId}
              AND status = 'PROVISIONING'
            """)
    int failProvisioning(@Param("workspaceId") String workspaceId);

    @Update("""
            UPDATE agent_workspace
            SET writer_run_id = #{runId},
                last_accepted_fencing_token = #{nextFencingToken},
                version = version + 1,
                updated_at = UTC_TIMESTAMP(6)
            WHERE workspace_id = #{workspaceId}
              AND status = 'READY'
              AND writer_run_id IS NULL
              AND last_accepted_fencing_token = #{expectedFencingToken}
            """)
    int claimWriter(
            @Param("workspaceId") String workspaceId,
            @Param("runId") String runId,
            @Param("expectedFencingToken") long expectedFencingToken,
            @Param("nextFencingToken") long nextFencingToken
    );

    @Update("""
            UPDATE agent_workspace
            SET last_accepted_fencing_token = #{nextFencingToken},
                version = version + 1,
                updated_at = UTC_TIMESTAMP(6)
            WHERE workspace_id = #{workspaceId}
              AND status = 'READY'
              AND writer_run_id = #{runId}
              AND last_accepted_fencing_token = #{expectedFencingToken}
            """)
    int takeoverWriter(
            @Param("workspaceId") String workspaceId,
            @Param("runId") String runId,
            @Param("expectedFencingToken") long expectedFencingToken,
            @Param("nextFencingToken") long nextFencingToken
    );

    @Update("""
            UPDATE agent_workspace
            SET writer_run_id = NULL,
                last_accepted_fencing_token = #{revokedFencingToken},
                version = version + 1,
                updated_at = UTC_TIMESTAMP(6)
            WHERE workspace_id = #{workspaceId}
              AND status = 'READY'
              AND writer_run_id = #{runId}
              AND last_accepted_fencing_token = #{expectedFencingToken}
            """)
    int releaseWriter(
            @Param("workspaceId") String workspaceId,
            @Param("runId") String runId,
            @Param("expectedFencingToken") long expectedFencingToken,
            @Param("revokedFencingToken") long revokedFencingToken
    );
}
