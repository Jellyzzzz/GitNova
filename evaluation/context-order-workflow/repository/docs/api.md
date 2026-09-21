# 公开 API 与语义

PlaceOrder(requestId, actorId, destination, lines, percent) 是调用参数。
OrderLine(sku, quantity) 表示需求数量；Catalog.Product 的价格与初始库存来自固定 CSV。
RequestCoordinator.submit(PlaceOrder) 返回 OrderReceipt，包括 orderId、Quote、是否重放。

OrderService.pay(actorId, orderId) 标记支付；重复支付不新增事件。
CancellationService.cancel(actorId, orderId) 返回 CancellationReceipt：
orderId、changed、当前 OrderStatus 与订单 version。

OrderQuery.view(actorId, orderId) 返回订单只读视图；all(actorId) 只返回该用户的订单。
Inventory.available(sku) 是可用库存，reservation(orderId) 是历史预占，
released(orderId) 表示释放过。EventLog.forOrder(orderId) 返回该订单的历史事件。

BatchImporter.importRows(batchId, rawRows) 接收不含表头的 CSV 行：
requestId,actorId,sku,quantity,percent,destination
此简化格式不支持引号、嵌套逗号或多 SKU 单行；多 SKU 使用 submit API。
数字字段允许首尾空白。空行也是一条非法输入，不可静默跳过。

错误约定：非法参数、未知 SKU、无库存、跨用户、非法状态或幂等冲突以
IllegalArgumentException / IllegalStateException 表达。批处理将这些逐行记录为拒绝。
测试必须区分“抛异常”与“抛异常且无副作用”。
