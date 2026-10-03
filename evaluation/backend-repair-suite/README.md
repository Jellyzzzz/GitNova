# GitNova 跨仓库修复测试集

目标：**4 个业务仓库 × 每仓库 5 个不同修复任务 × 每题 3 次独立尝试 = 20 题、60 次运行。**
这里只构建测试材料和验收，不启动 Spring Boot、MySQL 任务或付费模型调用。

这是一套人工设计、植入缺陷的 Java 小型仓库，不是开源真实 issue 基准、不是大仓库性能测试，也不是数据库/消息系统可靠性测试。几个任务的修复可能只有几行；它们测的是定位、约束遵守与修复正确性，不能代表所有 Coding Agent 能力。

| 仓库 | 5 个任务覆盖 | 运行条件 |
|---|---|---|
| Stockroom | 预留边界、重试身份、过期释放、失败调拨、整批补货 | Java 21，内存库存 |
| Eventstats | 时区、多租户去重、负时间窗口、百分位、稳定分页 | Java 21，事件集合 |
| BundleSync | 路径、短读/校验、增量删除、文件树冲突、原子发布 | Java 21，临时文件系统 |
| JobQueue | 优先级、租约 token、退避、超时重试、取消终态 | Java 21，显式传入虚拟时钟 |

## 文件组织与泄漏边界

```
suite.json                  20 个任务、故障覆盖、典型不完整修复
reference/<repo>/           正确业务代码 + 公开测试（验收端私有）
oracle/<repo>/Oracle.java   独立行为检查（验收端私有）
common/                     复用的公开测试断言和运行脚本
suite.py                    prepare / self-test / verify
run_live.py                 真实服务首轮预检，默认每仓库选第 1 题
test_suite.py               构造器与验收边界自测

生成目录（位于项目外）：
  tasks/<task-id>/repository/   唯一允许 push 给模型的目录
  tasks/<task-id>/task.md       作为该 Task 的用户消息
  tasks/<task-id>/scenario.json 配置候选值，不会自动设置运行服务
  freeze.json                  文件、任务、验收代码与配置摘要
  run-plan.json                60 个 NOT_RUN 计划项
```

**不能 push 整个 evaluation 目录、reference、oracle 或生成目录的父目录。**
每题的公开仓库只含该题的缺陷，其他四条业务路径保持正确。没有把五个不相关缺陷一起塞进一个 starter，让某题为了通过验收顺便修别的题。

## 先构造，再检查

以下命令在 GitNova 根目录执行。`--output` 必须是新的目录，已存在时拒绝覆盖。

```sh
batch_dir=$(mktemp -d /Users/zhaoguodong/code/gitnova-repair-batch-XXXXXX)
PYTHONDONTWRITEBYTECODE=1 python3 evaluation/backend-repair-suite/suite.py prepare \
  --output "$batch_dir/packs"

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s evaluation/backend-repair-suite -p 'test_suite.py' -v

PYTHONDONTWRITEBYTECODE=1 python3 evaluation/backend-repair-suite/suite.py self-test \
  --output "$batch_dir/fixture-checks"
```

`self-test` 使用本机已有 `gitnova-workspace:java21`，解析为具体 image ID。共检查 **44 个版本**：4 个正确参考、20 个初始缺陷、20 个不完整修复。所有版本都必须编译并跑完整个公开/隐藏检查；编译报错或容器超时不算“反例成功”。错误版本必须被相应业务类别的隐藏检查检出。

Docker 验收复用已有 `context-large-output/verify.py` 的执行边界：网络关闭、非 root、源码及 oracle 只读挂载、只写临时目录、限制 CPU/内存/PID/执行时间。本脚本另外限制回传日志长度。验收时不执行模型修改的入口脚本，而是自行编译并调用固定检查类；若新增 RegressionChecks，也执行并要求成功。

这能防止通过删测试、改 runner、缺少结果标记获得假通过；**不是针对恶意 Java 代码伪造检查输出的完备防作弊系统**。

## 候选修复如何验收

```sh
PYTHONDONTWRITEBYTECODE=1 python3 evaluation/backend-repair-suite/suite.py verify \
  --task stockroom-01 \
  --candidate /absolute/path/to/the/agent-workspace \
  --output "$batch_dir/stockroom-01-r1-verification"
```

校验允许修改的文件 → 复制稳定候选文件树 → Docker 编译 → 固定公开检查 → 独立 oracle → 可选新测试 → 检查原候选未在验收期间改变。

- `ACCEPTED`：行为检查通过且未超出修改范围。
- `REJECTED`：检查执行完整但行为不符。
- `COMPILE_ERROR`、`SCOPE_VIOLATION`：单独记录，不能伪装成工具/模型成功。
- `INCONCLUSIVE`：超时、缺少检查标记、退出码矛盾或验收时文件树变化等，没有足够验收证据；排查基础设施原因，不悄悄替换样本。

比较的是行为，不要求修复与 reference 相同。新测试是可选的，本轮不把“写测试的质量/变异覆盖率”算成一个独立能力指标。

## 真实调用复用已有链路

第二轮同 Session 的 **8 条连续 Task** 已单列在 [session-chain/README.md](session-chain/README.md)。使用 `session_chain.py` 构造/验收，以及 `run_live.py --session-chain` 执行；不改变下面独立冷启动任务的默认行为，也不混入 60 次运行统计。

后续仍使用已有 Gitlet CLI 和 `evaluation/context-large-output/live_client.py`：

1. 从该题 `repository/` **复制**到本次尝试的客户端目录，`init → add → commit → push`；不在冻结 fixture 里直接初始化或修改。
2. 真实 HTTP 注册/登录/建仓库；共享客户端 `setup` 接收 `--repository`、`--fixture-root`、`--scenario`。
3. 为本次尝试新建 Session/Workspace，以 `task.md` 创建 Task；`live_client run --arm G80` 发起调用并读取真实 Run/Steps。
4. Task 结束后，用上面的 `verify` 验收实际 Workspace，并保存配置、身份、步骤、usage 和验收结果。
5. r2/r3 仍从同一个初始文件摘要重新建 Session，不能复用前次修改或摘要。不同策略也需要独立 Session。

`run_live.py` 已接通上述顺序，默认仅跑四道题，不会自动扩大到 60 次。载入 `.env.local`、通过环境配置 `MYSQL_PWD`，确认新 jar 已构建、8080 未被占用且没有活动 Run 后：

```sh
PYTHONDONTWRITEBYTECODE=1 python3 evaluation/backend-repair-suite/run_live.py \
  --packs "$batch_dir/packs" \
  --output "$batch_dir/live-round1"
```

使用同一真实服务，每题单独注册/建仓库、推送、创建 Session/Task；验证后的各题结果和失败样本保存在 `batch.json`。这里读 MySQL 只用于观测，不直接写入 Task/Run/Step 或成功状态。模型完成后再使用独立验收器，验收答案不反馈到本次任务。

共享客户端已增加 `compactTargetRatio` 的持久化配置断言；旧场景未声明该字段时保持原行为。服务仅在没有 QUEUED/RUNNING Run 时停止；若超时或数据库状态无法确认，保留服务及现场，不自动重放任务。

**仍未自动执行 3 轮共 60 次。** 首轮是连接真实系统的预检，后续正式批次需明确运行编号、重复范围并汇总已有样本，不能不断创建新输出目录重试直到通过。

生成配置沿用最近校准的一组候选值：128,000 context、80% 摘要、90% 强压缩触发、60% 强压缩目标、保留 2 组、40 model calls、80 tool calls、32,768 output、300 秒单次 timeout；主模型 max、摘要 high。**这些是待真实预检的配置，不是最优参数，也没有启动服务去修改配置。** 正式批次应冻结实际 jar、镜像、provider/model、prompt/tool schema、运行策略摘要。不能依赖 `.env.local` 偶然继承值。

## 如何统计，避免夸大结果

- 每题分别报 0/3、1/3、2/3、3/3；总体报独立验收通过数/60、每仓库通过数/15；保留失败原因，不能从三次里只挑成功样本。
- `Run.COMPLETED` 与 `oracle accepted` 分开统计。正确代码但 Run PARTIAL 与 COMPLETED 但验收失败都保留原始状态。
- 记录主模型、普通摘要、强压缩分别的调用次数、input/output usage、延迟、工具错误、历史回读；缺失 usage 记 unknown，不能记为 0。
- 一种策略是 60 次；两种策略对照是 120 次，不是 60 次。
- 这 60 次是独立冷启动修复，不证明跨 Task 记忆。原 `context-order-workflow` 的同 Session 多 Task 链单列验证，不能混入这 60 次分母。
- 小仓库很可能不触发摘要/强压缩。按实际触发数报告 exposure；需要另跑定向长会话才能评价强压缩是否丢信息，不靠塞无意义文件或偷偷改小窗口强行得出结论。
- 这是开发者可见的回归集，不是防止训练污染的第三方盲测集，也不能据此声称超越 Claude Code。
