# 统一 Artifact 读取协议：真实服务复测与 128k 基线对比

## 结论

2026-09-21，沿用 2026-09-20 的 128k 四组、每组三条 Task 场景，验证新的文本视图、确定性预览及 `readFile(artifact://...)` 协议。

**11/12 Run 为 COMPLETED，1/12 为 PARTIAL；95 次主模型响应，累计 2,309,279 tokens，0 次摘要尝试。** 每条 Task 后的独立代码验收均通过，但代码通过不能替代 Run 完成状态。

确定的改进是：失败日志首屏从看不到中间 7 条 FAIL，变成 7/7 可见；模型能用完整逻辑路径跨 Task 回读原始日志，无需复制哈希。不能据此宣布所有路径都更省：B 比上次少 23.97%，D 略多且首个 Run 耗尽调用预算。

## 测试流程与冻结条件

真实客户端注册、登录、创建仓库、CLI push → HTTP 创建 Session/Task → MySQL、Outbox、RabbitMQ → Worker/Runtime → DeepSeek → Docker 执行。

- 每组新 Session/Workspace；组内顺序执行「修复两处生产缺陷 → 增加边界测试 → 只读交付审核」。不重启组内服务、不重置文件或历史。
- 同一批中没有人工指导模型、修改生产代码、重试失败 Task 或调整参数。D 首 Task 为 PARTIAL 后仍按原协议提交不同的后续 Task；这不是恢复或重跑原 Run。
- 原始七个文件的摘要、三个任务文本摘要、实验配置和 Docker image 与上次逐项一致。初始仓库约 8 KB，生产 Java 32 行，公开用例 160 个；这是小代码、大日志、跨 Task 取证场景，不是大型仓库能力评估。
- 模型 `deepseek-v4-flash`，thinking disabled，temperature=0；超时 60 秒；窗口 128,000；输出 4,096；安全余量 1,024；摘要/紧急处理阈值 0.8/0.9；保留近期 2 组；每 Task 最多 14 次主模型调用、35 次工具调用。
- 内联/预览预算仍为 4,096/1,024 tokens。新 Artifact 文本回读每页最多估算 2,048 tokens、200 行；这是新读取协议的一部分，不等同于旧 JSON 回读的分页行为。
- 本轮重新构建 JAR，包含上一轮协议实现；不是只改预览选段的单变量实验。所有组共享新工具 schema，固定 System/Tools 本地估算从 1,956 变成 2,062。因此 A/C 也可能改变模型路径。
- 复用的 scenario JSON 中 “same JAR” 注释描述的是此前 32k→128k 比较；本次跨日期 JAR 确实不同，以此报告和保存的 JAR digest 为准。

最大输入仍为 122,880；扣掉固定估算 2,062，动态预算为 120,818。80% 触发对应总输入估算约 98,716。实际本地输入峰值最高 56,481，Provider 峰值最高 56,416，未触发摘要。因此 A/C 实际都是不摘要、不外置；B/D 实际都只外置，配对差异不是摘要效果。

## 对比结果

Token 为 Provider usage 的输入加输出累计，不是单次窗口，也不是按缓存价格计算的账单。本次无 usage 缺失、无未返回的主调用。旧批四组均为 3/3 COMPLETED。

| 组 | 设置 | 上次总 tokens | 本次总 tokens | 相对变化 | 本次完成 |
|---|---|---:|---:|---:|---:|
| A | 不外置、不摘要 | 895,466 | 543,892 | -39.26% | 3/3 |
| B | 外置 | 565,277 | 429,782 | -23.97% | 3/3 |
| C | 摘要开启，但未触发 | 954,026 | 631,596 | -33.80% | 3/3 |
| D | 外置＋摘要开启，但未触发 | 700,666 | 704,009 | +0.48% | 2/3 |

| 组 | Task 1 修复 | Task 2 测试 | Task 3 审核 | 主调用数 | 单次输入峰值 | 外置投影 | Artifact 回读 |
|---|---:|---:|---:|---:|---:|---:|---:|
| A | 74,667 | 310,047 | 159,178 | 20 | 56,369 | 0 | 0 |
| B | 76,190 | 209,085 | 144,507 | 22 | 38,371 | 6 | 2 |
| C | 162,147 | 207,553 | 261,896 | 24 | 56,416 | 0 | 0 |
| D | 168,348（PARTIAL） | 300,860 | 234,801 | 29 | 48,990 | 4 | 8 |

输入合计 2,261,569，输出合计 47,710。Run claimed→finished 累计耗时分别为 A 61.66、B 61.39、C 62.45、D 65.90 秒，不含服务启动、排队及独立验收。跨日供应商缓存、路由、负载和模型选择均未控制，不能将耗时或 token 差异全部归因于实现。

## 协议确实改善了什么

### 1. 原文证据出现在首屏

B 首个失败命令原始 TOOL_RESULT 在 Session sequence=29，投影在 30；D 分别为 28、29。两组日志实际完整捕获 162 行、7,265 bytes，预览显示 21 行、省略 141 行，`captureTruncated=false`。

同样 1,024-token 预览预算下，旧 B/D 首次预览的 FAIL 行覆盖为 0/7，新 B/D 为 7/7：L66、70、74、78、86、90、94。逐字匹配原始输出，不是模型生成的诊断；周边选段和头尾一同保留。此指标只衡量这个场景中已知失败行的可见性，不代表任意日志的语义召回率。

### 2. 真实跨 Task 回读，不再复制哈希

B 第三条 Task 实际发出并成功执行：

```json
{"filePath":"artifact://tool-results/29/stdout.txt","startLine":60,"endLine":100}
```

D 第三条 Task 同样成功读取 `artifact://tool-results/28/stdout.txt`。两组最终报告保留完整路径，能区分历史 `153/160` 与当前 `160/160`。本次 10 次 Artifact 回读全部使用统一 `readFile`，全部成功；未调用旧 `readArtifact`，也未出现上次 A 的虚构 ID 查询。A/C 使用仍在 Session 历史中的原始内联输出，没有重新执行 BASE 并冒称原始日志。

### 3. 分页确实前进，但也会增加交互

D 首 Task 对同一原始日志依次读到 1–41、42–82、83–123、124–162 行。连续页逐行覆盖无缺口，`nextRequest` 指向同一资源，最后 `hasMore=false`；没有递归生成新 Artifact。

但首屏已经呈现所有 FAIL，模型仍选择读完整日志。协议正确不意味着模型必然最省；本轮没有自然使用 Artifact `searchText`，所以真实模型验证尚未覆盖其使用收益，也没有覆盖超长单行或跨 Session 恶意引用。

## 暴露的问题：不能以 token 下降掩盖

### P1：readFile 条件必填项没有在 schema 中表达

`ReadFileTool.definition()` 为兼容 Artifact cursor，只把 `filePath` 放在 required；仓库读取实际上仍要求 revision/startLine/endLine。文字描述有要求，但结构声明与执行约束不充分对应。

本次 A/B 各 6 次、C/D 各 2 次仅带 filePath 的仓库读取都被拒绝，共 16 次 `INVALID_READ_ARGUMENTS`。模型随后补参数继续。另 C 有 1 次 `listChanges / REVIEW_DIFF_SCOPE_REQUIRED`。没有越权读取，但无效调用占用了成本和执行预算。

优先修复方向：在当前 validator/provider 实际支持范围内表达仓库读取与 Artifact 范围/游标读取的分支约束，保持服务端严格校验，不为省一次错误而默认降低 revision 边界。修复后先复测这个局部行为，再做整组对照。

### P1：D 修复正确，但 Run 没有正常完成

MySQL 确认为 `PARTIAL / MAX_MODEL_CALLS_REACHED`。14 次响应的大致路径：探索与读文件 → 修正缺失参数 → 执行初始测试 → 分两次读 fixture → 分四页读原始日志 → 重读两个 BASE 文件 → applyPatch → 测试 → 再读成功日志及 diff。

最后一次响应请求的是 readFile/getWorkspaceDiff，工具都执行成功，但预算已用完，没有下一次模型调用发出 finishTask。没有完成工具拒绝事件，也不是 Artifact 路径、超时或摘要故障。独立测试通过只能证明代码修复有效。

下一步应优先让首屏给出失败附近的定向读取示例；目前通用提示仍为 startLine=1/endLine=100。再考察回读页预算与模型必要验证的交互，不宜立即用更高 maxModelCalls 把问题遮掉，也不该禁止确有必要的完整读取。

### P1：交付说明仍可能混淆「代码正确」与「Run 已完成」

D 第三条 Task 文字宣称“两条任务均未失败，无需列为未完成项”，与首 Run 的 PARTIAL 事实不符。当前 `SessionContextService` 未将 RUN_PARTIAL/RUN_COMPLETED 纳入模型历史投影，模型可看到代码和测试，但不能从这些事实推出服务端已完成流程。

应讨论加入简短、可信的前序任务结果，而不是只依赖模型回忆或完整复放生命周期。既保留已确认修改，也明确未完成的 Run；这是本次发现，未在实验中改动已有上下文语义。

### 其他质量问题

A/B 第二条 Task 各有一次 `claimedValidations must include the latest successful validation command`，Harness 纠偏后完成。A 最终 finding 有一句“折扣少 1 分，进而总额少 1 分”，与其正确列出的 expected=1504/actual=1505 相反；这再次说明数据引用正确不等于整份解释无误。本报告的人工审阅是代表性核对，不是完整自然语言质量评分。

## 独立验收与完整性

- 12 份 Task 后验收：公开测试 160/160、隐藏 Oracle 2,125/2,125，全部通过；每条 Task 写入范围均符合授权，第三条只读任务文件哈希不变。
- 后续 Task 初始文件哈希与上一条结束时一致；同 Session Workspace 未重新物化。四组 Session sequence 分别连续为 1…106、1…117、1…117、1…132。
- 95 次主模型响应与启动记录对应；每 Run toolCallId 唯一、结果身份及名称匹配，无孤立/重复 Tool Result。
- 本次 10 次外置投影的实际 Artifact bytes、digest、size 和原始 ToolResult JSON 一致，来源绑定的是较早的原始 TOOL_RESULT，不是投影 Step。
- 实时批次结束后，在独立临时副本恢复两类旧缺陷，验证模型补出的测试能抓到回归；没有改真实 Session Workspace：

| 组 | 新增检查数 | 当前代码失败数 | 恢复旧折扣失败数 | 恢复旧运费失败数 |
|---|---:|---:|---:|---:|
| A | 19 | 0 | 6 | 6 |
| B | 19 | 0 | 5 | 6 |
| C | 21 | 0 | 6 | 7 |
| D | 18 | 0 | 5 | 6 |

## 已知边界与复查入口

本轮未触发摘要，不能评价摘要算法或触发参数优劣；每个配置只有一次路径，不能给稳定节省率。没有做 JVM 重启、持久 generation 同步、租约接管和恶意权限测试。数据库 Workspace generation 仍为 0、Runtime 内已推进的已知缺口没有在本轮修复。Artifact 读取仍是有大小上限的完整 JSON 解码，不是端到端流式存储。

- 原始数据根目录：`/Users/zhaoguodong/code/gitnova-context-scenario2-20260918/live-artifact-protocol-20260921-S6VPFR`。
- 汇总 `matrix.json`；可复算对比 `comparison.json`；每组 `run-steps.json`、`followups/*/run-steps.json`、`verification/`、`edge-mutations/*/report.json`。
- 上次报告：[128k 多 Task 基线](RESULTS-20260920-multitask-128k.md)。对比脚本：[compare_artifact_protocol.py](compare_artifact_protocol.py)，只读已保存结果及本地 Artifact，不访问模型、不写数据库。
- 云端测试仓库 repoId=20；A Session `9a9369b4-806e-45f2-bda4-62e161406ecf`，B `3f2ea9ec-66f3-46cd-a961-59054bd9a93d`，C `2f155f27-b5a8-4e7c-87b7-492e53ded160`，D `86a40017-d0a7-4e28-9a03-297895d26842`。
- JAR SHA-256：`a9c4c5afb4e5876b72ebdfbc36792a275a616eec2c5d904ca4c0138c6405cfa8`。
- Docker image：`sha256:2236aa4655ce559a47e1f574e8253412c384adad937d254395a1b02542a2edea`。

本轮只新增审计脚本和实验报告，没有修生产代码、改默认参数或提交/推送。`mvn -q -DskipTests package` 通过，本轮没有重跑 JUnit。服务和测试容器已停止，8080 无监听，MySQL 无 QUEUED/RUNNING Run；新建测试仓库、Session、Workspace、Artifact 和日志保留供复查。
