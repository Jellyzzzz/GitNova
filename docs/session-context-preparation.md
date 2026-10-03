# Session 上下文组装、摘要与强压缩

## 阅读顺序

1. `AgentRuntime.run`：加载 Session 投影与 usage；每次主模型请求前调用组装器。
2. `ContextAssembler.assemble`：计量 → 判断触发 → 摘要 → 必要时强压缩 → 试组装 → 复核 → 持久发布。
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

启用强压缩的新 Task，每次组装最多调用两次摘要模型：一次普通摘要，以及仍无法容纳时的一次强压缩。强压缩复用 `ContextSummarizer.summarize(input, targetSummaryTokens)`，不创建另一套模型网关、摘要对象或存储。

强压缩只有在普通处理后仍不能满足 b 的要求时使用；已有摘要但没有可移出的旧组时，也允许单独重写该摘要。普通摘要已经足够时，不调用强压缩。失败后同一 Session 恢复不会无限尝试；比例回到 a 以下或冻结策略改变后才重新允许强压缩。

强压缩失败、没有缩减、仍超过目标或必须保留内容本身太大时，返回 `CONTEXT_PREPARATION_FAILURE`。原始 Step/Artifact 不变，不通过拆开 tool call/result 或删除当前任务来强行过关。b 是策略阈值，不是供应商物理上限。

## 强压缩的目标与发布

触发比例和压缩后目标不同。目标必须满足 `0 < compactTargetRatio < summaryTriggerRatio`。当前默认目标为 0.6，仅是实验起点。

```text
targetInputTokens = fixedTokens + floor(dynamicBudget × compactTargetRatio)
protectedTokens = SYSTEM + tools + 当前 Task 原文 + 近期完整交互 + 摘要消息包装
targetSummaryTokens = min(maxOutputTokens, targetInputTokens - protectedTokens)
```

先使用不含摘要正文的纯投影计量保护内容；测量占位内容不会发送给模型或持久发布。目标正文空间不为正时直接停止，不浪费一次模型调用。强压缩提示词优先保留目标、约束、未解决问题、下一步和精确历史来源，以引用代替冗长日志，并区分初始失败、后续修复与当前验证。

正文目标不是供应商输出上限：thinking 也可能消耗输出额度，因此保持原 `maxOutputTokens`。模型生成后必须重新计量**完整 ModelRequest**，确认确实变小且不超过目标，不能只相信提示词中的 token 要求。

新策略不会发布仍超限的普通摘要再对它继续有损总结；强压缩重新使用已提交的原始来源。未达到目标的候选仅作为尝试结果记录，不替换有效摘要。只重写已有摘要时，新 `summaryId` 指向旧 `parentSummaryId`，`throughSessionSequence` 不变；合并新历史时才推进到闭合组边界。

发布后重读 Session，保留生成期间新提交的消息。若新消息再次超过 b，使用尚未使用的强压缩机会；两阶段已用完就停止，不进行第三次调用。Runtime 随后仍刷新 Workspace、检验最终请求预算；摘要不获得 Workspace/Validation 的权威。

## 计量与持久化

主模型的 `INVALID_RESPONSE`（例如工具参数 JSON 损坏）不再必然立即结束 Run。
Runtime 先记录 `MODEL_CALL_FAILED`，再追加 `MODEL_RESPONSE_CORRECTION` 类型的 Harness feedback，
其因果引用指向失败事件而不是不存在的 `MODEL_RESPONSE`。反馈包含安全的错误类型、路径和可用的行列号；
原始坏响应不注入消息、不执行其中任何工具，也不伪造 TOOL 返回。下一轮从最后一次被接纳的历史重新生成，
先前成功的工具结果及对应 reasoning_content 保持原样，不自动重放工具。

纠偏复用冻结的 `maxProtocolCorrections`，与其他协议纠偏共享；当前配置为 1，设置 0 则禁用。
每次尝试仍占主模型调用预算，预算耗尽后保留失败原因并停止。鉴权、网络、超时、上下文超限等不走此格式纠偏。
失败记录或反馈无法提交、Lease 丢失时不会继续请求。未解析到的 usage 为未知，不能按零消耗计算；
这些失败记录不建立 usage anchor，也不会给 Session 历史制造未闭合的工具组。本次不改变摘要模型的失败策略。

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

- `CONTEXT_CONTROL_UPDATED`：普通摘要、紧急摘要和强压缩的尝试状态。新记录 schemaVersion=2，读取兼容旧的 schemaVersion=1。
- `CONTEXT_SUMMARY_RESULT`：schemaVersion=2，候选输出、usage、采用评估或失败类别；`operation=SUMMARY/COMPACTION` 区分两种调用，强压缩另记录 `targetInputTokens`。旧记录不改写。
- `CONTEXT_SUMMARY_CREATED`：真正生效的摘要及来源覆盖范围。

以上写入均走既有 fenced appender；LLM 调用不放进数据库事务。提交失败不能继续向主模型暴露未提交的候选。进程在摘要请求返回和结果提交之间崩溃，仍可能缺失该次供应商成本；超时或被摘要响应校验拒绝的响应也可能只有失败记录、没有 usage，不能记为零成本或声称精确计费。

## 配置

本地启动 shell 中设置（容量必须按实际供应商/模型确认，不在此猜测）：

```sh
export AGENT_CONTEXT_WINDOW_TOKENS=<模型上下文容量>
export AGENT_CONTEXT_SAFETY_MARGIN_TOKENS=<保守余量>
export AGENT_CONTEXT_SUMMARY_TRIGGER_RATIO=0.8
export AGENT_CONTEXT_COMPACT_TRIGGER_RATIO=0.9
export AGENT_CONTEXT_COMPACT_TARGET_RATIO=0.6
export AGENT_CONTEXT_KEEP_RECENT_GROUPS=4
```

a/b/target/N 都是待实验校准的配置，不是最佳参数结论。输出预留使用 `gitnova.agent.runtime.max-output-tokens`。新 Task 冻结配置，已有 Task 不随配置文件变化被悄悄改写。带压缩目标的执行契约使用 schemaVersion=7；旧契约保持原 JSON/digest，缺少压缩目标时仍保持原停止行为，不偷偷启用新策略。无需修改数据库表。

## 验证与限制

`ContextPreparationTest` 覆盖阈值相等、摘要失败/无缩减、目标恰好满足/超出、只重写摘要、当前任务补回、Session 尝试状态恢复、生成期间的新消息、提交失败及 Lease 丢失。`SessionContextServiceTest` 验证相同覆盖范围发布、旧父摘要冲突及近期 thinking/tool call/result 保持原样。`AgentRuntimeArtifactTest` 使用实际本地计量，覆盖普通摘要失败后强压缩成功、继续主模型与工具调用的链路。

这些测试使用脚本模型和模拟数据库提交；不等同于真实 MySQL、MQ、HTTP 或模型 API 验证。强压缩的语义质量和目标占比仍需真实任务验证，不能由预算断言证明。完整 crash recovery、新的 Memory/RAG 系统、保护近期组本身超限时的动态缩组、超大摘要输入的分块处理不属于本次实现。当前本地 token 估计不是供应商精确 tokenizer，安全余量需要真实 usage 校准。跨 Task 约束目前仍依赖已有摘要提示词，不在本次新增独立指令存储。

历史按页读取，但未压缩尾部仍会进入 JVM 内存；这里没有承诺有界内存加载。当前不在每次推理前轮询新用户消息，摘要后重载与新 Run 加载才会读到这些新记录。
