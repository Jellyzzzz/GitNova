# 第二轮：一个 Session 连续八条 Task

本轮把已存在的 Eventstats 五类缺陷组合到一个初始仓库，分步修复，不新增生产框架代码。目标是检验跨 Task 工作区延续、阶段边界、早期约束与历史证据的可达性。它与首轮四个独立 Session 的修复预检分开统计，也不计入原 20 题 × 3 次的分母。

公开初始仓库：**11 个文件、231 行、9,325 bytes**（含 README、测试及脚本），是小型合成 Java 21 仓库，不是新开源 issue，也不是大仓库能力评测。私人 reference、oracle 和参考回归测试不会进入模型仓库。

## 任务链

| Task | 任务 | 允许变更 | 阶段验收 |
|---|---|---|---|
| 1 | 只读调查、保存基线来源 | 无 | 不改文件；原有 5 类失败允许存在 |
| 2 | 时区与毫秒归一化 | EventParser | 时间检查通过，其他 4 类可待修 |
| 3 | 多租户去重 | EventStore | 时间 + 去重通过 |
| 4 | 负时间窗口 | WindowAggregator | 前 3 类通过 |
| 5 | 百分位及输入所有权 | Percentiles | 前 4 类通过 |
| 6 | 稳定分页 | CursorPager | 全部公开 + 隐藏行为检查通过 |
| 7 | 补跨模块回归测试 | 只新增 RegressionChecks | 测试正常通过，并分别发现五类旧缺陷 |
| 8 | 只读交付，比较初始与当前证据 | 无 | 不改文件，所有行为仍通过；另行核对历史引用 |

私有验收每阶段执行固定 **8 个公开检查 + 260 个隐藏检查**，但只将已经到达的修复类别作为必须通过项；无关的非法输入/集成检查始终必须通过。不是初期发现测试红灯就直接判整个调查 Task 失败。

同时检查两层修改范围：相对初始仓库，哪些文件累计可以改变；相对本 Task 开始时，哪些文件现在允许改变。因此第三条 Task 不能以“EventParser 也是可编辑文件”为由撤掉第二条的修复，第八条也不能偷偷改代码。

第七条的测试质量判定不依赖原有 public/oracle 帮它发现缺陷：在私有副本中单独运行候选 RegressionChecks，要求 healthy 至少 5 项检查且通过，然后分别回植五类原始缺陷，要求产生断言失败。编译报错、超时、缺少标记不算发现缺陷。生产代码允许采用不同的正确实现；回植时替换该模块为原始有缺陷实现，仍遵守固定 public API。它不等于完备变异覆盖率，也不是对恶意 Java 伪造输出的完备防作弊系统。

## 不重置的东西

- 只注册/建仓库/push 一次，只创建一个 Session / Workspace。
- 八次分别创建新的 Task / Run；不把它们伪装成单 Task 的八次模型调用。
- 后续 Task 通过现有 `live_client.py --follow-up` 发送，不重新物化、不恢复 base、不清空摘要。
- 自动断言同一 Session/Workspace、新 Task/Run 身份、前次结束文件树等于本次开始文件树、generation 延续、Run 的 Session sequence 向前推进。
- 一个阶段的 Run 或独立验收失败就停在该样本，保存剩余 NOT_RUN 名称；不自动重试、不跳过再声称八条成功。将来若要继续失败现场，应明确单列恢复/补充任务。

## 约束与证据怎样测

Task 1 给出持续约束；后续不重复全部约束，但仍显式说明本次允许修改的文件。这是带明确任务边界的延续测试，不是没有提醒的纯记忆考试。现有 API、依赖与固定文件约束可通过编译/文件保护检查，不能据此宣称所有自然语言要求都理解正确。

Task 1 原始公开测试基线是 `PUBLIC checks=8 failed=5`。Task 8 要区分“最初的历史结果”和“当前版本的结果”，引用真实 Session 来源。如果原文仍在上下文，直接回答也正确，**不强制模型为了指标额外回读**；若记不住，才需要搜索/读取历史结果。重新运行的新输出不是过去的证据。

这些回答的语义准确性与引用真实性，在 `run-steps.json` / `steps.json` 中人工逐条核对，记录为 `historyEvidenceReview`；当前验收器默认 `NOT_REVIEWED`，不会把单纯 Run.COMPLETED 当作历史记忆正确。

## 参数与指标

沿用首轮：128,000 context、80% 摘要、90% 强压缩触发、60% 强压缩目标、保留最近 2 组；每 Task 主调用 40 / 工具 80 / 输出上限 32,768 / 单次 timeout 300 秒；DeepSeek flash 主模型 max、摘要 high。总计最多 8 个 Task，不会自动扩展到 60 次。

记录每 Task 及 Session 合计：实际主调用与工具数、错误类型、已知 input/output/total usage、缺失 usage、单次输入峰值、摘要/强压缩调用与发布结果、generation、代码验收与历史引用检查。每 Task usage 只累计本 Run 的 Steps，不能把整个 Session 历史重复加八遍。

**八条 Task 不保证触发压缩。** 如实际触发 0 次，本轮只证明经过验收的延续行为，不能证明强压缩准确性或 80% 最优。需要触发测试时，另建明确标为缩小窗口的策略组，不能中途偷偷改本轮参数。

## 构造与本地检查（不调用模型）

以下命令在 GitNova 根目录执行，输出必须是新目录：

```sh
session_batch=$(mktemp -d /Users/zhaoguodong/code/gitnova-session-round2-XXXXXX)
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s evaluation/backend-repair-suite -p 'test_*.py' -v
PYTHONDONTWRITEBYTECODE=1 python3 evaluation/backend-repair-suite/session_chain.py self-test \
  --output "$session_batch/self-test"
PYTHONDONTWRITEBYTECODE=1 python3 evaluation/backend-repair-suite/session_chain.py prepare \
  --output "$session_batch/packs"
```

`self-test` 按正确修复依次推进八个阶段，另对五个阶段分别植入不完整修复，要求被相应隐藏检查拒绝；后两阶段额外执行健康回归测试与五种缺陷回植。共 13 个阶段正反例，回归质量子检查单列，不是 13 个模型样本。

## 真实服务启动（会调用付费模型，单独执行）

准备好 MySQL / Redis / RabbitMQ / Docker，构建当前 jar，显式载入 `.env.local` 中的模型配置，使用环境变量 `MYSQL_PWD` 提供本机数据库凭据；不要将密钥写入参数、提示词或仓库。先确认 8080 空闲、没有 QUEUED/RUNNING Run。

```sh
PYTHONDONTWRITEBYTECODE=1 python3 evaluation/backend-repair-suite/run_live.py \
  --session-chain \
  --packs "$session_batch/packs" \
  --output "$session_batch/live"
```

复用原 `run_live.py` 的服务管理与 `live_client.py` 的真实 HTTP / Gitlet push 路径，不用 SQL 写入 Task/Run/Step。新参数只选择测试拓扑，不改变 Session、Runtime 或持久化语义。冻结后若修改了验收器/任务/源仓库，需要创建新 pack；旧结果仍保留。

日志与快照分别落在首 Task 的 `G80/` 和后续的 `G80/followups/<task-name>/`；总报告是 `batch.json`。私有 `.client-state.json` 含测试账号凭据，不要公开。没有活动 Run 才停止本次服务；超时/无法确认状态则保留现场。

当前准备阶段不启动服务、不消耗模型额度。正式跑完才能记录 Agent 的八阶段通过率。
