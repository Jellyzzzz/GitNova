刚才的计价修复已经完成。现在在这个 Session 的现有工作区上继续补边界回归测试，不要重新实现或撤销已经通过验证的修复。

本条任务允许新增 src/test/java/pricing/EdgeRegression.java，并修改 run-tests.sh 将它加入测试链；这两处是本任务允许的全部写入范围。生产源码、README、fixtures/orders.csv 和现有 BatchRegression.java 保持不变。

请使用仓库现有的纯 Java 21 方式，不引入网络依赖或测试框架。新增 pricing.EdgeRegression.main，应有至少 12 个独立检查，覆盖我们此前明确的舍入、折后运费门槛、零金额/全额折扣、最大合法值及四类非法输入。断言应检查真实返回的金额或异常；出现任何失败必须非零退出，不能仅打印成功。末尾输出简短的 EDGE SUMMARY（包含 cases 和 failed）。

sh run-tests.sh 必须先保留并运行原来的完整批量回归，再运行新增边界测试；任一测试失败即整体失败。构建产物仍在 /tmp，不能污染工作区。请实际执行修改后的整条测试命令，完成时说明新增覆盖及真实结果。
