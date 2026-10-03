# BundleSync

Java 21 的文件包校验、增量规划和快照发布组件。执行 `sh run-tests.sh`。测试数据与构建目录均放在临时目录。

## 契约

- 仓库路径为非空 POSIX 相对路径；拒绝绝对路径、反斜线、空段、`.`、`..`、NUL 和 Windows drive 前缀。合法 Unicode 文件名保留原文。
- 文件集合不能有重复路径，也不能同时包含文件 `a` 与文件 `a/b`，不依赖输入顺序。
- BlobCopy 不拥有输入输出流，不能关闭它们；支持短读/空文件。内容必须恰好匹配声明的长度和完整 SHA-256，任何不符均抛 IOException。错误前可能已写入 staging，调用者负责不发布。
- SyncPlan 比较完整的旧/新文件集合：删除缺失项，写入新增或摘要/长度变化项，跳过不变项。先返回按路径排序的 DELETE，再返回按路径排序的 PUT。
- SnapshotPublisher 只发布不存在的新目录；先完整验证和物化临时目录，再以原子 rename 发布。读取失败时删除本次 staging，不返回半成品、不覆盖或删除已存在的目标。
- 本次假定没有并发发布者，parent 是调用方指定的可信本地目录。atomic rename 不代表断电后的 fsync durability，更不是通用分布式文件系统。
