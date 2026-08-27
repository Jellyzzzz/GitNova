package com.gitnova.mapper.agent;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gitnova.entity.agent.AgentWorkspaceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
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
}
