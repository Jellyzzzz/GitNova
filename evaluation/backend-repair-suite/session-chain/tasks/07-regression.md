# 增加跨模块回归测试

实现已修复，不再修改生产文件。本 Task 只允许新增 `src/test/java/eventstats/RegressionChecks.java`，提供 main 方法。使用现有 `testing.Checks.test/equal/rejects` 等断言，最后 `finish("REGRESSION")`；至少五个实际执行的检查，不能只打印成功。

覆盖此前五类缺陷的边界：非整小时时区与毫秒；租户键与冲突重试；负时间整倍数/半开区间；nearest-rank 与输入所有权；同时间戳、多租户的稳定分页。最好增加 ReportService 组合路径的检查。运行脚本已经会执行这个类，无需改入口。

测试需要能发现旧缺陷再次出现，而不只是证明主方法可以运行。本次不要通过修改生产实现来迁就测试。
