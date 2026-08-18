-- =============================================
-- GitNova 数据库初始化脚本
-- =============================================

CREATE DATABASE IF NOT EXISTS gitnova
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE gitnova;

-- =============================================
-- 用户表
-- =============================================
CREATE TABLE IF NOT EXISTS user (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    username    VARCHAR(50)  UNIQUE NOT NULL,
    password    VARCHAR(100) NOT NULL,           -- BCrypt
    email       VARCHAR(100),
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- 仓库表
-- head_commit_sha1 is a cache of the default branch. Hosted-push CAS uses
-- branch.head_commit, which is the authoritative per-branch pointer.
-- =============================================
CREATE TABLE IF NOT EXISTS repository (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    name             VARCHAR(100) NOT NULL,
    owner_id         BIGINT NOT NULL,
    is_private       TINYINT  DEFAULT 1,
    description      VARCHAR(255),
    head_commit_sha1 VARCHAR(40),                -- default branch cache, non-authoritative
    created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_owner_name (owner_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- 仓库成员表
-- =============================================
CREATE TABLE IF NOT EXISTS repo_member (
    id      BIGINT PRIMARY KEY AUTO_INCREMENT,
    repo_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role    ENUM('owner', 'collaborator') NOT NULL,
    UNIQUE KEY uk_repo_user (repo_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- Commit 元数据索引层
-- Commit objects are canonical bytes in ObjectStorage; this table is only
--    a rebuildable index for front-end commit-log queries, avoiding
--    扫描磁盘文件，直接走 SQL 查询。两者必须保持同步写入。
-- =============================================
CREATE TABLE IF NOT EXISTS commit_record (
    sha1        VARCHAR(40) PRIMARY KEY,
    repo_id     BIGINT      NOT NULL,
    parent_sha1 VARCHAR(40),                     -- 单亲；merge 暂不支持
    message     VARCHAR(255),
    author_id   BIGINT,
    branch_name VARCHAR(100),
    created_at  DATETIME,
    INDEX idx_repo_branch (repo_id, branch_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- 分支表
-- =============================================
CREATE TABLE IF NOT EXISTS branch (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    repo_id     BIGINT      NOT NULL,
    name        VARCHAR(100) NOT NULL,
    head_commit VARCHAR(40)  NOT NULL,           -- authoritative branch pointer
    UNIQUE KEY uk_repo_branch (repo_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- Code Review 结果表（Agent 模块写入）
-- =============================================
CREATE TABLE IF NOT EXISTS review_comment (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    commit_sha1 VARCHAR(40)  NOT NULL,
    repo_id     BIGINT       NOT NULL,
    file_path   VARCHAR(255),
    line_number INT,
    severity    ENUM('info', 'warning', 'error'),
    comment     TEXT,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
