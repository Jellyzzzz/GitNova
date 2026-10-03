# Stockroom

Java 21、无第三方依赖的库存预留与调拨服务。数据仅保存在内存中，不模拟数据库事务。

运行：`sh run-tests.sh`。构建产物写入临时目录，不改动源码树。

## 业务契约

- 所有数量是正整数，每个 SKU 的可用库存上限为 1,000,000。
- 预留扣减可用库存；库存刚好够用时允许预留。不够、SKU 不存在或参数非法时不能产生任何修改。
- requestId 重试的 sku、quantity、ttl 必须一致，now 不参与请求身份；返回第一次的预留。冲突请求必须拒绝且不修改库存。
- `now >= expiresAt` 即到期；每份预留只能释放一次。
- 调拨先验证源库存、目标 SKU 和目标容量；任何失败都不能丢失源库存。
- CSV 补货格式为 `sku,quantity`，允许空行和 `#` 注释；重复 SKU、未知 SKU、非法数量、最终库存超限均使整批失败。失败时不能部分补货。

## 调用关系

ReservationService → Inventory / Reservation；TransferService → 两个 Inventory；
RestockService → StockCsv → Inventory。检查不变量时请观察失败前后的库存，而不只观察异常。
