# OrderFlow：结束报告契约修正后的真实 G80 实验

## 范围与条件

本批只运行 GitNova，没有启动 Claude Code。此前 CC 参考组保留原样，不增加新调用。
真实注册、登录、创建仓库、Gitlet CLI push、Session、Task、MQ Worker、Runtime、Docker 工具执行；
SQL 仅用于观察。参考修复和独立验收器未进入模型 Workspace，未人工修改模型候选代码。

- 批次：`/Users/zhaoguodong/code/gitnova-orderflow-report-20260921-HsGMTh`
- G80：128,000 窗口；普通摘要 80%、强压缩边界 90%；最近两组保护；外置 4096/1024 tokens。
- deepseek-v4-flash，thinking 配置 disabled，temperature=0；每 Task 40 次模型调用、80 次工具调用、输出8192。
- 模型调用/读取超时120秒，Task上限1800秒；试验进程使用 caffeinate 防止空闲睡眠。
- 与上一批的主要生产代码差异：成功验证不再是结束报告的强制前提；模型报告不被视为目标完成的证明。
  Workspace generation、canonical diff、修改路径检查仍保留；没有修改 Run 状态枚举。
- 当前 JAR SHA-256：`ae6cdbfb506c929674c0a344d1be290bca6d3435e05be02b2fd7ac76bdc5cc92`。
  初始文件、任务及配置的完整摘要在 frozen.json 中。

## 实际结果

| Task | Run 结果 | 主模型 tokens | 摘要 tokens | 主模型调用 | 独立代码验收 |
|---|---|---:|---:|---:|---|
| 1 调查 | COMPLETED | 455,314 | 0 | 21 | 通过，只读 |
| 2 计价 | COMPLETED | 370,316 | 0 | 7 | 通过 |
| 3 取消 | COMPLETED | 472,667 | 0 | 7 | 通过 |
| 4 幂等/批处理 | COMPLETED | 670,091 | 0 | 8 | 通过 |
| 5 新回归 | PARTIAL / MAX_MODEL_CALLS_REACHED | 1,643,815 | 92,254 | 40 | 通过，但未调用 finishTask |
| 6 只读交付（用户批准追加） | COMPLETED | 1,268,011 | 0 | 17 | 通过；历史证据回读不通过 |
| 合计 | 不能记作原协议六阶段全部完成 | 4,880,214 | 92,254 | 100 | 见下文限制 |

总已知消耗 **4,972,468 tokens**，包括一次摘要；主模型输入4,818,696、输出61,518。
摘要输入89,957、输出2,297。已返回的 usage 均已计入，没有将摘要成本记为零。
Token 数量不是实际账单，不能直接按统一单价与 CC 的缓存/非缓存输入比较。

第5条耗尽预算后，原 runner 按协议停止，experiment.json 保持 STOPPED_ON_FAILED_SAMPLE。
经用户明确批准，重启同一 JAR，在**同一个 Session、同一个 Workspace**新建第6条原定只读 Task。
使用现有客户端的 --continue-after-failure；未重放第5条，未更改旧 Run 的 PARTIAL。
追加结果单独保存在 G80/delivery-continuation.json，不覆盖原实验结果。

## 本轮证明的改善

### 分阶段工作不会再被最后一个失败命令一票否决

Task 2 实际顺序为 pricing/contract/inventory 成功、额外边界 Probe 成功、all 失败，之后
finishTask 一次接受；Task 3 也在最后一次 all 仍失败后正常提交报告。
两次失败分别属于尚未修复的后续模块，独立阶段验收通过，没有靠调整命令顺序绕过门禁。
这直接复现并消除了上一批 pricing 的错误结束纠偏。

### 摘要自然触发、发布并继续执行

摘要发生在 Task 5：

- Step217（北京时间17:13:50.356）记录本次摘要控制状态。
- Step218（17:14:00.731）记录 CANDIDATE_ACCEPTED；预算估算98,363 → 10,563。
  前者为 Provider usage 加增量、后者为本地重组估算，不能把比例当成精确压缩率。
- Step219 提交摘要，覆盖源历史 throughSessionSequence=201，正文5354字符。
- Step220 的下一次请求引用 contextThroughSessionSequence=219；实际 Provider inputTokens=11,832。
- 此后本 Run 又产生38条模型响应。Task 6 在 JVM 重启后继续读取同一 Session 的摘要及后续历史。

一次摘要的输入89,957、输出2,297；控制事件到结果事件约10.38秒，包含调用与本地处理，
并非独立测量的纯网络耗时。本轮没有验证强压缩；该算法目前仍未实现。
这验证的是80%条件下的一次实际摘要闭环，不证明80%是最佳参数。

### 代码与测试产物有独立证据

最终生产代码：公开79/79、独立1,046/1,046。新增 EdgeRegression：23/23。
分别回植五个原缺陷，新增回归均非零退出：金额5项失败、运费3项、取消3项、指纹6项、批处理3项。
各阶段写入范围与文件哈希连续性检查通过，Task6前后文件完全一致。
因此 Task5 属于“产物通过、Run预算耗尽”，不能伪称 Run 已正常完成。

## 暴露的问题

### 1. 补丁表达错误消耗大量轮次

Task5调用19次 applyPatch，包含10次非 SUCCESS（其中1次是合法 partial success）：

- 1次 PARTIAL_SUCCESS：新测试文件已创建，后续 runner 更新失败；已成功内容保留、generation推进。
- 6次 PATCH_DOES_NOT_APPLY：包括 hunk 起始行或行数与当前文件不符。
- 1次 UPDATE 传 content 而不是 patch。
- 2次同一批多次操作同一路径，被 DUPLICATE_PATCH_PATH 拒绝。

例如 run-tests.sh 的 java 行实际在第7行，模型先提交从第5/6行开始的不匹配补丁，随后修正才成功。
错误在摘要前已开始（Step216），不能仅凭发生摘要就归因于摘要失真。
另有新测试自身的预期值/不存在的 TestSupport.checks() 方法错误，模型最终修好了这些问题。
第40次模型响应仍请求最终读文件，没有调用 finishTask，随后预算终止。

### 2. 小结果在摘要后缺少可回读的历史入口

Task1没有产生任何 TOOL_OBSERVATION_PROJECTED：原始完整日志作为小结果内联，仍 durable 存在
TOOL_RESULT Step93/96，但未形成可通过 Artifact 工具读取的资源。
摘要保留了初始失败总数，却没有保留详细 CASE 原文与可用读取入口。
Task6尝试搜索 artifact://tool-results 目录及猜测 Step1，得到 INVALID_ARTIFACT_ARGUMENTS /
ARTIFACT_NOT_FOUND；这不是“原始历史已被删除”，而是模型没有可用的历史检索入口。

模型明确声明改用 BASE 代码推导，没有把重跑旧代码伪称原始执行；但推导仍出现实际错误：

| 案例 | Step93/96 原始 actual | 交付报告中的推导 actual |
|---|---|---|
| mixed-lines | 110 | 11 |
| 5505x10 | 550/4955/0/4955 | 551/4954/0/4954 |
| 9999x50 | 4999/5000/0/5000 | 5000/4999/0/4999 |

报告还将 Artifact 查询参数被拒描述成“没有命中”，以及将当前 Task / 上一 Task / Session 的
修改归属混用。机器检查显示 Task6确实没有写文件，但不能因此认证其整篇自然语言报告。
因此，历史原文回读和交付语义准确性不能判为通过。

### 3. JVM重启后 generation 回退

Task5末次 Workspace diff/工具状态为 generation=13；重启后 Task6首次 MODEL_CALL_STARTED 为
generation=1。两个阶段之间的文件哈希完全一致，旧 Run 仍为 PARTIAL。

数据库 agent_workspace.generation 在本批仍为0；LocalWorkspaceRegistry 冷加载从该持久化值
及旧 fingerprint 开始，再由 refresh 推进，不能恢复内存中已经走到13的版本时间轴。
模型把13→1解释成外部文件修改并不正确。这属于 Workspace generation 持久化/恢复缺口，
不能把“文件还在、Session可继续”说成“跨JVM版本连续性已经保证”。本轮没有修数据库或生产代码。

## 结论与后续

- 结束门禁修正有效；摘要实际成功一次，并能用于后续模型调用及新的 Task。
- 代码验收与自然语言报告验收必须继续分开：本例代码正确，不代表交付报告中的推导正确。
- 下一步值得优先处理：历史小结果的授权回读入口、Workspace版本恢复，以及补丁工具的可用性。
  单纯继续扩大预算或调整摘要比例不能替代这些修复。
- G70/G85本轮未跑；CC未重新运行。旧CC参考合计3,288,479 tokens，但其权限、缓存、思考/输出
  配置与本批仍存在差异。不能从单次样本计算严格的系统优劣或宣称超过成熟Agent。
- 两次试验服务均已停止，未留下8080监听；无活动Run。所有用户测试数据和失败样本均保留。

证据主要位于 G80/experiment.json、G80/followups/regression/run-steps.json、
对应 verification/report.json，以及 G80/delivery-continuation.json 和 followups/delivery/run-steps.json。
批次目录含私有客户端凭据，不应整体提交Git；本文只记录脱敏结论。
