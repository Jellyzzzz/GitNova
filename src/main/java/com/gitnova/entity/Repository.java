package com.gitnova.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 仓库表实体
 *
 * ⚠️ Warning：head_commit_sha1 是整个并发控制的核心字段
 * 所有 CAS 校验都基于这一列，不要随意加索引或触发器修改它
 */
@Data
@TableName("repository")
public class Repository {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private Long ownerId;

    private Integer isPrivate;

    private String description;

    /** CAS 乐观锁目标列 — 所有 push 操作必须校验此字段 */
    private String headCommitSha1;

    private LocalDateTime createdAt;
}
