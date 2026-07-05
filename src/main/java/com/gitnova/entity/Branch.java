package com.gitnova.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 分支表实体
 */
@Data
@TableName("branch")
public class Branch {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repoId;

    private String name;

    private String headCommit;    // 分支当前 HEAD commit SHA-1
}
