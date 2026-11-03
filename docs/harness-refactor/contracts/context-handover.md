# 跨工作线历史交接：Worker恢复数据，Core选择上下文

**可访问的历史范围不等于本轮模型上下文。** Worker只负责恢复范围、数据完整性、访问边界和环境事实；分组、近期保留、摘要、检索与预算选择由Core的SessionHistoryReader/ContextCoordinator完成。安装bootstrap不调用摘要模型，也不替Core选择“值得带入的任务”。

## 1. 恢复材料只描述已有事实

复用原始事件和ResultStore，不改旧eventId/hash。新bootstrap的STATE包含`history/epochs/<oldEpoch>/events.jsonl`及被引用对象；原日志只读，新epoch事件写在当前events目录。`state/history-index.json`继续记录各段的固定顺序、实际归档覆盖与对象引用，并受stateDigest保护；顺序来自已确认的历史来源，不按Task重要性重排。

将未实现的`state/context-selection.json`方案替换为`state/context-handover.json`。字段仅为：`schemaVersion=1、sessionId、sourceWorklineId、targetWorklineId、sourceArchiveId、sourceBundleSha256、syncId、targetHead、changePolicy`。它描述恢复来源与切线事实，不保存selectedGroups、carriedTaskIds、persistentInstructionEventIds这类prompt选择结果。原始用户指令、摘要及其来源引用仍在被恢复的原始历史中，不另造宿主挑选清单。

恢复范围以授权的Session归档与history-index为依据。不能声明归档截止之后的事件已恢复；也不能因为某段暂时不进prompt就不复制其对象。若对象缺失、hash不符或日志覆盖不完整，安装/恢复明确失败，不返回伪空历史或声称完整恢复。

## 2. Worker提供完整的授权历史与环境事实

安装器校验scope、日志范围、对象与摘要引用；然后提供范围内的**全部已提交Entry**，保留原始字节、固定段顺序和origin。history视图的过滤仅用于授权与可证明覆盖，不能按token、语义相关性、Task完成状态或“最近N组”提前裁剪。HistoryIndex只是这些记录的可重建索引，不新增一份事实库。

prepared ready不运行模型。平台激活后的第一条授权SUBMIT开始前，若未记录相同syncId的WORKLINE_SWITCHED，则先耐久追加；重复SUBMIT只返回原回执，不重复记录。Worker将必要事实以当前执行的`HARNESS_FEEDBACK(kind=RUNTIME_CONTEXT)`提供给Core：代码来源已变、固定目标H、草稿是否移植、旧验证仅针对其原树。Core消费有来源的环境说明，不需要Task/Workline平台DTO，也不凭说明取得访问权限。

同workline普通恢复不写WORKLINE_SWITCHED；只恢复已证实覆盖，并在确有中断时记录RECOVERY_INTERRUPTED。路径与JSON验证沿用05及09，不在交接中装载旧进程锁、控制令牌或运行句柄。

## 3. Core决定哪些内容进入请求

`SessionHistoryReader.load`读取上述授权视图，在固定Boundary内重建闭合Group与可用Summary。`ContextCoordinator.prepare`才选择原文、近期组、摘要和检索证据，执行预算与压缩策略；被省略的历史仍能通过history_search/history_read回查。Worker不得要求Core采用某个摘要或某组消息。

Core保留当前执行USER原文/已应用补充；显式长期指令按原始来源及已记录的撤销/替代事实处理，不能从摘要中猜出新权限，也不能把旧任务的一次性要求永久升级。未完成目标是历史候选，不由Worker以carriedTaskIds认定为新任务义务。

旧摘要只能作为带来源的历史投影，不能证明新树通过旧测试。Core结合最新可信环境事实处理旧证据；旧摘要覆盖不匹配或无效时，在可访问的原始历史上重建，不能强行重写coveredThrough。调整保留组数、检索与摘要策略只修改Core，不改bootstrap/Worker协议。Core选择始终不能扩大Worker授予的访问范围。

## 4. 一次调用的数据流与验收

```text
Server固定授权归档与切线来源
→ Worker校验并恢复全部范围内历史/对象，开放只读视图
→ 首次授权SUBMIT记录环境变化
→ Core读取固定Boundary，分组/预算/摘要/检索
→ ModelRequest
```

验证：同一恢复包在不同Core预算下允许生成不同合法请求，Worker开放的历史不变；旧小结果不在prompt里仍可回读；缺失对象不能假装恢复成功；越权Session历史拒绝；旧验证不能当新树证据。具体Entry/Writer接口见05第2.3—2.6。
