# Harness-in-Sandbox 重构前的本地基线

记录日期：2026-10-03。此文件完成施工指南的步骤 0，不代表新架构已经实现或双机联调通过。

## 1. 冻结范围

- 保留当前分支 `refactor/agent-harness-v4-clean`，本次不切分支、不推送。
- 整理前 HEAD：`316112ae38747a5f5ceef95aa6e6b095fa76c919`。
- 旧架构实现与回归测试：`0d8f214`。包括自然 STOP / answer、Task 查询与薄前端、Context 强压缩、参数诊断和模型响应纠偏。
- 实验仓库、验收器、场景与已有报告：`6c1e039`。
- 本文和新版双机重构文档独立提交。最终起点由本地标签 `pre-harness-in-sandbox` 标识；这是可恢复的代码基线，不是新产品的发布标签。

起始工作区有 63 个已修改文件和 154 个未跟踪文件。未使用 `git clean`、硬重置或整目录删除。用户确认后，5 份旧 SPEC 的 `run → context` 替换及 Compose 本地密码改动先保存补丁，再恢复到原提交，不进入新基线。

本次唯一新增的源码树修正是 `EditFileToolTest` 的旧断言：直接调用工具仍验证 `INVALID_PATCH_OPERATION_TYPE`；经注册表调用则验证提前发生的 `SCHEMA_VALIDATION_FAILED`、`NOT_STARTED`、`/operations/0/type` 和 `ENUM_MISMATCH`，保留禁止执行 Workspace 修改的断言。没有因此修改生产语义。

## 2. 实际验证

| 检查 | 本次结果 | 范围 |
| --- | --- | --- |
| `mvn -B -Punit-test test` | 773 项，0 失败 / 0 错误 / 0 跳过 | 本地默认单测；初跑的 1 项旧断言失败已记录并修正 |
| `mvn -B -Pgateway-test test` | 53 项，0 失败 / 0 错误 / 0 跳过 | 本地 MockWebServer，不调用模型 API |
| 从 `6c1e039` 导出到空目录后重新运行上述两条命令 | 773 + 53 项再次通过 | 没有复制原 `target/`、`.env.local` 或运行数据；依赖使用本机 Maven 缓存 |
| `python3 -m unittest discover -s evaluation/backend-repair-suite -p 'test_*.py'` | 26 项通过 | 实验准备、范围和计划检查，不是 26 次模型运行 |
| `node --check src/main/resources/static/app.js` | 通过 | JavaScript 语法，不是浏览器端到端验收 |
| 原文档包 SHA-256 清单 | 全部匹配 | 原包文件未改写；本地基线和盘点结果是另增文件 |
| 文档 Java 契约编译 / 本机 HTTP、SSE 探针 | 通过 | 31 个参考类型和隔离探针，不是正式 Agent Worker |
| 双机文档静态检查 | 4 组通过 | 没有访问 Windows / OpenSandbox |

本机为 macOS ARM64，Microsoft JDK 21.0.11、Maven 3.9.16；根工程编译目标仍为 Java 17。新文档中的 protocol/server 17 与 core/worker 21 编译检查没有改变根工程配置。

通用文档自检在 Mac 上**整体返回 FAIL**：Linux 启动器依赖 `/proc/<pid>/stat`，不能在 macOS 原生运行，应在 Windows WSL2 / Linux 环境补测。另一个检查虽然外层显示 PASS，但 `COMMAND_SCHEMA_FIXTURES` 的内部结果为 SKIPPED，因为当前 Python 缺少 `jsonschema`；不能算 Schema 校验通过。本次没有安装依赖或修改脚本来隐藏限制。

原包保留 Markdown 行尾双空格、CSV 的 CRLF 和 SSE 的末尾空行，因此普通 `git diff --check` 会报告这些格式。未为消除提示而改写原包或破坏 SHA-256 清单；新增基线文件单独检查。

本机检查保存在 [Mac 自检原始结果](audit/local-macos-self-check-20261003.json) 和 [双机静态检查](audit/local-dual-host-check-20261003.json)。包中原有的 `audit/self-check.json` 等历史记录未被覆盖。

未运行 MySQL/Redis/RabbitMQ 集成测试、真实模型、OpenSandbox SDK/容器、Windows 网络和 120 项产品验收。本次未启动 Spring Boot；检查时 8080 无监听。

## 3. 自然 STOP 的真实调用链

```text
AgentTaskService.create
  → 新 Task 的冻结工具集排除 FinishTaskTool
  → AgentRuntime.run：提交 MODEL_RESPONSE 后处理 STOP
  → handleNaturalStop：拒绝空正文；刷新 Workspace，漂移时反馈而非交付旧答案
  → completeAnswer：AgentAnswer(content, modelCallId)，ANSWER_DELIVERED
  → DefaultDurableRunExecutor：将 answerModelCallId 传入 terminateRun
  → MyBatisAgentTaskRunStore.terminateRun
      验证同 Run 内已提交、非空、无 tool calls 的 STOP 响应
      终止事件记录 answerEventId
  → AgentStepMapper.selectCompletedAnswer：沿终止事件精确关联原 MODEL_RESPONSE
  → AgentTaskService.find / AgentTaskController.find
  → GET /api/repos/{repoId}/agent/sessions/{sessionId}/tasks/{taskId}
  → data.answer.content
  → static/app.js：轮询并显示最终正文
```

主要入口：

- [AgentRuntime](../../src/main/java/com/gitnova/service/agent/runtime/AgentRuntime.java)
- [AgentTaskService](../../src/main/java/com/gitnova/service/agent/task/AgentTaskService.java)
- [MyBatisAgentTaskRunStore](../../src/main/java/com/gitnova/service/agent/execution/mybatis/MyBatisAgentTaskRunStore.java)
- [AgentStepMapper](../../src/main/java/com/gitnova/mapper/agent/AgentStepMapper.java)
- [AgentTaskController](../../src/main/java/com/gitnova/controller/AgentTaskController.java)
- [薄前端](../../src/main/resources/static/app.js)

不要退回“扫描最后一条消息”或把 `reasoning_content` 当 answer。旧冻结 Run 兼容路径仍保留 `FinishTaskTool`；它的存在不代表新 Task 仍使用强制结束协议。运行正常结束与独立业务验收通过仍分开。

## 4. 迁移盘点与实验材料

[本地源码盘点](audit/local-source-inventory.json) 基于 `6c1e039`：175 个迁移表路径均存在；另有 90 个未列入原迁移表的 Java 文件，其中 89 个是测试，唯一新增生产类型是 `AgentAnswer.java`。这不是迁移遗漏已全部解决的证明；删除旧代码前应逐项核对去向，尤其不能丢掉 answer 来源身份。

当前 Flyway 文件为 `V1`—`V5`，下一可用编号为 `V6`。本次未安装新 DDL、未连接数据库迁移；正式创建迁移前重新扫描编号，不能将本记录当作编号预留。

实验 fixture、Task 原文、验收器和已有 `RESULTS*.md` 已入 Git。报告引用的本机历史证据路径仍存在；`XXXXXX` 是命令示例占位，不是丢失的样本。原始运行日志、数据库、Workspace、私密环境配置没有被复制进 Git，也没有被清理。因此 Git 标签恢复的是源码和实验定义，不是完整线上数据快照。

本机已有 `gitnova-workspace:java21` 镜像：

```text
sha256:2236aa4655ce559a47e1f574e8253412c384adad937d254395a1b02542a2edea
linux/arm64
```

这只是本机旧测试环境，不是 Windows 的 `linux/amd64` 镜像，也不是对每份历史实验镜像的重新认证。

## 5. 恢复与下一步

本地备份目录为 `/Users/zhaoguodong/code/GitNova-pre-harness-20261003.cjtuJQ`。包含整理前的完整 HEAD bundle、已跟踪文件补丁、154 个未跟踪文件的归档，以及初跑失败/修正后通过的测试日志；`.env.local` 和忽略的运行数据不在备份归档中，仍保留原位。

六个文件的独立补丁为 `local-only-spec-and-compose.patch`。若以后确实需要恢复这些本地改动，从仓库根目录先检查，再应用：

```bash
git apply --check /Users/zhaoguodong/code/GitNova-pre-harness-20261003.cjtuJQ/local-only-spec-and-compose.patch
git apply /Users/zhaoguodong/code/GitNova-pre-harness-20261003.cjtuJQ/local-only-spec-and-compose.patch
```

该补丁包含已确认移出的无效旧文档命令，不应为了“恢复全部”而盲目重放。没有必要恢复时就保留备份。

切分支由用户操作。先确认 `git status --short` 为空，再从本地冻结点创建，而不是从旧远端提交创建：

```bash
git switch -c refactor/harness-in-sandbox pre-harness-in-sandbox
```

随后进入 02 的步骤 1，建立三个独立模块；环境准备按 06 在 Windows 上并行推进。本次没有创建这些模块，没有删除旧 Runtime、Workspace、MQ 或数据库表。
