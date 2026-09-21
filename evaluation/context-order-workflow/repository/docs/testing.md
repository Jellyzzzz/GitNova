# 公开检查

AllTests 支持选择 suite。每个检查创建独立 Fixture，订单ID和库存不会跨测试泄漏。
contract / inventory 是基本健康检查；pricing、cancellation、idempotency、batch
针对各模块；workflow 对组合操作进行检查。

测试输出包括原始 expected/actual。日志里出现 FAIL 是诊断线索，最终退出码和汇总是
本次执行的事实。检查没有运行完、进程被杀或输出被截断时，不得声称所有测试通过。

新增 EdgeRegression 应只依赖生产 API 和 TestSupport。入口为 public static void main。
它不能修改固定 AllTests 或 fixtures；必须非零退出表示断言失败。
建议同时断言返回值与副作用：库存、订单数、事件数和订单版本。

测试运行器将 javac 产物写到 mktemp 创建的 /tmp 子目录。
运行后删除的仅是该次构建目录，不会删除工作区源码或历史结果。
输入CSV用于 CLI/demo，API测试同时直接构造多SKU、拆分行、重复键和状态序列。
