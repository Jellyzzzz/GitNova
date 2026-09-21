# 场景 2：大测试输出中的订单计价修复

本目录是实验夹具，不是 Agent 业务实现。只将 `repository/` 上传到 GitNova；
`oracle/`、本说明、校准程序和实验结果均不能进入模型 Workspace。
本场景暂不测跨 Task 记忆，也不能仅凭一次任务证明摘要收益。

## 任务与独立验收

统一用户任务见 `TASK.md`。初始仓库的两个缺陷是：折扣分币直接截断、
免运费错误地使用折扣前金额。模型只能修改两个 Policy，不能更改测试或契约。

- 160 条固定订单逐条执行、逐条打印实际结果；不是重复填充日志。
- 关键失败位于中段。预览保留日志两端，完成报告仍需要找到初次失败的具体订单和金额。
- 2,125 个仓库外验收用例单独计算结果，检查折扣、运费、总价及非法输入。
- 原始仓库、只修折扣、只修运费都必须被拒绝；完整参考修复必须通过。
- 所有编译产物位于容器 `/tmp`；无网络、只读挂载、非 root 执行，不改变仓库文件树。

## 四组模型对照

`scenario.json` 冻结实验参数，**只是实验清单，不是已接入 Spring 的配置文件**。

| 组别 | 大结果外置 | LLM 摘要 |
|---|---|---|
| A | 关闭 | 关闭 |
| B | 开启 | 关闭 |
| C | 关闭 | 开启 |
| D | 开启 | 开启 |

共同条件：同一初始 commit、同一 TASK.md、同一模型/工具定义/输出上限、同一 Docker 镜像；
每次运行创建干净 Session/Workspace，不能接着上一组修好的代码运行。
`readArtifact` 在四组均保持注册，不能用删工具来关闭外置，从而改变固定输入成本。
各组仍保留相同 Session 历史读取和完整 journal，不能用关闭 Session Context 代替关闭摘要。

沿用参数：18,000 实验窗口、1,024 安全余量、摘要/紧急阈值 0.6/0.9、保留最近 2 组；
模型输出上限 2,048，最多 14 次模型调用、35 次工具调用；外置门槛 4,096、预览上限 1,024。
18,000 是受控实验预算，**不是宣称供应商上下文只有 18k**。

独立开关已接入并随 Task 配置冻结：`AGENT_CONTEXT_SUMMARY_ENABLED` 与
`AGENT_OBSERVATION_EXTERNALIZATION_ENABLED`，默认均为 true。关闭摘要不会关闭 Session 历史或硬预算检查。
旧 execution config 的编码保持不变；关闭任一功能时使用显式开关的 schema 4。
当前强压缩未实现，不能将终止兜底记成压缩成功。
四组真实实验结果见 `RESULTS-20260918.md`；不可用设置空 `contextBudget` 的方式替代实验开关。

每组记录：全部主模型和摘要 usage、调用数、延迟、工具错误、外置/回读/摘要次数、
最终独立测试结果、受保护文件是否变化、失败订单报告是否准确。
若一组没有触发摘要，应如实记录 0，不能强行摘要或宣称已测得摘要收益。
单次每组只能试跑，后续重复交错运行才能减少模型随机性和缓存顺序影响。
当前只保存配置中的模型名和 Provider usage，未保存响应 model 标识或 cache 命中明细，
因此本地 tokenizer 估计不能冒充供应商计费 token，也不能直接换算人民币成本。

## 复跑本地场景验收

在 GitNova 根目录运行（Docker 引擎及 `gitnova-workspace:java21` 镜像需已存在）：

```sh
python3 evaluation/context-large-output/verify.py --output target/scenario2
mvn -q -o dependency:build-classpath -Dmdep.outputFile=target/scenario2-classpath.txt
```

校准使用上一步打印的 `verification-*` 目录，不要将花括号占位符原样执行：

```sh
java --class-path "target/classes:$(< target/scenario2-classpath.txt)" \
  evaluation/context-large-output/ScenarioProbe.java \
  /absolute/path/to/verification-directory
```

若没有已编译的 GitNova 类，先 `mvn -q -DskipTests package`。
校准复用生产 `TokenEstimator` 和 `ToolObservationPreview`，输入来自真实 Docker 测试日志。
校准保存 JSON 并验证完整内容往返，但**不代表已经验证生产 Artifact Store 的授权、journal 引用或模型回读**。
这些必须在正式 HTTP → Runtime → Model 实验中观察。

验证模型修改后的实际 Workspace：

```sh
python3 evaluation/context-large-output/verify.py \
  --candidate /absolute/path/to/workspace --output target/scenario2-candidate
```

该操作检查文件集合及受保护文件摘要，然后在隔离容器运行公开与独立测试。
失败报告保留在输出目录；不会回滚或修改候选 Workspace。

## 2026-09-18 本地构建结果

注意：下表是**验收器自检变体，不是四组模型对照结果**。

| 代码变体 | 公开失败 / 160 | 独立失败 / 2,125 |
|---|---:|---:|
| 原始仓库 | 7 | 1,320 |
| 只修折扣 | 4 | 801 |
| 只修运费 | 5 | 798 |
| 完整参考修复 | 0 | 0 |

最初日志为 14,636 bytes：本地 Docker 不截断，但正式预跑发现 Gateway 另有 8,192 bytes 上限。
原先只依据 Docker 65,536 bytes 上限判断场景可用不成立，早期 A/B 结果已排除出正式比较。
夹具现使用有列定义的紧凑计价诊断：stdout 7,265 bytes，低于两层限制，160 个真实订单全部保留。
完整 ToolResult 估计 4,742 tokens，预览估计 733 tokens（参考编码 `o200k_base`，非 DeepSeek usage）。
ORD-065、ORD-069、ORD-073 的失败详情存在于完整结果，但不在预览中。
正式 B/D 均触发两次外置，且通过 readArtifact 回读首次测试输出；详见结果报告。

本机已初始化的 Gitlet 测试仓：
`/Users/zhaoguodong/code/gitnova-context-scenario2-20260918/repository-calibrated`

本地基准 commit：`ceccbf2d64579bb7ed501debb0d254e2f062e55c`（7 个受控文件）。
push 转换后的云端基准 commit：`abb532bb1836baf48b2d2c9f9a2e43795c49098a`，repoId=16。
校准目录：`/Users/zhaoguodong/code/gitnova-context-scenario2-20260918/corrected-calibration/verification-ayngs8no`
真实实验目录：`/Users/zhaoguodong/code/gitnova-context-scenario2-20260918/live-confirmed`

真实 GitNova 服务已在 8080 启动，使用 `.env.local` 及上述实验参数；
当前日志：`/Users/zhaoguodong/code/gitnova-context-scenario2-20260918/server-confirmed-D.log`。
四个 Session/Task 已通过真实 HTTP 创建并执行，模型消耗和失败均保留，不自动复活旧 Run。
