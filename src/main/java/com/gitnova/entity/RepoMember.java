package com.gitnova.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 仓库成员表实体
 */
@Data
@TableName("repo_member")
public class RepoMember {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repoId;

    private Long userId;

    /** 角色：owner / collaborator */
    private String role;
}
