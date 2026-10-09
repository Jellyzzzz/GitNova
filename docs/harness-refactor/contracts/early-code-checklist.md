# 已写少量协议代码：只改这些边界，不重写整个模块

1. CommandType仍8个；不添加同步文件命令。
2. AgentCommand/AgentEvent/ExportManifest/PlatformOperation.Request加入worklineId，活动wire版2；复制字段前对照唯一reference。
3. CommandReceipt/WorkerHealth/TaskView/WorkerConfig及ControlTypes保留同scope；Core不创建TaskInput；Message + SessionRuntime + Control + 已绑定Writer是输入边界，详见05第2.5。
4. 新协议拒绝旧mode/allowedTools权限字段，trusted RuntimeConfig.allowedTools只从初始化配置绑定；v1只用于历史只读解析，不默认接受线上旧请求。
5. 更新所有命令fixture、事件示例、平台能力fixture、Schema回环；PREVIEW/Checkpoint/初始化/停机类taskId和attemptId必须null。
6. 上面列出的未来字段要求不是当前文件创建顺序。当前从02步骤3的Message与Core循环开始；步骤1.1—1.7是协议内部的顺序，已写内容保留。查询/快照/平台类型在步骤6/8/9首次消费前补。不要先创造空包充数；文档导入脚本不会碰这些源码。

先commit/备份自己明确选定的早期代码，再手动应用字段改变。不要运行git add .或盲目覆盖agent-runtime。H0记录工作树未跟踪文件、已经通过的测试与下一项行为。

Submit.worklineStatus仍是服务端冻结的代码线事实；Worker把必要事实单独耐久写为当前execution的HARNESS_FEEDBACK(kind=RUNTIME_CONTEXT)，不再为Core增加同名业务字段。public不能自填环境消息；无需新增CommandType或TaskMode。
