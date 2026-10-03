# Eventstats

Java 21 的多租户事件统计库：时间解析、去重、窗口汇总、百分位数、稳定分页。`sh run-tests.sh` 运行全部公开检查。

## 契约

- 输入行是 `tenant|eventId|ISO_OFFSET_DATE_TIME|value`，时间转换为 UTC epoch milliseconds；金额是非负整数。
- 去重键是 `(tenant,eventId)`，完全相同的重试返回 false；相同键但时间或值不同必须抛异常且保留原值。
- 汇总区间为 `[start,end)`，窗口从 epoch 0 对齐；窗口起点是 `floor(timestamp / width) * width`，负时间也适用。
- 百分位数使用 nearest-rank：排序后第 `ceil(p*n/100)` 个元素，p 在 (0,100]；不能修改调用者的列表。
- 原始事件按 `(timestamp,tenant,id)` 升序分页；cursor 是最后一个已返回元素，下一页严格在 cursor 之后。时间相同的不同事件不能漏掉或重复。
- page limit 在 1..100；空集合、超过最后一条的 cursor 返回空页。

ReportService 组合 EventParser、EventStore、WindowAggregator 和 Percentiles；CursorPager 为原始证据提供稳定的回查入口。这里只测试单进程语义，不声称实现消息系统或数据库事务。
