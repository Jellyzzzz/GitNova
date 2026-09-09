# Docker 命令隔离与 Runtime Journal 接线

## 1. 这次改了什么

沿用现有 Session → Workspace → Task → Run 关系，不重建逻辑 Workspace，不把 Harness 搬进容器。

```text
Session 的 Workspace（现有目录，文件树持续演化）
  ├─ readFile / search / applyPatch：现有受控 Gateway
  └─ runCommand：DockerWorkspaceCommandExecutor
       └─ 短生命周期容器，只挂载这一个 Workspace

DefaultDurableRunExecutor
  → RunJournalScope（Session / Task / Run / Worker / fence / config digest）
  → AgentRuntime
       ├─ 模型、工具、纠偏、完成判定 → 现有 RunJournal → MySQL Step
       └─ AgentRunResult → 现有 terminateRun → Run / Task projection
```

本次生产代码只新增 `DockerWorkspaceProperties`、`DockerWorkspaceCommandExecutor` 两个类。没有新增容器 Service 层、另一个 Runtime 或另一个 JSON codec。

## 2. 一次 runCommand 的实际调用顺序

1. 模型返回 tool call。Runtime 先提交 `MODEL_RESPONSE`，然后才允许分发。
2. ToolRegistry 沿用现有 capability 与 schema 校验；RunCommandTool 构造 CommandRequest。
3. LocalWorkspaceGateway 获取 Workspace 写锁、跨进程文件锁，核验 mutation fence。
4. 检查有无未核实结束的命令，刷新文件树，再比较 expectedGeneration。
5. Gateway 把**可信 workspaceRoot 和已校验 workingDirectory** 分别传给 executor。不能只传子目录，否则无法正确挂载整个仓库、访问同级模块。
6. executor 再校验真实路径。工作目录必须位于 root 内，不能经过符号链接。模型的 argv 只作为容器命令参数，宿主机没有 `sh -c <model command>`。
7. 在工作区外写入 `.工作区目录名.pending-command`，记录随机、唯一的容器名，并 force 到磁盘。这个文件不挂进容器。
8. `docker create` 使用服务端固定参数，然后 `docker start --attach`。镜像预先构建，不在执行期间联网拉镜像。
9. 并发读取 stdout/stderr；每路保留前 64 KiB，后续继续读取并丢弃，避免管道塞满。Gateway 仍有第二层输出限制。
10. 正常结束用 inspect 验证状态确实是 `exited`，再读取容器 ExitCode。Docker CLI 的返回码不等于仓库命令返回码，`created + ExitCode=0` 也不代表命令执行成功。
11. 超时杀掉 CLI 还不够：finally 中强制移除整个容器，并查询确认容器不存在。容器 PID 1 的 timeout 另提供 JVM 退出后的限时后备机制。
12. 只有清理结果确定，才移除 pending 标记并返回。create 请求本身超时/结果不确定时，即便暂时查不到容器，也保留标记，防止迟到的 create。
13. Gateway 重新计算完整文件树指纹；有变化就推进 generation。命令非零退出或超时也可能留下有效文件修改，不回滚这些已发生的事实。
14. Runtime 提交 `TOOL_RESULT`，然后才追加下一轮模型 Observation。只有符合原有规则的成功命令，才能产生当前 generation 的 validation evidence。

`exitCode != 0` 表示命令已完成但验证失败，不等于 Docker 基础设施失效。无法确定容器停止时则是基础设施/工作区状态不确定，禁止继续写入。

## 3. 隔离策略

| 边界 | 本次机制 |
|---|---|
| 网络 | `--network none`，无外网；容器内仍有 loopback |
| 文件 | 只 bind 当前 Workspace 到 `/workspace`，镜像根文件系统只读 |
| 临时输出 | `/tmp` 使用 128 MiB tmpfs；构建工具可显式将产物写到这里 |
| 身份 | 非 root 的数字 UID:GID；本机配置需匹配工作区目录权限 |
| 特权 | drop ALL capabilities、no-new-privileges、沿用 Docker 默认 seccomp |
| 资源 | 默认 1 CPU、512 MiB 内存、128 PID；memory-swap 与 memory 相同 |
| 环境 | 不传服务 API key/数据库密码，不挂 Docker socket；显式清空 Docker 客户端可能注入的代理环境变量 |
| 日志 | 禁用 Docker 持久日志驱动；双流分别限量保留 |
| 不确定副作用 | 工作区外 pending 标记；后续 refresh/mutation fail closed |

资源限制不是 Docker 的默认行为，必须显式配置。参考 [Docker 资源约束](https://docs.docker.com/engine/containers/resource_constraints/) 与 [none 网络](https://docs.docker.com/engine/network/drivers/none/)。

注意：不会自动把 `target/` 等目录从 Workspace 事实中排除。命令如果确实写到工作区，就会影响指纹和 generation；测试示例用 `javac -d /tmp` 避免编译产物污染源码树。

## 4. Journal 的提交顺序

| 执行边界 | 持久化事件 | 提交成功后才允许 |
|---|---|---|
| 模型调用意图 | MODEL_CALL_STARTED | 调用 ModelGateway |
| 模型返回 | MODEL_RESPONSE | 进入 assistant 上下文、分发 tools |
| 工具返回，包括拒绝/失败 | TOOL_RESULT | 加入 TOOL Observation、执行下一个工具 |
| 纠偏/漂移反馈 | HARNESS_FEEDBACK | 加入反馈并发出下一次模型请求 |
| 完成检验 | COMPLETION_DECISION | 返回 accepted Outcome、终结 Run |

`MODEL_CALL_STARTED` 包含稳定的 modelCallId、规范化 requestDigest、contextThroughRunStepSequence、workspaceGeneration。每次 append 返回已提交的 runStepSequence，Runtime 更新 watermark。

`DefaultRunJournal` 继续通过 Spring 事务代理调用 fenced Appender；沿用已有双序列分配、eventId 幂等与数据库 lease/fence 校验。Runtime 和整个外部模型/容器调用不套一个长数据库事务。

`COMPLETION_DECISION` 保存完整 accepted Outcome，包括模型 draft、服务端 canonicalDiff 与 validation。此前只有 Run 的 COMPLETED 状态、读不到最终 summary 的问题，在这条路径上已补齐。

生产 Bean 强制注入 Journal/codec，且必须通过带 scope 的 run 入口。已有独立 Runtime 测试仍可以使用明确不持久化的短构造器；生产 Bean 不会静默降级到这个入口。

同一 Run 内重复 toolCallId 在执行前被拒绝，避免不同调用占用同一个结果事件身份。

## 5. 本机启动与验证

本次安装 Colima + Docker CLI，启动参数为 2 CPU / 2 GiB 内存 / 20 GiB 稀疏磁盘。Colima 是宿主 Docker daemon 的 Linux 环境，不是每个 Session 的 Workspace。

```bash
colima start --cpu 2 --memory 2 --disk 20 --vm-type vz
DOCKER_BUILDKIT=0 docker build -t gitnova-workspace:java21 docker/workspace
```

这里使用 legacy builder 是为了不额外引入 buildx；不是运行时依赖。安装 buildx 后可用正常 BuildKit 构建。基础镜像已固定 digest。

启动 GitNova 的同一个终端配置：

```bash
export WORKSPACE_DOCKER_ENABLED=true
export WORKSPACE_DOCKER_USER="$(id -u):$(id -g)"
export WORKSPACE_DOCKER_IMAGE=gitnova-workspace:java21
```

然后按原有方式加载 `.env.local` 并启动 Spring Boot。已经在运行的旧 JVM 不会自动获得这次代码；确认没有正在执行的任务后再重启。Linux 生产机应使用服务工作区所有者的非 root UID:GID，不能直接照抄本机的 `501:20`。

默认 `enabled=false`，未开启时命令仍返回 EXECUTOR_UNAVAILABLE，不会退回宿主机执行。

```bash
# 单元测试，不需要模型 API / MySQL / Docker
mvn -q test

# 真实 Docker；测试目录位于项目 target/docker-it，适配 Colima 的共享路径
WORKSPACE_DOCKER_USER="$(id -u):$(id -g)" mvn -q -Pdocker-it -Dtest=DockerWorkspaceIntegrationTest test

# 真实事务提交 + 生产 DurableRunExecutor/Runtime/Journal，模型和 Workspace 是测试替身
# 使用独立 gitnova_journal_it 数据库，不操作 gitnova 业务库
mvn -q -Pmysql-it -Dtest=AgentRuntimeJournalMySqlIntegrationTest test
```

MySQL 测试不套 test transaction。在下一次模型调用内部用另一条连接查询 Step，验证前一条记录已经提交，而不只是当前事务可见。测试只清理它自己创建的唯一 fixture；测试库和 Flyway 表保留复用。

2026-09-09 验证结果：默认回归 374 项通过；真实 Docker 4 项通过；真实 MySQL Journal 1 项通过。Docker 测试覆盖编译执行与隔离、超时后的后台进程、双流限制与非零退出、Gateway generation 更新。MySQL 使用本机 9.6，Flyway 提示其测试支持版本落后于此服务版本；本次迁移与事务断言均通过，但这不替代正式部署版本兼容测试。

查看一次真实 Run 的完成内容：

```sql
SELECT run_step_sequence, step_type, JSON_PRETTY(payload_json)
FROM agent_step
WHERE run_id = '替换为你的 runId'
ORDER BY run_step_sequence;
```

## 6. 失败恢复和明确未完成的部分

1. **这不是完整 Resume。** 检测到旧 Run 已有 MODEL_CALL_STARTED 时，返回 RECOVERY_CONTEXT_REQUIRED，不从空 transcript 重放。后续需要 Context Projector 恢复上下文、核对未完成 tool call，再接 Recovery Run。当前生产 Executor 按失败结束该次 Run，Task 保持可继续；原始 Steps 保留。
2. **MySQL 与文件系统不是一个事务。** 工具可能已经改文件，但 TOOL_RESULT 提交失败。本次会停止后续执行，不假称 exactly-once；独立 ToolExecution 状态机、稳定 effect identity 与自动 reconcile 仍待实现。
3. **pending 标记不是自动恢复器。** 异常时先停止旧 Worker、禁止新 mutation，读取标记中的精确容器名，在健康 Docker daemon 上确认相关请求已结束、容器已停止并移除，才允许管理员删除这一枚标记并 refresh。不能因为一次查询为空或单纯等待 timeout 就自动删标记。
4. **Docker 隔离不等于敌对多租户沙箱完工。** 尚无 bind 工作区磁盘配额、专用 sandbox runtime/VM、跨主机调度和自动垃圾回收；Docker daemon 本身是高权限可信控制面。
5. **离线依赖必须预置。** 网络关闭后，空 Maven 缓存不能在线下载依赖。应构建经过批准的工具链/依赖镜像，而不是给模型开放宿主 `.m2` 或服务 secrets。模型本身的 API 调用仍由容器外的 Gateway 进行。
6. **Session 快照恢复、Artifact 外置、脱敏策略、Context/Memory 尚未因此完成。** Journal 复用现有 payload 契约；完整敏感数据治理与大结果外置需要后续接入，当前真实集成测试只使用合成无敏感数据。
7. 未新增 HTTP 结果查询接口，也未在这次测试中调用付费真实模型。HTTP/队列链既有接线不变；本次验证重点是 Docker 边界和生产 Runtime 事实落库。

不用虚拟机时可运行 `colima stop` 释放内存，不会删除镜像和逻辑 Workspace 文件。
