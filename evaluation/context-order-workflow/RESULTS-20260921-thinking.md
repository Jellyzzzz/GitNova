# OrderFlow：G80 / G75 Thinking 真实链路对照

本批使用真实 HTTP、MySQL、Redis、RabbitMQ、DeepSeek API 和无网络 Docker；不是 JUnit 模拟。
每组从相同的 46 个公共文件创建独立 Session / Workspace，顺序执行六条 Task。
只把 repository/ 推送给模型，独立 oracle 不进入模型工作区。不重跑 Claude Code，不覆盖旧样本。

## 固定参数

- 主模型：deepseek-v4-flash，thinking enabled，reasoning_effort=max。
- 摘要：同模型，独立 thinking enabled，xhigh 规范化为 high。
- 窗口 128000；安全余量 1024；输出上限 32768；保留最近 2 组。
- G80 / G75 普通摘要阈值分别为 80% / 75%；紧急处理阈值均 90%。
- 每 Task 最多 40 次主模型调用、80 次工具调用；模型调用/读取超时 300 秒。
- 每 Task 实验观察上限 3600 秒，不是新增的 Runtime 执行语义。
- 外置阈值 4096 tokens，预览 1024 tokens；两组都包含新 edit 工具。
- thinking 请求不发送 temperature；不能把冻结 policy 中的 temperature=0 当作确定性保证。

请求输入上限为 `128000 - 32768 - 1024 = 94208`，还需从中扣除 system/tools 的固定部分
才得到动态预算。阈值针对动态预算，不是直接对 128000 乘 80% 或 75%。

本轮补齐摘要独立推理开关（此前摘要请求明确关闭 thinking），并把它保存到 Task 的冻结
执行配置。旧合同不注入新默认值。客户端在提交后核对数据库中的主/摘要 thinking 配置。
默认测试 693 项与网关测试 50 项通过，0 failures/errors/skipped；重新打包后才启动实验。

## 私有证据

批次目录：`/Users/zhaoguodong/code/gitnova-orderflow-thinking-20260921-gFrZ0F`。
`frozen.json` 保存源文件、任务文本、参数、JAR 和镜像摘要；`G80/experiment.json` 与
`G75/experiment.json` 保存分阶段结果。每 Task 保留原始 Steps、最终 Run、Workspace
manifest 及独立验证结果。凭据仅在私有客户端状态中，不复制进本报告。

G80 Session：`1df592cb-7a17-4c35-adb5-85c5c903b362`。
G75 Session：`a9327c89-f4f4-46a8-be64-84f44816b2c3`。

## 运行结果

G80 六条 Task 全部 COMPLETED。G75 第 1–4、6 条 COMPLETED，第 5 条仍为
PARTIAL / MODEL_GATEWAY_FAILURE；不能把最终实验状态理解成六条 Run 全部成功。
两组各阶段的独立代码验收、写入范围、Workspace 连续性检查均通过；历史证据质量另列。

| Task | G80 已知 tokens（含摘要） | G80 主响应 / 摘要 | G75 已知 tokens（含摘要） | G75 主响应 / 摘要 |
|---|---:|---:|---:|---:|
| investigate | 384231 | 17 / 0 | 299950 | 12 / 0 |
| pricing | 525728 | 14 / 1 | 689919 | 12 / 1 |
| cancellation | 643634 | 14 / 0 | 1105685 | 23 / 1 |
| requests | 786883 | 19 / 1 | 819050 | 19 / 1 |
| regression | 924698 | 15 / 1 | 1335561 + 未知用量 | 28 / 3 |
| delivery | 633373 | 10 / 2 | 491043 | 10 / 1 |
| 合计 | 3898547 | 89 / 5 | 4741208 + 未知用量 | 104 / 7 |

| 指标 | G80 | G75 |
|---|---:|---:|
| 主模型已知 input / output | 3441319 / 200262 | 4188153 / 238955 |
| 主模型已知总 tokens | 3641581 | 4427108 |
| 摘要 input / output | 217089 / 39877 | 263273 / 50827 |
| 摘要总 tokens | 256966 | 314100 |
| 主请求 STARTED / RESPONSE | 89 / 89 | 105 / 104 |
| 发布摘要 / 摘要响应 | 5 / 5 | 7 / 7 |
| 最大单次实际输入 | 74505 | 71298 |
| 非 SUCCESS 工具结果 | 11 | 6 |
| 六个 Run 耗时合计（秒） | 1026.65 | 1233.99 |
| 最终 Workspace generation | 8 | 7 |

Run 时间不包括启动、push、人工确认和外部验收耗时。Token 总量包含重复/缓存输入，
output 包含供应商报告的思考消耗，不等于账单金额。G75 有一次被网关拒收的响应，
usage 未落库，4741208 只是已知下界，不按零补齐。

旧版实验统计的 `unknownMainUsage` 仅检查已存在的 MODEL_RESPONSE，误将该组写成 0。
本次核对 STARTED 与 RESPONSE 的 modelCallId 后确认缺少一次；统计脚本已补计未返回调用。
原始实验快照不覆盖，校正量见本批 `usage-audit.json`。所有 Run 状态与原始 Step 均未修改。

## G75 第五条失败与第六条续接

- 第五条 Run：`0e176f64-85fd-4215-b711-64f27b1e83ef`。第 29 次请求返回 HTTP 200，
  网关报 INVALID_RESPONSE，retryable=false；不是模型调用次数耗尽，也不是已确认的超时。
  原始日志位置：`G75/server.log:136`。日志没有具体校验原因和响应体，因此尚不能确认
  是哪个字段或协议规则导致拒收，不能直接归因于供应商或 thinking。
- 第五条代码、公开检查、隐藏 oracle、五个原始缺陷回植检查都通过。模型新增 54 条回归
  检查；原始缺陷回植到副本后均能被检测，不是仅凭模型声称通过。
- 用户明确授权后，保留第五条 PARTIAL，并以同一个 Session / Workspace 新建原定的
  第六条只读 Task。不是重放第五条，也不是复活旧 Run。
- 第六条 Run：`f7c74841-8344-462f-9f81-c2b2a7efb6cf`，COMPLETED / FINISH_SUCCEEDED。
  10 次主响应、1 次摘要、491043 tokens、183.83 秒；无工具错误，文件哈希未变，
  generation 7→7。最终公开 79 项、独立 oracle 1046 项和新回归 54 项均通过。
- `G75/experiment-before-delivery.json` 保留续接前失败样本；
  `G75/experiment.json` 为 COMPLETED_WITH_PRIOR_FAILURE。旧 Task 保持 WAITING_USER、
  旧 Run 保持 PARTIAL；新 Task 成功不能替代对旧 Task 生命周期的收口。

这证明真实服务重启后，同一 Session 的既有工作区和历史能被下一条 Task 使用。
不等于旧 Run 原地恢复、任意宕机恢复或物理 Workspace 丢失后的重建已得到验证。

## G80 已确认的质量边界

代码通过独立检查，不代表历史回读全部成功。

- Task 5 正常完成，新回归能检出逐个回植的原始缺陷，不再因 40 次模型预算耗尽而 PARTIAL。
- 主模型真实返回 reasoning_content，后续工具调用及跨 Task 历史恢复继续成功。
- 摘要实际发布于 Session sequence 116 / 240 / 336 / 399 / 431；对应后续主请求实际输入
  为 9270 / 13830 / 39857 / 33731 / 21607 tokens，不是只生成但未使用。
- 11 次非 SUCCESS 工具结果：6 次 review-scope 不适用、3 次文件路径不存在、1 次搜索路径
  不合规、1 次 Artifact 不存在。不能把它们统称为 JSON/schema 参数错误。
- 最初原始失败日志仍完整保存在 Session Step 85，`failed=37`；并未从 MySQL 丢失。
  它当时是 inline 小结果，没有可供当前读工具寻址的 Artifact。
- 第一次摘要正确保留 37 个失败及其来源；滚动摘要后来只保留 generation 3 的中间状态
  `failed=10`，交付阶段不再拥有完整初始证据。最终报告承认原始日志不可回读，将 BASE
  重建结果明确标成替代证据，没有把重跑冒称最初运行。
- 尝试 `artifact://tool-results/1/result.json` 被拒。猜一个不存在的引用，不能证明整个
  历史不存在；当前缺的是 inline 历史经过摘要后可按来源回查的入口。

## G75 的历史保真反例

G75 也完成了真实的滚动摘要与后续使用。摘要发布 sequence 为
122 / 205 / 278 / 363 / 418 / 458 / 522；各自下一次主响应的 inputTokens 为
16159 / 14163 / 13519 / 16001 / 38690 / 21418 / 23615。

但最终交付报告并未完全通过历史准确性检查：

1. 原始 Step 76（generation 0）仍完整记录 `failed=37`，其中 pricing 22、cancellation 3。
   第一次摘要 sequence 122 也准确记录了这些数量和来源。
2. 后续摘要逐渐只留下 generation 3 的中间失败记录；sequence 522 将其称作“最初调查”。
   **中间状态被误当初始状态**，不是数据库丢失原始日志。
3. 交付报告承认无法逐字回读原始日志，这一点正确；但又将计价/运费的历史案例缺失写成
   “当时测试未覆盖的盲区”。原始 Step 76 明确有 22 个计价失败，这个推断错误。
4. 报告还沿用了上个 Task 的“本任务授权两个测试文件”措辞，而第六条本身只读。
   实际文件检查确认未越权，但自然语言中的 Task 归属未完全刷新。

因此结论是：**代码正确性和约束遵守通过；原始日志回查未完成，历史阶段保真未通过。**
不能由 COMPLETED 或 verifier 的源码检查推出语义报告全部正确。

G75 共 6 次工具失败：edit 原文未找到 1 次、命令单参数过长 1 次、文件不存在 2 次、
搜索路径不合规 2 次。第六条使用了服务端给出的 Artifact 读取入口，没有再次猜造引用。

## 比较限制

G80 与上轮 report-contract 样本（4972468 tokens）相比，本次总量减少约 21.6%，且 Task 5
从 PARTIAL 变为正常结束。但 edit、thinking、输出额度、超时、可用动态输入预算均变化，
不能把差额单独归因于任一项优化。

本轮 G80/G75 配置间仅摘要阈值不同，但首个 Task 尚未摘要时，调用路径和成本已经不同
（384231 对 299950 tokens）。每组单次样本不足以证明哪个阈值最优。
本次 G80 已知总消耗比 G75 低约 17.8%，使用 5 次摘要而不是 7 次；只能作为该样本的观察，
不能证明提前摘要必然更贵，也不能把全部差异归给阈值。
强压缩、RAG/历史回读仍不能仅凭本实验宣称完成。

## 下一步应针对的缺口（本批未改变运行契约）

- 给 INVALID_RESPONSE 保留安全、可枚举的具体校验原因及请求标识，不直接把密钥、
  完整 response 或 reasoning_content 打进普通日志，再对失败分类做离线回放。
- 小结果仍可直接内置，但“首次无需回读”不等于“摘要后永久无需回读”。为已持久化 Step
  提供 Session 鉴权下的确定性历史读取入口，避免迫使模型猜 Artifact 路径。
- 让历史事实携带明确的 source sequence / generation / Task 范围。重点验收
  “缺少记录不能推导从未发生”和“generation 3 不能被改写为最初 generation 0”。

实验结束后已确认：数据库无 QUEUED/RUNNING Run；本批 Spring 服务已停止，8080 空闲，
无残留执行容器。MySQL / Redis / RabbitMQ 等基础服务未停止。没有提交或推送代码。

DeepSeek 参数与模式语义参考：[官方 Thinking Mode 文档](https://api-docs.deepseek.com/guides/thinking_mode/)。
