-- Durable Task/Run execution ownership and transactional dispatch intent.

-- Lease is Run execution ownership. Workspace retains only mutation authority.
ALTER TABLE agent_workspace
    DROP COLUMN lease_owner,
    DROP COLUMN lease_until;

CREATE TABLE agent_task (
    task_id                    CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id                 CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    creation_idempotency_key   VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by_actor_id        BIGINT NOT NULL,
    status                     VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_json               JSON NOT NULL,
    request_digest             CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    current_run_id             CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_run_number            BIGINT NOT NULL DEFAULT 0,
    terminal_reason            VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    version                    BIGINT NOT NULL DEFAULT 0,
    created_at                 DATETIME(6) NOT NULL,
    updated_at                 DATETIME(6) NOT NULL,
    terminal_at                DATETIME(6) NULL,
    PRIMARY KEY (task_id),
    UNIQUE KEY uk_agent_task_creation_idempotency (
        session_id,
        creation_idempotency_key
    ),
    UNIQUE KEY uk_agent_task_identity_session (task_id, session_id),
    KEY idx_agent_task_session_status (session_id, status),
    CONSTRAINT fk_agent_task_session
        FOREIGN KEY (session_id) REFERENCES agent_session(session_id),
    CONSTRAINT chk_agent_task_status CHECK (
        status IN ('ACTIVE', 'WAITING_USER', 'COMPLETED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT chk_agent_task_last_run_number CHECK (last_run_number >= 0),
    CONSTRAINT chk_agent_task_version CHECK (version >= 0),
    CONSTRAINT chk_agent_task_terminal_state CHECK (
        (
            status IN ('COMPLETED', 'FAILED', 'CANCELLED')
            AND current_run_id IS NULL
            AND terminal_reason IS NOT NULL
            AND terminal_at IS NOT NULL
        )
        OR (
            status IN ('ACTIVE', 'WAITING_USER')
            AND terminal_reason IS NULL
            AND terminal_at IS NULL
        )
    ),
    CONSTRAINT chk_agent_task_waiting_has_no_run CHECK (
        status <> 'WAITING_USER' OR current_run_id IS NULL
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_run (
    run_id                     CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id                 CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id                    CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_number                 BIGINT NOT NULL,
    predecessor_run_id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    status                     VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    active_slot                TINYINT GENERATED ALWAYS AS (
        CASE
            WHEN status IN ('QUEUED', 'RUNNING') THEN 1
            ELSE NULL
        END
    ) STORED,
    last_run_step_sequence     BIGINT NOT NULL DEFAULT 0,
    lease_owner                VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until                DATETIME(6) NULL,
    current_fencing_token      BIGINT NULL,
    execution_config_json      JSON NOT NULL,
    execution_config_digest    CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    termination_reason         VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    version                    BIGINT NOT NULL DEFAULT 0,
    created_at                 DATETIME(6) NOT NULL,
    claimed_at                 DATETIME(6) NULL,
    last_heartbeat_at          DATETIME(6) NULL,
    finished_at                DATETIME(6) NULL,
    updated_at                 DATETIME(6) NOT NULL,
    PRIMARY KEY (run_id),
    UNIQUE KEY uk_agent_run_task_number (task_id, run_number),
    UNIQUE KEY uk_agent_run_one_active_per_task (task_id, active_slot),
    UNIQUE KEY uk_agent_run_identity_task (run_id, task_id),
    UNIQUE KEY uk_agent_run_identity_session (run_id, session_id),
    KEY idx_agent_run_session_status (session_id, status),
    KEY idx_agent_run_lease_scan (status, lease_until),
    CONSTRAINT fk_agent_run_task_session
        FOREIGN KEY (task_id, session_id)
        REFERENCES agent_task(task_id, session_id),
    CONSTRAINT fk_agent_run_predecessor_task
        FOREIGN KEY (predecessor_run_id, task_id)
        REFERENCES agent_run(run_id, task_id),
    CONSTRAINT chk_agent_run_number CHECK (run_number > 0),
    CONSTRAINT chk_agent_run_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT chk_agent_run_step_sequence CHECK (last_run_step_sequence >= 0),
    CONSTRAINT chk_agent_run_fencing CHECK (
        current_fencing_token IS NULL OR current_fencing_token > 0
    ),
    CONSTRAINT chk_agent_run_version CHECK (version >= 0),
    CONSTRAINT chk_agent_run_lease_state CHECK (
        (
            status = 'QUEUED'
            AND lease_owner IS NULL
            AND lease_until IS NULL
            AND current_fencing_token IS NULL
        )
        OR (
            status = 'RUNNING'
            AND lease_owner IS NOT NULL
            AND lease_until IS NOT NULL
            AND current_fencing_token IS NOT NULL
        )
        OR (
            status IN ('COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED')
            AND lease_owner IS NULL
            AND lease_until IS NULL
        )
    ),
    CONSTRAINT chk_agent_run_terminal_state CHECK (
        (
            status IN ('QUEUED', 'RUNNING')
            AND termination_reason IS NULL
            AND finished_at IS NULL
        )
        OR (
            status IN ('COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED')
            AND termination_reason IS NOT NULL
            AND finished_at IS NOT NULL
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Circular Task.currentRun projection is added only after agent_run exists.
ALTER TABLE agent_task
    ADD CONSTRAINT fk_agent_task_current_run
        FOREIGN KEY (current_run_id, task_id)
        REFERENCES agent_run(run_id, task_id);

-- A Workspace writer must be a Run belonging to the same Session.
ALTER TABLE agent_workspace
    ADD CONSTRAINT fk_agent_workspace_writer_run
        FOREIGN KEY (writer_run_id, session_id)
        REFERENCES agent_run(run_id, session_id);

-- Optional Task/Run Step coordinates become relationally trustworthy.
ALTER TABLE agent_step
    ADD CONSTRAINT fk_agent_step_task_session
        FOREIGN KEY (task_id, session_id)
        REFERENCES agent_task(task_id, session_id),
    ADD CONSTRAINT fk_agent_step_run_task
        FOREIGN KEY (run_id, task_id)
        REFERENCES agent_run(run_id, task_id),
    ADD CONSTRAINT chk_agent_step_run_requires_task CHECK (
        run_id IS NULL OR task_id IS NOT NULL
    );

CREATE TABLE agent_outbox (
    outbox_id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_id                    VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_digest                CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_type              VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id                CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type                  VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_json                JSON NOT NULL,
    payload_digest              CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status                      VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_count               INT NOT NULL DEFAULT 0,
    available_at                DATETIME(6) NOT NULL,
    published_at                DATETIME(6) NULL,
    created_at                  DATETIME(6) NOT NULL,
    updated_at                  DATETIME(6) NOT NULL,
    PRIMARY KEY (outbox_id),
    UNIQUE KEY uk_agent_outbox_event (event_id),
    KEY idx_agent_outbox_publish (status, available_at, outbox_id),
    CONSTRAINT chk_agent_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED')),
    CONSTRAINT chk_agent_outbox_attempt CHECK (attempt_count >= 0),
    CONSTRAINT chk_agent_outbox_publish_state CHECK (
        (status = 'PENDING' AND published_at IS NULL)
        OR (status = 'PUBLISHED' AND published_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
