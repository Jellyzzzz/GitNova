# 排障时核对什么

金额不对：先记录 SKU、quantity、percent、subtotal、discount、net、shipping、total。
同一 subtotal 拆成多行不能改变结果；5030分的折前金额不等于满足折后免运费。
禁止通过改 fixtures/catalog.csv 的价格来让测试通过。

库存不对：比较操作前后的 available、reservation、released、order version 和事件数量。
重复取消导致多一条事件也是错误，即使 Inventory 已经阻止第二次物理释放。
已支付取消失败但库存回升，说明拒绝前出现了副作用，异常处理不会自动恢复。

重试不对：记录 actorId 与 requestId；比较聚合商品、优惠和目的地。
等价请求的重排不应该冲突，变化的优惠或地址不应该被误判成旧结果。
取消后重试原键只是重放历史提交结果，不是重新预占库存。

导入不完整：核对输入行数与 RowResult 数，特别看第一次坏行之后还有没有结果。
已接受的行不能因后面的行失败消失。可以用 fixtures/import.csv 重现组合情况。

这是单 JVM 演示，不要把成功重试说成跨进程 exactly-once。
引用日志要保留来源：先前失败与修复后的新验证属于不同执行。
