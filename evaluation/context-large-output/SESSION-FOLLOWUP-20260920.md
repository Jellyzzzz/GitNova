# 同一 Session 的真实连续任务验证

## 结果

沿用已完成计价修复的 Session，不重建 Workspace，不清空历史，不切换模型配置。
真实 HTTP 新建两条后续 Task，经原 MySQL/Outbox/RabbitMQ/Worker/Runtime 链执行。
这次不增加对照组，也不强制摘要。继续使用 32k、80% 摘要、90% 后续处理阈值及原外置策略。

| Task | 内容 | 结果 | 主调用发起/返回 | 摘要 | 已观测 tokens |
|---|---|---|---:|---:|---:|
| 1（此前已完成） | 修复折扣与运费 | COMPLETED | 10/10 | 0 | 101,393 |
| 2（本次新增） | 补边界回归并接入脚本 | COMPLETED | 6/6 | 1 | 114,042 |
| 3（本次新增） | 只读交付审核、完整回归 | PARTIAL：MODEL_GATEWAY_FAILURE | 5/4 | 0 | 至少 67,165 |

本次新增已观测消耗至少 **181,207 tokens**；包含前序 Task 1 的 Session 合计至少 **282,600**。
Task 2 的 114,042 = 主模型 93,142 + 摘要模型 20,900。
Task 3 最后一次调用缺少 response usage，不能按零费用计算。原失败记录保留，没有盲目续跑直到成功。

## 真正验证了什么

- 三条 Task 的 sessionId 都是 `02dbdac8-db4d-4033-b295-cb5924cb4710`。
- 三条 Task 共用 workspaceId=`192e8ee8-3997-46ee-81d3-621063feb50b`，Session 创建和 Workspace 物化都只有一次。
- Task 2 的初始文件 SHA-256 与 Task 1 独立验收副本一致，不是原始有缺陷的文件树。
- Task 2 首请求已有 31 条消息，而非新的 system/user 两条；上下文覆盖到 sessionSequence=53，Workspace generation=1。
- Task 2 两次 applyPatch 分别新增 EdgeRegression.java、更新 run-tests.sh，generation 1→2→3；其余文件逐文件哈希不变。
- Task 3 首请求从 sessionSequence=88 的 Session 投影继续，15 条消息，generation=3；此前 Task 2 的摘要保留在 Session 历史中。
- Task 3 完整测试命令 exitCode=0，generation 3→3；任务前后文件哈希完全一致，没有发生写入。
- Session 共 3 个 USER_MESSAGE_RECEIVED、3 个 TASK_CREATED、3 个 RUN_QUEUED；截至结束 110 条 Step，sequence 连续。

这些支持“跨 Task 的历史、摘要与当前文件树接续已经运行”，但不支持“交付审核全部完成”或“历史回忆质量已通过”。

## 摘要自然触发

Task 2 前几次请求估算为 19,190 → 21,478 → 23,316，随后达到 23,797。
固定内容估算为 1,956，动态预算为 `32,000 - 1,024 - 2,048 - 1,956 = 26,972`，
因此触发时比例 `(23,797 - 1,956) / 26,972` 约 80.98%，符合实际配置。

摘要被接受后，下一份请求估算为 5,725；这只是上下文估算变化，不是 Provider 直接报告的节费量。
摘要 ID：`4dda1f42-3e9c-40b6-9474-397c5d324f22`，覆盖原始历史到 sessionSequence=59。
后续两组原文包含新增测试与脚本修改，不被摘要覆盖。

发现一处明确的摘要事实错误：

```text
原始首次测试：cases=160 passed=153 failed=7
摘要写为：    cases=160 passed=153 failed=0
摘要同时又列举了 7 个 FAIL 订单。
```

元数据/预算校验通过，不等于语义事实正确。没有手工修补这份摘要，也没有把它当成已验证事实。
摘要中“尚未创建测试/尚未修改脚本”描述的是所覆盖旧前缀，当时后续修改仍在保留的原文组中；
这一时间范围问题与上面错误的 failed 数字要区分，不能混为一谈。

## 新增测试的独立验收

模型在 Session Workspace 中新增 `src/test/java/pricing/EdgeRegression.java`，更新 `run-tests.sh`。
测试脚本保持先跑原 BatchRegression，再跑 EdgeRegression，`set -eu`、构建输出在 /tmp。

独立 Docker 验收：

- 原批量回归：160/160。
- 新增边界检查：19/19，检查真实金额与四类 IllegalArgumentException。
- 独立隐藏用例：2125/2125。
- 在隔离副本中恢复旧 DiscountPolicy：新 EdgeRegression 检出 5 个失败，exitCode=1。
- 单独恢复旧 ShippingPolicy：检出 6 个失败，exitCode=1。

反向验证没有修改真实 Session 工作区，证明新测试并非仅打印成功。
一个轻微可读性问题仍在：名为 shipping-one-below 的测试注释写净额 4999，实际参数得到 4950；
4999 的实际场景由 round-half-up-large 覆盖。不能仅凭测试通过就声称测试命名/注释完全准确。

## Task 3 的阻塞位置

Task 3 已执行 diff/源码读取、完整回归、CSV 查阅及 Artifact 范围读取。
最后一次请求估算 20,162，低于本配置的摘要触发点及输入上限，随后 ModelGateway 抛异常。
仅有 MODEL_CALL_STARTED，没有 MODEL_RESPONSE，也没有 finishTask/COMPLETION_DECISION。

现有 Runtime 的网关异常 catch 只返回 MODEL_GATEWAY_FAILURE，未记录具体 errorCode、HTTP 状态或原因。
因此无法从当前日志判断是传输、供应商返回、输出长度还是响应解析问题，不能归因于上下文预算或摘要。
服务仍正常，已无 QUEUED/RUNNING Run；Task 3 进入 WAITING_USER。

接下来最小的工程修正应先让网关失败可诊断，再继续同一 Session 的审核；摘要数值失真也必须明确处理。
本次不改数据库、持久化语义或生产模型策略，未验证 JVM 重启恢复。

## 客户端接线与使用

复用 `live_client.py`，新增 `--follow-up` 和 `--task-file`：

1. 必须已有 Session 和初始 Task，不偷偷新建 Workspace。
2. 每个 follow-up 名称对应一条逻辑 Task，绑定消息 digest 和稳定幂等键。
3. 消息改变必须用新的 follow-up 名称；已存在的 Task 只查询，不再次提交。
4. 默认要求已记录的前序 Task 全部 COMPLETED，避免无声跳过失败。
5. 仅首次 Task 校验原始文件树/generation=0；后续保存开始时的真实文件 manifest。
6. steps.json 保留 Session 历史，run-steps.json 仅保留本 Run；usage 按 Run 统计，避免反复计算旧 Task。

```sh
python3 evaluation/context-large-output/live_client.py run \
  --arm D --output <包含现有 Session 的私有客户端状态目录> \
  --repository <原本地 Gitlet 仓库> \
  --scenario evaluation/context-large-output/scenario-32k.json \
  --follow-up regression \
  --task-file evaluation/context-large-output/followups/02-regression.md
```

Task 3 使用 follow-up=`delivery-review` 和 `followups/03-delivery-review.md`。
脚本并不是生产 Session API；新 Task 仍由现有 HTTP application boundary 创建。
verify.py 增加 `--followup-tests`，允许本任务指定的新增测试/脚本布局；另以逐 Task manifest 核对修改范围。
没有新增生产 Service/helper，没有重编译或变更正在运行的 JAR。

## 证据位置

数据目录：`/Users/zhaoguodong/code/gitnova-context-scenario2-20260918/live-session-followup-20260920-qLQFVo/`。

- lineage.json：原 Task 1 与已验证文件树。
- task-comparison.json：逐 Task 统计。
- D/followups/regression/：Task 2 请求、journal、摘要、usage、文件前后状态和独立/反向验证。
- D/followups/delivery-review/：Task 3 失败轨迹、usage 与文件不变证据。
- server-log-snapshot.log：服务日志快照；凭据仅存权限 600 的 .client-state.json。

Task 2 Run：`d25a1c7e-5909-446b-aeac-e3ee593eabab`。
Task 3 Run：`7efe4e05-58fe-4286-a4e7-93153cbc10b9`。
服务仍在 8080（PID=81727），未提交或推送 GitNova 代码。
