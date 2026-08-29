package com.gitnova.entity.agent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Mutable durable projection of the Logical Workspace owned by one Session.
 */
@Data
@TableName("agent_workspace")
public class AgentWorkspaceEntity {
    @TableId(value = "workspace_id", type = IdType.INPUT)
    private String workspaceId;

    /**
     * UNIQUE: one Session owns at most one Logical Workspace.
     */
    private String sessionId;

    private String baseRevision;

    private Long workspaceEpoch;

    private Long generation;

    private String manifestDigest;

    private String contentFingerprint;

    private String providerType;

    private String providerRef;

    private String status;

    private Long lastAcceptedFencingToken;

    private String writerRunId;

    private Long version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
