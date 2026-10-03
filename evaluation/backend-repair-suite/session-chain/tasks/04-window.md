# 修复负时间窗口

在当前文件树上，只修改 `src/main/java/eventstats/WindowAggregator.java`。epoch 之前的事件也必须按向负无穷取整的窗口对齐；保持 `[start,end)` 和租户过滤语义，特别检查负整倍数边界。

验证本阶段并保留之前的修复；尚未修复的百分位和分页问题留给后续任务。
