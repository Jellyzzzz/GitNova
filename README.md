# GitNova
GitNova 是一个轻量级私有代码托管平台，其核心引擎直接复用 CS61B Gitlet 项目中自实现的版本控制逻辑（Blob/Tree/Commit 对象模型、SHA-1 内容寻址、分支指针管理），并在此之上构建：

## Owl 本地预览

Owl 是独立的 TypeScript 编码 Agent，支持终端交互、Bash 工具、会话恢复和自动上下文压缩。运行需要 Node.js 22.19 或更新版本以及 Bash。

在仓库根目录执行 `npm ci`，然后在 `.env.local` 中填写 DeepSeek 配置。

```dotenv
DEEPSEEK_API_KEY=your_key_here
DEEPSEEK_MODEL=deepseek-flash
DEEPSEEK_THINKING_MODE=enabled
```

用 `npm run owl` 启动 TUI，或用 `npm run owl:text` 进入文本模式。输入 `/resume` 可以恢复已有会话。会话保存在 `~/.owl/sessions/` 下，Bash 命令以启动 Owl 的用户权限运行。
