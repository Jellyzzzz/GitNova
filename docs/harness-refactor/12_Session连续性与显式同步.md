# 12｜Session连续性、merge与显式同步

## 1. 当前唯一有效的产品决定

用户最新决定：Session在merge后保留，可以继续发消息；远端merge不代表沙箱已切换到目标分支；下一段代码工作要显式选择同步基线；分支删除或沙箱回收后可以重建底层实例，逻辑Session继续。

01、02、05、08、09现已同步：**merge不以W=S为前提；merge不新建聊天Session。** 未发布修改留在旧W中，由后续同步显式处理。Task自然结束仍不发布；report_progress仍是唯一Agent代码提交通道。

本次保留一个较保守的业务门槛：合并Agent受管来源时不允许活跃Task或结果未确定的平台发布操作。这是GitNova自己的取舍，不是Git的普遍要求。检查只用平台权威记录和仓库写门闩；**不为merge调用checkpoint、下载W或唤醒休眠Sandbox**。

## 2. 用一组具体状态理解

```text
Session Q一直存在
Workline L1：从A开始，受管分支agent/Q/L1已发布到S
Sandbox本地：W = S + 草稿D
目标分支：T
PR合并：M = merge(O,T,S)
```

merge成功后：目标HEAD=M，PR=MERGED，L1=MERGED，Session Q仍OPEN，活动绑定仍指向旧树W。D没有被提交，也没有被删除。页面保留原对话，PR显示已合并；代码基线信息诚实显示仍在L1/S，不插入“环境休眠/已关闭”的假聊天。

用户只是追问解释时，不需要同步。普通Task仍可受理并带上L1的代码来源。模型可能产生本地草稿，**有shell就不能靠隐藏edit工具声称严格只读**；平台仍必须拒绝向MERGED工作线report_progress。提示语帮助模型理解，真正的发布限制在Server校验。

用户选择“基于目标分支继续”才发同步请求。这不是NLP分类器把问答改为CODING；是一个明确的仓库状态变更动作。目标分支只是前进、而当前PR仍OPEN时，不自动把工作线判死：老基线本来可以继续开发，由PR三方合并处理目标差异。

## 3. 字段语义

| 字段 | 属于谁 | 精确语义 |
|---|---|---|
| sessionId | Session | 对话/历史/访问控制身份，不随merge变化 |
| activeWorklineId | Session | 新Task默认使用的代码线；同步最终激活时才变 |
| worklineId | Workline | 一次固定起点和受管源分支；旧行不改名复用 |
| baseCommit | Workline | 该线创建时的固定代码起点 |
| publishedHead | Workline | 该线最后一次已确认的远端提交，不等于当前脏树 |
| sourceBranchId | Workline | 受管分支的稳定身份；删除后仍保留旧ID作为历史引用，显示名或同名重建不改变归属 |
| targetBranchId | Workline | 默认准备提PR的目标；不是允许任意覆盖的写权限 |
| status | Workline | PREPARING/ACTIVE/MERGED/RETIRED/SOURCE_MISSING；不替代Session.status |
| runnerEpoch | Binding | 同一Session每次替换运行实例单调增加，不拿workline顺序冒充epoch |
| syncId | SyncOperation | 一次同步的幂等身份，网络重试不换 |
| targetHead | SyncOperation | 本次明确选择且已冻结的H，不随重试追踪main |
| oldArchiveId | SyncOperation | 旧工作树/基准/状态的可信恢复点 |
| oldTreeDigest | SyncOperation | 用户确认舍弃或迁移的是哪份W |
| newWorklineId/newEpoch | SyncOperation | 准备中的新线/新实例身份，失败不能冒充已激活 |

## 4. 三种同步选择，不能含糊成force=true

**REQUIRE_CLEAN（默认）**：读取可信旧快照的托管代码树W，与其已确认baseline B逐字节/文件集合比较。W≠B返回UNPUBLISHED_CHANGES，原代码保留，不切指针。仅产生备份不算用户同意丢弃。

**CARRY_CHANGES**：先保存W及B，计算 `merge(base=B, ours=H, theirs=W)`。成功得到W'；新工作线远端分支指向H，本地baseline=H、worktree=W'，移植部分仍是未发布草稿，必须report_progress才有新Commit。冲突只返回路径/冲突块引用，不将冲突标记写进旧活动树，也不完成同步。

**DISCARD_CHANGES**：用户明确确认“放弃当前未发布修改并使用H”，请求绑定oldTreeDigest与H。先确保旧RECOVERY平台已保存，再安装H；Digest或目标版本改变就拒绝，不能让过期确认丢掉后来新增改动。归档按正常保留策略保存，可供人工恢复；并非默认撤销按钮。

B是旧线最后确认发布的S，不是最初A。否则会把已经merge进目标的PR修改再次移植。若旧线的发布结果UNKNOWN，先按原operationId核对；不能靠新的H覆盖不明结果。

当前旧工作线仍有OPEN PR时，本次“切换后续工作线”返回OPEN_PR_NEEDS_DECISION：先明确关闭或完成旧PR。它不是实现完整git pull/rebase UI。读历史与继续在原分支工作不受此限制。

## 5. UI到服务的契约

页面长期展示轻量代码来源信息：分支、确认HEAD、已合并/源分支不存在等真实仓库事实。基础设施sleep/resume不显示。显式同步按钮是用户要求换代码基线，不是“启动沙箱”按钮。

`POST /api/repos/{repoId}/agent/sessions/{sessionId}/sync`

请求字段：requestKey、expectedWorklineId、expectedSessionVersion、targetBranchId、targetHead（来自用户已看到的预览）、changePolicy、expectedTreeDigest（DISCARD必填）。服务端重新鉴权、解析内部身份。响应202含syncId；同键同内容返回同操作，不同内容409。

`GET .../sync/{syncId}` 返回 PREPARING、SNAPSHOTTING、INSTALLING、SUCCEEDED、CONFLICT、FAILED，以及fixedTargetHead、newWorklineId或安全错误细节。状态是产品操作进度，不把Worker IP/token/目录暴露给前端。

同步前的预览复用现有PREVIEW_CHANGES：公开 `POST .../change-preview` 只要求获得一次确定的代码变更预览。活动任务/写进程未停止时返回BUSY，不对变化中的目录做“精确脏状态”保证；已有空闲Worker在本地短冻结点生成预览，没有实例则读取最后可信RECOVERY，不仅为预览启动新环境。返回worklineId、publishedHead、treeDigest、observedAt、dirty与可用性。无法得到可信快照时treeDigest为空且禁用DISCARD确认；不能用空摘要代表干净。目标分支列表返回用户可见的固定H；POST sync仍重新验证。预览不是同步成功，也不授权以后随意舍弃新改动。

新增Task与SYNC争用同Session门闩。同步激活期间新执行请求返回SESSION_TRANSITION_IN_PROGRESS，前端保留输入和原幂等键；不把消息悄悄送到旧线或新线。历史GET永远不触发同步。只有用户重复提交/继续动作才能恢复待发送消息，不由浏览器反复新建身份。

## 6. 施工卡 S1：先写状态转换，不碰容器

**文件**：`service/agent/control/SessionSyncService.java`，`mapper/agent/control/WorklineMapper.java`，`entity/agent/control/WorklineRows.java`（嵌套WorklineRow/SyncRow）；新增不是把旧Workspace搬回来。

**输入与输出**：输入固定Q/L1/H/policy/requestKey；输出可查的syncId与当前阶段。成功不是“已发HTTP”，而是Q当前指针已原子切到已初始化的L2。

**思考路径**：先画Q指针，说明在每一个失败点它仍指向谁。准备中L2存在并不等于用户已经在L2上工作。旧结果必须仍能按L1查询。

**实现顺序**：校验结构→权威仓库权限→按键查原操作→事务内锁仓库/Session→核对版本、无活跃Task与未决平台副作用→固定H和L2身份→保存sync意图/Session转换gate→提交。所有重试复用这条意图。目标当前已不是用户预览H时409；开始后若目标又前进，仍完成固定H并在结果注明，不追赶“最新”。

**第一项测试**：不建Sandbox，用FakeInstaller模拟成功/失败。失败Q仍指向L1，成功只切一次；两浏览器发不同同步请求只能一个取得gate。

**检索目标**：`optimistic locking expected version`、`SELECT FOR UPDATE`、`saga durable intent`；先看现有CommandDispatcher的同身份核对，不引入新工作流框架。

## 7. 施工卡 S2：拿到旧代码的确定证据

若旧绑定READY且空闲：复用CHECKPOINT_SESSION(MAINTENANCE)，保存RECOVERY包、完整引用和事件覆盖，ACK后旧Worker PARKED。若已回收：使用最后一个可信RECOVERY，核对其worklineId、publishedHead、treeDigest与操作记录；不能仅从远端Commit恢复，因为它不含草稿。

snapshot失败→保留旧实例，Sync FAILED或可重试阶段；不要先销毁。旧实例不可达且缺最新快照→明确RECOVERY_UNAVAILABLE，不能声称旧草稿已保住。旧发布结果未知→同步等待核对而不是直接进入安装。

**第一项测试**：W含一个未发布新文件；发布分支只含S；快照恢复后文件仍存在。模拟对象缺失、日志缺覆盖、ACK丢失，验证不换新syncId，不提前切线。

**检索**：旧08 `checkpoint/ack/reconcilePending`、`BundleValidator`、`RECOVERY_BASE_DIVERGED`。包复制及目录安全沿现有Agent契约，不重写一套ZIP实现。

## 8. 施工卡 S3：决定新树，全部在临时状态中完成

对REQUIRE_CLEAN先比较W/B；CARRY调用13的同一ThreeWayMergeService，参数命名明确；DISCARD验证确认Digest。输出准备包包含新树W'、新baseline H、新workline身份、允许带入的历史信息。

新baseline永远是H，不能因为W'含移植草稿就把它作为“已经发布”。新分支先只引用H，不造空Commit；若没有任何代码差异，report_progress应返回NO_CHANGES。

上下文处理：原Session历史和resultId不删除。新bootstrap只保存固定切线来源，未激活不记录成功切线。平台激活后第一条授权SUBMIT在运行模型前耐久记录一次WORKLINE_SWITCHED，再构造新活动Context，列出旧L1、新L2、H、草稿是否移植；保留用户明确长期约束与未完成目标，旧工具测试结果标为旧代码证据。第一轮模型必须知道当前树已变，不能继续沿用“刚才测试已经通过”。原始历史可以检索，但不全量原样作为当前进度重放。

**测试**：B/H/W不同文件修改可移植；同一区域冲突不动旧树；移植后publishedHead=H；原resultId可读但带L1来源。

## 9. 施工卡 S4：复用启动包安装，不新增文件RPC

本次选择简单、可靠的同步实现：**新epoch、新状态根/卷，复用INITIALIZE**。不是Server向正在运行的目录不断写文件，也不是每个Task重新建容器。一个明确同步操作才做一次替换。

旧RECOVERY可靠保存并ACK后，先STOP旧Worker并确认旧实例不再写入；保留归档，然后新建准备绑定→上传固定bootstrap→INITIALIZE→核对health的Q/L2/newEpoch/H/configDigest。新实例ready只说明可用，不自动成为Q的活动实例。

最终短事务重新锁仓库/Session，核对gate owner=syncId、版本和当前权限，激活L2与新Binding，更新Q.activeWorklineId，标sync SUCCEEDED。旧L1若MERGED保留MERGED，否则标RETIRED；不改它原有base/head/PR。清理旧资源是后置动作，清理失败不撤销已经成功的同步。

**故障恢复**：新实例创建响应不明先查createOperationId标签；不重复创建。ready后Server崩溃按syncId检查准备实例并完成激活。旧线PARKED但同步失败，需从旧RECOVERY恢复L1再接任务，不能只把数据库改IDLE就向PARKED进程派发。始终避免同一个可写卷两实例。

**测试**：上传失败、安装一半失败、ready响应丢、最终事务失败、成功后清理失败、旧epoch晚到发布。最后一个必须被平台拒绝，不能污染L2。

## 10. 源分支删除与沙箱回收

分支删除是仓库事件：普通OPEN PR来源默认禁止删除，要求先关闭；MERGED/CLOSED保留固定提交证据后可删。Agent当前线异常丢失来源时标SOURCE_MISSING，保留W；不自动用原分支名重建来“修复”。用户显式同步生成新分支。

回收只改变Binding，不改Workline是否已合并。普通透明恢复仍恢复旧L1/W；**回收恢复≠同步到main**。否则用户走开一小时回来，代码被换了，这是数据语义错误。

## 11. 完成这一功能的判据

同一sessionId完成Task→发布→PR→merge→留住草稿→CARRY显式同步→新workline→新Task→发布；每一步刷新页面仍可查询。再模拟旧分支删除和空闲回收，证明对话身份不变、代码来源诚实、未发布修改不丢、旧token不能写新线。不得用两条新聊天Session冒充同一Session连续。

## 12. 安装、激活与回退：执行时按表逐格核对

完整故障表在[同步恢复状态表](contracts/sync-recovery-table.md)。同步状态只存阶段，不使用HTTP超时推导FAILED。`syncId`和`newEpoch`在第一次准备事务分配并保留；即使安装失败，下次另一个操作也不能重复使用该epoch。

新受管分支在**最终激活事务**创建并指向固定H，之后写newWorkline.sourceBranchId/ACTIVE、Session.activeWorklineId/currentRunnerEpoch、Sync SUCCEEDED与必要业务事件。准备包中的分支名由服务端预先确定；新Worker的ready不要求该分支已经在库中存在，否则会与激活互相等待。若最终发现名称已被别人占用，返回冲突并保留旧归档，不偷改新分支名继续同一个请求。

没有active Task的准备实例只能接收上传/INITIALIZE/health/导出与控制请求；模型代理、平台发布、普通Task必须核对**平台当前已激活Binding和Workline**，不能只验证JWT签名就放行。平台不会向准备实例发送SUBMIT。WORKER_READY事件可作为准备证据归档，但不切换Session指针。

回退到L1不是把oldEpoch重新激活：旧实例已停止且需要恢复时，从oldArchive创建另一个新的epoch，继续L1/W；L1未被新线替代前一直是Session.activeWorklineId。失败清理只取消自己syncId持有的gate，不能清后来操作的gate。旧归档和未发布草稿保留，冲突信息可查询；不做无授权的回滚Commit。

源分支缺失与工作线已合并是两份事实：ACTIVE线发现源缺失可标SOURCE_MISSING；MERGED线仍保留MERGED，并在查询中另返回sourceExists=false。不能用SOURCE_MISSING覆盖已合并事实、误恢复发布权。

在休眠状态执行DISCARD或CARRY，旧归档必须有可证明的最新覆盖；无法证明时返回RECOVERY_UNAVAILABLE。原始结果的跨线目录/索引/Context选择结构按05第16节实现，不把旧进程句柄或旧控制token装到新实例。

跨线Context文件及首次记录的精确规则见[Context交接合同](contracts/context-handover.md)。

**合并事实怎样进模型：** Server在构造每次私有SUBMIT时，从已锁定Workline冻结`worklineStatus`（ACTIVE/MERGED/SOURCE_MISSING），持久在命令信封中；Worker将必要说明耐久写成当前execution的HARNESS_FEEDBACK(kind=RUNTIME_CONTEXT)，Context在模型请求前单独注入。Core不接TaskInput或工作线业务字段。它不是TaskMode，不决定自然语言意图，也不授予工具权限；公开Task不能传入它。这样merge不重载沙箱也能让下一Task知道旧线已合并。若执行时平台状态后来变化，外部发布仍以Server当前授权为准，不凭此提示字段放行。
