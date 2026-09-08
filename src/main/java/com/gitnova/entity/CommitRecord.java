package com.gitnova.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Commit 元数据索引表实体
 *
 * <p>Canonical Commit bytes live in repository-scoped ObjectStorage. This
 * table is a rebuildable relational index for query paths.</p>
 */
@Data
@TableName("commit_record")
public class CommitRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sha1;

    private Long repoId;

    private String parentSha1;    // 单亲；merge 暂不支持

    private String message;

    private Long authorId;

    private String branchName;

    private LocalDateTime createdAt;
}
