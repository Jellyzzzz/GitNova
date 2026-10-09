# 19｜已集成范围与早期代码导入检查

## 1. 这份文件不再是补丁层

两份包的有效正文、reference、Schema、样例、DDL与文件地图已经写回同一根目录。原“一会话一分支”“merge检查W”“合并后新Session”不作为当前行为。修正理由和来源见audit/修正登记.md，不需要拿旧ZIP对照决定写法。

## 2. 你只写了少量代码时怎么接入

先git diff核对已写文件，不自动覆盖src。CommandType保持8种；AgentCommand/Event/Manifest/PlatformOperation、CommandReceipt/TaskView/WorkerHealth和WorkerConfig保留完整平台scope。Core不再新增TaskInput；正文用Message，SessionRuntime只持本地资源/只读history，Writer按执行绑定且由Worker自动附平台来源。线上schemaVersion=2及已实现ProtocolJson不改。具体参考文件由04指向，不能建立第二份agent_integration/reference。

此处列的是最终字段一致性要求，不是要求一次写完全部类型。当前按02的“当前实际施工顺序”进入步骤3实现Core执行链，之后完成其余接线与整体验收；1.1—1.7仍是协议内部顺序，不阻塞Core。AgentEvent没有append/read方法；查询、manifest与平台操作协议在实际消费前补齐，不从交付范围删去。

数据表、Mapper仍在后续对应步骤才实现；当前不用执行DDL。旧Session.mode/单值HEAD不要照搬；Task的所有权不由自然语言分类。首次创建L1是Agent平台初始化的一部分，不能到sync时才补所有scope。

## 3. 施工衔接位置

| 原02步骤 | 已集成的工作 | 放行证据 |
|---|---|---|
| 1 | v2协议与三元scope，public/private分离 | Schema与往返/错scope |
| 3→2 | Core统一Message输入；日志适配保留固定来源scope，原历史不改写 | 独立Core循环、跨epoch/线历史引用 |
| 6/8 | 控制壳先于安装、日志后开，ready不等于平台active | 重复INITIALIZE/安装崩溃 |
| 10 | Session+初始Workline+Binding，epoch单调分配 | 重试固定A/不重复分支 |
| 11 | 新Task预期line/version，普通回收排队、SYNC拒绝 | 迟到结果不清新槽 |
| 14 | Workline ACTIVE发布、统一repo门闩、Outbox写入 | 原operation结果核对 |
| 15→13 | 通用PR，受管来源DB资格，merge不碰W | dirty merge Session继续 |
| 15后→12 | 显式sync新线，新epoch/new root，不新聊天 | Q不变、草稿处理正确 |
| 16/18 | 恢复同线不是同步，新增协作总验收 | 统一acceptance真实证据 |

## 4. 已部署旧新链时才需要的数据迁移

你当前只处于早期实现，不必为了不存在的生产数据新造兼容引擎。确有旧持久数据时：为原Session建立L1，按历史Binding epoch回填线归属；保留人工CLOSED，不能把它当merge自动关闭重开；原JSON/ZIP hash不改，建立外部scope映射。旧工作分支字段只作历史兼容，新写路径不双写权威HEAD。真实Flyway只能追加。

## 5. 本轮不替你做的操作

没有复制reference到src，没有替换你的已写Java，没有执行migration，没有切git分支，没有恢复旧MQ或自动Review。import_docs.py只处理文档目录，并对旧目录作外部备份。源码实现、编译、数据库与双机测试仍按02及12—16由你完成。

跨线Context文件及首次记录的精确规则见[Context交接合同](contracts/context-handover.md)。

**合并事实怎样进模型：** Server在构造每次私有SUBMIT时，从已锁定Workline冻结`worklineStatus`（ACTIVE/MERGED/SOURCE_MISSING），持久在命令信封中；Worker通过宿主写入接口将它记录为当前execution的`HARNESS_FEEDBACK(kind=RUNTIME_CONTEXT)`，Context从已提交事实读取并单独注入，不增加Core的worklineStatus业务字段，也不改写USER正文。它不是TaskMode，不决定自然语言意图，也不授予工具权限；公开Task不能传入它。这样merge不重载沙箱也能让下一Task知道旧线已合并。若执行时平台状态后来变化，外部发布仍以Server当前授权为准，不凭此提示消息放行。
