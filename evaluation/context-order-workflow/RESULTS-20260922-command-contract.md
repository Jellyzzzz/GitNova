# G80：澄清 runCommand 契约后的真实复测

## 结果与边界

六条 Task 均为 COMPLETED / FINISH_SUCCEEDED；阶段独立验收、逐 Task 写入范围、
Workspace 连续性检查全部通过。最终公开检查 79 项、隐藏检查 1046 项、新增回归 27 项
均通过；把五种原始缺陷分别回植到副本，新回归均能检出。交付 Task 的文件哈希未变，
Workspace generation 保持 6，MySQL 中最终 generation 也为 6。

这证明代码与执行链路通过本次验收，不代表历史证据回查或最终自然语言报告完全正确。
本次原始调查小结果仍不可通过当前读工具寻址，模型再次采用明确标注的 BASE 复现替代。

## 固定条件与证据

- 与上一轮 thinking G80 的 scenario、46 个初始文件、六条任务文本、Docker 镜像逐项相等。
- 主模型 deepseek-v4-flash，thinking=max；摘要 thinking=xhigh，实际规范化为 high。
- 窗口 128000，摘要触发 80%，紧急处理 90%，安全余量 1024，最近保留 2 组。
- 每 Task 40 次主模型调用、80 次工具调用；输出上限 32768，调用/读取超时 300 秒。
- 外置阈值 4096 tokens，预览 1024 tokens；每 Task 观察期限 3600 秒。
- 重新打包加载更新后的 runCommand 描述和参数说明。未修改小结果寻址、摘要提示词、
  Run 状态或实验参数；未重跑 G75、Claude Code，也未覆盖旧批次。
- JAR 不同，且基于当前含既有 WIP 的工作树构建；保留冻结 JAR 摘要，不宣称是可证明
  只有一个变量的多次随机实验。新增契约文字也会增加每轮固定输入。

旧批次：`/Users/zhaoguodong/code/gitnova-orderflow-thinking-20260921-gFrZ0F/G80`。

新批次：`/Users/zhaoguodong/code/gitnova-orderflow-command-contract-20260922-XyM6xr/G80`。

新 Session：`60e6cf83-39f0-403d-9e79-9a58a7707d64`；repoId=28；
Workspace：`78cd2a52-962d-4ead-b2c2-8d8c5498e3df`。

新 JAR SHA-256：`3efc5093250fc5cdde2d08067aba2be740d040c781078cf1949de2f7198656eb`。
父目录 `frozen.json` 保存输入、镜像和 JAR 摘要；`experiment.json` 保存逐阶段结果；
每 Task 的 `run-steps.json`、`summary.json`、Workspace manifests、`verification/` 保存证据。
私有客户端状态包含凭据，不提交、不复制进报告。

## 与上一轮 G80 比较

| 指标 | 上一轮 | 本轮 |
|---|---:|---:|
| 正常结束的 Task | 6/6 | 6/6 |
| 已记录总 tokens（主模型＋摘要） | 3,898,547 | 4,086,931 |
| 主模型 input / output | 3,441,319 / 200,262 | 3,567,018 / 234,774 |
| 摘要总 tokens | 256,966 | 285,139 |
| 主模型调用 | 89 | 89 |
| 发布摘要 | 5 | 6 |
| runCommand 调用 | 43 | 28 |
| readFile 调用 | 115 | 149 |
| 非 SUCCESS 工具结果 | 11 | 6（含 1 次 PARTIAL_SUCCESS） |
| stdout/stderr 捕获不完整 | 2 | 2 |
| 六个 Run 耗时合计（秒） | 1026.65 | 1138.31 |

总 tokens 增加 188,384（4.83%），不能宣称本轮节省了总成本。
命令调用减少 34.9%，但文件读取增加 34 次，主模型调用总数未下降：执行路径发生转移，
不能把所有 runCommand 减少都解释为消灭了无效轮次。耗时不包括启动、push 和独立验收。
本轮 89 个 MODEL_CALL_STARTED 对应 89 个 MODEL_RESPONSE；6 个摘要结果均包含 usage，
没有已识别的缺失 usage。Token 总量包含重复/缓存输入与推理输出，不等于账单金额。

| Task | 上轮 tokens / 主调用 | 本轮 tokens / 主调用 | 本轮摘要数 |
|---|---:|---:|---:|
| investigate | 384,231 / 17 | 458,845 / 20 | 0 |
| pricing | 525,728 / 14 | 331,106 / 4 | 1 |
| cancellation | 643,634 / 14 | 773,510 / 15 | 1 |
| requests | 786,883 / 19 | 1,378,874 / 27 | 2 |
| regression | 924,698 / 15 | 851,070 / 17 | 2 |
| delivery | 633,373 / 10 | 293,526 / 6 | 0 |

## 模型选择：可以直接复查的案例

### 1. 临时探针遵守了单次调用生命周期

计价、取消和请求探针均把临时目录创建、源码写入、编译和执行放在一次调用内。
本轮未观察到跨调用依赖 /tmp 导致文件消失。仍有环境假设错误：交付 sequence 448
尝试使用镜像没有安装的 python3，exitCode=3；随后改用 awk/sed，于 sequence 451 完成复现。
不能把工具说明清晰等同于模型完全理解镜像能力。

### 2. 有一次重跑来自捕获丢失，不是单纯重复核对

计价探针 sequence 118：exitCode=0，但 stdout 捕获到 8192 bytes 后截断，199 项检查的
末尾汇总未捕获。模型在 sequence 124 改成失败行＋汇总，得到
`PROBE SUMMARY suite=edge-regression checks=199 failed=0`（56 bytes）。
两次均为 generation 1；未捕获的内容无法靠回读 Artifact 找回。

### 3. 摘要本身也在建议重新验证已完成事项

请求修复 sequence 271 已记录 `sh run-tests.sh all`、exitCode=0、generation 3、全绿。
sequence 291 的摘要明确保留这一事实，却同时在 Next Steps 写入：

> （可选）如需再确认无回归，重跑 `sh run-tests.sh all`

sequence 318 模型再次执行同一命令，generation 仍为 3。这是一个明确的重复执行样本。
不能简单归因于摘要忘记了通过结果；摘要保留结果，同时又提供了重跑建议。
该时序支持“摘要中的待办建议可能诱发重复”的判断，但单次样本不能证明唯一因果。

### 4. 小结果历史仍没有直接回查入口

原始 generation 0 的全套失败日志仍在本 Session 的 sequence 75，`failed=37`。
它是 inline 小结果，没有签发可读 Artifact。本次交付没有虚构这个日志的 Artifact 路径；
读取的是实际已签发的 `artifact://tool-results/417/diff.patch`，然后在 /tmp 重建 BASE 行为。
最终报告明确说复现不是原始日志，并得到复现 `failed=37`。这避免了冒充原始证据，
但没有完成原始日志的直接回查；仍然付出了重新构建/执行的成本。

报告还有两处语义边界：把上个回归 Task 的 generation 5 失败称为“本任务的早前尝试”；
把目录没有 .git 作为“没有 commit”的理由。前者混淆 Task 归属；后者不适用于 GitNova
的自定义 Commit/ObjectStorage 模型。不能用源码验收通过替代报告准确性检查。

### 5. 剩余工具纠错没有消失

- sequence 158：searchText 的 Workspace path 使用不合规，INVALID_SEARCH_PATH。
- sequence 190：Snapshot 场景调用 getDiff，REVIEW_DIFF_SCOPE_REQUIRED。
- sequence 196：finishTask finding 带未知字段 startLine_dup，INVALID_COMPLETION_DRAFT。
- sequence 312：猜错 Money.java 所在包，FILE_NOT_FOUND。
- sequence 317：单个 shell 参数 10918 UTF-8 bytes，超过 8192 上限；拒绝执行。
  模型随后拆成各自完整的一次性探针，未通过跨调用 /tmp 拼接绕过限制。
- sequence 392：applyPatch 已创建 EdgeRegression.java，但 run-tests.sh 的 UPDATE 不匹配，
  返回 PARTIAL_SUCCESS，generation 3→4。已成功文件未回滚；模型后续用 edit 完成修正。

此外，模型编写的临时探针和回归中出现过错误期望值，再修正测试重跑。这属于测试构造
质量问题，不是 JSON/schema 错误，也不能把所有非零命令退出码算作基础设施失败。

## 本轮结论

契约澄清有可观察的正向行为，但没有使总 tokens 或主模型轮次下降。下一步值得分别验证：
已提交 inline 结果的确定性历史读取；摘要不得无依据地重新打开已完成验证；
输出捕获完整性与预览省略的独立处理。这些均未在本轮实验中修改。

实验结束后确认：无 QUEUED/RUNNING Run、8080 无监听、无运行中容器。仅本轮 Spring
服务自动退出；数据库、中间件、实验仓库与原始证据保留。未提交或推送代码。
