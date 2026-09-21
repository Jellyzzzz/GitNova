# OrderFlow

一个无外部依赖的 Java 21 订单工作流。金额以整数分计，库存按件，业务时钟由调用方传入。
这是顺序执行的内存模型，不宣称线程安全、进程重启恢复或跨数据库事务。

入口从 request.RequestCoordinator.submit 开始。order.OrderService 负责计价、库存预占及
订单保存；order.CancellationService 负责取消；batch.BatchImporter 逐行提交导入请求。
audit.EventLog 与 query.OrderQuery 提供可观察结果。每个 TestSupport.Fixture 是独立业务实例。

运行：sh run-tests.sh all。也可选择 contract、inventory、pricing、cancellation、
idempotency、batch、workflow。编译及运行中间文件位于 /tmp，不能污染工作目录。
测试框架逐例打印 CASE|suite|id|expected|actual|PASS/FAIL，并在结尾给出计数。
失败输出是实际观察值，不应通过修改断言或文档消除。

先读 docs/architecture.md、docs/contracts.md、docs/api.md；
生产约束、操作诊断、fixture 说明分别见 docs/operations.md 和 docs/testing.md。
当前仓库保留若干需调查的行为偏差。契约是目标，当前实现和测试结果用于定位偏差。
