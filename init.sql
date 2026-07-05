-- GitNova 数据库初始化脚本
-- 执行前请确认已创建数据库：CREATE DATABASE gitnova DEFAULT CHARACTER SET utf8mb4;
-- 在 IDEA Database 面板的 Query Console 中直接粘贴执行即可

USE gitnova;

-- ===== 用户表 =====
CREATE TABLE IF NOT EXISTS `user` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username`   VARCHAR(50)  NOT NULL                COMMENT '用户名，唯一',
    `password`   VARCHAR(100) NOT NULL                COMMENT 'BCrypt加密后的密码',
    `email`      VARCHAR(100)                         COMMENT '邮箱（可选）',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ===== 仓库表 =====
-- 注意：head_commit_sha1 是CAS乐观锁的核心字段，Phase 3并发控制依赖它
CREATE TABLE IF NOT EXISTS `repository` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '仓库ID',
    `name`             VARCHAR(100) NOT NULL                COMMENT '仓库名称',
    `owner_id`         BIGINT       NOT NULL                COMMENT '所有者用户ID',
    `is_private`       TINYINT      NOT NULL DEFAULT 1      COMMENT '是否私有：1=私有 0=公开',
    `description`      VARCHAR(255)                         COMMENT '仓库描述',
    `head_commit_sha1` VARCHAR(40)                          COMMENT '当前HEAD的commit SHA1，CAS并发控制核心字段',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_owner_name` (`owner_id`, `name`),
    KEY `idx_owner_id` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库表';

-- ===== 仓库成员表 =====
CREATE TABLE IF NOT EXISTS `repo_member` (
    `id`      BIGINT NOT NULL AUTO_INCREMENT COMMENT '成员记录ID',
    `repo_id` BIGINT NOT NULL               COMMENT '仓库ID',
    `user_id` BIGINT NOT NULL               COMMENT '用户ID',
    `role`    ENUM('owner','collaborator') NOT NULL COMMENT '角色：owner=所有者 collaborator=协作者',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_repo_user` (`repo_id`, `user_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库成员表';

-- ===== Commit元数据索引表 =====
-- 说明：Gitlet的Commit对象序列化存磁盘，此表只是索引层
-- 目的：前端查询commit log时走SQL，不扫描磁盘文件
-- 两者必须同步写入，以TransferService的@Transactional保证
CREATE TABLE IF NOT EXISTS `commit_record` (
    `sha1`        VARCHAR(40)  NOT NULL COMMENT 'Commit的SHA1，主键',
    `repo_id`     BIGINT       NOT NULL COMMENT '所属仓库ID',
    `parent_sha1` VARCHAR(40)           COMMENT '父Commit的SHA1，初始commit为NULL',
    `message`     VARCHAR(255)          COMMENT 'commit message',
    `author_id`   BIGINT                COMMENT '提交者用户ID',
    `branch_name` VARCHAR(100)          COMMENT '所属分支名',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    PRIMARY KEY (`sha1`),
    KEY `idx_repo_branch` (`repo_id`, `branch_name`),
    KEY `idx_repo_created` (`repo_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Commit元数据索引表';

-- ===== 分支表 =====
CREATE TABLE IF NOT EXISTS `branch` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '分支ID',
    `repo_id`     BIGINT       NOT NULL               COMMENT '所属仓库ID',
    `name`        VARCHAR(100) NOT NULL               COMMENT '分支名称',
    `head_commit` VARCHAR(40)  NOT NULL               COMMENT '当前分支HEAD的commit SHA1',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_repo_branch` (`repo_id`, `name`),
    KEY `idx_repo_id` (`repo_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分支表';

-- ===== Code Review结果表（Phase 4 Agent写入）=====
CREATE TABLE IF NOT EXISTS `review_comment` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '评论ID',
    `commit_sha1` VARCHAR(40)  NOT NULL               COMMENT '对应的commit SHA1',
    `repo_id`     BIGINT       NOT NULL               COMMENT '所属仓库ID',
    `file_path`   VARCHAR(255)                        COMMENT '文件路径',
    `line_number` INT                                 COMMENT '行号',
    `severity`    ENUM('info','warning','error')      COMMENT '严重程度',
    `comment`     TEXT                                COMMENT 'review意见内容',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间',
    PRIMARY KEY (`id`),
    KEY `idx_commit_sha1` (`commit_sha1`),
    KEY `idx_repo_id` (`repo_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM Code Review结果表';
