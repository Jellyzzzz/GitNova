# 修复网关后的四组真实多 Task 测试

## 结论

2026-09-20，使用修复后的同一 JAR 顺序运行四个全新 Session，每个 Session 发起三条 Task：修复计价 → 新增边界测试 → 只读交付审核。
真实客户端注册/登录/建仓库/CLI push，再经 HTTP → MySQL/Outbox/RabbitMQ → Worker/Runtime → DeepSeek → Docker 执行。
没有插入伪造 Step、强制摘要、重置组内历史、人工修补摘要、失败后重跑到成功，或中途改变参数。

**12 条 Task 均已发起并结束，但没有一组完成全部三条 Task。**
本轮最明显的阻塞是单次输出上限 2,048（4 次截断），其次是未摘要历史的输入超限，以及一次真实的 10 秒读取超时。
不能将本轮结果解释为“已经找到最省 token 的策略”。

## 固定条件

- 模型别名 `deepseek-v4-flash`，thinking disabled，temperature=0；供应商内部路由/缓存未控制。
- contextWindowTokens=32,000；summaryTriggerRatio=0.8；compactTriggerRatio=0.9；keepRecentGroups=2。
- 安全余量 1,024，单次输出额度 2,048，每 Task/Run 最多 14 次模型调用、35 次工具调用。
- 内联/预览预算为 4,096/1,024，四组均保留相同的 readArtifact 工具定义。
- 仓库 repoId=17，repoKey=`10/17`；base commit=`abb532bb1836baf48b2d2c9f9a2e43795c49098a`。
- JAR SHA-256：`1c33053da5528660586b9e93eb822929a9c3de6504169f6c06ac1542a6bd02d3`。
- Docker image：`sha256:2236aa4655ce559a47e1f574e8253412c384adad937d254395a1b02542a2edea`。
- 初始 7 文件逐一校验 SHA-256 相同；每组第一请求都是 2 条消息，固定内容估算 1,956、总输入估算 2,109。
- 12 份冻结配置逐项核对：除外置/摘要开关外，运行参数一致。序列化格式 A/B/C 为 schemaVersion=4，D 为 3；这是现有 Codec 对“默认两个开关均开启”的兼容编码，不是另一套运行策略。

本配置输入硬上限为 `32,000 - 1,024 - 2,048 = 28,928`。
动态预算为 `28,928 - 1,956 = 26,972`，80% 摘要触发对应总输入估算约 23,534。
配置名中的 compact 阈值不意味着已实现独立强压缩算法；本轮没有强压缩成功事件。

## 结果及用量

tokens 来自已记录的 Provider usage，包含主模型和摘要模型的输入、输出；不代表账单金额。

| 组 | 外置 / 摘要 | Task 1 修复 | Task 2 补测试 | Task 3 审核 | 已观测总 tokens |
|---|---|---|---|---|---:|
| A | 关 / 关 | COMPLETED | PARTIAL：输入超限 | FAILED：发送前输入超限 | 130,362 |
| B | 开 / 关 | COMPLETED | PARTIAL：输出截断 | PARTIAL：输入超限 | 237,073 |
| C | 关 / 开 | COMPLETED | PARTIAL：输出截断 | PARTIAL：输出截断 | 233,732 |
| D | 开 / 开 | PARTIAL：输出截断 | COMPLETED | FAILED：读取超时 | 至少 347,073 |

| 组 | Task 1 tokens | Task 2 tokens（含摘要） | Task 3 tokens | 主调用发起 / 返回 | 摘要次数 / tokens | 外置 / Artifact 回读 |
|---|---:|---:|---:|---:|---:|---:|
| A | 81,780 | 48,582 | 0（未发送） | 10 / 10 | 0 / 0 | 0 / 0 |
| B | 125,828 | 82,909 | 28,336 | 15 / 15 | 0 / 0 | 3 / 4 |
| C | 101,771 | 70,587 | 61,374 | 16 / 16 | 1 / 19,604 | 0 / 0 |
| D | 125,746 | 221,327 | 未知 | 26 / 25 | 1 / 20,929 | 5 / 4 |

累计发起 67 次主调用，取得 66 次 response usage，另有 2 次摘要 usage。
**总已观测消耗至少 948,240 tokens。** D 的最后一次请求没有收到 response，不能把这次未知费用当作零。
A 的少量消耗来自提前停止，不是同等任务完成度下的优势。C/D 的摘要成本已计入，未从总量扣除。

## 跨 Task 接续与隔离

| 组 | Session | Workspace | Session Step 数 |
|---|---|---|---:|
| A | de4901ca-7e35-4145-906b-931a9f8bfc9e | 3587a953-1b35-4b8b-a36b-bd0e0c679296 | 62 |
| B | 3c94d525-3c19-463b-8737-24dd157d226e | 0f8526be-a131-403f-921c-1b04dafbca4e | 81 |
| C | fdc7aff8-5a99-4b6e-a8bb-8a03245e04e2 | 73df5414-60c1-4eb1-aa28-b1316eeea1cd | 85 |
| D | 06a092bb-c2a6-4f1c-bb02-506eead3dc46 | cb1e19ec-46d7-4334-9adb-9c793d41579f | 121 |

每组均只有一次 SESSION_CREATED/WORKSPACE_MATERIALIZED、三次 USER_MESSAGE_RECEIVED/TASK_CREATED；Step sequence 连续。
每个 Task 开始前的文件指纹与前一个 Task 结束后完全相同，未偷偷重置代码。每条任务的实际写入均在各自授权范围内。

Task 2 的首模型请求：A 27 条消息、22,491 输入估算；B 33 条、24,683；C 摘要后 7 条、8,483；D 32 条、20,948。
四组 Task 2 都从 generation=1 开始。D 写测试后推进到 generation=3，Task 3 首请求仍为 generation=3。
8 份外置记录均按 repoKey + Session namespace 验证文件大小和 SHA-256。

本轮测试后，数据库 Workspace 行仍为 generation=0，内存/工具事实已到 1 或 3。这是之前保留的持久化缺口，不算本轮修复；
组内没有重启 JVM，本轮不证明跨 JVM 恢复或完整 durable workspace。

## 摘要实际作用与质量边界

- C 在 Task 2 第一次主调用前：输入估算 **23,774 → 8,483**；摘要费用 19,604 tokens，覆盖源历史到 sequence=34。
- D 在 Task 2 第三次主调用前：输入估算 **24,034 → 5,536**；摘要费用 20,929 tokens，覆盖到 sequence=48。
- 两次均自然达到 80% 阈值，并非按“多一组就摘要”，也未调用额外模型替摘要打分。
- 两份新摘要都保留了首次 `160/153/7` 和修复后 `160/160/0`，本轮未再次观察到上次把 failed=7 改为 0 的问题。
- **仍不能称摘要正确性已验证**：C 对 ORD-077 写成 `100×50/100=50.5`，正确应为 `101×50/100=50.5`。
- D 将 Artifact ID 简写为 `5cdce…`、`d2e9…`，不能直接作为 readArtifact 的合法完整标识。
- D 摘要中的“前序任务已实际执行完毕”容易混淆“代码已修复且测试通过”与“Run 已 COMPLETED”；实际 Task 1 是 PARTIAL。当前历史投影未完整表达 Run 终止状态，这也限制了摘要能知道什么。

以上保持原样，没有在实验中人工纠正。检索/摘要的证据标识和确定性状态保留仍需设计，不能靠这两次摘要无计数错误就宣布解决。

## 独立代码和测试验收

四组 Task 1 后的代码、以及每条后续 Task 后的代码，均通过原公开 160 用例和独立隐藏 2,125 用例。
这只证明当前计价实现正确，**不代表 Task 2 新增测试或 Task 3 审核已完成**。

- A/B/C 没有生成 EdgeRegression，补测试任务未完成。
- D 真正新增 16 项边界检查，并接入 run-tests.sh；原批量回归先执行，新边界回归后执行，构建在 /tmp。
- D 新增检查独立运行：16 项，failed=0，exitCode=0。
- 隔离副本仅恢复旧 DiscountPolicy：新检查检出 4 个失败，exitCode=1。
- 隔离副本仅恢复旧 ShippingPolicy：新检查检出 5 个失败，exitCode=1。
- 反向检查没有改真实 Session 工作区，也没有把隐藏 oracle 发送给模型。

D 补测试期间还真实验证了两种纠偏：

1. applyPatch 创建测试文件成功，但脚本 UPDATE 不匹配；返回 PARTIAL_SUCCESS，generation 1→2，测试文件未回滚。模型随后重读并更新脚本，generation 2→3。
2. 首次 finishTask 未包含最新成功命令，被 CompletionInspector 拒绝；模型追加验证并再次 finishTask 后完成。

因此 D 的 221,327 tokens 还包含补丁失败和完成声明纠偏成本，不能全部归因于摘要/外置算法。

## 网关新证据与下一步

4 次 LENGTH 的 outputTokens 均为 2,048，工具调用列表为空；残缺工具未执行，usage 有记录，后续 Task 能继续读取 Session。
这真实验证了本轮修复后的截断分类与跨 Task 投影，不证明上次旧日志中的网关失败一定也是截断。

D Task 3 第一次请求的日志：

```text
runId=1ee99b1e-a90b-4d23-83a2-1bf989216d2d
errorCode=TIMEOUT, httpStatus=null, retryable=true
causeType=SocketTimeoutException
claimedAt → finishedAt = 10.114s
```

网关构造器只配置 callTimeout=60s；本机 OkHttp 4.12.0 源码中 Builder 默认 readTimeout=10,000ms。
**60 秒的整个请求上限并未取消独立的 10 秒读取超时。** 此次观测与默认读取超时一致；没有响应头，无法从日志进一步断定供应商后台是否实际完成或计费。

下一步应先明确并配置网关 readTimeout，再单独扩大 maxOutputTokens（例如 4,096）重测编码/交付任务；相应输入预算会自动减少。
不应先用这批受输出截断和超时干扰的结果决定最优摘要阈值。没有在本轮中途修改这些参数或自动重跑失败样本。

## 复现与交付

新增 scenario-multitask-32k.json、两条状态中性的后续任务、run_multitask.py 和 verify_edge_mutations.py，复用原 live_client.py / verify.py。
live_client 增加显式 `--continue-after-failure`：允许对已终止的 PARTIAL/FAILED 前项发起不同后续 Task；默认仍拒绝，不会重放旧 Tool Call。
保存每条 Task 前后文件指纹，并兼容 usage 指标缺失。没有新建生产类或修改本轮生产算法。

加载本地密钥与 MYSQL_PWD、确认 8080 空闲且无活动 Run 后：

```sh
python3 evaluation/context-large-output/run_multitask.py \
  --output <新的私有结果目录> \
  --repository /Users/zhaoguodong/code/gitnova-context-scenario2-20260918/repository-calibrated
```

本轮证据目录：`/Users/zhaoguodong/code/gitnova-context-scenario2-20260918/live-multitask-32k-20260920-rUq8rC`。
matrix.json 包含固定条件与每条 Task 汇总；各组/后续目录有提交结果、冻结配置、原始 Steps、前后指纹、服务日志和独立验收报告。
`.client-state.json` 包含凭据，权限 600，不进入仓库或公开报告。

构建、Python 语法和参数校验通过；本轮未增加 JUnit、未重新执行上一轮的 572+29 单测。
测试结束后查询 QUEUED/RUNNING=0，所有本轮 Spring Boot 服务正常关闭，8080 已空闲；测试仓库、Session 和证据均保留，未提交或推送代码。
