# Mapper方法、作用域与事务施工

## 1. 共用约束

列名/类型唯一来源schema-columns.json与../schema/target.sql.reference。参考POJO使用UTC LocalDateTime，公开API用Instant。SQL只放单条读取/写入，不调用HTTP/模型/MQ。所有新增方法需在真实MySQL测试确认，不把内存规格模型当SQL实现。

锁顺序：repository → Session → Workline/Binding → PR → Branch(id升序) → Task/Attempt → Operation/Publication/Sync/Merge → counters/Outbox。同仓库业务写先锁已存在repository行；只有本地事件归档可以从Session开始，之后不获取repo锁。关系Follow只锁两user行/id升序再统计，不参与repo。消费投影只取receipt/PR或通知，不反取repo写锁。

`RepositoryMapper.lockForWrite(repoId)`为SELECT该repo FOR UPDATE，并返回当前可见性及授权所需身份；最终敏感授权在同事务使用当前锁定读，数据库故障不能当NONE继续。`BranchMapper.lockById(repoId,branchId)`按两个ID排序；保留已有findHead/compareAndSetHead，新增按稳定ID更新避免同名重建。

## 2. SessionControlMapper / WorklineMapper（步骤10先写基础）

| 方法 | SQL事实与必要条件 |
|---|---|
| findOwned/listOwned | session的repo/actor归属；不靠已失效客户端指针 |
| lockSession(Q) | SELECT agent_session WHERE session_id=Q FOR UPDATE |
| insertSession / insertWorkline / insertBinding | 三类独立行；L PREPARING、source_branch_id初始可空；Binding带Q/L/newEpoch |
| reserveEpoch(Q) | Session锁内next_runner_epoch+1并返回，失败实例也不回收该数字 |
| lockWorkline(Q,L) / lockBinding(Q,L,E) | 所有身份都校验，不能只匹配Q |
| activateInitial / activateSynced | TX-S2/Y2，指针、workline、分支、binding、sync结果同事务 |
| setPublishedHead(Q,L,expected,new) | 更新agent_workline.published_head/version；不写Session旧HEAD字段 |
| setLatestRecovery(Q,L,archive) | 只接完整RECOVERY且覆盖可信，不用PROGRESS覆盖 |
| claimLifecycle / recordObservedStatus | 条件scope+version+owner；过期不直接认为旧实例死亡 |
| insertSync / findSyncByKey / lockSync | 唯一(Q,actor,requestKey)，比较固定请求摘要 |
| recordSyncStage | 已完成事实后推进阶段；UNKNOWN不直接FAILED |

Task受理占槽（持上述锁与权限检查后）：
```sql
UPDATE agent_session
SET active_task_id=#{taskId},session_version=session_version+1,updated_at=UTC_TIMESTAMP(6)
WHERE session_id=#{sessionId} AND active_workline_id=#{worklineId}
  AND session_version=#{expectedVersion} AND workflow_state='OPEN'
  AND sync_operation_id IS NULL AND active_task_id IS NULL;
```
注意Task事务先插Task，再占槽；占槽失败整事务回滚。幂等已有Task在版本检查前按actor+requestKey查回，不因后来版本变了重复执行。

最终同步激活（其余参与行同事务）：
```sql
UPDATE agent_session SET active_workline_id=#{newWorklineId},
 current_runner_epoch=#{newEpoch},sync_operation_id=NULL,
 session_version=session_version+1,updated_at=UTC_TIMESTAMP(6)
WHERE session_id=#{sessionId} AND active_workline_id=#{oldWorklineId}
 AND sync_operation_id=#{syncId} AND active_task_id IS NULL
 AND session_version=#{transitionVersion};
```
transitionVersion是取得gate后持久记录的基准（若实现沿用expected_session_version则规定gate使version+1，expected+1即此值）；gate期间其他改变Session指针的动作拒绝，不能每次重试猜当前version。

## 3. TaskControlMapper / CommandDispatcher（步骤11）

findTask/findByIdempotency(Q,actor,key)、lockTask、insertTask、insertAttempt、attachAttempt、insertCommand、findCommand、scanPending、recordDelivery、requestCancel、updateExecution、updateAttemptExecution。POJO分别TaskRow/AttemptRow/CommandRow，不复用旧AgentTask CHECK。

插Task先令current_attempt_id=NULL；环境可用后插Attempt，再附着Task。新Attempt只在明确重试中创建；HTTP重发使用同commandId/attemptId。CANCEL对未发送意图ABORT；SENDING/UNKNOWN仍按相同attempt核对取消，不能因receipt404断言没执行。

终态投影更新Task条件至少taskId/currentAttemptId/worklineId；随后清Session槽必须同时匹配Q/L/currentEpoch/currentTask。旧终态可以存档，不可清新Task槽：
```sql
UPDATE agent_session SET active_task_id=NULL,session_version=session_version+1
WHERE session_id=#{sessionId} AND active_workline_id=#{worklineId}
 AND current_runner_epoch=#{epoch} AND active_task_id=#{taskId};
```
控制命令成功只更新delivery和receipt，不把202变成COMPLETED。更新0行必须区分重复已达状态与版本/身份冲突。

## 4. EventArchiveMapper / DeliveryMapper

`findByEventId/findCoordinate/insertEvent/advanceContinuousCursor/listAfter/findAnswer`：insert原事件、更新同Q/L/E连续sequence与Task投影在TX-E1同事务。archive_offset只作为同Session已提交序列查询；同Session归档都先锁Session，使后提交小offset不从已前移游标漏掉。未知间隙先补齐再推连续cursor。

`insertArchive/findByExport/confirmArchive`：导出校验、下载与文件持久化在事务外；固定包身份(hash/size/scope)确认后短事务STORED。RECOVERY含state与baseline，连续事件覆盖含快照事件满足后CONFIRMED；更新Workline.latestRecoveryArchiveId。旧ACK晚到只确认对应archive，不清Task，不发布，不前移另一工作线恢复点。

## 5. PlatformOperationMapper / Publication

findOperation、findByToolCall(attemptId,toolCallId)、insertOperation、markProcessing、completeOperation、scanPending；publication按operationId唯一，导出按Q/exportId去重。首次受理绑定Q/L/E/Task/Attempt/toolCallId/type与规范化摘要；未知操作只能原ID核对。

最终发布：repo→Q→L/Binding→Branch→原Task/Attempt→op/publication；验证原受理身份、原expectedHead及当前仓库写授权，更新branch CAS、CommitRecord、L.publishedHead、publication、operation结果、业务Outbox同事务。用户取消等待不回滚已受理Commit，不按Task终态把原目标改成当前线。sync/merge必须等未决操作明确结束。

对象编码/下载/哈希/祖先遍历尽量在事务外；若库里HEAD/权限变化则拒绝候选。CommitRecord按repo+sha，不按branchName过滤祖先；在线GC在本范围关闭。

## 6. PullRequestMapper / RepoCounterMapper

| 方法 | 约束 |
|---|---|
| findByCreateKey / findOpenPair | 前者是一次意图，后者是活动分支对；不同键不能突破生成列唯一约束 |
| insertRequest / insertInitialRevision | 与PR_OPENED及number/repoSequence同事务 |
| listVisible/detailCurrent | ACL先过滤；OPEN读当前S/T，CLOSED/MERGED读固定关闭/合并证据 |
| insertRevisionIfAbsent | UNIQUE(pr,branch,repoSequence)，targetHeadSeen没采集可空 |
| insertComment/findCommentByKey/updateComment | author由登录态派生；更新匹配version，普通/行锚点完整性在服务与CHECK验证 |
| insertMutation/findMutation | close/reopen/draft回执同事务，不用旧请求重演新状态 |
| insertMerge/findMerge/lockMerge | 固定R/S/T/time/message/actor/hash；重复sameID不同内容冲突 |
| commitMergeResult | PR/version/HEAD均未变才target CAS+PR MERGED+line状态+操作结果+Outbox |
| snapshotClosingHeads | 分支删除之前保留确定source/target/base；历史不依赖分支还活着 |

RepoCounterMapper在repo门闩内创建/锁定一行，分配next_pr_number、event_sequence。不要MAX()+1无锁，不把自增ID当跨事务有序提交游标。派生PR_SOURCE_UPDATED使用原repoSequence和PR scope，不调用RepoCounter。

## 7. SocialMapper / Profile查询

Follow：lockUsersAscending→确保统计行→lockStatsAscending→存在检查/INSERT或DELETE。**只有关系实际插入/删除1行才增减统计**。唯一冲突不等于任意DB错误可忽略。Star按repo门闩与当前授权执行相同模式；Watch按version CAS，PARTICIPATING的既有行保留version而不是删除重置。

主页/收藏/关注作者仓库列表在SQL JOIN当前repository/member关系过滤后keyset分页；批量加载，不每条调用一次远程权限；不返回隐藏项目的总数。实际user/repository成员列名在H0核对，原SQL不存在的字段不能凭文档猜。

## 8. DomainEventMapper / NotificationMapper

Outbox认领短事务：查询PENDING或租期已过CLAIMED，用FOR UPDATE SKIP LOCKED，批量写claimToken/leaseUntil后提交；锁外发送，按eventId+claimToken更新结果。毒消息写QUARANTINED。Publisher与消费者确认分离。

消费者insertReceipt与revision/notification/activity在同事务；重复hash一致直接返回既有结果。同ID不同hash隔离。回滚后独立持久化domain_event_failure，再ACK原消息；failure也写失败则不ACK。完整过程见15及business-events。

NotificationMapper.setRead匹配id、recipient_id、version；先读到desired状态已相同则幂等返回，否则UPDATE version+1。缓存不是read权威，也不能让重复事件插入重新清空read_at。Batch逐项回报，不用全局MAX(id)作为已提交高水位。

## 9. 实机验收门槛

每种Mapper至少列一个重复、一个真实双连接竞争、一个事务中途异常。H2/SQLite/内存只能辅助，不代替MySQL生成列、NULL唯一、外键、CHECK、行锁及隔离验证。本包没有执行任何线上迁移；导入脚本不会连接DB。
