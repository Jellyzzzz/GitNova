# 修复百分位数

只修改 `src/main/java/eventstats/Percentiles.java`。按 README 的 nearest-rank 定义计算，支持不可变列表，不修改调用者的原始列表；空列表与非法 percentile 的拒绝行为保持不变。

在目前工作区运行测试，确认先前的修复仍然有效，分页问题暂不处理。
