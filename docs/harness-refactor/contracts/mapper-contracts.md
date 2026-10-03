# Mapper、SQL及Service事务接口施工表

目标包：`src/main/java/com/gitnova/mapper/agent/control`。每个接口加 `@Mapper`，多个参数逐个加 `@Param`，使用注解SQL或同名XML任选编码形式，但SQL语义不变。本表固定方法；不要让IDE自动推导列名或将旧mapper混进新事务。

行类型完整定义在 `reference/server-control/src/main/java/com/gitnova/entity/agent/control/ControlRows.java`；逐列字典在 [sql-field-map.md](sql-field-map.md)。手写INSERT按DDL所有NOT NULL列赋值，`createdAt/updatedAt`一次固定UTC；generated列和自增列不放INSERT。所有update检查affectedRows，0不是成功。

## SessionControlMapper（步骤 10）

```java
SessionRow findSession(String sessionId);
SessionRow findByCreationKey(String key);
SessionRow lockSession(String sessionId);
int insertSession(SessionRow row);
BindingRow findBinding(String sessionId, long epoch);
BindingRow lockBinding(String sessionId, long epoch);
int insertBinding(BindingRow row);
int attachSandbox(String sessionId, long epoch, String operationId, String sandboxId,
                  LocalDateTime expiresAt, long expectedVersion);
int activate(String sessionId, long epoch, long expectedVersion);
int setActiveTask(String sessionId, String taskId, long expectedVersion);
int clearActiveTask(String sessionId, String taskId);
int updatePublishedHead(String sessionId, String expectedHead, String head);
int setLatestArchive(String sessionId, String archiveId);
List<BindingRow> scanBindings(int limit);
int claimLifecycle(String sessionId,long epoch,String owner,LocalDateTime until,long version);
int recordObservedStatus(String sessionId,long epoch,String status,LocalDateTime expiresAt,
                         String errorCode,long version);
List<SessionRow> listOwned(long repoId,long actorId,String afterId,int limit);
```

`lockSession`是 `SELECT * FROM agent_session WHERE session_id=#{sessionId} FOR UPDATE`。必须在Spring事务里调用，否则锁在方法返回前已释放。`lockBinding`同时匹配epoch。

```sql
UPDATE agent_session SET active_task_id=#{taskId}, version=version+1, updated_at=UTC_TIMESTAMP(6)
WHERE session_id=#{sessionId} AND version=#{expectedVersion}
 AND execution_backend='SANDBOX_AGENT' AND status='ACTIVE'
 AND workflow_state='OPEN' AND active_task_id IS NULL;

UPDATE agent_session SET active_task_id=NULL, version=version+1, updated_at=UTC_TIMESTAMP(6)
WHERE session_id=#{sessionId} AND active_task_id=#{taskId};

UPDATE agent_sandbox_binding
SET sandbox_id=#{sandboxId},status='BOOTING',expires_at=#{expiresAt},
    version=version+1,updated_at=UTC_TIMESTAMP(6)
WHERE session_id=#{sessionId} AND runner_epoch=#{epoch}
 AND create_operation_id=#{operationId} AND version=#{expectedVersion}
 AND sandbox_id IS NULL AND status IN ('CREATING','UNKNOWN');
```

activate还要匹配current_runner_epoch并确认该binding READY。分支插入与Session ACTIVE在TX-S2同一事务。claimLifecycle只能在旧operation租约过期/为空或同owner时成立；持有者不在数据库事务里做长网络请求。create响应不明不能把新createOperationId代替旧ID。

## TaskControlMapper（步骤 11）

```java
TaskRow findTask(String taskId);
TaskRow findByIdempotency(String sessionId,String key);
TaskRow lockTask(String taskId);
AttemptRow findAttempt(String attemptId);
AttemptRow lockAttempt(String attemptId);
int insertTask(TaskRow row);
int insertAttempt(AttemptRow row);
int attachAttempt(String taskId,String attemptId,long attemptNumber);
int insertCommand(CommandRow row);
CommandRow findCommand(String commandId);
List<CommandRow> scanPending(LocalDateTime now,int limit);
int recordDelivery(String commandId,String state,String receiptJson,String error,
                   LocalDateTime nextAttemptAt);
int requestCancel(String taskId);
int updateExecution(String taskId,String currentAttemptId,String status,String reason,
                    String answerEventId,LocalDateTime finishedAt);
int updateAttemptExecution(String attemptId,String status,String reason,String answerEventId,
                           LocalDateTime finishedAt);
int updateSettlement(String taskId,String expectedAttempt,String settlement,String publication);
```

findTask之后仍由Service核对repo/session/actor，mapper不等于授权。insertTask先置current_attempt_id=NULL，insertAttempt之后attachAttempt，避免循环FK失败。Task与Attempt状态同时更新。

```sql
SELECT * FROM agent_control_command
WHERE status IN ('PENDING','SENDING','UNKNOWN') AND next_attempt_at<=#{now}
ORDER BY next_attempt_at,created_at LIMIT #{limit};

UPDATE agent_control_task
SET status=#{status},terminal_reason=#{reason},answer_event_id=#{answerEventId},
    finished_at=#{finishedAt},updated_at=UTC_TIMESTAMP(6),version=version+1
WHERE task_id=#{taskId} AND current_attempt_id=#{currentAttemptId};
```

scanPending不claim AgentLoop线程，只恢复离散控制意图。多副本可重复发送相同commandId，由Worker幂等接收；绝不创建第二个attempt抵消网络超时。撤销发生在SUBMIT发送前：同Session锁下将尚未发送的意图ABORTED；已经SENDING/UNKNOWN则查询/投递同attempt的CANCEL，404后仍须核对SUBMIT结果。

## EventArchiveMapper（步骤 11）

```java
EventRow findByEventId(String eventId);
EventRow findCoordinate(String sessionId,long epoch,long sequence);
int insert(EventRow row); // 回填archiveOffset；不是INSERT IGNORE
int advanceCursor(String sessionId,long epoch,long expectedSequence,long nextSequence);
List<EventRow> readAfter(String sessionId,long afterOffset,int limit);
List<EventRow> readEpoch(String sessionId,long epoch,long afterSequence,int limit);
```

TX-E1先lockSession、lockBinding，再查事件与连续性。相同ID必须比较摘要，相同则无副作用返回；不同报警，不用INSERT IGNORE掩盖。seq=current+1才插入并投影；重复<=current核对，跳号不前移游标。游标update成功与投影同事务。public readAfter只接Session专属流，limit上限；返回副本在Service白名单化，不能直接serialize私有payload。

```sql
UPDATE agent_sandbox_binding SET last_archived_sequence=#{nextSequence},
 version=version+1,updated_at=UTC_TIMESTAMP(6)
WHERE session_id=#{sessionId} AND runner_epoch=#{epoch}
 AND last_archived_sequence=#{expectedSequence};
SELECT * FROM agent_event_archive WHERE session_id=#{sessionId}
 AND archive_offset>#{afterOffset} ORDER BY archive_offset LIMIT #{limit};
```

## DeliveryMapper（步骤 8、14）

```java
ArchiveRow findArchive(String archiveId);
ArchiveRow findArchiveByExport(String sessionId,String exportId);
int insertArchive(ArchiveRow row);
PublicationRow findPublication(String publicationId);
PublicationRow findByExport(String sessionId,String exportId);
PublicationRow lockPublication(String publicationId);
int insertPublication(PublicationRow row);
int recordCandidate(String publicationId,String candidateCommit);
int recordPublicationResult(String publicationId,String expectedStatus,String newStatus,
                            String resultCommit,String errorCode);
int recordSettlement(String archiveId,String outcome,String head,String commandId);
List<PublicationRow> scanUnsettled(int limit);
```

文件保存不在mapper事务内：下载到临时路径→限额/manifest/hash验证→force/原子发布不可变storage_key→insertArchive。以(sessionId,exportId)唯一找赢家并比bundle hash；同身份不同内容是错误。归档已保存但DB未成功的对象只是孤儿，不重新读活动目录。

TX-P1固定timestamp/message/expectedHead；构造对象完成后TX-P2以Session→Publication→Branch顺序锁，检验当前HEAD，推进Branch、CommitRecord、Publication、Session.lastPublishedHead同事务。使用BranchMapper.compareAndSetHead，不从Mapper直接执行模型。recordSettlement附记与创建ACK命令同事务，ACK重试只读同记录。

## PullRequestMapper（步骤 15）

```java
PullRequestRow find(long repoId,long prId);
PullRequestRow lock(long prId);
PullRequestRow findOpen(long repoId,String source,String target);
List<PullRequestRow> list(long repoId,String status,long afterId,int limit);
int insert(PullRequestRow row);
int updateState(long prId,long expectedRevision,String state,int draft);
CommentRow findCommentByKey(long prId,long actorId,String key);
int insertComment(CommentRow row);
List<CommentRow> comments(long prId,long afterId,int limit);
MergeRow findMergeByKey(long prId,String key);
MergeRow lockMerge(String mergeId);
int insertMerge(MergeRow row);
int commitMerge(String mergeId,String commit);
int failMerge(String mergeId,String status,String errorJson);
int markMerged(long prId,long expectedRevision,String fromHead,String commit,
               String method,LocalDateTime at);
```

所有用户入口先RepositoryAccessService鉴权，PR必须属于path repo。草稿转换/关闭/重开使用revision CAS。已MERGED不能重开；重开可能与另一个OPEN PR撞唯一索引，应409不覆盖。评论幂等key碰撞比较body，不返回不同内容的旧评论当成功。

merge请求先固定sourceHead,targetHead,revision,actor,key,timestamp；事务外读取对象/计算三方候选；TX-R2锁关联Session→Merge意图→PR→按名称排序的两条Branch。源Agent Session未IDLE/SETTLED拒绝，目标是活跃受管Agent分支也拒绝。重新校验双HEAD/revision再CAS目标、写CommitRecord、更新PR和Merge意图；任何一项不满足整笔回滚，候选对象可不可达。

## Mapper SQL怎么写到文件

本手册选择注解SQL，不额外建XML文件：上述签名放对应 `*Mapper.java`。SELECT短语放`@Select`；INSERT与UPDATE用Java文本块（Server Java17支持）。批量/动态条件不用字符串拼接用户输入，条件选择写成固定SQL方法；排序字段使用白名单。日期/JSON参数绑定，不把JSON正文拼入SQL。

建议先写Session/Task/Event三组mapper的真实MySQL集成测试，再编排Service。测试使用事务回滚或独立测试数据库；建表迁移不能对个人唯一开发库直接试错。本包仅对DDL做静态检查，没有宣称MySQL执行成功。
