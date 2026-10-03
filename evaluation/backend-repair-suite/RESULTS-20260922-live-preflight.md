# 首轮真实服务预检

日期：2026-09-22。范围是 **四个仓库各一道题、每题一次**，不是 20 题/60 次正式批次。

完整证据：[batch.json](/Users/zhaoguodong/code/gitnova-repair-live-20260922-aHfSEy/round1/batch.json)。
每题目录包含 `local-cli.log`、`push.log`、`G80/submission.json`、`run.json`、`run-steps.json`、`workspace.json` 和独立 `verification/report.json`。账号凭据只在权限受限的 `.client-state.json`，不复制到报告。

## 条件

- 真实 MySQL / Redis / RabbitMQ / Spring Boot / DeepSeek API / Docker；没有用 JUnit、FakeModelGateway 或 SQL 写入假成功状态代替业务链。
- DeepSeek `deepseek-v4-flash`，主模型 thinking=max，摘要 thinking=high。
- context=128,000；摘要触发 0.8；强压缩触发 0.9，目标 0.6；保留最近 2 组。
- 每 Task 40 次模型调用、80 次工具调用；output=32,768，调用/read timeout=300 秒。
- 外置开启，inline=4,096、preview=1,024。以上实际 Task 的 MySQL 冻结配置均经客户端断言。
- jar SHA-256：`34f9c5f25604feb9499a16bd7273234e4bdfa5b3f825720b4fc92fb7a8a087a5`。
- Docker image：`sha256:2236aa4655ce559a47e1f574e8253412c384adad937d254395a1b02542a2edea`；实际执行与独立验收均按该 ID 固定。

## 结果

| 任务 | Run / 独立验收 | 模型调用 | 工具调用 | 累计 input | 累计 output | 总 token | 单次 input 峰值 | Run 秒 |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| stockroom-01：预留边界 | COMPLETED / ACCEPTED | 12 | 20 | 170,977 | 14,244 | 185,221 | 25,490 | 66.6 |
| eventstats-01：时区解析 | COMPLETED / ACCEPTED | 8 | 16 | 73,845 | 6,953 | 80,798 | 15,347 | 30.6 |
| bundlesync-01：路径校验 | COMPLETED / ACCEPTED | 13 | 22 | 197,248 | 15,527 | 212,775 | 27,582 | 67.2 |
| jobqueue-01：优先级 | COMPLETED / ACCEPTED | 9 | 16 | 85,805 | 5,637 | 91,442 | 14,652 | 26.6 |
| 合计 | **4/4** | **42** | **74** | **527,875** | **42,361** | **570,236** | **27,582** | **191.0** |

Run 秒取 claimedAt 到 finishedAt，不包含客户端建仓库/推送和独立验收耗时。
Token 来自主模型 response 的 provider usage，42 次 MODEL_CALL_STARTED 均有对应 MODEL_RESPONSE，没有缺失 usage。累计输入包含历史被多次带入的消耗，不是单次上下文占用，也没有换算成未缓存计费量或费用。

本轮没有产生 `TOOL_OBSERVATION_PROJECTED`、`CONTEXT_SUMMARY_RESULT` 或 `CONTEXT_SUMMARY_CREATED`：没有触发大结果外置、摘要或强压缩。因此不能据此评价上述策略的收益。

四题的 30 项公开检查与 682 项隐藏检查全部通过，新增 RegressionChecks 也均执行成功。每题最终仅修改允许的目标业务类与新增的 RegressionChecks，原有公开测试和 runner 未被改动。验收以行为为准，不比较是否与参考补丁逐字相同。

## 保留的失败与纠偏

共 **4 次非 SUCCESS 工具结果**，没有隐藏或重新创建 Run 替换它们；每题均只调用一次 finishTask。

| Task / Session sequence | 失败 | 实际含义 |
|---|---|---|
| stockroom-01 / 34 | PATCH_DOES_NOT_APPLY | UPDATE patch 未匹配当前原文，generation 仍为 0；随后改用 edit 完成修改。 |
| eventstats-01 / 29 | STALE_WORKSPACE_GENERATION | 新建回归测试仍传 expectedGeneration=0，Workspace 已为 1；被拒绝后以新状态继续。 |
| bundlesync-01 / 42 | INVALID_PATCH_OPERATION | DELETE 操作携带 content，违反工具参数契约；后续完成修正。 |
| jobqueue-01 | STALE_WORKSPACE_GENERATION | 同样出现旧 generation 的写请求，纠偏后完成。 |

BundleSync 还出现了一个有意义的过程：第一次新增测试运行输出 `REGRESSION checks=0 failed=0`；模型没有就此结束，随后修改测试，最终输出 `REGRESSION checks=16 failed=0`。这里不是独立验收器把答案反馈给模型，验收是在 Run 完成之后才执行。

上述现象支持“这四个样本中，拒绝错误工具调用后仍能完成修复”，不证明任意错误都可恢复。旧 generation 被拒绝也不等于测到了并发外部写入：本轮未注入并发修改。

## 后端事实核对

| repoId | Run | Task | Workspace generation | Workspace | writerRunId |
|---:|---|---|---:|---|---|
| 30 | COMPLETED / FINISH_SUCCEEDED | COMPLETED | 2 | READY | NULL |
| 31 | COMPLETED / FINISH_SUCCEEDED | COMPLETED | 2 | READY | NULL |
| 32 | COMPLETED / FINISH_SUCCEEDED | COMPLETED | 3 | READY | NULL |
| 33 | COMPLETED / FINISH_SUCCEEDED | COMPLETED | 2 | READY | NULL |

四个 `RUN_DISPATCH_REQUESTED` Outbox 均为 PUBLISHED；每个 Run 都有 RUN_CLAIMED、模型/工具记录和 RUN_COMPLETED。
Redis 实际出现本次 user/repo/API 限流 key 和仓库权限缓存 key；未额外验证 429、缓存失效竞态或 Redis 故障策略。

结束后 MySQL 活动 Run=0，服务进程 81520 已停止，8080 无监听，测试 Docker 容器已退出。测试仓库、Session、Workspace 与执行证据保留，未清理用户数据。

## 下一步边界

首轮证明四种小型代码任务可经过真实客户端 push → HTTP Session/Task → Outbox/MQ → Runtime → Docker 工具 → finishTask → 持久化 → 独立验收。
不证明 20 题整体通过率、三次重复稳定性、跨 Task 记忆、压缩质量或优于其他 Agent。
本轮 42 次调用、570,236 token 已构成可复盘的成本数据，但没有同题受控对照，不能直接归因于某个上下文设计。

本次仅增加预检脚本、usage 统计测试以及共享客户端对 compactTargetRatio 的断言；未为了通过本轮测试修改业务 Runtime、工具或测试题答案。
