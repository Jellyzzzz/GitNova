# 关键类的方法清单与装配图

本文件补充02中缩写的方法签名。仅列职责方法，不要求把每个private helper也拆类。所有路径见04。未单列的请求/结果类型作为所在类的 `public static record`，不是隐藏的第三方类型；跨模块已有类型从reference导入。

## 1. Worker控制层

`WorkerConfig`字段固定：`String sessionId, String worklineId, long runnerEpoch, int port, Path sessionRoot, URI modelEndpoint, String modelToken, String workerToken, URI platformEndpoint, String platformToken, RuntimeConfig runtime, Limits limits`。嵌套Limits字段：`int maxBodyBytes, int maxMessageBytes, int maxSubscribers, int maxPendingSteers, long maxBundleBytes, long maxExpandedBytes, int maxFiles, long maxFileBytes, int maxPathChars`。工厂 `static WorkerConfig fromEnvironment(Map<String,String>)`；错误配置启动失败，不静默采用开发密钥。资源参数在同一入口读取一次，不能各Tool读不同环境。

上述WorkerConfig还应持有`LinuxProcessSupervisor.Limits processLimits`，与HTTP/ZIP的`WorkerConfig.Limits`分开；它不是线上命令字段。其来源及字段见9.3，不能让Core反向import WorkerConfig。


`WorkerSession(FileSessionLog store, String sessionId, String worklineId, long epoch)`持有当前执行句柄、WorkerHealth.State、publishedHead、pendingCheckpointId和initialized标记；同一短锁维护。句柄是WorkerSession内的record，不创建新的顶层类：

```java
record Execution(AgentCommand acceptedCommand, String executionId,
                 ExecutionControl control, SessionLog.Writer writer,
                 ToolRuntime tools) {}

WorkerHealth health();
Execution activeExecution(); // 无活动返回null，仅Worker内部使用
Execution requireExecution(String taskId, String attemptId);
void initialized(String publishedHead, String configDigest);
void begin(Execution execution); // IDLE→RUNNING
void beginFinalizing(Execution execution, AgentOutcome result);
void finishTaskLocally(Execution execution, AgentOutcome result);
void beginCheckpoint(String exportId);
void checkpointStored(AgentCommand.CheckpointAck ack);
void block(String code);
void stopAccepting();
```

`TaskInbox`构造依赖 `WorkerSession, SessionRuntime, FileSessionLog, Function<WorkerSession.Execution,AgentEngine> engineFactory, ExecutorService taskExecutor, TaskFinalizer, WorkerResources, Clock`。engineFactory由WorkerAssembly提供普通构造函数/lambda，不新建工厂接口。Inbox维护commandId→digest/receipt索引；已受理Task/Attempt→Execution的查询由WorkerSession提供。不要再另存一个可变currentControl。方法仍为accept(AgentCommand)、receipt(String)、task(String)；receipt返回Optional，task不存在由HTTP映射404。

同一commandId已受理必先返回原receipt；不同digest拒绝。首次受理的executionId必须写入COMMAND_ACCEPTED，响应丢失后按原ID查询，不重新构造执行。初次调度失败也记录明确结束，不删除受理事实。接收handler只持短锁，不在锁中等模型、seal、下载或网络ACK。

**TaskInbox只做一次身份绑定。** 校验Q/L/E、HEAD、配置digest、deadline与活跃槽；记录原命令和executionId；创建该次Writer/Control/获授工具并交给Execution。任务线程调用 `engineFactory.apply(execution).run(submit.message(), session, execution.control(), execution.writer())`。正文逐字不变，由Core的String重载转USER Message；也可在Worker包装同一Message再调用。两者只选一条，不重复追加用户消息。取消通过taskId/attemptId找到原Execution.control，不能取消后来启动的任务。

必要工作线事实由Inbox使用宿主写入接口保存HARNESS_FEEDBACK(kind=RUNTIME_CONTEXT)，随后进入模型上下文，不把命令JSON拼进USER正文。详细字段见05第2.3—2.5。Core不接AgentCommand，不生成平台任务，不把executionId当平台授权。

SessionRuntime只持本地sessionId/目录/只读history/ResultStore；Sealer与平台工具从Worker配置及不可变Execution取得平台身份。独立本地调用直接打开本地资源，以空origin创建Writer，再run一句话；无需TaskInput、Worker或平台回执。

`AgentWorkerServer`只注册路由，依赖WorkerConfig、WorkerApplication与WorkerResources；Boot阶段不要求SessionLog/TaskInbox存在，初始化完成后从单一RuntimeServices句柄读取它们；它不new Engine。初始化PUT从request body流读取，经WorkerResources写incoming；不把zip body读成一个无限byte[]。`QueryHandlers`使用同一WorkerResources，不提前依赖具体ExportStore。

**装配闭合方式：** boot先建立独立进程锁、BundleCodec/ExportStore、LocalWorkerResources与HTTP；initialize(commandId,init)完全安装目录后调用activate构建运行对象。运行图为Log/ResultStore→SessionRuntime/WorkerSession→Sealer→LocalTaskFinalizer→Inbox。WorkerAssembly同时提供每次执行的工具/Engine装配逻辑；受理后才绑定平台工具，不能在共享工具里替换currentTask。所有对象成功后单次发布RuntimeServices。具体安装重试/回执见09第3节。

这里两个名字都不是要你另找的框架类：`WorkerApplication`和`RuntimeServices`都定义在现有`WorkerAssembly.java`内部，不新增顶层helper。`WorkerApplication`持有配置、进程锁、HTTP、执行器、WorkerResources及一个`AtomicReference<RuntimeServices>`；Boot时引用为空。`RuntimeServices`至少包含`SessionRuntime, WorkerSession, TaskInbox, ProcessSupervisor, SessionSealer`，其余组件已通过Inbox/装配入口等依赖图持有，不重复保存全部对象。

`WorkerApplication`的方法入口：`start()`启动控制HTTP；`activate()`在安装完成后构建运行图、耐久写WORKER_READY、最后发布句柄；`Optional<RuntimeServices> runtime()`提供只读访问；`close()`按停止受理→取消任务/确认进程退出→关闭运行资源/HTTP/执行器→最后释放进程锁的顺序清理。activate使用初始化互斥保护，同一实例不可并行激活；装配中途失败关闭本轮已创建资源，不发布半成品。WORKER_READY表示本地已准备，不代替平台激活Binding的事务。

`LocalWorkerResources(WorkerConfig, BundleCodec, ExportStore, Runnable activate, Supplier<SessionSealer> sealer)`在构造时接受安装成功回调和激活后资源入口。WorkerApplication先建立自身生命周期容器，再连接`this::activate`及读取运行句柄中Sealer的supplier，最后启动HTTP；不是用尚未赋值的局部变量捕获Application。Boot不取Sealer；checkpoint/preview在激活后才取。它不能构造另一个Application，也不能提前要求WorkerSession存在。安装/装配I/O错误由initialize边界转IOException并失败，不执行后续就绪发布。

`QueryHandlers(WorkerAssembly.WorkerApplication application, WorkerResources resources)`逐次取得当前句柄。Boot的live直接从Application读取，ready明确为未就绪；INITIALIZE回执由安装标记提供。Task/普通command查询仅在运行句柄存在时调用Inbox；不可将未初始化伪装成“Task不存在”。TaskView.answer沿`ANSWER_DELIVERED.responseEventId → MODEL_RESPONSE`读取原文，不是按最后一条assistant推断。

提交、取消、Steer需要的任务执行器与控制入口必须独立；SSE订阅与双流输出也不能占满它们。WorkerApplication负责关闭自己创建的执行器，FileSessionLog只关闭自己的通道，不释放传入的进程锁。普通进程清理成功才能接下一Task；不能确认时Worker保持BLOCKED，而不是强行回IDLE。

## 2. 文件与快照边界

`BundleCodec`的嵌套记录：`Inputs(ExportManifest manifest, Path treeRoot, Path stateRoot, Path baselineRoot, SessionLog.Boundary boundary)`、`Restored(Path stagedRoot, ExportManifest manifest)`。方法：

```java
void write(Inputs inputs, Path temporaryZip) throws IOException;
Restored readBootstrap(Path zip, Path stagingRoot, String expectedSha256) throws IOException;
String treeDigest(List<ExportManifest.Entry> entries);
```

write只能读取已被Sealer固定的文件集和日志字节边界；read不直接覆盖正在使用的根目录。使用中央目录验证ZIP链接/类型/重复路径，不能仅依赖JDK ZipEntry缺少权限字段就宣称检查过符号链接。Commons Compress依赖应加入实际调用BundleCodec/BundleValidator的模块。

`ExportStore`构造器 `ExportStore(Path exportRoot)`；`Path begin(String exportId)`创建独占临时文件；`WorkerResources.ObjectFile publish(String exportId,Path temporaryZip,ExportManifest manifest)` force并原子发布；`require(String exportId)`返回已发布对象；`deleteAfterAck(String exportId,String confirmedArchiveId)`检查确认后清理。同一次operation/checkpoint重试复用固定exportId；一个Task内不同progress必须可以有多个exportId，不能重试生成另一份时间不同的包。

`LocalTaskFinalizer`实现TaskFinalizer；构造依赖WorkerSession、ProcessSupervisor。finish(acceptedCommand,outcome)先找到精确匹配的Execution，关闭输入并确认受管进程/双流已停止，再撤销该Writer；由WorkerSession持有的store以原身份写TASK_FINISHED后转IDLE。不能关闭共享history，也不得在此seal/publish/调用平台。

`SessionSealer`不实现TaskFinalizer。构造依赖WorkerSession、SessionRuntime、RuntimeConfig、ProcessSupervisor、BundleCodec、ExportStore、Clock。方法 `sealProgress(String operationId,String expectedHead):ObjectFile`、`checkpoint(String commandId,AgentCommand.Checkpoint):void`、`acknowledgeCheckpoint(AgentCommand.CheckpointAck):void`、`preview(...):void`。前者用于Tool局部冻结，后者仅后台IDLE恢复归档。sealProgress在串行工具边界执行，不等待自身Tool结束。

WorkerAssembly采用Boot/activate两阶段，不能先打开state日志再安装恢复目录。

## 3. 平台控制层方法

同包 `ControlTypes` 中的LaunchSpec、Binding、VerifiedBundle等以reference为准。数据库POJO不传进Core。`SandboxControl`接口如下，适配器把供应商异常转换为IOException并附可识别原因，业务层再按明确语义重试：

```java
Binding create(LaunchSpec spec) throws IOException;
Binding connect(String sessionId,String worklineId,long epoch,String sandboxId) throws IOException;
WorkerEndpoint endpoint(Binding binding) throws IOException;
void renew(Binding binding,Duration ttl) throws IOException;
boolean destroyAndConfirm(Binding binding,Duration waitBudget) throws IOException;
```

destroyAndConfirm只有管理状态明确Terminated/资源确认不存在才true；超时/403/5xx不能当不存在。不要通过SDK健康检查失败推断容器已死。SDK close只关本地client资源。

`AgentWorkerClient`构造依赖 `HttpClient, ObjectMapper, Duration commandTimeout`。方法：

```java
CommandReceipt send(WorkerEndpoint endpoint,String workerToken,AgentCommand command) throws IOException;
Optional<CommandReceipt> receipt(WorkerEndpoint endpoint,String token,String commandId) throws IOException;
TaskView task(WorkerEndpoint endpoint,String token,String taskId) throws IOException;
void streamEvents(WorkerEndpoint endpoint,String token,long epoch,long after,
                  Consumer<AgentEvent> consumer,BooleanSupplier cancelled) throws IOException;
void uploadBootstrap(WorkerEndpoint endpoint,String token,String bootstrapId,Path zip,String sha256) throws IOException;
Path downloadExport(WorkerEndpoint endpoint,String token,String exportId,Path temporary,long maxBytes) throws IOException;
```

endpoint带路由前缀和headers；令牌header用独立名称，不覆盖provider需要的Authorization。流读超时用心跳/中断管理，不直接套短commandTimeout。consumer在事务保存成功后推进cursor，异常关闭流，从旧cursor重连。404只映射到Optional.empty的接口是receipt，不把其他错误全部吞成empty。

`SessionLifecycleService`方法 `create(long repoId,long actorId,String sourceBranch,String key):SessionRow`，内部 `ensureExecutable(String sessionId)`、`reclaimIdle(String sessionId)`、`restore(String sessionId)`；不提供suspend/resume产品按钮；状态转换与慢操作按05 TX-S1/S2进行。`TaskApplicationService.submit(long repoId,String sessionId,long actorId,String message,String key,String expectedWorklineId,long expectedSessionVersion):Submission`；没有submitMode分类入口，授权来自初始化时受信配置。`steer/cancel/retryInterrupted/detail`均先校验path repo/session/task归属，再执行05对应事务，返回receipt或TaskView，不能只靠taskId查到就返回。

`CommandDispatcher.dispatch(String commandId)`只做一次可解释发送，`reconcilePending()`扫描一批记录逐个核对；不在定时线程运行AgentLoop。`EventCollector.attach(sessionId,epoch)`创建可取消的读取任务；`stop(sessionId,epoch)`仅停止订阅，不取消Agent。`EventProjector.archiveAndProject(event)`是事务入口；只有这一处将原始事件更新成平台任务状态。

`ArchiveService.collect(String sessionId,String exportId):ArchiveRow`，`require(String archiveId):VerifiedBundle`，`createBootstrap`已经拆到步骤10的RepositoryBootstrapService（不依赖ArchiveService）。后者复用CommitTreeService/对象存储读取，不从用户任意URL下载。`BundleValidator.validate(Path,ExpectedScope,Limits):VerifiedBundle`仅接受校验成功的包。

`PublicationService.publish(String operationId,String archiveId):PublicationRow`，`reconcile(String operationId)`。仅显式REPORT_PROGRESS进入；方法体遵循05 TX-P1/P2，不处理Task完成、skip或checkpoint ACK。

## 4. 仓库树的唯一方法合同

`CommitTreeService.Tree(Map<String,String> blobs)`保存path→GNOV blobId的不可变映射。`load(String repoKey,String sha):Tree`读固定Commit，`readBlob(repoKey,blobId):byte[]`只读并限制单文件字节。`saveTree(repoKey,Map<String,Path> files,parent,Instant at,message):String`在事务外产生完整mapping和候选Commit。`ancestors/commonAncestor`沿有界单父链，不按branchName当血缘。repoId由应用解析为授权repoKey，不能从公共请求直接接受repoKey。

`ThreeWayMergeService.merge(String repoKey,Tree base,Tree ours,Tree theirs):MergeCandidate`注入只读内容读取器/哈希编码依赖，不访问数据库、不推进分支。`MergeCandidate`是候选变更：changedFiles(有界文件内容/超总限转临时文件)、deletions、conflicts；将其应用于ours的mapping后由CommitTreeService写候选对象。三方比较先看blobId，只有双改文本按需取字节。它是无业务写副作用的算法组件，不谎称完全没有读取I/O。

同步时W来自可信RECOVERY；平台把其托管字节按同一GNOV codec/hash转换为不可变blob对象，再形成W的mapping；仅存blob不是创建Commit，也不推进任何远端Branch。B与H来自固定Commit，仍调用同一个merge(repoKey,B,H,W)。如此不用一次将三棵大树全部加载到内存，也不需要第二个同步合并算法。

`BranchPublicationService.createReference/publishExpected/deleteReference`承担权威仓库短事务。昂贵对象验证在事务外，最后重新检查expectedHead/权限；调用受管策略和业务Outbox，不读取活动工作树。`CommitCompareService.compare(repoId,sourceHead,targetHead)`解析权限范围后返回固定O/S/T、分页FileDiff；文件内容查询仍走相同只读blob读取器。

## 5. 方法体编写前统一的类型位置

所有SQL行来自reference的ControlRows/WorklineRows/PullRequestRows/SocialRows/DomainEventRows/NotificationRows。API DTO属于对应Controller/Types嵌套record，不复用SQL行。Server共享ExecutionScope仅传(Q,L,epoch)，不传实体到Core。旧构造式中的SessionLog已经是安装后的活动日志；Boot阶段必须使用RuntimeServices尚为空的容器，不创建伪空日志。

## 6. Workline、同步与准备实例

`WorklineMapper`字段方法见mapper-contracts。`SessionLifecycleService.create`先创建Q与L1 PREPARING，分配newEpoch并保存Binding；`ensureExecutable`只恢复当前线，不选main；`restore`从当前线可信RECOVERY输入生成新epoch。`SessionSyncService.submit(actor,repoId,sessionId,SyncRequest)`返回`SyncView(syncId,state,fixedTargetHead,newWorklineId,errorCode)`；`reconcile(syncId)`按固定意图推进；`get(actor,...,syncId)`只读；新线最终激活事务在TX-Y2。

`SyncRequest`字段与sync-request.schema.json严格相同。内部`SyncCandidate`包含syncId、旧archive/hash/tree、H、新W'摘要、bootstrapId/hash/位置；不把用户force布尔值传给安装器。`prepareOldArchive(syncId)`返回可信固定RECOVERY，`prepareBootstrap(syncId)`在临时状态完成策略，`install(syncId)`复用固定Binding/createOperationId，`activate(syncId)`只数据库事务，`failKnown(syncId,code)`只标已确定失败并安排可恢复清理。网络超时不调用failKnown；按sync-recovery-table核对。

`AgentManagedBranchPolicy`实现repository/ManagedBranchPolicy：
- `requirePublicationAllowed(scope,operationId)`：新受理查current活动绑定/Workline ACTIVE；已受理操作按原身份处理，取消等待不改其目标。
- `requireMergeAllowed(repoId,sourceBranchId,targetBranchId)`：从实际受管关系查Q/L；持repo门闩后锁Q/L/Binding，检查无活跃Task、无sync gate、无未决平台操作；普通来源直接返回。
- `markSourceMerged(sourceBranchId,prId)`：同merge事务把L标MERGED，不关闭Q，不读本地W。
- `requireBranchMutationAllowed(...)`：普通push不推进别人的受管源/目标，删除规则见13。

实现不注入WorkerClient/Sealer/Core。所有入口共用政策，禁止Controller用可空client传入Session判断是否需要检查。

## 7. PR（13施工卡对应的方法体入口）

`PullRequestTypes`作为service/pullrequest中的嵌套类型集合：`FileChange(path,kind,oldBlob,newBlob,binary,truncated)`、`CompareView(prId,prVersion,O,S,T,viewToken,files)`、`Conflict(path,reason,evidenceId)`、`MergeCandidate(changedFiles,deletions,conflicts)`；对象均不可变，不含活动工作树Path。

`CommitCompareService.compare(repoId,sourceHead,targetHead)`返回O/S/T及可分页Diff；`diffFiles(repoId,O,S,cursor,limit)`返回文件页；`ThreeWayMergeService.merge(repoKey,baseTree,oursTree,theirsTree)`按第4节读取不可变内容并计算候选，不存PR或创建Sandbox。

`PullRequestService.create(actor,repoId,CreateRequest)`固定创建回执/初始revision/Outbox；`detail/list/comment/changeDraft/close/reopen`按13规则；`prepareMerge(actor,prId,MergeRequest)`保存固定合并意图；`calculate(mergeId)`事务外计算；`commitMerge(mergeId)`只短事务并参与调用者REQUIRED事务；`getMerge(actor,prId,mergeId)`从固定记录核对结果。输入MergeRequest严格见merge-request.schema.json。方法名不代表允许绕过统一应用入口。

`PullRequestApplicationService.merge`做权限初检→固定意图→候选→在共同repo门闩内先调用policy资格检查，再调用通用commitMerge和markSourceMerged。最终branch/PR/workline/结果/Outbox同事务；没有REQUIRES_NEW，没有checkpoint。准备阶段不持长维护租约。

`PullRequestRevisionProjector.consume(event)`使用receipt→PR锁，插revision及派生Outbox；不获取repo计数锁。普通创建首revision在PR创建事务中，历史投影不能生成第二份相同repoSequence版本。

## 8. 关系、订阅、通知与事件

`FollowService.setFollowing(actor,target,desired)`、`StarService.setStarred(actor,repoId,desired)`返回当前desired与权威计数；SQL影响行数决定统计；关系权限见14。`WatchService.update(actor,repoId,options,expectedVersion)`返回WatchView；`UserProfileService.profile/listVisibleRepositories`先ACL再分页，不输出SQL用户行的敏感字段。

`DomainOutboxService.appendInTransaction(EventDraft)`在已有业务事务中写固定事件；无事务则拒绝，不偷偷另开REQUIRES_NEW。`DomainEventPublisher.publishPending`认领/发送/确认；`reconcileMissingReceipts`只对保留期内expected consumers核对同事件；`DomainEventConsumer.consume`校验后receipt+业务同事务，失败走domain_event_failure。

`NotificationPolicy.recipients(event)`候选/静音/去重/当前ACL；`NotificationService.list/countUnread`当前ACL过滤；`setRead(actor,id,read,expectedVersion)`按版本和所属用户条件更新；`markPage(actor,List<ReadChange>)`每项回报，不推导全局高水位。`ActivityFeedService.list(actor,cursor)`查询Follow×PUBLIC-at-creation×current-PUBLIC，不广播私有活动。

这里的服务方法说明给输入、事实和提交边界；私有helper用普通方法即可，不要求每个名词再建接口/实现对子。具体文件唯一清单在04。

## 9. Harness内部数据结构、构造依赖与调用前置

本节补齐02中出现但没有展开的名字，**它们是待实现的施工契约，不代表生产目录已经存在**。reference已有的公共record/接口保持其字段和签名；下列具体类的构造器由此落实。先完成一条能运行的最小依赖链，不要求先创建所有类型。

### 9.1 本地历史：谁拥有序号，谁只做索引

`FileSessionLog(Path stateRoot, String streamId, FileLock directoryLock)`接收已安装目录和外部锁。history()返回只读SessionLog；bind(executionId,origin)返回绑定Writer；appendHost仅供宿主，readStored供Worker重建回执/SSE。内部StoredEntry(schemaVersion,Entry,origin)与digest是唯一记录，不另写平台日志。序号/时间在存储锁内分配；force后发布Boundary。详细方法、字段、投影和关闭语义见05第2.3。

`HistoryIndex(SessionLog log, ResultStore results)`维护可重建索引。`rebuild(log)`读取当前完整边界及恢复目录中已经校验的历史覆盖；`find(resultId)`返回`Optional<ResultRecord>`；`search(query)`返回一页。最低字段放在HistoryIndex的嵌套record中：

```java
record ResultRecord(SessionLog.Entry source, Map<String, ResultStore.Ref> objects) {}
record SearchQuery(String query, boolean caseSensitive, String toolName,
                   String executionId, String streamId, String cursor, int limit) {}
record SearchHit(String resultId, String view, long lineNumber, String text,
                 String executionId, SessionLog.Position position) {}
record SearchPage(List<SearchHit> hits, String nextCursor,
                  boolean hasMore, boolean scanIncomplete) {}
```

来源事件必须为本Session已提交TOOL_RESULT；resultId直接取source.eventId，不新增随机ID。objects使用日志原有引用；小结果正文在source.payload里，不强制再复制成文件。可选过滤字段为null表示不按该项过滤，**不表示允许其他Session**。query非空、limit正值且受配置上限约束；列表/映射作不可变快照。

cursor绑定固定查询、Session和所查历史的through边界，内部同时记录下一结果、view和行/长行偏移；首次查询由索引固定边界，后续页不能加入后来产生的结果。达到扫描预算但未找到匹配也可以返回空hits和nextCursor，`scanIncomplete=true`；只有实际扫描完成才能表示没有后续结果。未实现分页时明确返回限制，不伪造“全部搜完”。resultId属于协议资源身份，cursor是查询进度，二者不能互换。

### 9.2 Context：组在哪里，谁真正生成请求

生产链固定为：`Engine → ContextCoordinator.prepare → SessionHistoryReader.load → project → Request → Gateway`。Engine不再维护另一份权威messages并绕过Coordinator。HistoryReader从日志重建，最近窗口、摘要和索引均为投影。

- `SessionHistoryReader(HistoryIndex, ToolObservationProjector)`：`load(session,executionId)`固定日志边界，恢复Summary及完整Group；将原始TOOL_RESULT交Projector转成tool Message后放进Group。`project(snapshot)`仅把摘要与选定组按协议展开为消息，不调用模型、不产生新事件。
- `ToolObservationProjector(HistoryIndex, TokenEstimator, RuntimeConfig)`：接收已提交source、对应ToolCall和原始Result；校验callId、scope及来源，生成小结果原文或大结果预览。状态、source/resultId、完整性和回读提示都计入预算；不改写durable Result，不再次执行原工具。
- `ContextSummarizer(ModelGateway)`：`summarize(HistorySnapshot source, SessionLog.Writer events, RuntimeConfig config, ExecutionControl control, long maxSummaryTokens): Summary`。Writer已绑定本次执行，Config提供模型/thinking，Control提供取消；token上限由Coordinator决定，模型不能填来源身份。摘要器保存purpose=SUMMARY的请求/响应，只返回候选Summary；**CONTEXT_SUMMARY_CREATED由Coordinator校验后唯一追加**。
- `DefaultContextCoordinator(SessionHistoryReader, ContextSummarizer, TokenEstimator, RuntimeConfig)`：prepare取得session、control、本次Writer、可见工具定义，重建完整候选输入，先预算再调用摘要器。摘要来源边界固定，覆盖闭合组；保留最近组与当前execution的USER原文/已应用补充。强压缩复用同一摘要器、传更严格maxSummaryTokens，不能用删除未配对消息救场。

初次调用和摘要后的调用仍包含受信system instructions；迁移旧PromptAssembler中纯文本组装逻辑，去掉Server Context依赖，先放Coordinator内普通私有方法即可，不新建策略继承树。固定system与tools也必须进入计量，用户原文属于动态上下文，不免计token。

`ContextTypes.Summary.coveredThrough`取被替代历史的末条源事件，而不是CONTEXT_SUMMARY_CREATED自身。候选摘要校验失败不追加生效事件；成功append后才用于下一请求。已低于硬上限时可按限定策略沿用原文；仍超限则停止并保留原因，不能无限重试摘要。`maxSummaryTokens`包含摘要输出约束，思考输出也受供应商总输出限制。

`observeResponse`只接主调用已持久化的Response。Coordinator若保留usage校准缓存，prepare发现executionId/模型/配置或上下文形态改变就使旧锚点失效；历史Summary可跨Task，单次请求usage不能冒充新Task窗口实测值。恢复时重新从日志计算，缓存不是恢复真相。

### 9.3 工具与进程：返回值怎么变成下一轮输入

`LinuxProcessSupervisor.Limits`定义为实现类内的record：`Duration maxCommandTimeout, Duration terminateGrace, int maxManagedProcesses, int maxPollBytes, int maxArgBytes`。均为正，maxManagedProcesses包含RUN与START，输出总捕获上限仍复用RuntimeConfig.maxCaptureBytes。WorkerConfig.fromEnvironment一次解析后由Assembly传入，不由模型改限额。开发缺省值可用600秒、2秒、8、65536、131072，对应`GITNOVA_PROCESS_TIMEOUT_SECONDS / GITNOVA_PROCESS_TERM_GRACE_SECONDS / GITNOVA_MAX_MANAGED_PROCESSES / GITNOVA_PROCESS_POLL_BYTES / GITNOVA_PROCESS_ARG_BYTES`；这是明确的起始配置，不是实验测得最优参数。实际执行时间受请求timeout、部署上限与剩余Task deadline共同限制。Mac可以构造/校验这些值，进程取消保证仍须Linux实测。

`ToolTypes.Context`由Engine用Writer.executionId、SessionRuntime、Control及当前callId构造；工具不接受模型指定的Session/目录根。ToolRuntime的可见定义与分发来自同一个获授Map。工具参数JSON解析失败、缺字段和参数冲突生成结构化Result；持久化或未知执行状态不能压成普通参数错误。

`LocalShellTool`把RUN/START参数转为`ProcessSupervisor.Spec(processId,argv,cwd,environment,timeout,captureLimit)`：processId在受控进程管理端创建，cwd经SafePaths解析，environment来自受信配置，argv包装模型生成的脚本。Supervisor不写TOOL_STARTED/TOOL_RESULT；Engine统一写这两种事件。stdout/stderr各用一个Capture，先启动两路消费再等待退出，达到捕获上限仍排空管道，finally关闭/回收。

`ProcessSupervisor.Exit`包含可空exitCode、timedOut/cancelled/stoppedConfirmed、stdout/stderr的Ref及耗时。LocalShellTool将其原样放入Result.payload；Engine把Ref列入TOOL_RESULT.objects后append，Projector才生成Observation。退出非0是已执行事实，不自动等于工具基础设施失败；未确认停止不能伪造exitCode，Finalizer须阻止继续执行。

START返回受控Background句柄，不代表命令已完成；POLL/STOP只能使用本实例已登记句柄。POLL读取已捕获范围，返回真实运行状态和下一游标；未完成输出不能发布成以后会被悄悄改写的immutable Ref。若要保存一次POLL的字节，保存该时刻有界副本，最终输出另有固定对象。

文件读取/搜索先通过shell调用cat/sed/rg/find；不为这些命令各造一个Java算法。read_file可选，用于稳定行号、分页或单独授予读权限；edit_file保留现有精确匹配价值。专用`read_file/edit_file`只操作受控workRoot，`history_read/history_search`只按原事件查state对象；不要把两套路径解析混用。编辑算法可复用旧纯匹配/替换逻辑，但旧Workspace generation/Mapper不是新本地工具前置。shell脚本仍能访问OS权限允许的文件/网络；SafePaths校验cwd不能约束脚本内部行为，不能把它说成只读或完整沙箱。命令引起的文件变化也是真实工作树的一部分；发布与恢复封存时再统一固定文件集。

`ToolSchemaValidator.FieldError`是该类内部record，最小字段为`path, code, expected, actual`；path如`$.startLine`，actual仅放类型/有界安全描述，不回显secret。`SafePaths.Conflict`若作为异常使用，就是SafePaths内的异常类而不是record，携带稳定code与安全message，由Tool入口转Result；不要因为总表写了“嵌套类型”就猜它必须是record。Args只在各Tool内部存在，不作为Worker协议。

### 9.4 从第一行代码到第一次观察：依赖如何逐步闭合

| 你现在想看到的行为 | 必须先存在的类型/实现 | 可以暂用的测试替身 | 不能伪装已实现的部分 |
|---|---|---|---|
| 一条Submit成功解析、错配被拒绝 | CommandType、AgentCommand/Payload、解析器；Receipt随后加入 | 测试中的固定UUID与JSON | 权限、运行态与真实受理 |
| append后重开还能读到两条事件 | SessionLog.Entry/Writer、FileSessionLog | 临时目录和独立文件锁 | 宿主丢失恢复 |
| read→工具结果→自然STOP（当前首先实现） | ModelTypes.Message、SessionRuntime/Outcome、Control、ToolRuntime、Engine、history/Writer | 测试Log/ResultStore、Gateway、固定ContextCoordinator、测试工具 | 不要求HTTP接受；不证明耐久存储、真模型或平台 |
| 同Session第二Task看见第一Task证据 | HistoryIndex/Reader、Projector、Coordinator | 测试摘要Gateway | 长期Memory/RAG |
| HTTP回执立即返回，cancel能打断慢任务 | WorkerSession、Inbox、执行器、HTTP与生命周期句柄 | 测试Resources/Finalizer | 正式INITIALIZE、快照恢复 |
| 正式启动并接一次真实模型调用 | 安装器、真实Resources/Finalizer、全部获授Tools及模型适配 | 不允许生产Fake | 尚未接入的发布/PR需不授权 |

每行先把直接互相引用的一小组类型写完再编译，而不是每个record都必须独立编译。调用者需要的下一阶段能力先隔离在已有接口；只在test提供替身，完成该行后立即观察行为，再往后接。失败返回、资源关闭和一个最小反例与主流程一起写，不留到“后续优化”。
