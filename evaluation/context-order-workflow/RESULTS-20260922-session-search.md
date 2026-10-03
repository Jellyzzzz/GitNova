# G80：Session 小结果历史搜索接线后的真实复测

## 结果与边界

六条 Task 全部 COMPLETED / FINISH_SUCCEEDED，阶段独立验收、逐 Task 写入范围、
Workspace 连续性均通过。最终公开检查 79 项、隐藏检查 1046 项、新回归 64 项通过；
五种原始缺陷分别回植到副本，新回归均检出。交付阶段前后 Workspace manifest 完全相等，
generation 保持 8，MySQL 最终记录也为 8。

本轮补齐并实际使用了「摘要后发现并回读 inline 历史小结果」的链路：第六条 Task 从
Session 搜索找到第一条 Task 的原始失败日志，引用真实 expected/actual，未重跑 BASE
冒充原始证据。但总 tokens 比上一轮增加 10.16%，不属于降本成功，也不证明报告完全正确。

## 固定条件与改动

- 对照上一轮 command-contract G80：scenario、46 个初始文件、六条任务文本、Docker
  镜像摘要逐项一致。通过真实 HTTP 注册、登录、建仓库、Gitlet push、创建 Session 和
  六个连续 Task；MySQL、Redis、RabbitMQ、Docker、DeepSeek 均为实际服务。
- 主模型 deepseek-v4-flash，thinking=max；摘要 xhigh（映射 high）。窗口 128000，
  摘要触发 80%，紧急处理 90%，安全余量 1024，近期保留 2 组。
- 每 Task 主调用 40 次、工具 80 次；输出 32768，调用/读取超时 300 秒；外置阈值
  4096 tokens，预览 1024 tokens，Task 观察期限 3600 秒。未中途调参或覆盖旧批次。
- 新增 Session 已提交 Tool Results 搜索、inline 历史视图回读；摘要提示词只补充用户
  Constraints & Preferences 的来源、适用范围和明确更新规则，没有新增 Task 语义系统。
- 本次审阅修正 SearchTextTool 重复 path schema、合并重复前缀判断，补充集合搜索入口、
  小结果可检索、空页仍需 follow nextRequest 等描述；没有增加 helper 类。
- 构建包含既有 WIP，包括用户移除 GetDiffTool/ListChangesTool 的 @Component。
  工具集合与提示词也发生变化，不是严格单变量实验；新增回归断言数量也不同。
- 未重跑 G75 或 Claude Code，未修改 Run 状态契约，未提交或推送。

旧批次：`/Users/zhaoguodong/code/gitnova-orderflow-command-contract-20260922-XyM6xr/G80`。

本轮：`/Users/zhaoguodong/code/gitnova-orderflow-session-search-20260922-djhxSq/G80`。

Session `af494231-1225-41a4-8212-c399afa19984`，repoId=29，
Workspace `41da1f0b-950e-4df9-848e-6abc91a4c665`。

冻结 JAR SHA-256：`5e10fbb85eb149e2b68e48d35ef230b1bbb163b5a1b2151f43968bd4293b31bd`。
父目录 frozen.json、experiment.json、各 Task 的 run-steps.json、Workspace manifests、
verification/report.json 保留原始证据。私有客户端凭据文件不复制到报告、不提交。

## 总量比较

| 指标 | 上轮 command-contract | 本轮 Session search |
|---|---:|---:|
| 正常结束 Task | 6/6 | 6/6 |
| 主模型＋摘要 tokens | 4,086,931 | 4,502,047 |
| 主模型 input / output | 3,567,018 / 234,774 | 3,971,724 / 226,456 |
| 摘要 tokens | 285,139 | 303,867 |
| 主模型调用 | 89 | 94 |
| 发布摘要 | 6 | 6 |
| runCommand 调用 | 28 | 26 |
| readFile 调用 | 149 | 148 |
| 非 SUCCESS 工具结果 | 6 | 5 |
| stdout/stderr 捕获不完整 | 2 | 0 |
| 六个 Run 耗时合计（秒） | 1,138.31 | 1,159.63 |

本轮主调用均有响应，usage 无已识别缺失；总量包括推理、重复/缓存输入，不等于账单
金额。Run 耗时不含启动、push、独立验收。新增 415,116 tokens，不能仅依据此单次结果
认定搜索必然增加成本，也不能因为能回读就宣称更省。

| Task | 上轮 tokens / 主调用 | 本轮 tokens / 主调用 | 本轮摘要数 |
|---|---:|---:|---:|
| investigate | 458,845 / 20 | 523,582 / 21 | 0 |
| pricing | 331,106 / 4 | 476,222 / 10 | 1 |
| cancellation | 773,510 / 15 | 812,531 / 15 | 1 |
| requests | 1,378,874 / 27 | 643,607 / 12 | 1 |
| regression | 851,070 / 17 | 1,394,063 / 24 | 2 |
| delivery | 293,526 / 6 | 652,042 / 12 | 1 |

## 历史回读：可以核对的实际调用链

1. 第一条 Task 的 sequence 79：runCommand，generation 0，exitCode=1，stdout 完整
   捕获 87 行，末行 `ALL SUMMARY selection=all failed=37`。它未被外置。
2. 到第六条 Task，已经经过五次摘要。sequence 433/437 搜索 `|FAIL`，前两页无匹配，
   hasMore=true；模型继续使用 nextRequest。
3. sequence 441 命中 `artifact://tool-results/79/stdout.txt`，附 sourceTaskId、
   sourceRunId、sourceStepSequence=79、sourceGeneration=0 和 readRequest。
4. sequence 445/449/456 用 readFile 和返回的 cursor 读完原始 87 行；另有搜索页读取
   其中的失败行。来源是已提交 TOOL_RESULT，不是重新运行命令，也没有递归新建 Artifact。
5. sequence 494 的 finishTask 报告引用真实案例，例如：

   - L20：pricing/1x50，expected=1/0/600/600，actual=0/1/600/601。
   - L58：cancellation/repeat-no-event，expected=2，actual=3。
   - L67：idempotency/reordered-replays，expected=true，actual=IllegalStateException:request intent conflict。
   - L75：batch/bad-middle-keeps-tail，expected=3/2/1/998，actual=2/1/1/999。

6. 当前验证独立取得：sequence 470 在 generation 8 运行测试，79 项旧断言＋64 项新
   回归全过，未拿 generation 0 的历史结果当作当前验证。交付只有两次 runCommand：
   当前测试和环境检查，没有还原/重跑 BASE。

这证明「持久化历史 → Session 搜索 → 原始视图读取 → 后续 Task 使用」已经实际闭环。
摘要之外的原文仍可访问，不要求当初的小结果必须先被 Artifact 化。

## 暴露的成本和语义问题

### 空页成本真实存在

全轮 19 次 searchText，其中 17 次为 Session 历史搜索，11 次返回空页且搜索未结束；
另有 7 次历史 readFile。当前从旧到新，每页最多检查 20 条 Tool Result，前面大量
readFile 源码结果会先占扫描名额。page 输出预算 2048 tokens 是另一独立限制。

第一条 Task sequence 90/94 搜索目标日志，连续两页均未扫描到 79。模型只续读一次，
然后在 97/98/102 换查询从头开始，最终 sequence 105 重跑同一套测试，generation 仍为 0。
因此不能说「工具可访问」就等于「模型能高效取到」。

第六条 Task 已经取得原日志后，仍在 465/466/481 换查询重新扫描集合，得到空的首页。
它还并行分页搜索同一日志、读取同一日志，存在重复取证。下一步可讨论扩大单次后台
扫描范围但维持输出预算、增加来源工具/Task 过滤、同一来源元数据合并；本轮未继续改。

### 搜索命中不等于原始运行事实

sequence 438 的 `ALL SUMMARY` 首个命中来自 Step 69/readFile：是测试代码里的打印
语句，不是运行日志。结果已携带 toolName、sourceStep，后续取得 Step 79/runCommand
才形成执行证据。模型必须继续区分源码、历史运行与模型自己的完成报告。

### 报告仍有误判

最终报告把 `git rev-parse` 的 “not a git repository” 解释为“没有 commit 面”，
这不适用于 GitNova 的自定义 Commit/ObjectStorage，不能据此证明未提交；本轮无
提交应依据工具调用记录与服务端行为。报告还把历史搜索概括为“只扫描两页”，而实际
交付阶段有 12 次 Session 搜索、多个查询/cursor 流。工具来源元数据不会自动保证
模型对整个过程的总结准确。

### 其它纠错与提示词观察

- sequence 147/148：FILE_NOT_FOUND；278：STALE_WORKSPACE_GENERATION；
  286/289：COMMAND_ARGUMENT_TOO_LARGE。均保留原始失败记录，未调整上限绕过。
- 首次摘要 sequence 131 将原始 Session 约束与 Task 2 的计价范围分开，并保留来源
  sequence。是本次样本的正向观察，不代表 Constraints 提示词能确定性保证后续语义。
- 本轮新增回归为 64 项，上轮为 27 项；二者均检出五种回植缺陷。断言数量不能直接
  当作覆盖质量，因此 Task 5 成本增长也不能全部视作无效轮次。

## 本地回归与收尾

接线后六个定向测试类合计 94 项，0 失败、0 错误、0 跳过；打包及 git diff --check
通过。这不冒充全量 Maven 测试。真实实验单独完成上述 HTTP/中间件/模型验收。

结束后确认本 Session 六个 Run 全部 COMPLETED，8080 无监听，无运行中容器。
仅本轮 Spring 服务自动退出；数据库、中间件、实验仓库和证据保留。
