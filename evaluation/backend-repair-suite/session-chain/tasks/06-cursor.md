# 修复稳定分页

只修改 `src/main/java/eventstats/CursorPager.java`。严格按 `(timestamp,tenant,id)` 升序、排他 cursor 翻页，反复读完后所有事件恰好出现一次；保持 limit 校验。不能只比较 timestamp 或只比较 id。

现在五类缺陷应都得到处理，请运行全部公开检查；如果发现前面任务仍有问题，明确报告，不擅自修改本 Task 之外的文件。
