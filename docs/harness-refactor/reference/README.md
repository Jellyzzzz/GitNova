# 接口参考，不是已经完成的 Agent

`agent-protocol`、`agent-core` 中是可独立编译的字段与接口参考。实现 body 仍按 02 文档手写。
所有 Java 顶层类型的实际目标路径是 `agent-runtime/<模块>/src/main/java/<包>/...`，不是 reference 路径。
不要求机械复制；这些文件用于确认你没有写出互不匹配的签名。

事件 payload 用 Map 是日志通用容器（工具输出具有异构结构），每一种 eventType 必须经过 05 的字段验证；任务命令 payload 是强类型 sealed record，由 HTTP 解码器按 type 显式选择，禁止 Jackson default typing。

离线检查：在文档根运行 `python3 scripts/self_check.py`。它编译这里的契约和通信探针，不构建尚未实现的完整产品。
