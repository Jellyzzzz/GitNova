package com.gitnova.entity.agent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Mutable MySQL projection of one durable Agent execution attempt. */
@Data
@TableName("agent_run")
public class AgentRunEntity {
    @TableId(value = "run_id", type = IdType.INPUT)
    private String runId;
    private String sessionId;
    private String taskId;
    private Long runNumber;
    private String predecessorRunId;
    private String status;
    private Integer activeSlot;
    private Long lastRunStepSequence;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private Long currentFencingToken;
    private String executionConfigJson;
    private String executionConfigDigest;
    private String terminationReason;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime claimedAt;
    private LocalDateTime lastHeartbeatAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
}
