CREATE DATABASE IF NOT EXISTS gitnova_go_dev
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE gitnova_go_dev;

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,

    username VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,

    display_name VARCHAR(100) NOT NULL DEFAULT '',
    avatar_url VARCHAR(512) NULL,
    bio TEXT NULL,

    email_verified_at DATETIME(6) NULL,
    disabled_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY uk_users_email (email)
) ENGINE=InnoDB;
