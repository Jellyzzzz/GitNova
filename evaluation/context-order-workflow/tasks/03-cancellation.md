继续在同一个工作区修复取消订单与库存一致性，只允许修改：
src/main/java/orderflow/order/CancellationService.java

请沿订单状态、Reservation、Inventory、EventLog 检查失败路径。
需要覆盖：正常取消、重复取消、已支付后取消、未知订单和跨用户取消。
尤其检查“抛了异常，但此前已经产生副作用”的情况。保留前一条任务的计价修复。
运行 cancellation 及前面通过的健康/计价检查；说明事件数、库存和订单版本的实际变化，
不要只判断是否出现异常。
