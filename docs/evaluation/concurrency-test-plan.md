# GitNova 高压力并发与故障测试设计

状态：设计稿，待审核；尚未实现、尚未运行、没有实测结果。

## 1. 目标

本计划不证明“GitNova 有很高 QPS”，而是验证并量化以下工程能力：

1. 同一 Run 在重复调度和多 Worker 竞争下只有一个权威执行者。
2. Lease 过期、Heartbeat 和 Takeover 竞争时不会产生双重 authority。
3. 新 fencing token 生效后，旧 Worker 的数据库终态写入和 Workspace 变更均被拒绝。
4. 同一 Session 的 Workspace 始终只有一个 writer；不同 Session 可以并行推进。
5. Step 双序列和 Transactional Outbox 在高争用下不丢失、不分叉、不产生语义冲突。
6. RabbitMQ 重复投递、Publisher confirm 歧义和短时不可用不会造成双重执行权。
7. Git 对象并发发布不覆盖已有内容，分支 CAS 在并发 Push 下只接受一个合法胜者。
8. 给出吞吐、延迟、错误率、锁等待、恢复时延和资源使用，而不是预先编造性能数字。

## 2. 测试边界

### 2.1 本轮覆盖

- MySQL Task/Run/Session/Workspace 状态机
- Run claim、heartbeat、lease expiry、takeover、terminal transition
- Session sequence 与 Run step sequence
- Transactional Outbox publisher
- RabbitMQ dispatch redelivery
- Local Workspace JVM 锁、跨进程文件锁、generation 和 fencing token
- Local ObjectStorage 原子发布
- Branch head CAS

### 2.2 本轮不覆盖

- `execution_config_json` 完整冻结契约的编解码与摘要正确性；已由 Codec/Store 测试覆盖，本轮不重复作为并发测试对象
- 尚未完成的 MODEL_CALL/TOOL_RESULT Durable Journal crash matrix
- Redis、Memory、RAG
- 真实 LLM 并发吞吐
- Agent 任务质量评测

后端压力测试统一使用 `FakeModelGateway` 或可控的阻塞 Runtime，避免把模型延迟、费用和限流混入后端指标。

## 3. 当前实现对应的并发权威边界

| 边界 | 当前机制 | 需要证明的性质 |
|---|---|---|
| 同一 Task 的活动 Run | `uk_agent_run_one_active_per_task` | 最多一个 QUEUED/RUNNING Run |
| 同一 Workspace writer | `writer_run_id` + Workspace 行锁/CAS | 同一 Session 最多一个写 Run |
| Run 执行权 | `lease_owner` + `lease_until` | 未过期 Lease 只有当前 owner 可续租/终止 |
| Epoch 隔离 | 单调 fencing token | takeover 后旧 token 永久失效 |
| 状态迁移 | Session → Task → Run → Workspace 固定锁序 | 无死锁或部分提交 |
| 事件顺序 | Session sequence + Run step sequence CAS | 连续、唯一、无分叉 |
| 异步派发 | DB transaction + Outbox | DB 提交后最终可派发 |
| MQ 消费 | manual ACK + claim/takeover | 重复消息不产生第二执行权 |
| Workspace mutation | JVM write lock + 文件锁 + generation/fence | 同 Workspace 写入串行、旧写者被拒绝 |
| Git 对象 | content address + no-overwrite atomic publish | 同 SHA 并发发布结果一致 |
| Branch 可见性 | `head_commit = expectedHead` CAS | 同一 base 只有一个 Push 胜出 |

## 4. 测试分层

### 4.1 Race Correctness Suite

使用 JUnit 5、`ExecutorService`、`CountDownLatch`/`Phaser` 同时释放线程；连接真实 MySQL，必要场景连接真实 RabbitMQ。每个场景结束后直接查询数据库和文件系统验证不变量。

特点：

- 追求可重复的竞争窗口。
- 任意一次 invariant violation 都立即失败。
- 不使用平均值掩盖偶发错误。
- 随机化线程顺序，但记录 seed，失败可复现。

### 4.2 Throughput/Contention Benchmark

使用独立 Spring Boot benchmark runner，直接调用 Store/Publisher/Workspace 边界。采集 HdrHistogram 或 Micrometer Timer，不把高负载循环写成普通单元测试。

特点：

- 预热和正式采样分离。
- 报告 p50/p95/p99/max，不只报告平均值。
- 默认配置和调优配置分别测量。
- 第一轮只建立 baseline 和容量拐点；审核实测结果后再冻结性能门槛。

### 4.3 Failure Injection Suite

在明确 crash window 注入进程退出、连接中断、confirm 延迟、RabbitMQ 停机和旧 Worker 继续写入。测试目标是 correctness 和 recovery time，不是 QPS。

### 4.4 Soak Test

持续 2～6 小时运行混合负载，关注连接泄漏、线程积压、Outbox backlog、Heartbeat 延迟和磁盘临时文件泄漏。

## 5. 压力等级

以下数字是负载设计，不是性能承诺。

| 等级 | 用途 | 建议规模 |
|---|---|---|
| PR | 快速正确性回归 | 每个 race 50～100 轮，16～64 并发线程 |
| Nightly | 高争用验证 | 单热点最高 1,000 竞争者；关键 race 1,000～10,000 轮 |
| Capacity | 容量曲线 | 1/10/50/100/200/500/1,000 并发客户端 |
| Soak | 稳定性 | 2～6 小时，持续 50%～70% 已测饱和负载 |

容量测试必须区分两条 Lane：

1. `default-config`：Hikari 10 连接、Heartbeat scheduler 4 线程、现有 Rabbit listener 配置。它回答“当前默认配置实际能扛到哪里”。
2. `scaled-config`：增加 DB pool、Listener concurrency、Heartbeat scheduler 后测试架构上限。每个调优值必须随结果记录，不能把调优结果冒充默认能力。

## 6. 核心测试场景

### C1. 单 Run 千 Worker 并发 Claim

准备一个 QUEUED Run，1,000 个不同 `workerId` 在同一 barrier 后调用 `claimRun`。

Nightly：1,000 轮，共 1,000,000 次 claim attempt。

必须满足：

- 每轮恰好一个 `CLAIMED`。
- Run、Workspace writer 和 fencing token 指向同一胜者。
- 只有一个 `RUN_CLAIMED` Step。
- Session/Run sequence 连续。
- 不出现死锁、部分提交或 Workspace writer 残留冲突。

记录：claim p50/p95/p99、DB lock wait、deadlock count、连接池等待、每轮完成时间。

### C2. 同 Session 多 Task 竞争 Workspace

在同一 Session 下准备 100 个不同 Task/Run，同时 claim。

当前架构语义应为：Task 可各自拥有活动 Run，但 Session 只有一个 Workspace writer，所以同一时刻只能有一个 Run 获得写 authority。

必须满足：

- `agent_workspace.writer_run_id` 最多一个。
- 非胜者不能得到有效 mutation authority。
- 胜者终止并释放 writer 后，下一个 Run 才能进入。
- 不把“同 Session 串行”误报为系统整体并发能力。

### C3. 多 Session 独立 Run 吞吐

准备 10,000 个 Session，每个 Session 一个 Task/Run；使用 256～1,000 个客户端线程并发完成 claim → heartbeat → terminal 的短链路。

阶梯负载：1、10、50、100、200、500、1,000 并发；每档预热 60 秒、采样 5～10 分钟。

记录：

- 完成链路 ops/s
- claim/heartbeat/terminal p50/p95/p99
- Hikari active/pending/timeout
- MySQL row lock time、deadlock、CPU、IOPS
- Session sequence append throughput
- 错误码分布

### C4. Heartbeat 与 Lease Expiry 临界竞争

把 lease 设在 DB 当前时间附近，使用 barrier 同时发起：

- 当前 owner heartbeat
- 一个或多个 `recordLeaseExpired`
- 多个 RecoveryScanner 实例扫描

每个 jitter 档位运行 10,000 轮：expiry 前 50ms、10ms、0ms、后 10ms、50ms。

合法结果只有两类：

1. Heartbeat 先成功：expiry 不得被记录。
2. Expiry 先被权威确认：旧 heartbeat 必须返回 `LEASE_LOST`。

禁止出现 heartbeat 成功且同一 fence 同时被记录为 expired。

### C5. Recovery Scanner 与重复 Recovery Message 风暴

准备 10,000 个已过期 Run，启动 8 个 Scanner 实例；对每个 recovery dispatch 再复制投递 100 次。

必须满足：

- 每个 `(runId, expiredFence)` 只有一个语义等价的 expiry event/outbox identity。
- 每个 Run 只有一个 `TAKEN_OVER`。
- 新 fence 恰好等于旧 fence + 1。
- 重复消息只产生 ACK/幂等结果，不产生第二次执行 authority。
- Scanner backlog 最终归零。

记录：scan throughput、recovery dispatch lag、takeover p99、duplicate delivery ratio、无效 takeover 数量。

### C6. Stale Fence 写入风暴

完成 fence N → N+1 takeover 后，让旧 Worker 用 fence N 持续提交：

- heartbeat
- terminal transition
- Workspace applyPatch
- Workspace runCommand

同时新 Worker 使用 fence N+1 正常推进。每类旧操作至少 100,000 次。

必须满足：

- stale heartbeat 100% 返回 `LEASE_LOST`。
- stale terminal 100% 被拒绝。
- stale Workspace mutation 100% 返回 fence conflict。
- 旧写者不能改变文件、generation、Run status、Task status。
- 新写者不因旧请求而失去 authority。

注意：当前通用 `AgentEventAppender` 本身不接收 fencing token，本计划不声称它可以独立拒绝 stale append；这里只验证当前实际暴露的 Store/Workspace 权威入口。

### C7. Terminal Transition 竞争

同一 RUNNING Run 同时收到 500 个 terminal 请求，覆盖：

- 完全相同的幂等重试
- 不同 outcome
- 不同 termination reason
- 旧 fence 和当前 fence 混合
- terminal 与 takeover 同时发生

必须满足：

- 只有一个终态语义被提交。
- 相同重试返回同一投影。
- 不同语义重试被拒绝。
- Run、Task、Workspace 三个投影要么全部提交，要么全部回滚。
- writer release 后 fencing token 被单调撤销。

### C8. Step 双序列热点争用

在同一 Session 中并发 append 100,000 个不同 event；同时对其中 10% event 发送 10 次相同重试，并对 1% event 使用相同 eventId、不同 payload 制造冲突。

必须满足：

- 相同 eventId/相同 digest 只对应一个 Step。
- 相同 eventId/不同语义 100% 被拒绝。
- `session_sequence` 从 1 到 `last_session_sequence` 连续无洞。
- 每个 Run 的 `run_step_sequence` 连续无洞。
- committed Step 数与 projection sequence 完全一致。

该场景会刻意打满 Session 行热点，用于量化单 Session 的序列化成本；跨 Session 吞吐必须单独测试。

### C9. 多 Publisher 与 Confirm 歧义

准备 100,000～1,000,000 条 PENDING Outbox，启动 8 个应用实例或 8 个 Publisher。注入：

- confirm 延迟 4 秒
- ACK 后、`markPublished` 前进程退出
- NACK、return、confirm timeout
- 多 Publisher 同时读取同一批 PENDING 行

当前查询没有行级 claim/`SKIP LOCKED`，因此重复 publish 是允许且预期被观察到的；验收重点不是 duplicate=0，而是：

- 不丢失已提交 Outbox。
- 最终每条合法 Outbox 进入 PUBLISHED。
- 重复发布不会产生第二执行 authority。
- `attempt_count`、backoff 和 backlog 最终收敛。
- 非法 payload 被隔离，不阻塞后续合法事件。

记录：publish throughput、confirm latency、duplicate rate、backlog lag p95/p99、drain rate。

### C10. RabbitMQ 停机与恢复

混合负载运行时执行：

1. RabbitMQ 停机 30 秒、2 分钟、10 分钟。
2. 网络延迟 100/500/2,000ms。
3. 丢连接、重启 broker、重建 consumer connection。
4. 对同一 dispatch 重复投递 100 次。

必须满足：

- MySQL 已提交 Task/Run/Outbox 不回滚、不丢失。
- broker 恢复后 backlog 最终 drain。
- 同一 Run 仍最多一个 owner。
- 恢复期间不会因 Publisher 重试导致无限热循环。

### C11. Workspace 同进程与跨进程争用

两条子 Lane：

1. 单 JVM：128 reader + 32 writer，同一 Workspace，验证 fair read/write lock、generation CAS 和无 torn write。
2. 8 JVM：共同挂载同一 Workspace 目录，每进程 16 writer，验证 mutation fence file 和 atomic replacement。

负载包括 applyPatch、runCommand、refreshWorkspace、readFile、getWorkspaceDiff。

必须满足：

- 同一 Workspace 同时最多一个 mutation critical section。
- 每次成功 mutation generation 单调增加。
- stale generation 不修改文件。
- stale fence 不修改文件。
- 文件最终内容必须对应某个完整成功操作，不允许半写入。
- 不遗留临时文件或损坏的 mutation fence。

跨进程读写一致性目前不是数据库 Lease 的替代物；若读操作能观察到外部进程 mutation 的中间态，应作为测试发现的问题单独评审，不能静默忽略。

### C12. Git 对象发布与 Branch CAS

对象场景：1,000 个线程/进程同时发布同一 SHA 内容；再并行发布不同对象；另外注入同 SHA 不同内容的错误候选。

Branch 场景：1,000 个 Push 使用相同 base、不同合法 target 同时更新同一 branch；另测不同 branch 和不同 repo 的并行能力。

必须满足：

- 同 SHA 最终只有一份完整对象，内容 digest 正确。
- 相同内容重试幂等。
- 冲突内容不能覆盖已发布对象。
- 同一 `(repo, branch, base)` 恰好一个 CAS 胜者。
- 失败 Push 的对象可以不可达，但 branch 不得指向未完整验证的对象。
- 不同 branch/repo 不应被不必要地全局串行化。

## 7. 故障注入矩阵

| 故障窗口 | 注入方式 | 核心验证 |
|---|---|---|
| Task/Run/Outbox COMMIT 后、publish 前 | kill application | 重启后最终派发 |
| Rabbit ACK 后、markPublished 前 | kill publisher | 允许重复，不允许丢失/双执行 |
| claim COMMIT 后、MQ ACK 前 | 断开 consumer | 重投递不产生第二 owner |
| MQ ACK 后、Runtime execute 前 | kill worker | Lease 过期后 recovery takeover |
| Heartbeat SQL 执行前后 | 网络阻断/延迟 | expiry 与 heartbeat 结果互斥 |
| takeover 后旧 Worker 恢复 | 暂停/恢复旧进程 | fence N 写入全部失败 |
| Workspace 原子替换前后 | kill writer | 文件为旧版或完整新版，不半写 |
| Object staging/promote 前后 | kill process | target 不可见或完整可见 |
| Branch CAS 前 | kill process | branch 保持旧 head |
| Branch CAS 后、事件发布前 | kill process | 单独评估 after-commit 事件恢复边界 |

最后一项当前没有 Transactional Outbox 保护 PostReceiveEvent，需要作为已知边界记录，不提前宣称 crash-safe。

## 8. 全局不变量审计

每轮结束必须执行独立审计器，而不是只相信请求返回值。

示例检查：

```sql
-- 一个 Task 不得有多个活动 Run。
SELECT task_id, COUNT(*)
FROM agent_run
WHERE status IN ('QUEUED', 'RUNNING')
GROUP BY task_id
HAVING COUNT(*) > 1;

-- Workspace writer 必须与同 Session 的 RUNNING Run 对齐。
SELECT w.workspace_id, w.writer_run_id, r.status,
       w.last_accepted_fencing_token, r.current_fencing_token
FROM agent_workspace w
LEFT JOIN agent_run r ON r.run_id = w.writer_run_id
WHERE w.writer_run_id IS NOT NULL
  AND (
      r.run_id IS NULL
      OR r.session_id <> w.session_id
      OR r.status <> 'RUNNING'
      OR r.current_fencing_token <> w.last_accepted_fencing_token
  );

-- Session sequence 不得有洞。
SELECT session_id, COUNT(*) AS step_count, MAX(session_sequence) AS max_sequence
FROM agent_step
GROUP BY session_id
HAVING COUNT(*) <> MAX(session_sequence);

-- Run sequence 不得有洞。
SELECT run_id, COUNT(*) AS step_count, MAX(run_step_sequence) AS max_sequence
FROM agent_step
WHERE run_id IS NOT NULL
GROUP BY run_id
HAVING COUNT(*) <> MAX(run_step_sequence);
```

还需审计：

- eventId 唯一且 digest 稳定
- terminal Run 没有 lease owner/lease until
- terminal/partial/failed Task projection 与 currentRun 一致
- Outbox PENDING/PUBLISHED/FAILED 状态字段满足约束
- Workspace generation、fingerprint、fence file 一致
- 对象存储不存在损坏或残留 staging 文件
- branch head 指向完整可解码 Commit 及其全部 Blob

## 9. 指标与报告格式

每次测试报告必须记录：

- Git commit、工作树状态
- CPU、内存、磁盘类型、OS/JDK
- MySQL/RabbitMQ 版本与配置
- 应用实例、Publisher、Consumer、Worker 数量
- Hikari pool、Heartbeat pool、listener concurrency
- 数据集规模、并发度、预热、持续时间、随机 seed
- throughput、p50/p95/p99/max
- success/conflict/retry/error 分布
- DB lock wait、deadlock、connection wait
- Outbox backlog、delivery lag、duplicate rate、drain rate
- JVM CPU、heap、GC pause、thread count
- correctness invariant violation 数量

Correctness 门槛从第一天就固定为：

```text
double authority = 0
stale fence accepted = 0
lost committed outbox = 0
sequence gap = 0
partial terminal projection = 0
corrupt/torn workspace write = 0
invalid branch CAS winner count = 0
```

吞吐和延迟不在设计阶段伪造阈值。第一轮实测生成容量曲线，找到 p99 急剧上升、错误率出现或资源饱和的拐点，再由审核决定简历可写的稳定并发档位。

## 10. 预期首先暴露的当前瓶颈

这些是基于代码结构的待验证假设，不是测试结果：

1. 默认 Hikari pool=10 会先限制高并发数据库链路。
2. Heartbeat scheduler=4 在大量长 Run 和数据库阻塞时可能出现调度延迟。
3. Rabbit listener 未配置 concurrency，且 `consume` 内同步执行 Runtime；单实例 dispatch 并行度可能接近 1。
4. Outbox batch=100、轮询 1 秒，多 Publisher 无行级 claim，confirm 延迟时会产生重复发送。
5. Session sequence 需要锁 Session 行，同 Session append 是天然热点。
6. 同 Session 只有一个 Workspace writer，因此可扩展并行单位主要是 Session，而不是同 Session 的多个 Run。
7. Workspace 跨进程 mutation 有文件锁，但跨进程 reader 没有对应的共享文件读锁，需要实测其观察一致性。

测试必须保留这些默认瓶颈的结果；不能只展示调优后的漂亮数字。

## 11. 建议目录与交付物

```text
benchmarks/
├── agent-contention/
├── outbox-rabbit/
├── workspace-contention/
└── git-hosting-contention/

src/test/java/com/gitnova/concurrency/
├── RunClaimRaceMySqlTest.java
├── HeartbeatTakeoverRaceMySqlTest.java
├── StaleFenceWriteMySqlTest.java
├── StepSequenceContentionMySqlTest.java
├── OutboxRabbitFailureTest.java
├── WorkspaceMultiProcessContentionTest.java
└── BranchHeadCasContentionMySqlTest.java

docs/evaluation/
├── concurrency-test-plan.md
├── methodology.md
└── results/
    └── <commit>-<environment>.md
```

测试实现应分成独立提交：Harness、MySQL races、Rabbit faults、Workspace contention、Git hosting contention、结果文档。不要把测试基础设施和生产修复混成一个提交。

## 12. 审核时需要确认的决策

1. 是否同意把“并发扩展单位主要是 Session”作为当前架构边界明确写入报告。
2. 是否同意同时保留 default-config 和 scaled-config 两套结果。
3. Nightly 上限是否采用单热点 1,000 竞争者、关键 race 10,000 轮。
4. 是否把 Git ObjectStorage/Branch CAS 纳入本轮“项目抗并发能力”，还是只先做 Agent Backend。
5. 第一轮是否只冻结 correctness 门槛，性能门槛等拿到容量曲线后再定。
