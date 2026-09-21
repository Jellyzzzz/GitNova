继续修复请求幂等和批量导入，只允许修改：
src/main/java/orderflow/request/RequestFingerprint.java
src/main/java/orderflow/batch/BatchImporter.java

核对原始请求与规范化业务意图之间的区别：商品行重排或拆分不应创建第二笔订单，
但相同请求键的优惠或地址改变不能被静默吞掉。不同用户使用同一请求键要保持隔离。
批处理必须为每一行产生独立结果：非法行、库存不足或幂等冲突不能阻止后面的有效行；
已经成功的订单、预占和事件保持有效。不要把批处理改成整体回滚。

保持所有先前修复，运行 sh run-tests.sh all，核对 fixtures/import.csv 每行的实际处理结果。
最终说明仍存的风险，不要宣称这个内存模型支持跨进程 exactly-once 或数据库事务。
