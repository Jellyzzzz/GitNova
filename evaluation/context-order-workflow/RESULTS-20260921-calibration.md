# OrderFlow 仓库自检与真实调用校准

本报告保留所有失败样本；不是挑选成功路径的成绩单。未改 Run 状态、终止语义或生产业务代码。

## 仓库与验收

- public repository：46 文件，53,382 bytes，1,230 行；Java 21、无外部依赖的顺序内存订单模型。
- 五个原始缺陷：金额舍入、净额运费、取消副作用、请求规范化、批处理错误后继续。
- 六个连续 Task：调查 → 计价 → 取消 → 幂等/批处理 → 新回归 → 只读交付。
- 同一 Session / Workspace；每阶段比较文件 SHA，既有测试和契约不能被模型修改。
- 79 项公开检查，1,046 项独立检查。新增回归另做“五个原缺陷逐个回植”的变异检查。
- 验收器、参考修复和结果报告不推送到模型仓库；模型修复没有使用参考答案替换。

自检报告：`target/orderflow/verification-xw6c5265/report.json`。
10 个场景均符合预期；参考回归另能检出五个原缺陷。先前参考测试编译错误也保留在
`target/orderflow/verification-anfl7vl5/`，修正的是夹具的 lambda 捕获，不是运行中的模型候选。

| 自检候选 | 公开失败数 | 独立失败数 | 预期验证 |
|---|---:|---:|---|
| 原始仓库 | 37 | 643 | 原缺陷确实失败，健康契约通过 |
| 修完计价 | 14 | 168 | 本阶段要求通过 |
| 再修取消 | 10 | 104 | 本阶段要求通过 |
| 完整参考修复 | 0 | 0 | 全部通过 |
| 单独回植舍入缺陷 | 13 | 337 | 检出 |
| 单独回植运费缺陷 | 13 | 215 | 检出 |
| 单独回植取消缺陷 | 4 | 84 | 检出 |
| 单独回植指纹缺陷 | 7 | 69 | 检出 |
| 单独回植批处理缺陷 | 5 | 36 | 检出 |
| 参考回归 | 0 | 0 | 回归通过，五个变异均检出 |

共享旧 pricing 验收器的四个自检场景也通过，证据位于
`target/orderflow-shared-regression/verification-uu8oretj/report.json`。
这些是夹具/验收器自检，不是模型成功次数。

## 两次保留的 GitNova 失败校准

窗口始终 128,000，普通摘要阈值 80%，输出 4,096，最多 20 次模型调用。
均走真实注册/登录/建库/CLI push/Session/Task/Worker/Runtime，SQL 只用于查询证据。

| 批次 | 工具上限 | Task 1 结果 | 主模型 tokens | 单次输入峰值 | 摘要 |
|---|---:|---|---:|---:|---:|
| mN3a2O / G80 | 35 | PARTIAL / MAX_TOOL_CALLS_REACHED | 123,463 | 22,042 | 0 |
| ysbFSr / G80 | 80 | PARTIAL / MODEL_OUTPUT_LENGTH | 392,354 | 39,729 | 0 |

第一批已执行 32 次工具，下一响应携带 4 次调用；`32 + 4 > 35`，Runtime 拒绝整批。
不是工具计数错，也不是原代码发生失败。调用分布为 listFiles 3、findFiles 2、readFile 27；
尚未执行命令。第二批读取 38 次文件、执行 3 次命令、检查 1 次 diff，末次响应的
finishReason 为 LENGTH，usage.outputTokens=4,097。归一化响应没有可执行工具或完整终稿，
不能声称它已经完成调查。两个批次都未进入后续依赖 Task，工作区保持原样。

两次校准均没有摘要，不具备评价摘要质量/阈值的条件。不得把首轮失败 tokens 与另一系统
六任务成功总量直接计算“节省百分比”。

## Claude Code 原预算参考组

证据目录：`/Users/zhaoguodong/code/gitnova-orderflow-20260921-mN3a2O/CC`。
CLI 2.1.241、deepseek-v4-flash、同一原始仓库和六条任务、同一个 CLI Session。
六阶段均通过，最终公开 79/79、独立 1,046/1,046、新增回归 27/27，五个变异全部检出。
交付报告引用的 20 条原始失败 CASE 行，均能逐字匹配 Task 1 的实际 tool result。

| Task | 可见模型响应数 | 含缓存及内部调用 tokens | 原生压缩 |
|---|---:|---:|---:|
| investigate | 17 | 322,444 | 0 |
| pricing | 7 | 310,771 | 0 |
| cancellation | 4 | 225,772 | 0 |
| requests | 3 | 201,664 | 0 |
| regression | 15 | 1,261,695 | 0 |
| delivery | 10 | 966,133 | 1 |
| 合计 | 56 | 3,288,479 | 1 |

其中非缓存输入 355,408、缓存输入 2,857,728、输出 75,343。缓存 token 不是免费 token，
也不是按同等单价计费；此处只统计数量，不把 CLI 自报美元数当成账单。

delivery 的 compact_boundary 记录：auto、pre_tokens=114,729、post_tokens=8,418、
duration_ms=39,275。之后模型继续执行并完成验收。这证明此场景能触发 CC 原生压缩，
不证明 GitNova 的摘要/强压缩已经通过，也不证明压缩内容绝对无损。

### 统计与可比性限制

1. CC `result.usage` 六任务相加为 2,922,522，而 `modelUsage` 为 3,288,479。
   差额 365,957 全部出现在发生压缩的 delivery。报告按 modelUsage 汇总一次；不将两者相加。
   内部压缩相关成本不能忽略，但没有更细分记录时不杜撰每个内部调用的精确用途。
2. 当时 runner 对相同 messageId 只保留最后一个块，manifest 的 thinkingBlocks=0 不可靠。
   复核原始 stream-json，六任务非空 thinking 块分别为 16/7/4/3/15/10。
   已修正后续统计代码；保留原始日志和原 manifest，不把“配置禁用”当成实际禁用。
3. 请求窗口 128k，CLI modelUsage 也报告 contextWindow=128000；请求输出 4096，CLI
   元数据却报告 maxOutputTokens=32000。后者不等同抓到了实际网络请求参数，但足以说明
   输出/思考设置没有实现严格对齐。CC 原生 prompt、工具协议与 GitNova 也不同。
4. CC 允许原生 Read/Glob/Grep 和限定 Bash，使用主机 Java；GitNova 命令在无网络 Docker。
   CC 共有 9 次权限拒绝，主要是循环批量读取及日志重定向。因此它是受约束的参考运行，
   不是默认无限权限 CC 的最佳表现，也不是严格的“只换 Harness”实验。
5. CC 和 GitNova 的部分运行时间重叠，耗时不能用于严格性能比较。没有重复采样、置信区间
   或跨模型验证；仓库为合成业务，不代表真实生产仓库规模。

## 扩大执行预算的后续批次

用户批准的新目录：`/Users/zhaoguodong/code/gitnova-orderflow-expanded-20260921-zqrHCX`。
输出 8,192、模型调用 40、模型调用/读取超时 120 秒、Task 超时 1,800 秒；工具仍为80，
窗口仍128k，摘要/强压缩触发仍80%/90%，外置阈值仍4096/1024。
未改生产配置默认值，所有覆盖值写入 frozen/effective scenario。

| Task | 结果 | 已知 tokens | 输入峰值 | 阶段代码验收 |
|---|---|---:|---:|---|
| investigate | COMPLETED | 387,762 | 38,314 | 通过 |
| pricing | COMPLETED | 626,285 | 60,501 | 通过 |
| cancellation | COMPLETED | 972,698 | 85,103 | 通过 |
| requests | FAILED / RECOVERY_CONTEXT_REQUIRED | 463,021 | 95,689 | 通过 |
| regression / delivery | 未执行 | — | — | — |

已知主模型合计 2,449,766。摘要请求没有得到可入库的完整响应，usage 未知，不能按零计费。
代码验收通过不等于 Run 完成：Task 4 修复已写入，但摘要/最终交付没有走完。

### 已确认的环境中断

北京时间：

1. 16:06:16，持久化 CONTEXT_CONTROL_UPDATED，普通摘要解除待触发状态，进入摘要路径。
2. 16:06:23，`pmset -g log` 记录 Idle Sleep，持续 222 秒。
3. 16:10:05，DarkWake；16:10:06，RUN_LEASE_EXPIRED。
4. 16:10:07，摘要网关 NETWORK_ERROR / HTTP 200；响应内容未完整消费。
5. 16:10:08，旧 fence 的结果落库被拒；新 Worker 以更高 fence 接管。
6. 恢复路径返回 RECOVERY_CONTEXT_REQUIRED，保留已有工作区，不盲目重放旧历史。

源码有独立四线程心跳 scheduler，每10秒续30秒租约。现有证据支持整机睡眠造成网络/续租
中断，不能说“LLM 摘要同步等待必然堵塞心跳”。扩大租约也不是此次校准的替代修复。
这同时验证了 stale writer 被 fencing 拒绝，并暴露完整 Context recovery 尚未接通的限制。

此前 experiment.json 的 summaryCalibration=NOT_TRIGGERED 只根据发布数量推导，命名不准：
此处实际是 **触发了，但没有发布**。已修正后续 runner 输出为
NO_SUMMARY_PUBLISHED_CHECK_CONTROL_AND_LOGS，并保留 summaryControlDisarmed 供排查；
是否真正发起摘要还须结合日志，不能只依赖控制状态事件。

同参数的新校准目录：`/Users/zhaoguodong/code/gitnova-orderflow-awake-20260921-bIpcTe`。
使用 `caffeinate -i` 仅防止试验进程存活期间的空闲睡眠；不更改永久电源设置，不能保证抵抗
用户合盖/强制睡眠。旧样本保持不变。

## 防睡眠重测：分阶段完成契约不匹配

保持相同扩大后的预算，使用全新 Session；防空闲睡眠断言已通过 pmset 确认。

| Task | 结果 | 已知 tokens | 输入峰值 | 阶段代码验收 |
|---|---|---:|---:|---|
| investigate | COMPLETED | 481,649 | 40,467 | 通过 |
| pricing | PARTIAL / INVALID_COMPLETION_DRAFT | 678,746 | 67,165 | 通过 |
| 其余四个任务 | 未执行 | — | — | — |

本批合计 1,160,395 tokens，没有发布摘要。Task 2 的 workspace generation 始终为1：

~~~text
applyPatch 修复两处计价文件
→ pricing exit=0
→ contract exit=0
→ inventory exit=0
→ all exit=1（未修复模块仍失败，属于当前阶段允许的遗留项）
→ finishTask 被拒：Workspace changes require a successful validation
→ 模型再按同样顺序跑三项成功检查，最后又跑 all
→ finishTask 再被拒，纠偏额度耗尽
~~~

定位到 `AgentRuntime.recordValidationEvidence`：runCommand 非零退出就将
latestSuccessfulValidation=null。`CompletionInspector` 只消费这一条记录，不知道当前 Task
要求的是 pricing/contract/inventory，也不知道全套检查哪些失败已明确推迟到后续 Task。
这不是 generation 变化、输出截断或模型传参错误。

因此当前场景与 Runtime 的完成条件存在不匹配，执行顺序会影响同一份代码能否完成 Task：
先跑 all 再跑成功子集可能得到成功证据，反过来会清空证据。前一个批次阶段2通过，不能据此
掩盖这次失败。受控拒绝本身遵守了现有实现；需要讨论的是它是否表达了产品真正的 DoD。

建议后续先明确“当前 Task 必须通过哪些检查”，记录 validation 的命令/作用域/generation
及结果，不将所有命令揉成一个 latestSuccessfulValidation；同一必要检查的新失败仍应否定
旧成功。不能简单保留任意一次 exit=0，也不能指导模型最后跑一个无关成功命令绕过门禁。
本轮未实现这项语义调整，未改 Run 状态、验证器或生产业务代码。

G70/G85 暂未执行：尚没有完整 GitNova 摘要发布并续接的有效基准，且运行会先受上述完成
契约影响。不能从当前数据选出最佳摘要阈值，也不能宣告强压缩已实现。

## 证据与清理边界

四个批次的原始导出分别在 code 下 mN3a2O、ysbFSr、zqrHCX、bIpcTe 后缀目录。
目录包含私有登录状态和 CLI 配置，不能整体提交 Git；只提交夹具、脚本与脱敏结论。
失败 Run 保持原 PARTIAL/FAILED 状态，未通过数据库改成成功，未删除测试仓库、Session 或
历史 Step。试验启动的 Spring 服务均已停止，8080 没有监听；临时 caffeinate 进程随试验退出。

本轮还完成 Python 入口语法检查、Git diff 空白检查、Maven package（跳过测试），以及跨组
配置误用的拒绝检查。没有将 Maven package 或夹具自检冒充生产全量 JUnit/E2E 通过。
四批源文件、任务、JAR 和镜像摘要相同；差异仅为上述记录的预算与防空闲睡眠措施。
