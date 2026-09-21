# 调用与状态边界

RequestCoordinator 使用 (actorId, requestId) 作为重试身份。
RequestFingerprint 表示这次请求的规范化业务意图，不是 HTTP 原始文本。
RequestJournal 保存成功提交的 fingerprint 与 OrderReceipt；拒绝请求不能留下成功记录。

OrderService 先读取 Catalog，再交给 PricingService 生成 Quote。
库存的可用量在 Inventory.reserve 中一次性检查全部 SKU 后扣减。
OrderRepository 保存 Order；EventLog 记录 ORDER_RESERVED。
Receipt 是提交瞬间的不可变返回，后续取消不改写已经返回的 Receipt。

Order 可以从 RESERVED 转成 PAID 或 CANCELLED。Inventory 持有 Reservation，
释放操作只对该订单的已预占数量生效。取消的访问检查、状态检查和副作用次序很重要：
异常不是自动回滚机制。不要在确认允许取消之前改变库存。

BatchImporter 处理的是多个独立逻辑请求。每行调用同一个 RequestCoordinator。
批内相同键也遵守幂等规则；一行失败既不回滚前面成功，也不代表后面不用处理。

Query 返回快照，不暴露可修改的 Map/List。EventLog 不是消息队列，只是可核对的顺序事件。
本项目没有后台任务、真实支付或跨进程恢复。不得通过新增大框架绕开当前业务缺陷。
