# V5-00 Baseline Freeze

Date: 2026-08-17
Baseline branch: `refactor/agent-harness-v4-clean`
Baseline commit: `ced2d1f` — `feat(agent): model protocol foundation — OpenAI-compatible gateway + run result enrichment`

## Protected working tree

The following pre-existing Agent Runtime/Review work was present before V5-00 and is protected WIP. V5-00 must
not reset, overwrite, or fold it into an unrelated refactor:

- `CodeReviewAgentLoop`, `OpenAiCompatibleModelGateway`, `AgentTerminationReason`, and `ToolRegistry`
- `AgentRuntimeConfiguration`, `AgentRuntimeProperties`, and `application.yml`
- new `AgentRuntime`, `AgentRuntimePolicy`, `ReviewVerification`, `ReviewVerifier`, and Review verifier tests

This baseline changes test infrastructure and documentation only. It does not change the Hosting implementation,
Git object format, transfer protocol, branch CAS semantics, public API, or Agent Runtime behavior.

## Toolchain and historical test state

| Item | Observed state |
| --- | --- |
| Source target | Java 17 (`pom.xml`) |
| Local toolchain | Microsoft OpenJDK 21.0.11 |
| Maven | 3.9.16 |
| Wrapper before V5-00 | present but not executable |
| Historical Surefire result | 104 tests, 0 assertion failures, 8 environment errors |
| Spring tests | Mockito inline MockMaker attempted runtime self-attachment under JDK 21 |
| Gateway tests | MockWebServer could not bind a loopback socket in the restricted environment |

The current working tree must be re-tested after every functional change; historical `target/surefire-reports`
files are evidence only, never the release verdict.

## V5-00T verification

On 2026-08-17, the current working tree was verified with Maven 3.9.16 and JDK 21.0.11:

| Lane | Result |
| --- | --- |
| `unit-test` | passed |
| `gateway-test` | passed when loopback sockets were permitted |
| `mysql-it` | passed; Spring Context started without Mockito self-attachment (real MySQL contract deferred to V5-00C) |
| `workspace-it` | passed with zero tests; this lane awaits V5-06 |
| `all-tests` | 104 tests, 0 failures, 0 errors |

The restricted execution sandbox had no Maven Central egress and no cached Wrapper distribution. It could therefore
not perform the Wrapper's first Maven download; the equivalent system Maven 3.9.16 command was used for this
verification. This is an environment limitation, not a reason to change the committed Wrapper URL.

## Capability matrix at freeze time

| Capability | Status | V5 treatment |
| --- | --- | --- |
| Model DTOs and blocking ModelGateway | present | preserve; extend only behind the current contract |
| Review read tools and ReviewVerifier | present/WIP | stabilize with FakeModelGateway before generic profiles |
| AgentRuntime | WIP | preserve until V5-01 closes review behavior |
| ObjectStorage | present but path handling is legacy | V5-00A replaces it with a single authoritative object layout |
| Object negotiation and TransferService | present but relies on Gitlet HEAD, byte-array pack input, and hard-coded limits | V5-00A Hosting Consistency Gate |
| Durable Session/Run/Step | absent | starts at V5-04 |
| Docker Workspace | absent | starts at V5-06 |
| Context/Checkpoint/Proposal | absent | starts at V5-10 through V5-13 |

## Exit criteria

V5-00/V5-00T is complete only when:

1. the Wrapper has a repeatable entry point;
2. unit tests are independent of ports, MySQL, Docker, and model-provider access;
3. gateway, MySQL, Workspace, and all-test lanes have explicit commands and tags;
4. JDK 21 test execution does not depend on Mockito runtime self-attachment; and
5. the protected WIP remains untouched.

The next implementation task is V5-00A Hosting Consistency Gate, in this order:
`RepoKey/storage roots → canonical codec → atomic ObjectStorage → streaming pack decoder → branch CAS/fast-forward → hosting integration tests`.
