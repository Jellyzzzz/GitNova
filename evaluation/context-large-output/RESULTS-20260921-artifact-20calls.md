# readFile 参数修正＋20 次调用上限：真实多 Task 复测

## 结果

2026-09-21，再次通过真实 HTTP 客户端注册、登录、建仓库、CLI push、创建 Session/Task，经 MySQL/Outbox/RabbitMQ、Worker/Runtime、DeepSeek 和 Docker 完成四组实验。

**12/12 Run 达到 COMPLETED / FINISH_SUCCEEDED，78 次主模型调用，131 次工具调用，累计 1,613,472 tokens。** 本轮无摘要调用、usage 缺失或未返回的主调用。每个 Task 后的公开及隐藏代码验收均通过。

相比上一轮：

- 正常完成数：11/12 → 12/12。
- 仓库 readFile 缺少必填字段的调用：16 → 0；全部 readFile 调用数为 88 → 54，当前没有 readFile 错误。
- 主模型调用：95 → 78。
- 累计 tokens：2,309,279 → 1,613,472（-30.13%）。旧批包含一次 PARTIAL，因此不是两批都完成全部工作下的严格成本比。

**本轮最多只用了 9 次主模型调用；新增的第 15–20 次额度没有实际使用。** 不能说这次靠放宽调用上限才完成。更直接的改善证据是参数错误消失和执行路线缩短；但上限与 Schema 同时变化，无法完全拆分其对模型计划的影响。

## 冻结条件与比较口径

主对照是紧邻的 [Artifact 协议 14 次批次](RESULTS-20260921-artifact-protocol.md)，不是更早的旧 hash 回读协议。

- 三条任务文本、初始七个文件逐项 SHA-256 一致；Docker image 一致。四组各自新建 Session，组内共享同一个 Workspace，顺序执行修复、补测试、只读审核，没有重置或重跑。
- 模型仍为 deepseek-v4-flash，thinking disabled，temperature=0；总/读取超时 60 秒。
- 窗口 128,000，输出额度 4,096，安全余量 1,024，摘要/紧急处理阈值 0.8/0.9，保留近期 2 组。
- ToolResult 内联/预览预算 4,096/1,024，Artifact 回读预算 2,048、最多 200 行，均未调整。
- 实验配置只有 maxModelCalls 从 14 改为 20；maxToolCalls 保持 35。每条 Run 的 MySQL 冻结配置均核对过。
- 新 JAR 包含 readFile 三种参数形式的 anyOf/pattern 声明及本地校验。没有改读取提示、Run 状态投影、Workspace 或持久化语义。实验过程中没有再改代码或调整参数。
- 本轮仍是小代码、大日志的场景：初始七个文件约 8 KB、生产 Java 32 行、公开 160 个用例，不代表大型仓库能力。

固定 System/Tools 本地估算从 2,062 增至 2,574；新增参数约束不是零成本。最大输入 122,880，动态预算 120,306，80% 对应总输入估算约 98,819。实际本地峰值 49,901，Provider 峰值 49,969，没有触发摘要。

因此 A/C 实际都不摘要、不外置，B/D 实际都只外置；本轮不能评价摘要质量、压缩收益或触发阈值是否最优。

## Token 与完成度对比

以下都是 Provider input＋output usage 累计，不是窗口占用，也不是按缓存折扣计价后的费用。

| 组 | 配置 | 上轮 tokens | 本轮 tokens | 变化 | 上轮 → 本轮完成数 |
|---|---|---:|---:|---:|---:|
| A | 不外置、不摘要 | 543,892 | 477,642 | -12.18% | 3/3 → 3/3 |
| B | 外置 | 429,782 | 320,642 | -25.39% | 3/3 → 3/3 |
| C | 摘要开启，未触发 | 631,596 | 481,828 | -23.71% | 3/3 → 3/3 |
| D | 外置＋摘要开启，未触发 | 704,009 | 333,360 | -52.65% | 2/3 → 3/3 |

| 组 | Task 1 / 调用数 | Task 2 / 调用数 | Task 3 / 调用数 | 单次输入峰值 | Artifact 回读 | 外置投影 |
|---|---:|---:|---:|---:|---:|---:|
| A | 75,926 / 7 | 268,121 / 9 | 133,595 / 3 | 47,988 | 0 | 0 |
| B | 67,721 / 9 | 109,181 / 6 | 143,740 / 5 | 31,260 | 2 | 5 |
| C | 81,417 / 8 | 214,944 / 7 | 185,467 / 4 | 49,969 | 0 | 0 |
| D | 57,476 / 8 | 126,567 / 7 | 149,317 / 5 | 31,753 | 2 | 5 |

本轮输入合计 1,573,272、输出合计 40,200。各组 Run claimed→finished 累计耗时分别为 49.69、52.31、51.48、49.14 秒，不含排队、服务启动与独立验收。

同一批外置组约 32–33 万 tokens，不外置组约 48 万；这与外置降低日志反复发送成本的方向一致。但每组只有一次路径，新测试内容也不完全相同，不能将单次比例当作稳定算法收益或生产 SLA。

## 三个重点观察

### 1. readFile 的参数修正确实经过真实 Provider 验证

A 首次读文件就一次性正确提供六个仓库读取的 revision/startLine/endLine。四组均没有 SCHEMA_VALIDATION_FAILED 或 INVALID_READ_ARGUMENTS；新 Schema 被真实接口接受，不再只有 MockWebServer 证据。

这不保证模型永远不漏字段；原来的服务端分支校验、范围校验和可信资源校验仍需要保留。

### 2. D 不再逐页读完整日志

上轮 D 首任务用四页读完 162 行失败日志，随后继续读取并耗尽 14 次调用，没有发出 finishTask。本轮 D 首任务 8 次调用完成，未回读任何 Artifact；初始预览已展示全部七条 FAIL。

后续真正需要证据时，D 使用定向范围而不是完整扫描：

- Task 2：`artifact://tool-results/61/stdout.txt`，163–182 行，检查新增边界测试输出。
- Task 3：`artifact://tool-results/24/stdout.txt`，60–100 行，读取修复前的失败证据。

B 第三条 Task 分别读取同 Session `/24/stdout.txt` 的 1–30、64–95 行，核对列格式及失败订单。四次历史回读均成功，全部走统一 readFile，没有使用旧 hash 工具，没有虚构 ID。

B/D 首个失败日志预览仍显示 7/7 FAIL 原文。四组最终审核的代表性订单 expected/actual、初始 153/160 与当前 160/160 均与原始证据相符；没有观察到重新执行 BASE 后冒称原始历史的路径。这是代表性人工核对，不是对全部自然语言声明的自动正确性证明。

### 3. 仍有普通工具错误，但未破坏已确认修改

A 第二条 Task 创建边界测试后，更新 run-tests.sh 的一次 patch 未匹配当前文本，返回 PATCH_DOES_NOT_APPLY。失败操作没有写入：generationBefore=2、generationAfter=2。模型修正后完成，新增测试文件没有因为后续脚本 patch 失败而消失。

这是本轮唯一非 SUCCESS 的 ToolResult；其余三组没有工具错误，四组均没有 Harness 完成纠偏。
Run 状态投影按用户要求没有修改。本轮所有 Run 都成功，所以不能由此声称上轮「后续 Task 不知道前序 PARTIAL」的问题已修复。

## 独立验收与证据一致性

- 每条 Task 后独立执行公开 160 例和隐藏 Oracle 2,125 例，12 份验收全部通过。
- Task 1 只改两个授权生产文件；Task 2 只改测试入口并新增 EdgeRegression；Task 3 全部文件哈希不变。
- 每条后续 Task 的初始文件哈希等于上条结束时哈希。同 Session 没有重新物化 Workspace。
- 四个 Session 的 sequence 连续覆盖 1…92、1…100、1…93、1…104；78 次模型启动与响应对应，toolCallId 唯一、结果身份/名称匹配，无孤立结果。
- 10 次外置投影的 Artifact digest、size、原始 JSON 内容及源 Step 绑定全部通过核对；回读没有再生成新的 Artifact。
- 真实批次结束后，只在独立副本回植旧缺陷。模型新增测试都能检测两类原始回归，测试后的真实 Workspace 哈希也再次核对未变：

| 组 | 新增检查数 | 当前代码失败数 | 回植旧折扣失败数 | 回植旧运费失败数 |
|---|---:|---:|---:|---:|
| A | 19 | 0 | 5 | 6 |
| B | 17 | 0 | 5 | 5 |
| C | 21 | 0 | 5 | 6 |
| D | 19 | 0 | 6 | 6 |

四组均满足任务要求的至少 12 个检查。检查数不同是模型路径差异的一部分，不能把 B 更少的实现篇幅直接视为同等内容的纯压缩收益。

## 局限与复查入口

没有在运行中修代码、人工纠正模型或重跑样本。温度为 0 仍不保证跨次路径相同；两项改动一起上线，无法严格分离参数声明与预算提示的影响。未测试摘要、故障恢复、恶意跨 Session 读取或大型仓库。数据库 Workspace generation 仍为 0 的已知持久化缺口不在本次范围。

- 数据根目录：`/Users/zhaoguodong/code/gitnova-context-scenario2-20260918/live-artifact-20calls-20260921-gx5Y6M`。
- `matrix.json` 保存冻结条件及分 Task 结果；`comparison.json` 为相对上一轮的可复算审计；各组 `run-steps.json`、`followups/*/run-steps.json`、`verification/` 和 `edge-mutations/*/report.json` 保存原始证据。
- 实验配置：[scenario-multitask-128k-20calls.json](scenario-multitask-128k-20calls.json)。对比复用 [compare_artifact_protocol.py](compare_artifact_protocol.py)。
- 测试仓库 repoId=21。A Session `2ea8d54b-bafe-43b3-9fcd-582e70871c26`；B `7884b70e-1d36-4f71-8da2-174106a6d4d5`；C `efb6a5e9-0c29-4d74-b10d-dc260a0a4343`；D `01c43292-902d-4662-9e02-4664a8fcab9c`。
- JAR SHA-256：`1fb92a5dd9d8d4e934f4c0a098c470b1ba43b695f0da275dfb47d0cde916087c`。
- Docker image：`sha256:2236aa4655ce559a47e1f574e8253412c384adad937d254395a1b02542a2edea`。

本轮重新打包后运行真实矩阵及独立验收，没有重跑 JUnit、提交或推送。Spring 服务及测试容器均已停止，8080 无监听、MySQL 无 QUEUED/RUNNING Run。测试仓库、Session、Workspace、Artifact 和日志保留供复查；私有客户端凭据不写入报告。
