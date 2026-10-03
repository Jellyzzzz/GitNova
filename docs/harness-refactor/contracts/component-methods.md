# 关键类的方法清单与装配图

本文件补充02中缩写的方法签名。仅列职责方法，不要求把每个private helper也拆类。所有路径见04。未单列的请求/结果类型作为所在类的 `public static record`，不是隐藏的第三方类型；跨模块已有类型从reference导入。

## 1. Worker控制层

`WorkerConfig`字段固定：`String sessionId, long runnerEpoch, int port, Path sessionRoot, URI modelEndpoint, String modelToken, String workerToken, RuntimeConfig runtime, Limits limits`。嵌套Limits字段：`int maxBodyBytes, int maxMessageBytes, int maxSubscribers, int maxPendingSteers, long maxBundleBytes, long maxExpandedBytes, int maxFiles, long maxFileBytes, int maxPathChars`。工厂 `static WorkerConfig fromEnvironment(Map<String,String>)`；错误配置启动失败，不静默采用开发密钥。资源参数在同一入口读取一次，不能各Tool读不同环境。

`WorkerSession(SessionLog log, String sessionId, long epoch)`保留当前TaskInput、WorkerHealth.State、publishedHead、pendingExportId、initialized标记；同一短锁维护。方法：

```java
WorkerHealth health();
TaskInput activeTask(); // 无任务返回null，内部使用，不是公共反序列化
void initialized(String publishedHead, String configDigest);
void begin(TaskInput input); // IDLE→RUNNING
void beginFinalizing(TaskInput input, AgentOutcome result);
void waitForSettlement(String exportId);
void settled(AgentCommand.Ack ack);
void block(String code);
void stopAccepting();
```

`TaskInbox`构造器固定接受 `WorkerSession, SessionRuntime, SessionLog, AgentEngine, ExecutorService taskExecutor, TaskFinalizer, WorkerResources, Clock`。持有 `Map<String,CommandReceipt>`和commandDigest索引、当前DefaultExecutionControl。方法 `accept(AgentCommand)`、`receipt(String)`、`task(String)`；receipt返回Optional，task不存在由HTTP映射404。

同一commandId已受理必先返回原receipt；不同digest拒绝。初次受理后即使调度失败也记录明确终止，不删除已受理事实。命令日志回放重建receipt，不扫描最后一条assistant推任务状态。接收handler只持短锁，不在锁中等模型、seal、下载或网络ACK。

`AgentWorkerServer`只注册路由。依赖TaskInbox、WorkerSession、WorkerResources、SessionLog；它不new Engine。初始化PUT从request body流读取，经WorkerResources写incoming；不把zip body读成一个无限byte[]。`QueryHandlers`使用同一WorkerResources，不提前依赖具体ExportStore。

**装配闭合方式：** WorkerAssembly先构造SessionRuntime、Engine、WorkerSession，再构造真实 `BundleCodec/ExportStore/SessionSealer/LocalWorkerResources`，最后构造TaskInbox与HTTP；Sealer只引用WorkerSession和日志等，不引用TaskInbox，因此没有构造循环。步骤 6的单元测试注入FakeFinalizer/FakeResources，步骤 8完成后才建立上述生产装配。

## 2. 文件与快照边界

`BundleCodec`的嵌套记录：`Inputs(ExportManifest manifest, Path treeRoot, Path stateRoot, Path baselineRoot, SessionLog.Boundary boundary)`、`Restored(Path stagedRoot, ExportManifest manifest)`。方法：

```java
void write(Inputs inputs, Path temporaryZip) throws IOException;
Restored readBootstrap(Path zip, Path stagingRoot, String expectedSha256) throws IOException;
String treeDigest(List<ExportManifest.Entry> entries);
```

write只能读取已被Sealer固定的文件集和日志字节边界；read不直接覆盖正在使用的根目录。使用中央目录验证ZIP链接/类型/重复路径，不能仅依赖JDK ZipEntry缺少权限字段就宣称检查过符号链接。Commons Compress依赖应加入实际调用BundleCodec/BundleValidator的模块。

`ExportStore`构造器 `ExportStore(Path exportRoot)`；`Path begin(String exportId)`创建独占临时文件；`WorkerResources.ObjectFile publish(String exportId,Path temporaryZip,ExportManifest manifest)` force并原子发布；`require(String exportId)`返回已发布对象；`deleteAfterAck(String exportId,String confirmedArchiveId)`检查确认后清理。多次seal同Task必须复用由任务固定的exportId，不能重试生成另一份时间不同的包。

`SessionSealer`实现TaskFinalizer。构造依赖 `WorkerSession, SessionRuntime, RuntimeConfig, ProcessSupervisor, BundleCodec, ExportStore, Clock`。finish先记录TASK_FINISHED（一次），进入FINALIZING；禁止新的工具写入，确认进程组退出，固定log截止N，导出树/历史/baseline，记录SNAPSHOT_SEALED后等待ACK。sealIdle同样生成独立exportId并等待存档确认；不自动重跑模型。preview在工具安全边界生成有上限的差异JSON，不改变baseline。

## 3. 平台控制层方法

同包 `ControlTypes` 中的LaunchSpec、Binding、VerifiedBundle等以reference为准。数据库POJO不传进Core。`SandboxControl`接口如下，适配器把供应商异常转换为IOException并附可识别原因，业务层再按明确语义重试：

```java
Binding create(LaunchSpec spec) throws IOException;
Binding connect(String sessionId,long epoch,String sandboxId) throws IOException;
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

`SessionLifecycleService`方法 `create(long repoId,long actorId,String sourceBranch,String key):SessionRow`，`suspend/resume/close(long repoId,String sessionId,long actorId):SessionRow`；状态转换与慢操作按05 TX-S1/S2进行。`TaskApplicationService.submit(long repoId,String sessionId,long actorId,String message,String key):Submission`；内部受信入口另有 `submitMode(...,TaskMode mode)`，普通Controller不暴露mode。`steer/cancel/retryInterrupted/detail`均先校验path repo/session/task归属，再执行05对应事务，返回receipt或TaskView，不能只靠taskId查到就返回。

`CommandDispatcher.dispatch(String commandId)`只做一次可解释发送，`reconcilePending()`扫描一批记录逐个核对；不在定时线程运行AgentLoop。`EventCollector.attach(sessionId,epoch)`创建可取消的读取任务；`stop(sessionId,epoch)`仅停止订阅，不取消Agent。`EventProjector.archiveAndProject(event)`是事务入口；只有这一处将原始事件更新成平台任务状态。

`ArchiveService.collect(String sessionId,String exportId):ArchiveRow`，`require(String archiveId):VerifiedBundle`，`createBootstrap`已经拆到步骤10的RepositoryBootstrapService（不依赖ArchiveService）。后者复用CommitTreeService/对象存储读取，不从用户任意URL下载。`BundleValidator.validate(Path,ExpectedScope,Limits):VerifiedBundle`仅接受校验成功的包。

`PublicationService.publish(String archiveId):PublicationRow`，`retry(String publicationId)`，`skip(String publicationId,long actorId)`，`settleAndAck(String publicationId)`。跳过必须先确认归档存在且仍有权限访问Task，不删除未发布工作树。方法体遵循05 TX-P1/P2/P3，不把对象IO塞长事务。

## 4. 仓库与PR方法

`CommitTreeService`嵌套 `Tree(Map<String,String> blobs)`；值为GitNova Blob ID，不是平台路径。`load(String repoKey,String sha):Tree`调用原GitObjectReader；`readBlob(String repoKey,String blobId):byte[]`有上限；`saveTree(String repoKey,Map<String,Path> files,String parent,Instant at,String message):String`使用既有codec/hash/storage，返回新Commit身份。`ancestors`在单父链上做有界遍历与visited检测；`commonAncestor`先记录一边祖先再沿另一边查首个共同节点；缺对象/循环/上限明确报错，不返回猜测祖先。

`BranchPublicationService.createReference(long repoId,String repoKey,String branch,String commit,long actorId)`验证对象并插分支；`publishExpected(long repoId,String repoKey,String branch,String expectedHead,String candidate,long actorId)`验证完整性/CAS/CommitRecord；授权仍由服务边界重新检查，受管branch不能绕普通Push直接写。Service调用的事务顺序见05，不在此重建第二套Session状态。

`CommitCompareService.compare(long repoId,String sourceHead,String targetHead):CompareResult`，结果记录固定双HEAD、mergeBase及 `List<FileDiff>`；FileDiff包含path/changeType/binary/added/deleted/unifiedDiff/truncated。Diff只用于展示，不作为提交字节。两个SHA必须来自本仓库且用户有权访问，不能猜任意storageKey。

`ThreeWayMergeService.merge(String repoKey,Tree base,Tree target,Tree source):MergeCandidate`。MergeCandidate包含 `Map<String,byte[]> changedFiles`、`Set<String> deletions`、`List<Conflict>`，Conflict含path/reason/baseBlob/targetBlob/sourceBlob。无冲突时通过CommitTreeService保存树；有冲突不发布。三方规则与JGit内容合并详见01第9章；二进制和删除冲突不能喂给文本合并后强行接受。

`PullRequestService`公开create/list/detail/addComment/setDraft/close/reopen/merge，对应05第9节固定HTTP字段。create与merge幂等比较完整请求摘要；list/comments游标使用ID升序，固定limit上限；详情必须返回本次用于计算Diff的sourceHead/targetHead，前端merge请求带这两个HEAD和expectedRevision。

## 5. 代码主体由你手写，但没有隐藏的服务框架要求

上面方法所需的具体算法在02/05中给出；创建记录、循环、集合和异常映射仍由你写。构造器可以接入现有仓库基础服务，但不能将它们变成Core依赖。除模型、文件日志、进程、网络和跨模块契约外，不要求再给每个纯函数加interface+impl。此表规定边界与调用次序，不要求把每个DTO都拆成一个额外文件。

## 6. 初始化依赖专门闭合

RepositoryBootstrapService构造器依赖CommitTreeService、ObjectMapper和平台bootstrap暂存根；`create(long repoId,String baseSha,String sessionId,long epoch,String configDigest):Path`固定输入，生成manifest中taskId/attemptId为null、epoch对应绑定、base/head为A、空事件历史的包；tree与baseline均为A。Server使用自己的轻量ZIP写入，不跨依赖agent-worker模块。生产包引入Tree/Manifest字节规则，但不引入Worker状态机。

步骤8完成Worker初始化/封存；步骤9接Sandbox和无DB的WorkerClient；步骤10准备平台Session与bootstrap；步骤11接任务、事件和平台归档；步骤14接自动发布。这是施工顺序修正，不改变完整交付范围。
