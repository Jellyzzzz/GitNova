# 15｜业务事件、Outbox、通知和关注动态

## 1. 两套事件不能混

AgentEvent是运行事实：模型调用、工具结果、检查点。DomainEvent是业务事实：分支已更新、PR已创建/合并、有人发表评论。通知和Feed只消费后者。

一个Agent调用create_pull_request和一个用户在页面创建PR，最后进入相同PR服务，产生相同PR_OPENED。不要为“AI创建的PR”新写一条消息中心通路。

## 2. 施工卡 E1：定义事件，先实现同事务写入

**文件**：`service/activity/DomainEvents.java`（有限record/枚举）、`service/activity/DomainOutboxService.java`、`mapper/activity/DomainEventMapper.java`。名称是建议新增；旧项目有满足契约的通用Outbox可替换，不并存两套等价组件。

信封字段：eventId UUID、schemaVersion、type、scopeKey、scopeSequence、actorId、repoId可空、subjectId、occurredAt、visibilityAtCreation、causationEventId（原始事件为空）、payload。payload只含必要业务字段和不可变ID，不放任意Entity、认证token或全文工具输出。

本次白名单：BRANCH_UPDATED、PR_OPENED、PR_SOURCE_UPDATED、PR_MERGED、PR_CLOSED、PR_REOPENED、PR_COMMENTED、USER_FOLLOWED。Star/Watch的频繁偏好变化可仅记录内部审计，不自动通知全站；默认Feed不广播收藏/取消关注。不是“每个数据库UPDATE都发消息”。

直接仓库写入产生的repo级事件sequence由同repo短事务分配（repo_counter.event_sequence）；同一次事务多个事件按固定顺序编号。没有repo的Follow事件按eventId去重即可，不假装所有业务存在全局严格顺序。PR版本投影只依赖repo级序号。

**思考路径**：先故意写一个错误流程“PR事务提交→发MQ”，问进程在中间退出会发生什么。正确的第一项不是接Rabbit，而是PR事务内插一条待投递Outbox。PR插入或Outbox插入失败都回滚。

**状态**：PENDING→CLAIMED→PUBLISHED；非法固定信封进入QUARANTINED，不吞掉；attempts/nextAttemptAt/claimToken/leaseUntil维护投递，不复活旧Agent run派发。每条Outbox绑定唯一业务eventId；重试序列化相同事实，不再次读取当前可变标题造不同payload。

**测试**：事务回滚时PR与Outbox都不存在；提交后发送前进程崩溃，Outbox仍可查；一条业务重试不造两条同事件。

**查**：旧项目检索 `Outbox|RabbitTemplate|publisherConfirm|PostReceiveEvent|@TransactionalEventListener`。`ApplicationEventPublisher`可用于唤醒扫描器，但不是耐久存储。

## 3. 施工卡 E2：Publisher如何可靠发送

**拓扑，本次固定**：一个durable topic exchange `gitnova.domain`；独立durable队列 `gitnova.notification`、`gitnova.activity`、`gitnova.pr-revision`，按需要的type绑定。独立队列让通知失败不拖住PR历史投影。队列容量/TTL/DLQ按本机资源设置并测试；不是无界堆积。

发布persistent消息、开启publisher confirms，启用mandatory并处理returned message。confirm是Broker受理，不是消费者业务已成功；consumer ack是另一条链，官方明确二者独立（17 MQ1）。必须把路由返回与confirm一起判断，不能只要ACK就标PUBLISHED而消息无队列接收。

**主循环**：短事务分页认领到期PENDING/过期CLAIMED，用FOR UPDATE SKIP LOCKED或条件claimToken；提交→锁外发送→等confirm/return→短事务按eventId+claimToken标PUBLISHED。Broker失败、timeout/未知结果保留重试；可能重复发送原eventId，但不能丢掉意图。

claim过期只能允许重新发送同事件，旧线程晚confirm按token条件不能覆盖新认领；这里重复可由消费者幂等处理。不要用这个轻量投递租约去推导“旧沙箱可以被新实例接管”，那是不同的副作用语义。

**第一项测试**：不接模型，手工写一条Outbox，Rabbit停机后PR操作仍成功且PENDING；启动Rabbit后正确收到；模拟confirm后DB更新前崩溃，重复事件只产生一份投影。

**查**：RabbitMQ Publisher Confirms、mandatory returns；Spring AMQP `CorrelationData/ConfirmCallback/ReturnsCallback`以项目版本为准；MySQL SKIP LOCKED只用在任务队列认领，普通业务不能随意跳过锁行。

## 4. 施工卡 E3：Consumer幂等与失败

消费者先校验schemaVersion/type/必要字段，DB事务中插 `domain_event_receipt(consumer_name,event_id,payload_hash)`；新事件才执行其投影，receipt和业务结果同事务；commit后ACK。重复且同hash直接ACK；同ID不同hash进入隔离告警，不能最后写者覆盖。

**注意**：如果把receipt先独立提交，接着写通知失败，后续重试会错误认为已处理。本包严禁这一顺序。

暂时错误回滚并按有界退避重试；毒消息进入DLQ/失败表保留eventId、errorCode、重放入口。不要无限立即requeue占满CPU；也不要catch记录后无条件ACK。当前没有运维后台时用内部命令和明确说明重放，API必须鉴权，不允许普通用户随意投递任意事件。

## 5. 施工卡 N1：接收人规则

**文件**：`service/notification/NotificationPolicy.java`、`NotificationConsumer.java`、`NotificationService.java`、`mapper/notification/NotificationMapper.java`；小结果类型嵌套，不每种type新建Service。

候选集合按事件决定：

| 事件 | 候选来源 |
|---|---|
| PR_OPENED/PR_SOURCE_UPDATED/PR_MERGED/PR_CLOSED/PR_REOPENED | 允许该类事件的仓库Watch用户 + PR作者/实际讨论参与者 |
| PR_COMMENTED | 允许评论的Watch用户 + PR作者/参与者 + 正文中合法@提及 |
| BRANCH_UPDATED | 显式选择分支更新的Watch用户；Agent工作分支更新不默认广播给只看PR的人 |
| USER_FOLLOWED | 被关注用户本人 |

随后去重→排除事件操作者→排除仓库IGNORE→检查用户存在/可使用→按MySQL当前仓库ACL确认→构造一条通知。@只是一个接收建议，不提升仓库访问权限。支持的@语法先沿用户唯一handle和长度规则，不用任意正则把邮箱/token都当用户名。

**投递时刻规则**：本次采用投递时当前ACL/订阅，但新Watch.createdAt晚于事件occurredAt不追溯旧事件。重试可因撤权少发，已落的通知在读取时仍受当前权限过滤。不是精确复原每个历史时刻的全量订阅快照；延迟补发的这种取舍要写进测试。

Gitea读取到的通知链也会合并候选、去重、处理静音并校验权限，但本包Outbox可靠性是我们的设计，不能说原项目Notifier本身就保证不丢。

## 6. 施工卡 N2：通知落表和分页

`user_notification` 每条eventId+recipientId+channel唯一；本次channel固定IN_APP。保存subjectType/subjectId/repoId、类型、时间、readAt，不保存可能撤回的整段私有正文。查询时基于当前可见对象生成短摘要；对象已删显示通用“该内容不可用”或过滤，不能回传删除前正文。

本次不做“一条PR所有事件折叠为一个会反复改版本的收件箱条目”，避免未读计数与重放之间不必要竞争。可以UI按PR分组，但数据库事件通知各自可追溯。

**事务**：小仓库场景每事件上限例如500接收人，一个事务完成receipt+授权筛选+通知插入。超过可配置硬限**不能截断后ACK**：标EVENT_FANOUT_LIMIT进入待处理/死信，整事件不宣称成功；扩大配额或实现持久分批游标后重放。这个发布规模边界应在验收显示，不冒充万人级通知架构。

**已读**：PATCH /api/notifications/{id}/read传read和expectedVersion，只对recipient=actor进行version条件更新；read/unread共用此入口。当前状态已经与请求相同时可返回当前状态；状态不同且版本过期返回409，不能让旧read重试覆盖后来unread。批量标记传一组已展示的{id,expectedVersion}（有数量上限），不使用全库auto_increment最大值冒充可靠的“截至现在已提交”边界。UI文案为“将本页标为已读”。

**未读数**：先用带当前ACL过滤的数据库COUNT；Redis可缓存短TTL辅助值，但不能让私有通知失权后仍暴露敏感计数。用户实际列表可见性比缓存即时漂亮更重要。

分页采用createdAt/id降序、cursor绑定actor/filter，每次查询当前ACL。异步新通知允许刷新出现，历史分页不承诺不可变全局快照；用稳定ID去重，不因页面排序改变重发通知。

**测试**：同事件三种候选只一条；重复投递不重新变未读；receipt后业务失败全回滚；无权限/IGNORE不收到；先收到再撤权读取不泄漏；A不能标B通知已读。

## 7. 施工卡 F1：关注动态是投影，不是全站消息队列

**文件**：`service/activity/ActivityConsumer.java`、`ActivityFeedService.java`、`controller/ActivityController.java`；存activity_entry，eventId唯一。

本次展示作者创建的公开PR、公开PR更新/合并/讨论及公开仓库分支更新（默认排除Agent草稿分支噪声，PR事件已足够）。Follow关系用于查询订阅作者，不生成每个粉丝一条数据库消息。Star/Watch不自动加入关注Feed；“通知”与“我关注的人发生了什么”是两个页面。

事件发生时仓库不是PUBLIC则不进入公开Feed；后来转公开不自动回填旧私有活动。事件发生时PUBLIC但现在PRIVATE也不可展示。查询必须同时满足visibilityAtCreation=PUBLIC、当前repository公开/有效、actor处于我的follow集合或本人。只使用“viewer现在有私有权限”也不能把全体成员的私有操作当公开作者动态。

**实现**：消费业务事件→类型/可见性白名单→插唯一activity→ACK；GET Feed用user_follow JOIN activity JOIN repository及当前可见性条件→稳定分页。源字段保存对象ID，不把旧repo名称/PR私有内容永久缓存成不可撤回字符串。初期查询式Feed足够，避免fan-out-on-write与百万粉丝优化。

**第一次测试**：A关注B，B公开PR活动可见；C无关注不可见；B仓库改私有后历史Feed项消失；重新公开后只恢复原PUBLIC事件，先前PRIVATE事件仍不出现。

**查**：Gitea notify/uinotification只作通知参考；本Feed方案是GitNova设计，不声称已审计Gitea全部动态实现。搜索 `fan out on read feed keyset pagination ACL`。

## 8. 与PR源版本的配合

BRANCH_UPDATED产生在仓库权威事务，不由Agent工具临时通知PR。pr-revision消费者用事件里的固定sourceHead和repoSequence记历史；详情和merge从权威Branch读当前S/T，不等待MQ追平。投影失败影响历史索引/通知及时性，不得改变已提交PR结果。

对于由BRANCH_UPDATED派生的PR_SOURCE_UPDATED事件，在投影事务里同时写派生Outbox，eventId可由(prId,原eventId,派生type)确定或记录映射，防重放产生不同派生事件。派生事件使用scopeKey=pr:<prId>:source与scopeSequence=原repoSequence，不再次分配repo计数，也不反向获取仓库写锁。消费回执、revision与派生意图同事务；当前PR非OPEN则只记历史、不派生更新通知。本次通知以投影时当前开放状态为准，不声称精确重建关闭/重开期间的通知时间线。不要Rabbit handler里直接再次发送无记录消息。

## 9. 出故障时你应该看到什么

Rabbit停机：主业务提交，Outbox堆积，监控告警；消费者恢复后补齐。数据库停机：业务写明确失败，不能先发成功通知。Redis停机：列表转DB或受限降级，关系不丢。毒消息：隔离且可追踪，不阻塞其余消息无限重试。权限撤销：新投递/当前读取都再次校验。

最低指标：outbox oldest age、pending/failed数量、消费者重试/DLQ计数、重复事件计数、通知延迟、每页SQL次数和耗时。没有这些观察点，看到页面“偶尔没有通知”无法定位是投递、授权还是UI。

## 10. 必须补齐的投递与重放边界

**mandatory不能证明三个目标队列都存在。** 它检测消息完全无法路由；只剩一个匹配队列仍可能confirm成功。启动Publisher前必须声明并验证exchange/三个队列/各自bindings；配置容量、拒绝溢出、DLQ规则时跑停机/解绑测试。PUBLISHED Outbox保留至保留期，按固定type→消费者路由表核对receipt或failure；缺目标消费者回执可重发同eventId，由已完成者去重。保留期内不能先删Outbox再声称有重放能力。这里选择有限保留及告警，不承诺无限停机也永不丢数据。

**消费失败的唯一兜底路径**：主事务回滚后，另开短事务以(consumerName,eventId)写domain_event_failure，记录原固定信封/hash、错误、attempts、nextAttemptAt和RETRY/QUARANTINED；该保存成功后才能ACK原消息。failure存储也失败则不ACK，关闭/暂停消费并退避。后台重放读取同条失败记录，调用相同消费事务；成功后记录receipt/业务结果，再将failure标RESOLVED。无效信封没有合法eventId时，用原bodyHash生成隔离身份，仅保留经大小限制的原始字节/安全错误；不执行它的业务投影。同ID不同hash只能隔离，不能覆盖原receipt。

三个消费者互不共享回执名，固定为notification/activity/pr-revision。不关注某类型的消费者不应收到该类型；错路由消息先核对路由清单后隔离，不无声drop。副作用全在本地MySQL时receipt与结果同事务；以后加邮件等外部副作用须另定发送意图，不能从当前模型推出端到端exactly-once。

已读更新的示例：客户端先读version=3；请求read(expected=3)成功变4；另一客户端unread(expected=4)成功变5；前一read再次抵达必须返回409，不能重新置为已读。若仅重复第一次请求、状态一直read，则返回当前read，不增加version。批量本页逐条验证，返回逐项结果，不把部分失败伪装全成功。

业务事件完整字段、产生位置、队列绑定、序号含义见[业务事件契约](contracts/business-events.md)。这些是本次GitNova的收口设计，不是声称已在OneDev/Gitea审计到相同保证。
