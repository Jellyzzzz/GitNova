# OrderFlow：跨 Task 上下文连续性场景

本目录是独立实验夹具，不修改 GitNova Runtime、Session、Workspace 或 Run 状态语义。
只有 repository/ 可以推送给 Agent。tasks/ 是依次发送的相同用户请求；
oracle/、verify.py、scenario.json、结果报告绝不进入模型 Workspace。

## 要回答的问题

1. 大结果外置开启后，真实跨模块任务产生的历史能否自然触发摘要？
2. 摘要后是否仍遵守早期约束、区分历史失败与当前验证、正确回读历史证据？
3. 同一条件下 70%、75%、80%、85% 触发线有什么代价与质量差异？
4. Claude Code + 同一 DeepSeek 模型在这些任务上的行为和成本如何？

仓库文件数量不是触发证据。先校准 G80；若没有 CONTEXT_SUMMARY_CREATED 且其后没有
实际模型调用及后续任务，就不能声称摘要已验证。不得注入无关长文、强制调用摘要、缩小
窗口后仍沿用原实验名称。若要增加任务深度，应先更新场景并重新冻结所有对照条件。

## 业务与任务

OrderFlow 是无依赖 Java 21 内存业务模型，不模拟真实数据库或分布式事务。
真实复杂度来自请求规范化、金额边界、状态机、共享库存、幂等与逐行批处理的交叉影响。
六条 Task 在同一 Session 中顺序运行，不重置 Workspace：

1. 只读调查，记录业务约束、失败样例和原始执行证据。
2. 修复计价与运费；其他已知缺陷可以暂留。
3. 修复取消与库存一致性；不能破坏 Task 2。
4. 修复请求幂等与批处理；不能回滚前面修复。
5. 添加跨模块回归，证明测试能发现原缺陷。
6. 只读交付审核，比较最初失败与当前事实，引用原始证据。

## 对照协议

scenario.json 是实验清单；入口是本目录的 run_experiment.py，不是旧 run_multitask.py。
它复用 context-large-output/live_client.py 的 HTTP 登录/push、Step 导出及生命周期检查，
通过 fixture-root/scenario 参数读取本场景。Claude Code 也复用已有 CLI 入口，使用本场景的
分阶段验收器；不复制后端协议，不把参考修复写进实际候选。

GitNova 各组只改变普通摘要触发比例，外置均开启，强压缩阈值均为90%。
强压缩目前未实现；遇到受控停止保留失败样本，不自动复活旧 Run。
Claude Code 为第四个参考组，使用原生工具和原生上下文处理，六条 Task 通过同一 CLI
Session 续接。不得为了对齐而给 CC 注入 GitNova 的摘要或工具结果。

所有组固定源文件与任务摘要、模型名、工具/权限清单、镜像、输出额度、超时和调用预算。
记录 CC CLI 版本、实际响应模型、可观测 thinking/effort、原生工具配置、有效窗口与实际
压缩事件。配置声明不等于 Provider 实际行为；无法对齐的项目写入报告。
没有验证生效的 CLI 环境变量不得当作有效窗口设置。
模型工作目录只放 public repository，验收器和参考修复不复制进去。
CC 使用独立配置目录，关闭个人 instructions、memory、MCP 和插件的自动加载；只传入模型
凭据，不继承数据库密码。注意这不是主机文件系统级隔离：CC 原生 Read/Glob/Grep 仍由 CLI
权限系统控制，不能把它等同于 GitNova 的无网络 Docker 执行环境。
CC 保留原生 shell，但仅允许测试入口和只读 Git/目录命令；复杂 shell 命令可能被拒绝，
拒绝次数及其影响必须进入报告，不能把本对照称作不受限的原生 CC 最佳表现。

## 验收与统计

独立 Java oracle 只依赖公开业务 API，不读取参考修复；参考文件只用于验收器自检。
自检应同时验证 baseline 失败、每阶段已修复部分通过、完整修复通过，以及回植单个缺陷
会失败。它不是模型对照结果。任务5的候选回归必须另外在回植缺陷副本上验证有效性。

模型可见公开检查可失败而调查 Task 仍成功；阶段验收使用各 Task 的 requiredSuites，
不能要求 Task 2 提前修好 Task 4，也不能用全套测试失败直接否定合法阶段完成。
只读 Task 必须文件哈希不变；写 Task 只允许 editableFiles 中的路径。
任何 Task 失败都保留状态和证据；依赖阶段不得静默重跑或用参考修复替换候选。

采集每次请求输入估算与 Provider usage、输出、摘要输入/输出/耗时、实际发布摘要、
摘要前后范围与字数、下一次摘要间隔、工具错误、Artifact 回读、每阶段独立验收。
累计成本包含主模型和摘要调用；未知 usage 单列，不能按零计算。
CC 缓存输入与非缓存输入分别列出，同时报告总量；不能把 tokens 当实际费用。
不得跨系统重复累加 response usage 与 CLI 已聚合的最终 usage。

冻结质量断言见 evidence-checks.json。机器检查负责实际行为、状态、源码保护与可核对字段；
摘要语义保真及报告引用需要原文审查，不能把关键词包含测试等同事实正确。

## 本地验收

在 GitNova 根目录：

~~~sh
python3 evaluation/context-order-workflow/verify.py --output target/orderflow
~~~

只运行无网络 Docker 编译/检查，不调用模型、不启动 HTTP 服务或读写业务数据库。
输出目录为新建子目录，不覆盖先前结果。

给定真实候选（阶段编号 1…6）：

~~~sh
python3 evaluation/context-order-workflow/verify.py --candidate /absolute/workspace \
  --stage 4 --output target/orderflow-candidate
~~~

候选阶段1用 contract/inventory 健康检查；全套已知缺陷仍作为观察记录。
Task级精确写入范围应由运行前后 manifest 比较，验收器还检查累计可修改范围。

## 真实请求入口

先在同一个启动 shell 加载本机未入库配置，确保 MySQL、Redis、RabbitMQ 和 Docker 已启动。
8080 必须空闲，数据库不能有待执行/执行中的旧 Run。脚本不会为了试验杀掉已有服务。

~~~sh
set -a
source .env.local
set +a
export MYSQL_PWD="${DB_PASSWORD}"
# DB_PASSWORD 未配置时，通过本地安全方式设置 MYSQL_PWD，不把密码写进命令参数或提交文件。

python3 evaluation/context-order-workflow/run_experiment.py \
  --output /absolute/fresh-experiment-directory --arm G80
~~~

脚本会通过本地 Gitlet CLI 建仓提交，再经真实 HTTP 注册、登录、建库、push、建 Session、
依次发起六个 Task。查询和导出数据库只读，不通过 SQL 创建成功状态。每个 Task 验收后再
进入下一个；Run 未完成、越权修改或独立验收失败都会停止后续依赖任务。

输出目录中的 frozen.json 固定源文件、任务、配置、JAR 和镜像。不能覆盖已有 arm。
同一批后续组使用同一个 output，将 arm 改成 G70/G75/G85/CC。CC 不经过 HTTP，但使用相同任务
与初始仓库，六条任务用同一个 CLI Session 续接；CLI max-turns 不等于 GitNova model calls。

用户批准的工具额度校准示例：

~~~sh
python3 evaluation/context-order-workflow/run_experiment.py \
  --output /absolute/another-fresh-directory --arm G80 --max-tool-calls 80
~~~

这只改变工具上限，不改变模型调用上限或窗口。后续组必须传入相同覆盖值；实际配置保存在
effective-scenario.json。旧批次保持原样，失败样本不能用新结果覆盖。

Thinking 对照使用独立新批次，主模型与摘要模型可分别指定：

~~~sh
python3 evaluation/context-order-workflow/run_experiment.py \
  --output /absolute/fresh-thinking-directory --arm G80 \
  --max-tool-calls 80 --max-model-calls 40 --max-output-tokens 32768 \
  --timeout-seconds 300 --task-timeout-seconds 3600 \
  --reasoning-effort max --summary-reasoning-effort xhigh
~~~

接着用完全相同的参数运行 G75。`xhigh` 在请求前规范化为 DeepSeek `high`，两份 thinking
配置均由 Task 冻结；客户端核对数据库实际配置，不只相信启动参数。未指定的摘要 thinking
默认关闭，不继承主模型。输出上限包括思考消耗，增加它也会减少动态上下文可用空间；
因此本批可比较 G80/G75，但与旧的无思考、8192 输出样本不是单变量对照。

若第五条 Run 未正常结束，但五个阶段的独立代码验收、写入范围和工作区连续性均通过，
经用户明确批准后，可用同一个 output、arm 与全部冻结参数追加 `--continue-delivery`。
它只提交原定第六条只读 Task，不重跑第五条、不修改旧 Run 状态；先保存
`experiment-before-delivery.json`，并另存 `delivery-continuation-server.log`。
交付成功后的实验状态为 `COMPLETED_WITH_PRIOR_FAILURE`，不是六条 Run 全部成功。
`unknownMainUsage` 包含已 STARTED 但没有已持久化 RESPONSE 的调用，不能将其成本按零计算。

第二次预算校准（用户批准输出及必要执行预算翻倍）使用新目录：

~~~sh
python3 evaluation/context-order-workflow/run_experiment.py \
  --output /absolute/expanded-budget-directory --arm G80 \
  --max-tool-calls 80 --max-model-calls 40 --max-output-tokens 8192 \
  --timeout-seconds 120 --task-timeout-seconds 1800
~~~

窗口、摘要阈值和外置预览额度不变。CC 必须同时保留 result.usage 与 result.modelUsage；
本次发现原生压缩调用可能只包含在后者。总量按 modelUsage 汇总一次（包括缓存输入），
不能只看 usage，也不能把两者相加。CC stream-json 同一 messageId 可分多条内容块输出，
统计 thinking 时不能只保留最后一条工具/文本块。

注意脚本正常退出只代表收集流程结束：业务是否成功必须看 experiment.json 的 status、
每个 Task 的独立验收，以及最后交付报告的证据核对。校准未触发摘要时，不自动跑全套阈值
比较。目录中的 .client-state.json 和 claude-state 属于本机私有运行材料，不提交到 Git。

实际运行结论见同目录 RESULTS-20260921-calibration.md。
