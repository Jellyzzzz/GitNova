# Database migration policy

`src/main/resources/sql/init.sql` is the legacy bootstrap script for existing local development data.
It must not receive new SPEC v5 schema changes.

New schema changes belong in this directory and must follow this convention:

```text
V<ordered-version>__<short_snake_case_description>.sql
```

Examples:

```text
V1__agent_session_and_run.sql
V2__agent_step_and_checkpoint.sql
```

The migration runner is intentionally not introduced by V5-00. V5-00C will add and configure a single
migration mechanism, then migrate existing development schemas in a dedicated change. Runtime services must
not create or alter tables programmatically.
