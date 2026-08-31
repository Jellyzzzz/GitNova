-- Permanently isolate malformed dispatch intents without blocking later rows.
ALTER TABLE agent_outbox
    ADD COLUMN last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN failed_at DATETIME(6) NULL,
    DROP CHECK chk_agent_outbox_status,
    DROP CHECK chk_agent_outbox_publish_state,
    ADD CONSTRAINT chk_agent_outbox_status CHECK (
        status IN ('PENDING', 'PUBLISHED', 'FAILED')
    ),
    ADD CONSTRAINT chk_agent_outbox_publish_state CHECK (
        (
            status = 'PENDING'
            AND published_at IS NULL
            AND failed_at IS NULL
            AND last_error_code IS NULL
        )
        OR (
            status = 'PUBLISHED'
            AND published_at IS NOT NULL
            AND failed_at IS NULL
            AND last_error_code IS NULL
        )
        OR (
            status = 'FAILED'
            AND published_at IS NULL
            AND failed_at IS NOT NULL
            AND last_error_code IS NOT NULL
        )
    );
