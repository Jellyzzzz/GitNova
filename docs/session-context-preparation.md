# Session 上下文组装与摘要接线

## 阅读顺序

1. `AgentRuntime.run`：加载 Session 投影与 usage；每次主模型请求前调用组装器。
2. `ContextAssembler.assemble`：计量 → 判断触发 → 摘要 → 试组装 → 复核 → 持久发布。
3. `SessionContextService.Snapshot.modelMessages`：当前 SYSTEM + 摘要 + 未覆盖的历史 + 必要时补回当前 Task 原文。
4. `ContextUsage.measure/accept`：请求前估计；主模型响应提交后更新真实 input usage。

`assemble` 返回 `List<ModelMessage>`。Runtime 替换的是模型可见投影，不删除 Step、Artifact 或原始工具结果。工具定义仍单独放在 `ModelRequest.tools`。

## 请求流程

Run 启动从 MySQL 读取同一 Session 下已提交的历史；Task/Run 不创建新的独立历史窗口。运行中的工具结果与反馈持久提交后增量加入消息列表，普通模型轮次不反复全量读取历史。

达到摘要条件时，读取一个固定结束序号的 Session 快照。最近 N 个完整交互组保持原文，旧前缀进入摘要。`HistoryEntry` 记录一条用户消息、独立反馈或一个完整工具组的来源范围，因此不会只拼接组而丢掉组外信息。

候选摘要组装是纯操作，不立即写数据库。组装后对新的请求重新计量；没有变小的摘要不替换旧上下文。before/after 的缩减判断使用同一种本地计量，避免把供应商真实 usage 与本地估计误差当作摘要效果。

采用摘要前，必须经过 `publishSummary` 的 Session 锁、父摘要检查和 fenced append。Runtime 通过提交回调推进 Run/Session 序号。发布之后重新加载，保留摘要生成期间追加的 Steps。再次刷新 Workspace，把摘要期间发生的外部修改作为当前事实反馈，最后复核请求预算。

## 阈值与防重复

- 小于 a：保留累计上下文（工具结果外置仍正常工作）。
- 大于等于 a：允许一次普通摘要，不会因为多一组就重复摘要。
- 普通摘要之后，比例重新低于 a 才重新允许普通摘要。
- 大于等于 b：即使普通摘要尝试过，也可进行一次紧急摘要；低于 b 后才重新允许下一次紧急尝试。
- 摘要失败或没有旧组可处理，也消耗本次触发机会。避免每轮调用昂贵的摘要模型。
- 触发状态由 `CONTEXT_CONTROL_UPDATED` Step 保存，换 Task/Run 后继续使用；冻结的预算、SYSTEM 或工具集改变后重新判断。

每次组装最多调用一次摘要模型，没有自动重试循环。摘要后仍达到 b 时，**强压缩算法尚未实现**：返回 `CONTEXT_PREPARATION_FAILURE`，日志说明需要更强压缩，不静默删除用户要求或工具结果。b 是当前策略停止线，不是供应商物理上限；最大输入预算另行严格校验。

## 计量与持久化

```text
inputLimit = contextWindowTokens - safetyMarginTokens - maxOutputTokens
dynamicBudget = inputLimit - fixedTokens
dynamicUsed = estimatedInputTokens - fixedTokens
useRatio = dynamicUsed / dynamicBudget
```

SYSTEM 和工具定义独立于可压缩历史，但仍占输入容量。用户请求占动态预算。

同模型、同 SYSTEM/工具集且历史前缀未变时，使用最近一次主模型的真实 inputTokens 加新增消息估计；首次请求、usage 缺失或摘要替换历史后重新本地估计。不会累加历次 usage 作为当前窗口长度。

摘要请求复用本次 Run 冻结的模型及 maxOutputTokens，工具列表为空；它有独立的输入预算检查、请求 ID。摘要成本保存在 `CONTEXT_SUMMARY_RESULT`，不计入主模型调用次数或更新主模型 usage anchor。主模型的 measurement 对应真正发送的最终请求，并在 MODEL_RESPONSE 提交后才 accept。

持久化仅扩展现有 `agent_step` 的类型和 JSON payload，不新增表：

- `CONTEXT_CONTROL_UPDATED`：允许下一次普通/紧急摘要的状态。
- `CONTEXT_SUMMARY_RESULT`：候选输出、usage、采用评估或失败类别。
- `CONTEXT_SUMMARY_CREATED`：真正生效的摘要及来源覆盖范围。

以上写入均走既有 fenced appender；LLM 调用不放进数据库事务。提交失败不能继续向主模型暴露未提交的候选。进程在摘要请求返回和结果提交之间崩溃，仍可能缺失该次供应商成本，不能声称精确一次调用或精确计费。

## 配置

本地启动 shell 中设置（容量必须按实际供应商/模型确认，不在此猜测）：

```sh
export AGENT_CONTEXT_WINDOW_TOKENS=<模型上下文容量>
export AGENT_CONTEXT_SAFETY_MARGIN_TOKENS=<保守余量>
export AGENT_CONTEXT_SUMMARY_TRIGGER_RATIO=0.8
export AGENT_CONTEXT_COMPACT_TRIGGER_RATIO=0.9
export AGENT_CONTEXT_KEEP_RECENT_GROUPS=4
```

a/b/N 是待实验校准的配置，不是最佳参数结论。输出预留使用 `gitnova.agent.runtime.max-output-tokens`。新 Task 冻结配置，已有 Task 不随配置文件变化被悄悄改写。旧配置没有 contextBudget 的任务继续保持旧行为。

## 验证与限制

`ContextPreparationTest` 覆盖阈值相等、摘要失败/无缩减、组外用户消息/反馈、当前任务补回、Session 触发状态恢复、生成期间的新消息、提交失败及 Lease 丢失。`AgentRuntimeArtifactTest` 包含摘要接入主循环后的多轮验证。

这些测试使用脚本模型和模拟数据库提交；不等同于真实 MySQL、MQ、HTTP 或模型 API 验证。完整 crash recovery、强压缩、RAG/Memory 检索仍不是本次实现。当前本地 token 估计不是供应商精确 tokenizer，安全余量需要真实 usage 校准。

历史按页读取，但未压缩尾部仍会进入 JVM 内存；这里没有承诺有界内存加载。当前不在每次推理前轮询新用户消息，摘要后重载与新 Run 加载才会读到这些新记录。
