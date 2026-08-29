package com.gitnova.entity.agent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Mutable MySQL projection of one Session-scoped Agent Task. */
@Data
@TableName("agent_task")
public class AgentTaskEntity {
    @TableId(value = "task_id", type = IdType.INPUT)
    private String taskId;

    private String sessionId;

    private String creationIdempotencyKey;

    private Long createdByActorId;

    private String status;

    private String requestJson;

    private String requestDigest;

    private String currentRunId;

    private Long lastRunNumber;

    private String terminalReason;

    private Long version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime terminalAt;

}
