# Database migration policy

`src/main/resources/sql/init.sql` is the legacy bootstrap script for existing local development data.
It must not receive new SPEC v5 schema changes.

New schema changes belong in this directory and must follow this convention:

```text
V<ordered-version>__<short_snake_case_description>.sql
```

Current sequence:

```text
V1__baseline_existing_schema.sql
V2__agent_session_foundation.sql
```

Flyway is the single migration mechanism. `baseline-on-migrate=1` adopts a legacy non-empty development
database without replaying V1; a new empty database executes V1 before applying V2. Runtime services must
not create or alter tables programmatically.
