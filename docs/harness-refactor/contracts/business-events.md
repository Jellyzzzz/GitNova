# 业务事件完整契约

业务事件schemaVersion=1，独立于Agent私有wire v2。有限payload，不传数据库实体。字段：eventId、schemaVersion、type、scopeKey、scopeSequence(可空)、causationEventId(可空)、actorId、repoId(可空)、subjectId、occurredAt、visibilityAtCreation、payload。hash为确定性编码后SHA256，投递头/认领token不参与。UUID+timestamp在业务意图第一次创建时固定，重试不重新生成。

| type | 唯一产生事务 | 关键payload | scope / 序号 | 目标队列 |
|---|---|---|---|---|
| BRANCH_UPDATED | 统一分支写入事务 | branchId, branchName, beforeHead可空, afterHead, managed(bool) | repo:<id> / 分配repoSequence | notification,activity,pr-revision |
| PR_OPENED | PR创建+首revision | prId, sourceBranchId,targetBranchId,sourceHead,targetHead | repo:<id> / 分配序号 | notification,activity |
| PR_SOURCE_UPDATED | revision+receipt+派生Outbox | prId,sourceBranchId,beforeHead,afterHead | pr:<id>:source / 原repoSequence，causation原eventId | notification,activity |
| PR_MERGED | target CAS+PR+merge结果 | prId,sourceHead,targetHead,mergedCommit | repo:<id> / 分配序号 | notification,activity |
| PR_CLOSED / PR_REOPENED | PR状态+mutation回执 | prId,version,sourceHead,targetHead | repo:<id> / 分配序号 | notification,activity |
| PR_COMMENTED | 新评论/回复+回执 | prId,commentId,合法mention用户ID(不含正文) | repo:<id> / 分配序号 | notification,activity |
| USER_FOLLOWED | 真实新关系+统计 | followerId,followeeId | user:<actorId> / null | notification |

scopeSequence不是全站提交顺序；Follow无需有序投影，因此为空。派生事件不占repo计数锁；同一源事件可能派生多个PR事件，使用不同PR scope。状态关闭/重开和投影延迟按13记录归属与通知取舍，不承诺过去订阅完全回放。

Rabbit routingKey固定为type的小写点分形式，例如pr.opened、branch.updated。exchange gitnova.domain，队列gitnova.notification/gitnova.activity/gitnova.pr-revision。消费者回执名分别notification/activity/pr-revision；绑定按此表，不用一个队列让3个消费者竞争导致各收一部分。合法但过滤掉的事件仍在对应消费者事务写receipt表示已评估；超配额或解码失败不能写成功receipt。

收到Outbox持久消息不需要调用模型；发布者记录confirm+return，消费者commit之后ACK。投递保留和失败重放见15：receipt是投影成功证据，failure是已接管重放证据，二者不是同一状态。没有任何一个布尔值能同时证明broker、所有消费者和外部邮件都完成。
