延续刚才的调查，修复计价链路，只允许修改：
src/main/java/orderflow/pricing/PricingPolicy.java
src/main/java/orderflow/pricing/ShippingPolicy.java

重点验证组合商品的小数分舍入、折后净额临界点，以及相同商品拆成多行的等价性。
保持之前明确的全部约束，不改测试、价格表、运费配置或接口。
运行 pricing、contract、inventory 检查，说明实际验证和未处理的其他模块缺陷。
不要求本任务修复取消、幂等或批处理；不能把它们的失败冒称已解决。
