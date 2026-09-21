# 场景 2：D 组独立复测（2026-09-20）

## 结果

本次为新的真实 HTTP Task，不是观察旧 Run，也不是重放旧写工具。
最终 `COMPLETED / FINISH_SUCCEEDED`；独立 Docker 验收公开用例 160/160、隐藏用例 2125/2125。
仅两个允许修改的 Policy 文件发生改变，其余文件和文件集合检查通过。
原 D 组网关失败样本完整保留，不能用此次成功覆盖旧失败。

| 指标 | 原 D | 本次 D | 原 C（仅摘要，参考） |
|---|---:|---:|---:|
| 主调用发起/返回 | 11/10 | 12/12 | 8/8 |
| 主模型 tokens | 79,432 | 93,723 | 63,950 |
| 摘要次数 | 2 | 2 | 2 |
| 摘要 tokens | 17,167 | 16,345 | 14,242 |
| 已观测总 tokens | 至少 96,599 | 110,068 | 78,192 |
| Run 结果 | PARTIAL，网关异常 | COMPLETED | COMPLETED |
| Run 耗时 | 32.55s | 31.95s | 25.42s |

本次主模型输入/输出为 89,950 / 3,773；摘要输入/输出为 13,805 / 2,540。
合计包括摘要模型费用；无缺失的主调用 response。Provider usage 不等于缓存折扣后的账单。
本次较原 C 多 31,876 tokens（约 40.8%），但不同执行轨迹、每组样本很少，不能据此估计稳定节费率。

## 相同条件与隔离

- 相同应用 JAR SHA-256：`1a5e59ad1e600c6990df60ef9825a9510f5cbbb960651c03698c517b33448da8`，未重编译或修改业务代码。
- 相同 Docker 镜像：`sha256:2236aa4655ce559a47e1f574e8253412c384adad937d254395a1b02542a2edea`。
- 同一 repoId=16，main commit=`abb532bb1836baf48b2d2c9f9a2e43795c49098a`。
- 相同 TASK.md，SHA-256=`b54c1dc35527bda0d8c85030c1adee4e429f86e1cebce5262faa30b86538ff0b`。
- 新 Session、新 Workspace，7 个初始文件与原 D 逐文件 SHA-256 相同，初始 generation=0。
- 完整 frozen execution config 和 configDigest 均与原 D 相等：外置/摘要都开启，18k 实验窗口、0.6/0.9 阈值、保留最近 2 组、4096/1024 内联/预览预算、14/35 主模型/工具上限。
- 模型仍为配置别名 deepseek-v4-flash，temperature=0；不是对供应商内部模型版本和缓存状态的固定。

## 实际执行轨迹

1. 浏览文件、README、脚本和源码，执行原始测试：160 个用例，7 个失败，exitCode=1。
2. 首份日志外置；模型分两次 readArtifact 回读同一 Artifact（offset=0、4096）。
3. 第一次摘要接受：准备阶段估计 11,355 → 8,416，摘要消耗 4,908 tokens。
4. 重新读取两个 Policy 文件，单次 applyPatch 修复两个文件，generation 0 → 1。
5. 再执行同一测试，160/160 通过，exitCode=0；第二份日志外置。
6. 第二次摘要接受：准备阶段估计 13,669 → 5,025，摘要消耗 11,437 tokens。
7. 重读修改文件、getWorkspaceDiff，再读 README/脚本，最后一次 finishTask 被接受。

共 23 次工具调用，13 次 readFile、2 次 runCommand、2 次 readArtifact、1 次 applyPatch、1 次 finishTask；无 ToolResult 协议失败。
两份命令 stdout 为 7,265 / 7,238 bytes，均未截断。两个 Artifact 的落盘 SHA-256 和 size 均通过核对。
65 条 Session journal 的 sequence 连续。最终报告中的 HALF_UP 条件及订单 65/69/93 的 expected/actual 与真实失败日志相符。

这里再次出现了外置后的全量回读，以及摘要后的文件重读；它们增加了后续上下文和调用次数。
只能说观察到这些动作，不能仅凭执行先后关系断言重读必然是摘要丢失信息造成。
摘要前后数值是上下文准备估算，不是 Provider 实测节省量。

## 仍未解决的边界

- 本次成功说明 D 组合能够完成该任务，不证明原网关错误已被修复；其异常分类仍未记录，原因未知。
- Workspace 数据库 generation 仍为 0，而工具观察为 1；这是原有持久化缺口，本轮没有修复或验证重启恢复。
- 没有验证跨 Task 记忆、长期压缩质量或 1M 窗口下的成本，不把一次复测包装成统计结论。

## 数据位置

- Run：`307c1c06-3f2f-4362-9c28-cf465001bb72`。
- Workspace：`/Users/zhaoguodong/code/GitNova/data/workspaces/1fb0d5fa-6a07-4686-8e6d-306f62c3058e`。
- 原始数据：`/Users/zhaoguodong/code/gitnova-context-scenario2-20260918/live-D-repeat-20260920-NWvCHk/D/`。
- 包含 run、steps、summary、model-calls.csv、comparison.json、integrity-checks.json 和 verification/ 独立测试报告。
- 服务日志位于同级父目录 server.log；真实服务留在 8080。本轮未提交或推送代码。
