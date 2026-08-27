-- Session-wide durable Recall timeline and its unique Logical Workspace.

CREATE TABLE agent_session (
    session_id                 CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    creation_idempotency_key   VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by_actor_id        BIGINT NOT NULL,
    repo_id                    BIGINT NOT NULL,
    repo_key                   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status                     VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    last_session_sequence      BIGINT NOT NULL DEFAULT 0,
    version                    BIGINT NOT NULL DEFAULT 0,
    created_at                 DATETIME(6) NOT NULL,
    updated_at                 DATETIME(6) NOT NULL,
    closed_at                  DATETIME(6) NULL,
    PRIMARY KEY (session_id),
    UNIQUE KEY uk_agent_session_creation_idempotency (creation_idempotency_key),
    KEY idx_agent_session_repo_status (repo_id, status),
    CONSTRAINT fk_agent_session_repository
        FOREIGN KEY (repo_id) REFERENCES repository(id),
    CONSTRAINT chk_agent_session_sequence CHECK (last_session_sequence >= 0),
    CONSTRAINT chk_agent_session_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_workspace (
    workspace_id                CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id                  CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    base_revision               CHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workspace_epoch             BIGINT NOT NULL DEFAULT 0,
    generation                  BIGINT NOT NULL DEFAULT 0,
    manifest_digest             CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    content_fingerprint         CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    provider_type               VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    provider_ref                VARCHAR(1024) NULL,
    status                      VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    last_accepted_fencing_token BIGINT NOT NULL DEFAULT 0,
    writer_run_id               CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_owner                 VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until                 DATETIME(6) NULL,
    version                     BIGINT NOT NULL DEFAULT 0,
    created_at                  DATETIME(6) NOT NULL,
    updated_at                  DATETIME(6) NOT NULL,
    PRIMARY KEY (workspace_id),
    UNIQUE KEY uk_agent_workspace_session (session_id),
    CONSTRAINT fk_agent_workspace_session
        FOREIGN KEY (session_id) REFERENCES agent_session(session_id),
    CONSTRAINT chk_agent_workspace_epoch CHECK (workspace_epoch >= 0),
    CONSTRAINT chk_agent_workspace_generation CHECK (generation >= 0),
    CONSTRAINT chk_agent_workspace_fencing CHECK (last_accepted_fencing_token >= 0),
    CONSTRAINT chk_agent_workspace_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_step (
    step_id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_id                     VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_digest                 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id                   CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_sequence             BIGINT NOT NULL,
    task_id                      CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    run_id                       CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    run_step_sequence            BIGINT NULL,
    step_type                    VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schema_version               INT NOT NULL,
    payload_json                 JSON NOT NULL,
    persisted_payload_digest     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    causation_event_id           VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    correlation_id               VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    workspace_epoch              BIGINT NULL,
    workspace_generation         BIGINT NULL,
    created_at                   DATETIME(6) NOT NULL,
    PRIMARY KEY (step_id),
    UNIQUE KEY uk_agent_step_event (event_id),
    UNIQUE KEY uk_agent_step_session_sequence (session_id, session_sequence),
    UNIQUE KEY uk_agent_step_run_sequence (run_id, run_step_sequence),
    KEY idx_agent_step_session_type (session_id, step_type),
    CONSTRAINT fk_agent_step_session
        FOREIGN KEY (session_id) REFERENCES agent_session(session_id),
    CONSTRAINT chk_agent_step_session_sequence CHECK (session_sequence > 0),
    CONSTRAINT chk_agent_step_run_coordinates CHECK (
        (run_id IS NULL AND run_step_sequence IS NULL)
        OR (run_id IS NOT NULL AND run_step_sequence IS NOT NULL AND run_step_sequence > 0)
    ),
    CONSTRAINT chk_agent_step_workspace_coordinates CHECK (
        (workspace_epoch IS NULL AND workspace_generation IS NULL)
        OR (workspace_epoch >= 0 AND workspace_generation >= 0)
    ),
    CONSTRAINT chk_agent_step_schema_version CHECK (schema_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
