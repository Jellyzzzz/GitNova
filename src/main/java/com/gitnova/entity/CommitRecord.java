package com.gitnova.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Commit 元数据索引表实体
 *
 * 💡 Design Note：Gitlet 的 Commit 对象本身序列化存磁盘，
 * 这张表只是"索引"，目的是让前端展示 commit log 时不用扫描磁盘文件，
 * 直接走 SQL 查询。两者必须保持同步写入。
 */
@Data
@TableName("commit_record")
public class CommitRecord {

    @TableId
    private String sha1;          // SHA-1 作为主键

    private Long repoId;

    private String parentSha1;    // 单亲；merge 暂不支持

    private String message;

    private Long authorId;

    private String branchName;

    private LocalDateTime createdAt;
}
