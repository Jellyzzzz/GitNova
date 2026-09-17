# Artifact 与模型可见 Observation 的接线

## 本次范围

已实现存储、回读工具、确定性预览、Journal 接口及 Runtime 工具结果接线。
未接管 `buildRequest` 或整体上下文预算；这部分由用户继续实现。
`ContextAssembler`、摘要触发策略、预算分配由后续主流程接入。
不新增 Artifact 表，不改 Task / Run / Workspace 的生命周期，不把文件路径暴露给模型。

## 三个不同位置

1. `AgentRuntime` 的 `messageFactory.assistant(response)`：继续记录模型回复及 tool calls。
   此时工具还没执行，交互组尚未闭合，不在这里外置工具结果或压缩交互组。
2. `appendToolObservation(...)`：这里决定工具结果完整内置还是外置后注入预览。
3. `buildRequest(...)`：每次模型调用前对已有闭合组组装、检查整体预算；不是每多一组就调用摘要模型。

## 现有数据结构及方法

- `ArtifactRef`：`artifactId / sha256 / sizeBytes / mediaType`。复用现有定义。
- `ObservationPolicy`：`maxInlineTokens / maxPreviewTokens`。由服务端配置，在 Task 创建时冻结进 Run 的执行配置。
- `ArtifactReadResult`：`offset / nextOffset / totalBytes / content / hasMore`。
  offset 是 UTF-8 字节位置，不是字符或行号；下一页使用返回的 nextOffset。
- `LocalArtifactStore.saveToolResult(context, result)`：完整写入后返回引用。
- `ToolObservationPreview.preview(name, result, ref, budget)`：只产生新的模型可见 JSON，不修改原始 result。
- `RunJournal.appendToolObservation(...)`：在完整 TOOL_RESULT 之后追加 TOOL_OBSERVATION_PROJECTED。
- `MessageFactory.toolObservation(call, observation)`：只序列化，保持原 toolCallId，不负责 I/O。
- `readArtifact`：模型输入 artifactId、offset、maxBytes；Session / repo 来自可信执行上下文。

`TOOL_OBSERVATION_PROJECTED` 是模型视图记录，不是第二个工具执行结果，也不能生成第二条相同 callId 的 TOOL message。

## 已接入 Runtime 的顺序

```java
// 工具已执行完。所有 verifier / evidence 提取仍使用原始 result。
state.committed(journal.appendToolResult(scope, fullResultPayload));

if (observationPolicy != null && preview.supports(call.name())
        && preview.exceedsInlineBudget(result, observationPolicy)) {
    // 必须先确认本 Run 冻结工具集中允许 readArtifact。
    // 不允许为了让引用可读而偷偷扩大本次 Run 的授权工具集。
    ArtifactRef ref = store.saveToolResult(context, result);
    JsonNode observation = preview.preview(call.name(), result, ref, observationPolicy.maxPreviewTokens());
    state.committed(journal.appendToolObservation(
            scope, call.id(), contextPolicyVersion, observation));
    state.messages.add(messageFactory.toolObservation(call, observation));
} else {
    state.messages.add(messageFactory.tool(call, result));
}
```

新策略启用时，Run 启动先要求 Journal、Artifact 组件及冻结工具集中的 readArtifact 均可用；
否则在第一次模型调用之前返回 CONTEXT_PREPARATION_FAILURE，不临时扩大工具集。
旧配置（没有 ObservationPolicy）继续完整内置；独立测试可通过旧配置运行，但不允许生成不可回读的引用。
大结果保存失败或必要预览字段仍超预算，返回 CONTEXT_PREPARATION_FAILURE；当前工具真实结果已先记录，
不伪造工具失败、不重跑工具、不执行同批剩余调用、不调用下一轮模型。
原始结果或投影引用的 Journal/lease/fencing 失败继续沿现有 durable 异常路径传播，不静默回退。
不支持外置的结果依然完整内置，所以这里不保证整份请求都在预算内；整体预算仍由 buildRequest 侧处理。

Artifact 写入先于引用的数据库提交：失败可能留下无引用文件，但不能把不存在的内容引用注入模型。
首次投影视图的 eventId 是 `run:{runId}:tool-call:{callId}:observation`；相同逻辑重试必须复用相同内容和身份。
未来重新生成不同预览需要新的逻辑事件身份，不能覆盖该事件。

## 配置冻结与旧数据兼容

```yaml
gitnova:
  agent:
    runtime:
      observation:
        max-inline-tokens: 4096
        max-preview-tokens: 1024
```

这两个现有配置默认值只是初始参数，不代表经过模型实验确定的最佳值。
AgentTaskService 将 ObservationPolicy 与已授权工具定义一起冻结。
带策略的 executionConfig 使用 JSON schemaVersion=2；新格式必须具有合法预算。
旧 schemaVersion=1 保持原 JSON/digest，解码得到 observationPolicy=null，明确表示旧的完整内置行为。
不能用当前 application.yml 的值补齐旧配置，也不能向旧冻结工具集合自动加入 readArtifact。
这里的 schemaVersion 是持久化格式标识，不是产品开发阶段；没有新增数据库表/列。

## 存储、读取与权限

文件位于已有 `gitnova.agent.artifact.base-path`，不进入 Workspace，不影响 generation。
目录按 repoKey + Session 做 SHA-256 命名，文件按实际存储字节的 SHA-256 命名。
同一 Session 中相同字节内容可复用文件；不承诺 JSON 字段顺序不同仍有相同 ID。
采用临时文件 → 文件 fsync → 同文件系统 hard link 原子发布且不覆盖 → 目录 fsync。
要求服务端私有的支持这些操作的文件系统；不支持时失败，不静默降级。

回读先从当前 Session 的 committed projection 查询服务端 ArtifactRef，再校验文件大小与完整 SHA-256。
只保留所请求的有限字节，避免将大文件整体装入内存；每次回读会扫描完整文件做校验，时间复杂度 O(artifact bytes)。
返回的是历史结果，不证明当前文件或 validation 仍然有效。
模型不能传物理路径、repoKey 或 sessionId；已知 artifactId 也不能越过 Session 授权。

## 预览与限制

可缩短日志 stdout/stderr、diff、搜索匹配、文件内容/目录列表等明确的正文。
数组保留完整条目，字符串保留头尾；status、错误码、generation、exitCode、原始 truncated 等不改写。
省略情况通过 `externalization.previewedFields` 和 `capturedCounts` 显式记录。
capturedCounts 只代表保存结果中原有的数组条目数或字符串 Unicode code point 数，不伪造仓库总数。
applyPatch / terminal / readArtifact 不进入此预览路径。

## Token 判断标准

先将整个 ToolResult 转为 MessageFactory 实际使用的紧凑 JSON 文本，再估算该文本的 token 数。
计入 status、payload、errorCode、message、retryable、truncated，而不是只数 payload 或文件内容。
不计算磁盘上 pretty JSON 的字节数，也不对 HTTP 请求转义后的 JSON 字符串再计一次。

```text
estimatedTokens <= maxInlineTokens → 完整内置（恰好等于也允许）
estimatedTokens >  maxInlineTokens → 对支持预览且允许回读的工具执行外置

外置后重新计量整个 Observation：
预览正文 + 保留字段 + ArtifactRef + externalization 元数据
estimatedTokens <= maxPreviewTokens 才满足预览预算
```

`TokenEstimator` 复用 JTokkit 1.1.0 的本地 o200k_base BPE 编码，不做 bytes→tokens 或 chars/4 换算。
这是固定参考编码下可复现的文本计数，不是 DeepSeek 专用 tokenizer，也不保证总是高估实际用量。
详见 [JTokkit 文档](https://github.com/knuddelsgmbh/jtokkit)。词表随依赖打包，估算不调用 LLM API。
文本里类似 special token 的字符串按普通文本计数，避免仓库内容触发特殊 token 异常。

单条 Observation 的估计只用于判断是否外置，不能直接冒充整个 ModelRequest 的输入量。
后续通过与模型匹配的 tokenizer 或真实 Provider usage 评估偏差；当前测试不能证明 DeepSeek 的实际估算误差范围。
[DeepSeek 官方说明](https://api-docs.deepseek.com/zh-cn/quick_start/token_usage/)也以 API usage 作为实际用量依据。
不引入未经实验确定的安全倍率，不声称某个阈值最优；阈值由传入的 ObservationPolicy 决定。

### 文本与请求的估值来源

```java
TokenEstimate itemCost = tokenEstimator.estimateText(modelMessage.content());
TokenEstimator.RequestEstimate requestCost = tokenEstimator.estimateRequest(request);
```

TokenEstimate 包含 tokens、quality、source。quality 表示计量方法，不是经过测量的准确率：

- REFERENCE_TOKENIZER：参考 o200k_base 的文本分词数，不宣称匹配当前 Provider。
- HEURISTIC：在参考分词基础上，还使用了请求协议结构的近似表示。

请求级结果分为 contentTokens、toolCallTokens、toolDefinitionTokens、protocolTokens，total 等于四者之和。
contentTokens 包括所有角色的正文，因此 System、用户任务、摘要、检索证据和 TOOL Observation 均会进入这里。
toolCallTokens 包含 assistant 请求的工具名和参数；toolDefinitionTokens 包含所有可用工具的名称、描述与 Schema。
protocolTokens 使用正文留空的 JSON 请求骨架计量 role、callId、包装结构。它是显式的结构开销代理，
不是 Provider 内部聊天模板的精确计数；独立计数的 token 边界也可能与实际模板不同。
正文按原始文本单独计量，不对 HTTP 转义后的正文再次计数；不把 requestId、temperature、maxOutputTokens 当作输入正文。
maxOutputTokens 应在整体容量预算中单独预留，而不是混入 estimatedInputTokens。

此处不根据 model 名字猜分词器支持情况；请求结果记录 model，但目前所有请求都是相同参考编码下的估计。
没有 Provider 精确计数实现，也不返回 EXACT。远程 count 同样必须按照 Provider 的实际保证标记，
例如 [Anthropic 文档](https://platform.claude.com/docs/en/build-with-claude/token-counting)明确说明其 count 是估计值。

后续按以下顺序接入，不在本轮提前新增空接口、缓存框架或自动修正倍率：

1. Artifact 已接到 appendToolObservation；下一步由 buildRequest 将最终 ModelRequest 交给 estimateRequest。
2. 同一 requestId 下记录估计值及返回的 ModelUsage.inputTokens；usage 缺失代表未知，不是 0。
3. 在足够的代表性数据上评估误差、延迟和低估幅度，再确定安全余量及摘要触发阈值。
4. 闭合组的成本缓存必须随内容、模型/计量方式、预览策略变化失效，不能只按 groupId 永久缓存。

请求级估计尚未接入 Runtime；没有新增模型调用，也没有更改已有持久化事件格式。
达到第二触发阈值不应机械地返回 COMPACT：仍需先尝试可用的摘要，只有处理后仍放不下才升级处理。

文件大小不参与上述大小结果判断；以下存储/分页的安全限制是独立约束，此次没有调整。
默认单 Artifact 上限 8 MiB、每次内容回读 4 KiB（不含工具结果 JSON envelope），可通过已有配置属性调整。

仍未实现：整体预算/摘要触发接线、原始结果脱敏策略、未引用文件 GC、远程/跨机器存储、Resume Context Projector。
调用方必须提供允许持久化的结果；不是凭借外置就能安全保存 secret。
若执行层已截断命令输出，Artifact 只能保存已捕获内容，不能恢复从未采集的日志。
目前完整 TOOL_RESULT 仍按原契约保存到 MySQL；这一步减少模型输入，不减少数据库原始 payload。

## 本地验证

```shell
mvn -q -Dtest=AgentRuntimeArtifactTest,AgentObservationConfigurationTest,AgentExecutionConfigCodecTest,AgentRuntimeJournalTest,TokenEstimatorTest,LocalArtifactStoreTest,ToolObservationPreviewTest,ReadArtifactToolTest,DefaultRunJournalTest,StorageConfigurationTest,MessageFactoryTest test
```

覆盖存储去重/损坏/并发发布、UTF-8 分页、Session 与 repo 隔离、授权拒绝、预览字段与原文不变、Spring 注册及 Journal 因果引用。
Token 测试覆盖完整 TOOL content 一致性、空 payload 的元数据成本、恰好等于/小于/大于阈值、预览引用计入预算，以及中文/代码/emoji。
工具链测试使用真实本地文件、真实 Registry / MessageFactory，Journal 查询使用 Mock。
Runtime 接线测试覆盖：原始结果→Artifact→投影提交→下一轮请求，模型随后调用真实 readArtifact；
恰好预算边界/超出 1 token、旧配置保留原文、回读不递归外置、terminal 不外置、验证证据保持原始语义；
保存失败、必要字段放不下及 Journal 提交失败均不导致工具重放/继续模型调用。
Spring 测试验证配置绑定、Runtime 依赖装配和 Task 创建时冻结预算及 readArtifact 工具。
这不等于真实 MySQL 查询或真实模型端到端测试。
