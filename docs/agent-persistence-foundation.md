# GitNova Agent 持久化基座设计

本文冻结 GitNova Cloud Coding Agent 的持久化语义，作为后续数据库、Artifact、Context/Memory、Worker 与恢复机制的开发基线。本文不是 SPEC，也不规定最终 Java 类数量或 SQL 字段细节。

## 1. 目标与边界

目标：

- JVM、Worker 或物理执行环境重启后，Session、Task、Run、Step 与 Logical Workspace 仍可识别和恢复；
- 完整执行事实可审计、可检索、可重放；
- Workspace 修改、Tool 因果关系、Checkpoint 与 Projection 状态具有明确一致性；
- Redis、RabbitMQ、Search Index 均可丢失和重建，不成为唯一事实源；
- 后续 Context、Memory、Compaction 只消费持久事实，不反向替代事实。

当前不解决：

- Redis/RabbitMQ/OpenSearch 的具体部署；
- Context 选择和压缩算法；
- Docker 容器实现；
- Proposal、审批和 Git write-back；
- 最终 SQL、Entity、Mapper 与 API 形式。

## 2. Durable Truth

唯一事实源分为两类：

- **MySQL**：Session、Workspace metadata、Task、Run、Step、Checkpoint、Memory metadata、Projection、Outbox 与生命周期状态的 durable truth；
- **Artifact Store**：大型 Tool Result、日志、Diff、Workspace Snapshot/Effect 等不可变原始内容的 durable truth。

派生组件：

- Redis 只保存可从 MySQL 重建的热状态和缓存；
- Search Index 只保存可从 MySQL/Artifact 重建的检索投影；
- RabbitMQ 只传递调度通知；消息丢失后由 Outbox 与 Run 状态重新派发。

Artifact Store 与 Git ObjectStorage 分离。它们可以都采用内容寻址，但对象语义、权限和生命周期不同。

## 3. 领域关系

```text
Session
│
├── Logical Workspace（1:1）
│      generation: 0 → 1 → ... → N
│
├── Task A
│    ├── Task Memory
│    ├── Run 1 → Step*
│    └── Run 2 → Step*
│
├── Task B
│    ├── Task Memory
│    └── Run 3 → Step*
│
└── Recall History（按 sessionSequence 排序）
```

定义：

- **Session**：一段持续 Coding Interaction，是交互连续性和 Logical Workspace 生命周期边界；
- **Logical Workspace**：Session 内持续演化的 authoritative code state，身份稳定，物理目录/容器可替换；
- **Task**：Workspace 上的逻辑目标，拥有 Goal/DoD、Plan、Progress、Facts 等 Task Memory，可跨多个 Run；
- **Run**：某个 Task 的一次连续 Harness execution attempt，临时获得执行权，不拥有 Workspace；
- **Step**：append-only durable execution fact；
- **Checkpoint**：在明确 Session watermark 和 Workspace generation 上形成的恢复投影。

创建新的 Task 或 Run 不创建 Workspace、不恢复 Base Revision，也不重置 generation。需要独立文件树时应创建/分叉新的 Session Workspace。

## 4. Session Workspace 语义

Session 创建时：

```text
Repository + Base Revision
        ↓ materialize
Logical Workspace W
generation = 0
        ↓
Session S.workspaceId = W
```

Task、Run 完成/失败、暂停、重试以及 Context compaction 均不重新初始化 Workspace。后续 Task 默认看到此前 Task 与用户保留的修改。

Workspace 状态：

- `baseRevision`：本 Workspace epoch 的来源代码快照；
- `workspaceEpoch`：显式 rebase/sync/publish baseline 时推进；
- `generation`：Session 内全局单调的可观察代码状态版本；
- `manifestDigest/fingerprint`：该 generation 的文件树校验身份。

一次多文件 mutation 只推进一次 generation；无实际文件变化时不推进。Repository branch HEAD 在外部推进只使 Session 标记 `BASE_STALE`，不得自动合入当前 Workspace。

权威关系：

```text
Current Workspace Evidence
    > Task Memory
    > Historical/Long-term Memory
```

Validation 不属于 Workspace 本体，而是绑定 `workspaceId + epoch + generation` 的 Step/Evidence。generation 推进后，旧 Validation 保留在 Recall 中，但作为当前完成证据立即失效。

## 5. 三条时间轴

每个 Step 最多同时参与三条时间轴：

```text
sessionSequence
= Session 跨 Task/Run 的全局 Recall 顺序

runStepSequence
= 单个 Run 内的局部执行顺序

workspaceEpoch + workspaceGeneration
= 代码状态演化顺序
```

约束：

```text
UNIQUE(sessionId, sessionSequence)
UNIQUE(runId, runStepSequence)
UNIQUE(eventId)
```

`sessionSequence` 必须在 MySQL 事务内通过 Session 行锁或 CAS 分配，不允许 `SELECT MAX + 1`。

## 6. Append-only Step

已提交 Step 不允许原地修改或普通业务物理删除。纠错、失效、补偿、状态变化均通过新 Step 表达。

所有 Projection mutation 必须：

```text
append describing Step
+ update Projection
+ insert Outbox（如需要）
→ 同一个 MySQL 事务提交
```

Projection 不得出现无法由 committed Step 解释的状态。Projection 可以更新或重建；Step 是事实历史。

Step 稳定信封至少包含：

```text
eventId
sessionId / sessionSequence
taskId（可选）
runId / runStepSequence（可选）
stepType
schemaVersion
payload
persistedPayloadDigest
causationEventId / correlationId
workspaceEpoch / workspaceGeneration（可选）
createdAt
```

## 7. Schema Version 与 Replay

`stepType + schemaVersion` 决定 payload 解析方式：

```text
TOOL_RESULT v1 → V1 Codec → upcast → 当前统一领域事件
TOOL_RESULT v2 → V2 Codec → upcast → 当前统一领域事件
```

Replay 不得直接把所有历史 JSON 反序列化为当前 DTO。已发布 Codec 和 golden JSON 兼容测试需要长期保留。

## 8. eventId 与幂等追加

`eventId` 是逻辑事件身份，必须在第一次数据库请求前生成，并在网络重试时复用。

```text
appendStep(eventId = X)
    ↓
X 不存在
→ 分配序号、插入 Step、更新 Projection/Outbox

X 已存在且 eventDigest 一致
→ ALREADY_COMMITTED，返回原 Step

X 已存在但 eventDigest 不一致
→ IDEMPOTENCY_KEY_CONFLICT
```

不得使用 `INSERT IGNORE` 隐藏相同 eventId 的 payload 冲突。重试命中已提交事件时，不得再次更新 Projection 或再次产生 Outbox。

## 9. Tool 因果与执行状态

身份：

- `toolCallId`：模型协议身份，Run 内唯一；
- `executionId`：Harness 分配的一次逻辑执行身份，retry/reconcile 不重新生成；
- `argsFingerprint`：canonical execution args 的安全指纹；
- `expectedGeneration`：执行依据的 Workspace generation。

Tool Result 必须引用已经持久化的 Tool Call；不存在无 Call 的 Result；一个逻辑执行最终只能有一个确定 terminal outcome。

状态机：

```text
NOT_STARTED
    ↓
IN_FLIGHT
    ├── SUCCEEDED                  terminal
    ├── FAILED                     terminal
    └── UNKNOWN
          ├── SUCCEEDED_RECONCILED terminal
          ├── FAILED_RECONCILED    terminal
          └── UNRESOLVED           terminal/manual
```

写 Tool 至少记录：

```text
generationBefore
generationAfter
effectDigest
changed/deleted paths 或对应 ArtifactRef
```

JVM 在副作用后、Result 落库前崩溃时，恢复流程先 refresh/reconcile authoritative Workspace。`UNKNOWN` 禁止自动重放，直到证明已生效、未生效可重试或无法自动判定。

## 10. Durable-before-context

凡是会影响下一轮推理的事实，必须先 durable commit，再进入 Model Context，包括：

- User Message；
- Model Response/Tool Call；
- Tool Result；
- Harness Feedback；
- Workspace Drift/Mutation；
- Validation；
- Completion/Correction。

数据库不可用时 Runtime 不应继续仅在 JVM 内推进事实。

## 11. 敏感数据与 Digest

完整执行事实不等于永久保存所有原始敏感字节。

三种表示必须分离：

```text
Execution Representation
= Tool 短暂使用的真实参数

Persisted Representation
= schema-aware sanitization 后的 canonical JSON

Model Observation Representation
= summary/preview/artifactRef
```

优先使用 `secretRef`，避免模型直接传递 Secret。Args、Tool Result、stdout/stderr、异常、日志、Artifact 和 Outbox 均需经过敏感信息策略。

Digest 区分：

- `executionArgsFingerprint`：对 raw canonical args 使用带版本密钥的 HMAC，用于逻辑执行一致性；
- `persistedPayloadDigest`：对 redacted canonical JSON 使用 SHA-256，用于持久内容校验。

## 12. Workspace Snapshot 与重建

Logical Workspace 采用：

```text
Base Revision
+ Latest Complete Snapshot
+ Snapshot 后的 Mutation Effects
→ staging 重建
→ Manifest/Tree Digest 校验
→ atomic publish
```

每个被 Harness 承认的 generation 都必须存在可重建的 durable effect。ApplyPatch、RunCommand 副作用和用户/外部 Workspace 修改都必须捕获新增、修改、删除以及二进制文件内容；不能只记录 stdout 或 unified diff。

Snapshot 保存 generation 对应的不可变文件树 manifest，文件内容由 Artifact Store 内容寻址保存。重建只恢复既有 generation，不因更换物理目录/容器而推进 generation。

Snapshot 可在以下条件创建：

- 累计 N 个 generations/effects；
- effect 总量超过阈值；
- Checkpoint、WAITING_USER/APPROVAL、Worker shutdown；
- 显式恢复优化点。

## 13. Session/Workspace Provisioning

MySQL 与文件系统无法组成普通 ACID 事务，创建使用可恢复 Saga：

```text
MySQL：Session/Workspace = PROVISIONING + SESSION_CREATED Step
    ↓
WorkspaceProvider：staging materialize → atomic publish
    ↓
MySQL：Workspace = READY、Session = ACTIVE
       + WORKSPACE_MATERIALIZED Step
```

使用稳定 `sessionId/workspaceId/eventId` 重试。DB 已存在但目录缺失时重试物化；目录已发布但 DB 未激活时验证并补写；半成品 staging 清理。Workspace READY 前不得启动 Task。

## 14. Lease 与 Fencing

同一 Session 可以有多个 Task，但同一 Logical Workspace 同时最多有一个合法 mutation writer。只读执行可并发，但必须接受 generation drift。

Fencing 不只校验数据库 append，还必须在 Workspace mutation 的资源临界区执行：

```text
Workspace write lock
→ 检查 token >= lastAcceptedFencingToken
→ 原子接受新 token
→ 检查 expectedGeneration
→ mutation
→ generation/effect capture
→ unlock
```

旧 Worker 即使复活，也不得在新 token 之后修改 Workspace 或追加 Step。

## 15. Run 状态与 RabbitMQ ACK

Run terminal/attempt-ending 状态单调，进入后不得被旧 Worker 恢复为 RUNNING。继续任务创建新 Run；仅 RUNNING 且 lease 过期的基础设施接管可由新 Worker 使用更高 fencing token 接续同一 Run。

RabbitMQ 消费顺序：

```text
delivery(runId)
→ MySQL durable claim
   - leaseOwner/leaseUntil
   - fencingToken++
   - RUN_CLAIMED Step
→ transaction commit
→ ACK RabbitMQ
→ 执行长 Run
```

Claim 前失败则不 ACK；Claim 后崩溃由 MySQL lease scanner 生成 recovery Outbox 重新派发。重复消息遇到有效 claim 或 terminal Run 时直接 ACK，不启动第二个 Runtime。

## 16. Transactional Outbox

任何需要异步派生的状态变化，Step、Projection 与 Outbox 同事务提交。Outbox Publisher 采用 broker confirm；发布成功但 Outbox 未标记时允许重复发布，Consumer 必须以 `eventId` 幂等。

RabbitMQ 丢失后扫描未发布 Outbox；Worker 丢失后扫描 lease-expired Run。MQ 不保存唯一待执行事实。

## 17. Checkpoint 与 Resume

Checkpoint 至少绑定：

```text
sessionId / taskId / sourceRunId
workspaceId / workspaceEpoch / workspaceGeneration
throughSessionSequence
throughRunStepSequence
taskMemoryVersion
stateSchemaVersion
manifestDigest
```

Checkpoint row 与 `CHECKPOINT_CREATED` Step 在同一个 MySQL 事务提交。Checkpoint 的 watermark 指向创建事件之前的 committed Step。

恢复：

```text
load Session/Logical Workspace
→ recover/verify Workspace tree
→ load Checkpoint
→ replay sessionSequence watermark 后的 committed Steps
→ refresh authoritative Workspace
→ invalidate stale Fact/Validation
→ 创建 Recovery/New Run（或接管 lease-expired RUNNING Run）
→ 获取新 fencing token
→ 继续执行
```

## 18. Memory 与 Projection

Task Memory、Context Summary、Repository/User/Experience Memory 都是 Projection，不替代 Recall Steps。

长期 Memory 必须保留 provenance：

```text
sourceTask/sourceRun/sourceSteps
artifactRefs
repo/user scope
revision/path evidence
evidenceDigest
status/confidence/lastValidatedAt
```

Memory 标记 STALE/SUPERSEDED 只更新 Projection 并追加对应 Step，不删除原始 Recall History。

## 19. 实现复用约束

后续实现不按“每张表一个 Service、每种 Step 一套流程”堆叠代码，而应复用以下稳定内核：

- 一个稳定的 `StepEnvelope`，不同事件只扩展版本化 payload/codec；
- 一个事务型 Event Appender，统一完成幂等检查、双序列分配、Step 插入、Projection mutation 与 Outbox；
- 一个 Artifact Store 端口，供 Tool Result、日志、Snapshot 和 Effect 共同使用；
- 一个 Session/Workspace 生命周期入口，Controller、Worker、Recovery 不得各自拼装可信上下文；
- Projection 通过按 stepType 注册的 handler 更新，避免在 Runtime 中复制持久化分支；
- 表级 Mapper/Repository 只负责数据访问，不为每张表再包装同构业务 Service；
- AgentRuntime 只依赖窄的 Journal/Context 端口，不感知 MySQL、Redis、RabbitMQ 或物理 Workspace Provider。

代码复用不能牺牲边界：Step append、Artifact、Workspace mutation 和 Context projection 仍是不同职责，不合并成全能 PersistenceManager。

## 20. 后续开发顺序

1. 冻结 Session/Workspace、Task/Run、ToolExecution 状态机；
2. 冻结 Step Envelope、schemaVersion、eventId、sanitization 与 digest；
3. 设计 Flyway schema 与数据库约束；
4. 实现 Session/Workspace provisioning 与 durable identity；
5. 实现 append-only Step 与事务型 Projection/Outbox；
6. 实现 immutable Artifact Store；
7. 实现 Tool causal lifecycle 与 UNKNOWN reconciliation；
8. 实现 Workspace Snapshot/Effect capture 与重建；
9. 接入 AgentRuntime，遵守 durable-before-context；
10. 实现 Checkpoint watermark、Replay 与 Recovery；
11. 实现 Task Memory、Context Assembly、Externalization 与 Compaction；
12. 接入 Redis、RabbitMQ Worker、长期 Memory 与 Hybrid Retrieval。

每一阶段必须在前一阶段的 durable truth 上增加能力，不复制 Runtime 循环，不让 Controller、Worker、Context 各自维护一套 Session 或 Workspace 状态。
