# GitNova
GitNova 是一个轻量级私有代码托管平台，其核心引擎直接复用 CS61B Gitlet 项目中自实现的版本控制逻辑（Blob/Tree/Commit 对象模型、SHA-1 内容寻址、分支指针管理），并在此之上构建：

## SPEC v5 开发基线

- [V5-00 Baseline Freeze](docs/v5/baseline.md)
- [测试入口与运行环境](docs/v5/testing.md)
- [数据库迁移约定](src/main/resources/db/migration/README.md)

## 执行链清理（2026-11-04）

- Spring Boot 后端保留登录、仓库权限、Redis 限流/缓存、对象传输和 Gitlet CLI push。
- 旧 Service Harness 及 Session/Task HTTP 执行入口已退役；页面不会再请求这些接口。
- 数据库实体、Mapper、Flyway 历史迁移和本地仓库/工作区数据保留，不执行删表或数据清理。
- TS 内核位于 `agent-runtime/agent-core-ts`，Session 生命周期仍在开发，尚未接回云端 HTTP。
- `docs/harness-refactor` 中的 Java Core 路径是历史施工参考，不代表当前可运行的内核实现。
- 清理前代码可从提交 `0cab9a5` 找回；旧实验材料仍保存在 `evaluation` 中。
