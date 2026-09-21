# Claude Code + DeepSeek Flash：同仓库、同三条任务

## 结果与比较口径

2026-09-20，使用本机 Claude Code 2.1.241，在独立测试副本中顺序执行原有三条任务。
同一个 Claude Session 通过 --resume 延续历史和工作区，没有在任务之间重置代码。
三次 CLI 均返回 success；三个阶段均通过原公开测试和 2,125 项隐藏验收，第二阶段实际新增了 20 项边界检查。
这是 Native CLI 与 GitNova 的参考性对照，不是模型设置、工具权限和运行环境完全一致的基准。

| Task | 模型响应数 | 工具调用数 | 单次输入峰值 | 总 tokens | 独立验收 |
|---|---:|---:|---:|---:|---|
| 修复 | 8 | 13 | 16,560 | 89,593 | 160/160；隐藏 2,125/2,125 |
| 补测试 | 9 | 10 | 30,841 | 228,702 | 160/160；边界 20/20；隐藏 2,125/2,125 |
| 交付审核 | 3 | 5 | 34,555 | 104,051 | 160/160；边界 20/20；隐藏 2,125/2,125 |
| 合计 | 20 | 28 | 34,555 | **422,346** | 三阶段均通过 |

CLI 的 num_turns 不是去重后的模型响应数。这里按 assistant.message.id 去重统计 8/9/3，避免 stream-json 将同一响应的 thinking/text/tool_use 分块重复计数。
未观察到 compaction 事件；本批不能评价 Claude Code 的摘要/压缩效果。

## Token 不能漏算缓存输入

Anthropic 兼容接口将输入分为 input_tokens、cache_read_input_tokens、cache_creation_input_tokens。
本报告用三者相加得到总输入，再加 output_tokens；不能仅拿未命中缓存的 input_tokens 与 GitNova 的全量 prompt tokens 比较。

| Task | 非缓存输入 | 缓存读取输入 | 缓存创建输入 | 输出 | 总计 |
|---|---:|---:|---:|---:|---:|
| 修复 | 15,362 | 70,528 | 0 | 3,703 | 89,593 |
| 补测试 | 25,131 | 195,456 | 0 | 8,115 | 228,702 |
| 审核 | 33,245 | 67,584 | 0 | 3,222 | 104,051 |
| 合计 | 73,738 | 333,568 | 0 | 15,040 | 422,346 |

三个阶段去重后的逐响应输入之和与 CLI 最终 usage 的完整输入一致。
CLI 显示的 costUSD 使用其模型价格映射；本次是自定义 DeepSeek model，不将其作为真实 DeepSeek 账单金额。

## 与 GitNova 128k 同场景比较

| 执行方式 | 完成数 | 模型响应数 | 总 tokens | 单次输入峰值 |
|---|---:|---:|---:|---:|
| Claude Code + Flash | 3/3 | 20 | 422,346 | 34,555 |
| GitNova A，无外置 | 3/3 | 26 | 895,466 | 67,144 |
| GitNova B，外置 | 3/3 | 23 | 565,277 | 50,404 |
| GitNova C，无外置、摘要未触发 | 3/3 | 27 | 954,026 | 74,225 |
| GitNova D，外置、摘要未触发 | 3/3 | 26 | 700,666 | 54,101 |

Claude Code 本样本比 GitNova 最省的 B 组少约 25.3% 总 tokens，少 3 次模型响应。
这个比例只描述本次执行结果，不能当成总体性能差距；尤其不能在未对齐 thinking 的情况下全部归因于 Harness。
原始七个文件 SHA-256、三条任务正文 SHA-256 与 GitNova 128k 批次一致。基准为 7 文件、8,008 字节、生产 Java 32 行的小型日志压力场景，不是大型仓库能力评估。

## 实际路线值得借鉴的部分

1. **在获取上下文时缩小输出。** 第一阶段实际用了 `sh run-tests.sh 2>&1 | tail -100`，随后用 `head -20` 和 `grep FAIL` 补齐表头与失败行。第三阶段用 `tail -25` 看当前验证结果，并另行验证退出状态。不只依赖事后压缩整段日志。
2. **审核时直接使用前文原始数字。** 第三阶段没有伪造 Artifact ID，也没有回退旧代码重算失败订单。最终列出的 ORD-065/069/077/089 等 expected/actual 与第一阶段日志一致。
3. **原生 Git 信息帮助收敛检查。** 用 git diff/status 确认变更与授权范围；未提交的新增测试通过 git status/Grep 确认，而不是误认为 git diff 包含未跟踪文件。
4. **仍然存在冗余操作。** 部分组合命令重复执行整套测试，只为改变过滤或查看退出状态。因此不是每一步都比 GitNova 更优。

GitNova 外置前的头尾预览在本 fixture 中省略了位于中段的 7 条 FAIL；Claude 通过过滤将这些高价值行挑出。
下一项值得局部验证的改进是更有针对性的命令输出预览：保留失败片段、总结和退出状态，完整日志继续可回读。此结论不要求重写 Runtime 或马上增加一套压缩算法。

## 错误与独立质量审查

- 第二阶段第一次 Write 参数不是合法 JSON，返回 InputValidationError；模型随后重试成功。日志提示可能是转义或截断，现有证据不足以认定具体原因。
- 第一、第二阶段各有一次 Bash 权限拒绝，随后改用获准的测试命令完成。拒绝来自本次受限非交互运行方式，不能直接算成 Claude Code 产品缺陷。
- 第三阶段最终说明错误地把 ORD-085 归为“两种缺陷叠加”。该订单旧/新运费均为 0，金额差来自舍入；这是解释错误，说明成熟 Harness 也不能保证每句根因归类正确。
- 最终“工作区干净”措辞不准确：仍存在有意保留的未提交修改，准确说法是没有额外构建产物污染。它在前文确实列出了这些修改，未将修改隐藏。
- 独立负向验收在临时副本中分别恢复两个旧实现：当前 20 项全过；旧折扣导致 5 项失败；旧运费导致 5 项失败。没有修改真实比较工作区，也未将隐藏验收反馈给模型。

## 配置与不可忽略的差异

- 使用 CLI 默认系统提示词和 Bash/Read/Glob/Grep/Edit/Write 六种内置工具，没有替换成 GitNova Prompt。
- --safe-mode、空 setting sources、独立 CLAUDE_CONFIG_DIR：排除用户插件、MCP、CLAUDE.md、记忆和自定义 agent 的污染；这不是启用所有附加功能的 Claude Code。
- --permission-mode dontAsk，按每条任务授权 Edit/Write 文件和必要 Bash 测试/检查命令；未启用 bypassPermissions。
- 配置请求模型为 deepseek-v4-flash，与 GitNova 同名。使用 DeepSeek 官方 Anthropic 兼容 endpoint；实际模型名称与供应商路由不能等同于冻结的模型权重快照。
- 设置窗口 128000；CLI modelUsage 显示 contextWindow=128000。
- 设置输出 4096。CLI modelUsage 中 maxOutputTokens=32000 是模型元信息，不能直接当成请求实参。本地模拟端点截获同配置请求，确认发送 max_tokens=4096。
- **thinking 未对齐。** 设置 MAX_THINKING_TOKENS=0 / alwaysThinkingEnabled=false 后，本地模拟请求实际省略 thinking，而非 sending thinking.type=disabled；同时发送 output_config.effort=high，temperature 省略。真实返回存在非空 thinking 块，虽然汇总 usage 的 thinking_tokens 为 0。不能声称与 GitNova thinking disabled / temperature=0 等价。
- 本地模拟探针只请求 127.0.0.1，使用假凭证并返回诊断错误，没有另行调用真实模型；它证明 CLI 序列化行为，不证明供应商如何解释缺省字段。
- 主流程在 macOS Java 21 执行；GitNova 在 Docker 执行。独立验收仍复用相同的 Docker image 与同一 verifier。Native 耗时累计 70.621 秒，不与 GitNova claimed→finished 作直接性能排名。
- --max-turns=14 为 CLI 自己的轮次限制，不等同于 GitNova 的 14 次模型调用；未配置完全等价的 35 次工具预算。三条任务都未因预算终止。

CLI 官方的 [命令参数](https://code.claude.com/docs/en/cli-reference) 和 [环境变量](https://code.claude.com/docs/en/env-vars) 用于核对上述选项；DeepSeek 的 [Claude Code 接入](https://api-docs.deepseek.com/quick_start/agent_integrations/claude_code/) 和 [Anthropic 兼容说明](https://api-docs.deepseek.com/guides/anthropic_api/) 用于确认 endpoint 与字段兼容性。实际日志与本地探针结果优先于仅凭配置推断。

## 复查入口

- 原始输出：`/Users/zhaoguodong/code/gitnova-context-scenario2-20260918/claude-flash-20260920-hTRinK`
- 副本：上述目录的 `repository/`，原测试仓库和 GitNova 四组 Workspace 未修改。
- Session：`bc9e3627-38e0-482e-9d85-6f9b6ed3c689`，历史保存在输出目录的 `claude-state/`。
- 每任务：`events.jsonl`、`result.json`、`before.json`、`after.json`、`verification/`；汇总 `manifest.json`。
- 负向验收：`edge-mutations/edge-mutations-rj7jjftq/report.json`。
- 驱动：`evaluation/context-large-output/run_claude_comparison.py`，复用 verify.py / verify_edge_mutations.py，没有新增生产服务或修改全局 Claude 设置。

全部进程与验收容器已结束；测试材料保留。未提交或推送 GitNova 代码。
