package com.gitnova.entity.agent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Mutable MySQL projection of an Agent Session.
 *
 * <p>This persistence entity must not be exposed as the AgentSession
 * domain aggregate.</p>
 */
@Data
@TableName("agent_session")
public class AgentSessionEntity {
    @TableId(value = "session_id", type = IdType.INPUT)
    private String sessionId;

    private String creationIdempotencyKey;

    private Long createdByActorId;

    private Long repoId;

    private String repoKey;

    private String status;

    private Long lastSessionSequence;

    private Long version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime closedAt;
}
