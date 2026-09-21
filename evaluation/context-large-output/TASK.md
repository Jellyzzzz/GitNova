请修复这个 Java 订单计价仓库，使 README 中的计价规则成立。

先运行 sh run-tests.sh 观察真实失败，再检查实现。只允许修改 src/main/java/pricing/DiscountPolicy.java 和 src/main/java/pricing/ShippingPolicy.java；不要改 README、CSV、测试、运行脚本或公共接口，不要跳过失败用例。修复后重新运行同一测试命令。

完成时说明两个问题的根因，列出初次测试中至少两个有代表性的失败订单 ID、expected/actual 金额及其失败原因，并报告最终验证结果。不要从“命令已完成”推断“测试已通过”。
