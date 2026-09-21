# Artifact 与模型可见 Observation 的接线

## 本次范围

已实现存储、回读工具、确定性预览、Journal 接口及 Runtime 工具结果接线。
本次修改统一历史资源的读取入口并改进预览，不改变现有 `buildRequest`、Session 上下文组装或摘要触发策略。
不新增 Artifact 表，不改 Task / Run / Workspace 的生命周期，不把物理存储路径暴露给模型。

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
- `MessageFactory.toolObservation(call, observation)`：保留原 toolCallId；新协议在序列化时去除后台 ArtifactRef，旧投影仍保留 hash 入口。不负责 I/O。
- `readFile` / `searchText`：按下面的协议读取/搜索历史资源；Session / repo 来自可信执行上下文。
- `readArtifact`：兼容旧 hash + offset + maxBytes 引用，不删除旧定义或历史数据。

`TOOL_OBSERVATION_PROJECTED` 是模型视图记录，不是第二个工具执行结果，也不能生成第二条相同 callId 的 TOOL message。

## 已接入 Runtime 的顺序

```java
// 工具已执行完。所有 verifier / evidence 提取仍使用原始 result。
var source = journal.appendToolResult(scope, fullResultPayload);
state.committed(source);

if (observationPolicy != null && preview.supports(call.name())
        && preview.exceedsInlineBudget(result, observationPolicy)) {
    // 必须先确认本 Run 冻结工具集中允许 readFile（或旧 readArtifact）。
    // 不允许为了让引用可读而偷偷扩大本次 Run 的授权工具集。
    ArtifactRef ref = store.saveToolResult(context, result);
    JsonNode observation = preview.preview(call.name(), result, ref,
            observationPolicy.maxPreviewTokens(), context.sessionId(), source.sessionSequence());
    state.committed(journal.appendToolObservation(
            scope, call.id(), contextPolicyVersion, observation));
    state.messages.add(messageFactory.toolObservation(call, observation));
} else {
    state.messages.add(messageFactory.tool(call, result));
}
```

新策略启用时，Run 启动先要求 Journal、Artifact 组件及冻结工具集中的 readFile 或 readArtifact 可用；
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
旧 readArtifact 只保留所请求的有限字节，每次仍扫描完整文件校验。
新文本视图先验证并解析有存储上限的完整 JSON，再按行/游标输出；当前时间和内存为 O(artifact bytes)，**不是流式 JSON 解码**。
默认原始 Artifact 上限 8 MiB；不扩大该上限。未来若需要处理远超此规模的日志，应单独改为持久化文本视图或流式索引。
返回的是历史结果，不证明当前文件或 validation 仍然有效。
模型不能传物理路径、repoKey 或 sessionId；已知 artifactId 也不能越过 Session 授权。

## 预览与限制

可缩短日志 stdout/stderr、diff、搜索匹配、文件内容/目录列表等明确的正文。
数组保留完整条目。命令日志优先选择 FAIL/ERROR/EXCEPTION 等候选行，再选头尾及相邻上下文；按原始行号排序、去重，逐行保留原文。
关键词仅用于选段，不推断任务成功/失败；无关键词不能证明没有错误。超长单行不吞掉全部预览预算，可用游标回读。
Diff 按完整 hunk 边界选择带文件/hunk 头的前缀；若首个 hunk 本身太大，只展示其头部并提供完整 diff 引用。
预览明确标为 previewOnly，**不能作为完整 patch 使用**。
status、错误码、generation、exitCode、原始 truncated 等不改写。
新 `previewFormat=line-excerpts` 的 capturedCounts 表示条目数/文本行数，omittedCounts 表示预览省略量；旧无格式标志的文本计数仍是 Unicode code point 数，不重写历史。
`text` 元数据进一步给出 capturedLines/capturedBytes/displayedLines/omittedLines/captureTruncated。日志预览中的 L<n> 是原始行号，不是重新计数。
applyPatch / terminal / readArtifact 和指向 Artifact 的 readFile/searchText 不进入外置路径，避免递归生成新 Artifact。

## 统一历史资源协议

```json
{"filePath":"src/Main.java","revision":"WORKSPACE","startLine":1,"endLine":100}
{"filePath":"artifact://tool-results/24/stdout.txt","startLine":60,"endLine":100}
{"path":"artifact://tool-results/24/stdout.txt","query":"FAIL","caseSensitive":true}
```

- `24` 是本 Session 原始 TOOL_RESULT 的 sessionSequence。回读以因果关联 JOIN 已提交的投影取 ArtifactRef；不是模型生成的文件地址，也不是投影 Step 自己的序号。
- 路径严格匹配，不接受任意 scheme、host、编码路径、`..`、query 或 fragment；不落入 Workspace 路径解析，不发起网络请求。
- 已存在的视图才可读取：stdout.txt/stderr.txt 为解码后文本、diff.patch 为 unifiedDiff；数组支持 lines/matches/files/entries/paths/hunks.jsonl，逐条序列化；result.json 保留完整结构。
- 不建立第二套内容存储；视图从已校验的不可变 JSON 派生。result.json 中字符串换行仍转义，因此日志应优先用 stdout.txt/stderr.txt。
- Artifact 禁止 revision；仓库文件仍要求 revision 和行范围。searchText 不带 path 时仍搜索整个当前 Workspace；带 path 当前仅支持 Artifact，不支持指定 Workspace 子目录。
- 读取为 1-based、包含末行，每段最多 200 行。返回预算固定为完整 ToolResult 的 2048 参考 tokens；这是服务端上限，不是 Provider 精确计数或整份 ModelRequest 上限。
- `nextRequest` 可直接作为下一次工具参数。超长单行可分段，characterOffset 为该行 Unicode code point 偏移；cursor 是资源/Session/查询绑定的续读位置，不是授权凭证。
- 搜索是 literal（非正则），每个匹配行返回一条；长行返回命中附近的片段及 readRequest。返回数量仅指本页匹配行；hasMore 表示尚有未扫描内容，不保证一定还有命中。
- 所有回读继续引用同一资源，历史结果不改变 Workspace generation，也不证明当前验证通过。
- captureTruncated 表示捕获本身不完整；预览省略和分页不把它改成 true。已丢失输出无法通过 Artifact 找回；捕获末行可能不完整时 completeLine=false。
- sourceSessionId 随预览和页面返回。短路径只能在原 Session 解释；未来跨 Session Memory 必须保留来源作用域并重新授权，不能裸搬 `/24/`。

### readFile 参数形式与实验调用次数

`readFile` 的 Schema 用 `anyOf` 明确三个互斥的参数形式，字段仍是原来的扁平结构，没有新增 mode 或包装对象：

| 读取对象 | 必填参数 | 不允许混入 |
|---|---|---|
| 仓库文件 | filePath、revision、startLine、endLine | cursor |
| Artifact 行范围 | filePath、startLine、endLine | revision、cursor |
| Artifact 续读 | filePath、cursor | revision、startLine、endLine |

`ToolSchemaValidator` 复用现有 required/type/additionalProperties 校验各分支，并支持路径 pattern；
缺失字段或混用形式在 Registry 分发前返回 SCHEMA_VALIDATION_FAILED。范围、版本可用性、路径安全、
Artifact 授权与游标真实性仍由原工具执行边界检查；这里不是完整 JSON Schema 校验器，也没有开启 Provider strict 模式。
DeepSeek 的[工具调用文档](https://api-docs.deepseek.com/guides/tool_calls/)列出 anyOf；
本次 MockWebServer 验证网关完整发送分支 Schema，不等于真实 Provider 已验证或模型一定不再漏参数。

服务默认 maxModelCalls 原本就是 20，现在也可用 `AGENT_MAX_MODEL_CALLS` 配置；
此前四组实验通过命令行单独覆盖为 14。后续使用
`evaluation/context-large-output/scenario-multitask-128k-20calls.json`，将实验上限改为 20，
其他实验额度不变（包括 maxToolCalls=35）。用 `run_multitask.py --scenario` 显式选择该文件；
旧的 `scenario-multitask-128k.json` 和已保存的 14 次实验结果保留。
此轮不调整 Run 状态投影、分页预算或读取提示策略。

### 升级注意

ReadFile/SearchText 定义已变化，旧 Task 冻结的 definitionDigest 可能不再匹配。保留 ToolSetResolver 的拒绝逻辑；不篡改旧配置、摘要或 Step。部署后创建新 Task 做验证，旧排队执行需要显式迁移/新执行，不能静默套用新工具定义。
旧投影无 resources 时 MessageFactory 不剔除 ArtifactRef；新投影的完整 hash 留在 Journal，模型仅拿到可访问路径。
模型可见历史与本轮实时输出使用同一个 MessageFactory 规则，避免跨 Task 读取时重新暴露另一种引用格式。

## Token 判断标准

先将整个 ToolResult 转为 MessageFactory 实际使用的紧凑 JSON 文本，再估算该文本的 token 数。
计入 status、payload、errorCode、message、retryable、truncated，而不是只数 payload 或文件内容。
不计算磁盘上 pretty JSON 的字节数，也不对 HTTP 请求转义后的 JSON 字符串再计一次。

```text
estimatedTokens <= maxInlineTokens → 完整内置（恰好等于也允许）
estimatedTokens >  maxInlineTokens → 对支持预览且允许回读的工具执行外置

外置后重新计量整个 Observation：
预览正文 + 保留字段 + 可见路径/读取提示 + externalization 元数据
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
