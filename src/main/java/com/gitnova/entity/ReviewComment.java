package com.gitnova.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Code Review 结果表实体（Agent 模块写入）
 */
@Data
@TableName("review_comment")
public class ReviewComment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String commitSha1;

    private Long repoId;

    private String filePath;

    private Integer lineNumber;

    /** 严重程度：info / warning / error */
    private String severity;

    private String comment;

    private LocalDateTime createdAt;
}
