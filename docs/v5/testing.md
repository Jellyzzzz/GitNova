# V5-00T test lanes

GitNova uses JUnit 5 tags and Maven profiles to keep pure unit tests runnable in restricted environments.
The Maven Wrapper is the canonical entry point; Maven uses the Java version selected by `JAVA_HOME`.

| Lane | Command | Environment contract |
| --- | --- | --- |
| unit-test | `./mvnw -q test` or `./mvnw -q -Punit-test test` | No listening socket, MySQL, Docker, or external model provider. This is the default lane. |
| gateway-test | `./mvnw -q -Pgateway-test test` | Loopback sockets allowed. Covers `MockWebServer` provider-adapter tests. |
| mysql-it | `./mvnw -q -Pmysql-it test` | Spring/MySQL integration lane. Its current bootstrap test does not issue a database query; V5-00C will add Testcontainers and a real MySQL contract. |
| workspace-it | `./mvnw -q -Pworkspace-it test` | Docker daemon available. This lane is intentionally empty until V5-06 adds Workspace tests. |
| all-tests | `./mvnw -q -Pall-tests test` | Supports every prerequisite above; intended for local development and CI. |

## Tagging rules

- Untagged tests are unit tests and must not start Spring, bind a port, access MySQL, or require Docker.
- `@Tag("gateway")` is required for loopback/provider-adapter tests.
- `@Tag("mysql-it")` is required for Spring/MySQL integration tests.
- `@Tag("workspace-it")` is required for Docker Workspace tests.
- A test with multiple external requirements carries every applicable tag.

## JDK support

The project source target remains Java 17. The current local toolchain is JDK 21, so Surefire starts the pinned
`mockito-core` JAR as an explicit Java agent. Tests therefore do not rely on runtime Byte Buddy self-attachment.
The Mockito version is pinned in `pom.xml`; update the dependency and the agent path together when Spring Boot's
managed version changes.

## Restricted environments

The Wrapper is executable and remains the canonical command. Its first invocation downloads the configured Maven
distribution, so an environment with no Maven Central egress and no cached distribution cannot execute `./mvnw`.
Use a preinstalled Maven with the same version only as an environment-specific fallback; do not replace the
repository's distribution URL with a machine-local absolute path.
