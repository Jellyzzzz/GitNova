package com.gitnova.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 仓库表实体
 *
 * {@code headCommitSha1} is only the cached HEAD of the default branch.
 * Hosted push CAS is authoritative on {@code branch.head_commit}.
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

    /** Non-authoritative cache synchronized after a successful default-branch CAS. */
    private String headCommitSha1;

    private LocalDateTime createdAt;
}
