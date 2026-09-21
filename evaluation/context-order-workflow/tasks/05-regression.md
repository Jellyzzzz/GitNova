生产修复已经完成，请补充独立的跨模块回归，只允许新增/修改：
src/test/java/orderflow/EdgeRegression.java
run-tests.sh

新增 Java main 测试类 orderflow.EdgeRegression，至少18个真实断言，覆盖：
金额舍入与净额运费边界、重复取消和已支付取消的无副作用、等价请求重放与冲突、
不同用户隔离、批处理中坏行之后继续处理，以及上述机制的组合流程。
断言不能只是打印固定成功字符串；失败必须非零退出。
最后打印 EDGE SUMMARY checks=N failed=M。保持原有 AllTests 全部执行，
sh run-tests.sh all 还应执行新增测试，不得删减现有检查或修改任何生产文件。

特别关注最早调查发现的失败为何会被这些测试捕捉。完成后运行全套验证并说明覆盖空白。
