# 唯一参考类型目录

agent-protocol/server-control以Java17编译，core/worker以Java21编译。这里是明确可编译的纯类型/端口与少量独立契约代码，不是完整生产模块；没有将接口编译成功等同业务实现。

Q/L/epoch在Command/Event/Manifest/Platform Request、health/receipt、Worker绑定、ControlTypes和SQL行贯穿；不复制进Core。Core用Message表达输入，用不透明executionId关联本地运行；SessionRuntime只读history、执行级Writer自动附来源。平台Task/Attempt映射只由Worker持有。SQL POJO字段由schema-columns生成；既有agent_session身份列仍需H0对照实际库，不猜线上DDL。

导入手册只替换docs，不把reference自动复制到src。按04的生产路径逐项手写/合并；不要整个reference建立成另一个项目，也不要保留agent_integration第二份协议。具体参数语义查05/16，方法体流程查02/12–15，核对脚本只验证此目录的类型与合同。

## 起步阶段：这里到底提供了什么

**reference不是施工顺序，也不是每个目标文件都有完整实现的模板库。** 当前按02“当前实际施工顺序”从步骤3的Message、SessionRuntime、Control、Writer和Core执行链开始；测试替身只用于开发期隔离验证，真实日志等依赖必须按步骤接入。步骤1.1—1.7只说明协议内部顺序，不是进入Core的必经关卡。分步完成全部实现，不以参考接口编译或脚本模型演示作为交付终点。

| 你找的文件 | 本目录实际提供 | 你要写什么、何时写 |
|---|---|---|
| AgentCommand.java | record/sealed Payload字段骨架 | 步骤1.2补静态校验，字段与分层规则见05 |
| ProtocolJson.java | **没有Java实现**；有[ProtocolJson.md方法说明](agent-protocol/src/main/java/com/gitnova/agent/protocol/json/ProtocolJson.md) | 步骤1.3按四个方法的数据流手写；复用旧Codec纯算法，步骤1.4做命令往返和边界测试 |
| AgentEvent.java | 事件record的字段骨架，不是interface | 步骤1.7补字段与构造校验；不添加append/read |
| SessionLog.java | 只读history + 嵌套Entry/Position/Writer；Writer绑定executionId | 步骤2实现同一FileSessionLog的bind、持久追加与读取；Core不直接调用宿主append |
| CommandReceipt.java | 受理结果record | 步骤1.5定义数据，步骤6TaskInbox才完成实际受理 |
| ModelTypes.Message / AgentEngine | 原文或USER Message入口；不另造TaskInput | 步骤3首先使用；用户原文不改写，Worker身份不拼进text；具体绑定见05第2.5 |

FileSessionLog耐久保存Entry及固定origin；Worker从同一记录投影AgentEvent，不生成第二套事实。PlatformCapabilities参考已移到agent-worker/platform；Core编译不需要protocol。“能序列化一条事件”与“日志实现完成”是两个不同验收。不要为了找不到不存在的方法体而提前实现Worker或数据库。
