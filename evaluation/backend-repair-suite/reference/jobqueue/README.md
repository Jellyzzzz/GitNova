# JobQueue

Java 21 的确定性任务调度模型。调用者传入毫秒时钟，不使用 sleep。`sh run-tests.sh` 运行公开检查。

这里是单进程状态机，不是 RabbitMQ，也不验证数据库锁、多进程 fencing 或宕机恢复。

## 契约

- 就绪 QUEUED 任务按 priority 降序、createdAt 升序、id 字典序升序选取；availableAt > now 不可领取。
- claim 增加 attempts、递增每任务 token，设置 RUNNING 与租期。运行中的任务必须由 reaper 处理到期后才能重新 claim。
- complete 必须同时满足 RUNNING、token 相同、now < leaseUntil；过期或旧 token 不允许改变状态。
- attempt 从 1 开始，重试延迟是 `min(cap, base * 2^(attempt-1))`，计算必须避免溢出；base > 0，cap >= base。
- reaper 在 now >= leaseUntil 时处理到期任务。attempts >= maxAttempts 则 FAILED；否则 QUEUED，availableAt = now + delay。重复扫描不能重复计数或改变已排队/终态任务。
- cancel 仅能把 QUEUED/RUNNING 变为 CANCELLED，首次返回 true；已取消、完成、失败返回 false 并保留状态。取消后的旧完成回调不能复活任务。
