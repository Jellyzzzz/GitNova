# OneDev与Gitea：具体代码阅读导航

以下为此前Agent文档生成阶段通过GitHub连接读取两仓库的**选定实现窗口**。这不是全仓库审计，也没有在本机构建/运行它们。这里给出确实读到的方法、相关调用链与适配建议，不将未读到的下游保证当事实。

固定源码基线：OneDev `0eb6c7fe6f683737c319ae17545d496ea5e36e64`；Gitea `1065f03454dac629a84eadd8b46876f66f0092b3`。链接固定到commit，不使用会漂移的main。此前对OneDev类名的引用以本轮实际路径为准：是service/PullRequestService和service/impl/DefaultPullRequestService。仓库源码下载到容器的尝试失败（容器DNS限制），不影响GitHub连接已经读取的内容；本包只附导航与读取清单，不附大段第三方源码。

## 1. 先读OneDev的PR创建，不要从整个框架读起

**入口：** [PullRequestResource：API入口](https://github.com/theonedev/onedev/blob/0eb6c7fe6f683737c319ae17545d496ea5e36e64/server-core/src/main/java/io/onedev/server/rest/resource/PullRequestResource.java#L240-L510) → [PullRequestService：业务契约](https://github.com/theonedev/onedev/blob/0eb6c7fe6f683737c319ae17545d496ea5e36e64/server-core/src/main/java/io/onedev/server/service/PullRequestService.java#L1-L107) → [DefaultPullRequestService：open/merge实现](https://github.com/theonedev/onedev/blob/0eb6c7fe6f683737c319ae17545d496ea5e36e64/server-core/src/main/java/io/onedev/server/service/impl/DefaultPullRequestService.java#L350-L560)。

**源码可直接观察到：** createPullRequest取得当前用户，构造source/target，验证代码读取权限，再构造PullRequest并调用open。open检查来源/目标不是同一个，查询已有开放/有效PR，计算或验证base，保存首轮head/targetHead更新与编号，持久化PR及相关记录，再安排检查并发布PullRequestOpened。

**你要带着的问题：** 哪些参数来自用户，哪些来自认证？为什么source/target不能互换？PR编号作用域和全局id为什么不同？保存一个PR为什么需要记录确定HEAD，而不只记录分支名？

**GitNova适配建议（我们的决定）：** Controller只做参数/身份转换；同仓库PR服务验证分支、权限、开放关系唯一性并保存。不要将OneDev的fork体系、默认指派、构建、审批整套复制进来。OneDev这里拒绝target已包含source；GitNova已选“允许无差异Draft、合并拒绝NO_CHANGES”，这是不同产品规则，保留明确差异，不能无意识照搬。

**第一项实现：** 固定两个普通分支，创建一次Draft，重复相同幂等键返回同PR；两个并发请求不能留下两条开放同源目标关系。注意：读到findOpen再persist并不足以证明独立拷贝方法就具有数据库并发唯一性，仍需在GitNova设置唯一约束/锁并测竞争。

检索目标：`createPullRequest`、`findOpen`、`findEffective`、`getMergeBase`、`PullRequestOpened`、`numberScope`。实体索引窗口：[PullRequest实体声明](https://github.com/theonedev/onedev/blob/0eb6c7fe6f683737c319ae17545d496ea5e36e64/server-core/src/main/java/io/onedev/server/model/PullRequest.java#L80-L235)。

## 2. PR合并：对照OneDev与Gitea的不同事务边界

### OneDev：固定合并预览和旧目标HEAD

读[DefaultPullRequestService.merge](https://github.com/theonedev/onedev/blob/0eb6c7fe6f683737c319ae17545d496ea5e36e64/server-core/src/main/java/io/onedev/server/service/impl/DefaultPullRequestService.java#L350-L560)。可直接看到先checkMergeCondition/checkMergeCommitMessage，再取checkMergePreview，按策略生成/调整mergeCommit，最终updateRef携带预览中的targetHead作为expected旧值；还处理源分支删除权限和是否存在关联workspace。

**应借鉴：** 合并前提、预览绑定的提交身份、expected旧HEAD以及删除源分支的独立条件。**不能直接推导：** 方法有@Transactional就代表数据库与Git磁盘引用是一项全局原子事务；本轮没有审计GitService和事务事件设施的完整实现。更不能把它的workspaceService依赖移进GitNova通用PR服务。

### Gitea：先记录合并意图，再识别副作用是否已经发生

读[services/pull/merge.go](https://github.com/go-gitea/gitea/blob/1065f03454dac629a84eadd8b46876f66f0092b3/services/pull/merge.go#L250-L520)。这段特别适合你的publication/UNKNOWN训练：Merge取得PR工作锁后重新加载对象，检查允许的策略；hasPullRequestCommitBeenMerged用已保存mergedCommit在目标分支中的存在情况核对。真正执行时，doMergeAndPush通过onMergedIntoTempBase回调先recordMergeIntent，再进行实际push；成功后MarkAsMerged，后处理再发通知。它还显式不让用户请求取消直接中止Git工作。

**应借鉴：** 把“计算候选”“持久意图”“外部副作用”“最终业务标记”“通知”区分开；重试先判断原副作用，而不是再造一次新意图。**不要照搬：** 临时标准Git仓库、Git CLI push/post-receive hook、LFS和Gitea自己的全局锁库。GitNova用GNOV对象存储+数据库分支CAS，不能假装doMergeAndPush可直接搬过去。

**第一项实现：** 固定R/S/T计算候选，模拟source或target在提交前变化，必须拒绝；再模拟“目标CAS与结果事务已提交，HTTP响应丢失”，按同mergeId查回原M。受管来源的无活跃Task/未决操作/sync gate是GitNova自己的规则；dirty只在显式同步时处理，见12，不声称来自这两仓库。

检索目标：`recordMergeIntent`、`hasPullRequestCommitBeenMerged`、`expectedHeadCommitID`、`onMergedIntoTempBase`、`MarkAsMerged`、`updateRef expectedOldObjectId`。

## 3. Diff和评论：先做分支比较，不先造完整审查框架

[OneDev getComparisonBase接口注释](https://github.com/theonedev/onedev/blob/0eb6c7fe6f683737c319ae17545d496ea5e36e64/server-core/src/main/java/io/onedev/server/service/PullRequestService.java#L1-L107)解释了为什么目标新增提交随后合入源分支时，PR比较基准不能简单固定成原oldCommit；目的是排除不属于本PR提出的目标改动。这里前轮已读取的是接口契约和注释，**没有完整审计getComparisonBase实现**。

GitNova固定共同祖先O→source S的PR展示，另保留target T用于合并计算；工作树预览则是confirmed baseline→W。完整交付同时包含普通讨论与固定版本行评论；实现行级评论时必须绑定path、比较版本/commit、行/side，源分支更新后不能把旧定位伪装为新代码已审查。后半句是我们的设计建议，不是此次已经读取的完整行评论代码结论。

**第一项实现：** target独有文件不应在PR里显示为source提出的删除；同一个PR刷新源HEAD后，旧Diff缓存键不能命中新HEAD。检索目标：`merge base three dot diff`、`getComparisonBase`、`sourceHead targetHead cache key`。

## 4. Follow：关系才是事实，计数只是同步维护的统计

入口[Gitea follow.go](https://github.com/go-gitea/gitea/blob/1065f03454dac629a84eadd8b46876f66f0092b3/models/user/follow.go#L1-L80)，对照[follow_test.go](https://github.com/go-gitea/gitea/blob/1065f03454dac629a84eadd8b46876f66f0092b3/models/user/follow_test.go#L1-L23)。

**源码事实：** Follow用(UserID,FollowID)联合唯一关系。FollowUser检查自己关注自己/已有关系和拉黑，再在db.WithTx中插关系、增加目标followers与当前用户following。UnfollowUser查询关系，再事务删除并减少两方计数。TestIsFollowing仅覆盖查询样例。

**应该学：** 关系行和计数应在一个确定事务内保持一致，Follow方向不能颠倒，拉黑不是一个装饰字段。**不宜照抄：** 该读取窗口中UnfollowUser未以删除影响行数决定是否扣减；并发保障还取决于调用与数据库环境。本轮未审计所有调用者/锁，也未复现bug，不能称已证明原项目计数有问题或绝对安全。

**GitNova建议：** 插入/删除关系的真实结果决定计数增减；同一关系唯一。获取所需用户行锁时保持稳定顺序，不只预先SELECT判断。需要测试“双请求同时取消关注”只减一次。数据库失败不可被bool false当作“未关注”继续修改。

**第一项实现：** 两次follow同人只一行；再做并发unfollow计数不负。检索目标：`FollowUser`、`UnfollowUser`、`rows affected conditional counter update`、`UNIQUE composite key MySQL`。

## 5. Star：不要与Watch/关注用户混为一个功能

入口[Gitea star.go](https://github.com/go-gitea/gitea/blob/1065f03454dac629a84eadd8b46876f66f0092b3/models/repo/star.go#L1-L116)。

**源码事实：** Star用(UID,RepoID)联合唯一；StarRepo在事务内查询是否已star，插入或删除关系，并维护仓库/用户num_stars。文件还包含分页GetStargazers，以及注明用于仓库转私有时清理Star的ClearRepoStars。

**应借鉴：** 收藏关系、列表、计数与权限变化需要一起考虑，不只是“POST插表”。**产品差异：** 是否私有化就清掉全部收藏是Gitea此函数表达的处理用途，不自动成为GitNova规则。本轮未追踪全部ClearRepoStars调用者和事务，不能把单个函数当完整私有化流程。

GitNova更适合先明确：仓库私有后是否保留原关系但只向仍有权限者显示，怎样使主页、收藏列表、动态和通知都遵守同一RepositoryAccessService。新增/取消Star的计数同样依赖实际关系变更，不照搬并发风险。

**第一项实现：** 收藏后仓库转私有，无权限用户的列表/详情不能泄漏内容；是否保留关系按已选产品规则验收。检索目标：`StarRepo`、`ClearRepoStars`、`GetStargazers`、`repository visibility cache invalidation`。

## 6. Watch与通知：最值得借鉴的完整纵向调用链

[watch.go：订阅数据](https://github.com/go-gitea/gitea/blob/1065f03454dac629a84eadd8b46876f66f0092b3/models/repo/watch.go#L1-L185) → [pull.go：创建PR](https://github.com/go-gitea/gitea/blob/1065f03454dac629a84eadd8b46876f66f0092b3/services/pull/pull.go#L1-L225) → [notify.go：通知分发](https://github.com/go-gitea/gitea/blob/1065f03454dac629a84eadd8b46876f66f0092b3/services/notify/notify.go#L1-L195) → [uinotification：队列/收件人](https://github.com/go-gitea/gitea/blob/1065f03454dac629a84eadd8b46876f66f0092b3/services/uinotification/notify.go#L1-L200) → [notification_list.go：权限过滤与持久化](https://github.com/go-gitea/gitea/blob/1065f03454dac629a84eadd8b46876f66f0092b3/models/activities/notification_list.go#L1-L240)。

**源码事实：** WatchMode区分默认参与/正常关注/忽略/自动关注，并有PR、Issue、Release选项；不是单一收藏布尔值。NewPullRequest的业务创建和通知分开，notify层遍历notifiers。UI通知实现收集关注者/参与者/@提及并去重后排入自己的队列；handler调用CreateOrUpdateIssueNotifications，随后对已通知用户更新计数。通知写入端再次处理显式取消订阅、仓库静音、机器人和仓库单元访问权限，不能只因用户曾订阅就永远能收到。

**本轮不能证明的事：** 未审查Gitea所有queue后端、持久化配置和崩溃恢复。代码中存在Push错误忽略和handler错误记录后continue的路径；不能据此宣称这条分发端到端exactly-once或事务性不丢。OneDev open中的listenerRegistry.post也不能自动等同于Outbox。

**GitNova建议：** 需要可靠站内通知时，PR业务事实与待投递业务事件在同一DB事务里保存；消费者按eventId+recipient+channel去重，再写站内通知。已有RabbitMQ/Outbox若适配此语义可复用；Redis只存可重建统计。投递时和读取时按当前权限处理，避免缓存越权。AgentEvent不能直接作为全站动态源；页面创建与Agent创建PR都经同一个PR服务产生相同业务事件。

**第一项实现：** 一次PR创建后仅给有权限且满足订阅规则的用户一条通知；重复事件不重复落通知；通知消费者暂停不影响已提交PR，恢复后能处理已保存待办。检索目标：`NewPullRequest`、`RegisterNotifier`、`issueNotificationOpts`、`CreateOrUpdateIssueNotifications`、`GetRepoIgnorersIDs`、`CheckRepoUnitUser`。

## 7. 推荐你自己的阅读顺序

先OneDev REST create → Service.open → merge；再Gitea Merge的意图核对；之后读Follow/Star/Watch三个小文件；最后沿上述通知链追一遍。每次只追一个用户动作，写出输入/状态/事务/失败/调用者，不要求读完整个平台。

你可以在**固定checkout**中按方法定位，而不是只搜索“如何实现GitHub”：

```bash
# OneDev checkout到上面的固定commit之后
rg -n 'createPullRequest|void open|void merge|findOpen|checkMergePreview' server-core/src/main/java/io/onedev/server
# Gitea checkout到上面的固定commit之后
rg -n 'recordMergeIntent|hasPullRequestCommitBeenMerged|onMergedIntoTempBase' services/pull
rg -n 'FollowUser|UnfollowUser|StarRepo|watchRepoByMode' models
rg -n 'NewPullRequest|CreateOrUpdateIssueNotifications|GetRepoIgnorersIDs' services models
```

这些是用户本机阅读命令；此前未替你clone仓库或运行其测试。Go语法不熟时优先辨认函数输入、事务闭包、SQL条件和错误返回，不必先系统学完Go再读这三个关系文件。

## 8. 已读源码窗口索引

下表是本轮实际读取范围，不是声称实现类已经全部审查。行号链接只定位读取窗口，具体函数用文件内搜索。原文件比窗口长时，窗口外调用者/下游需要另查。

| ID | 固定源码 | 本轮用途 |
|---|---|---|
| O1 | [server-core/src/main/java/io/onedev/server/service/PullRequestService.java](https://github.com/theonedev/onedev/blob/0eb6c7fe6f683737c319ae17545d496ea5e36e64/server-core/src/main/java/io/onedev/server/service/PullRequestService.java#L1-L107) | findOpen/open/merge/getComparisonBase；公开业务契约及比较基准注释 |
| O2 | [server-core/src/main/java/io/onedev/server/rest/resource/PullRequestResource.java](https://github.com/theonedev/onedev/blob/0eb6c7fe6f683737c319ae17545d496ea5e36e64/server-core/src/main/java/io/onedev/server/rest/resource/PullRequestResource.java#L240-L510) | createPullRequest/setTitle/setWorkInProgress/addReviewer；API身份/权限到服务入口 |
| O3 | [server-core/src/main/java/io/onedev/server/service/impl/DefaultPullRequestService.java](https://github.com/theonedev/onedev/blob/0eb6c7fe6f683737c319ae17545d496ea5e36e64/server-core/src/main/java/io/onedev/server/service/impl/DefaultPullRequestService.java#L350-L560) | merge/open/closeAsMerged；合并预览、expected旧HEAD、PR变更事件 |
| O4 | [server-core/src/main/java/io/onedev/server/model/PullRequest.java](https://github.com/theonedev/onedev/blob/0eb6c7fe6f683737c319ae17545d496ea5e36e64/server-core/src/main/java/io/onedev/server/model/PullRequest.java#L80-L235) | 实体索引、编号唯一范围、Source/Target及变更元数据；不是全部实体方法审查 |
| G1 | [models/user/follow.go](https://github.com/go-gitea/gitea/blob/1065f03454dac629a84eadd8b46876f66f0092b3/models/user/follow.go#L1-L80) | Follow/IsFollowing/FollowUser/UnfollowUser；关系与计数事务 |
| G2 | [models/repo/star.go](https://github.com/go-gitea/gitea/blob/1065f03454dac629a84eadd8b46876f66f0092b3/models/repo/star.go#L1-L116) | Star/StarRepo/IsStaring/GetStargazers/ClearRepoStars |
| G3 | [models/repo/watch.go](https://github.com/go-gitea/gitea/blob/1065f03454dac629a84eadd8b46876f66f0092b3/models/repo/watch.go#L1-L185) | WatchMode/Watch选项/watchRepoByMode；只读到WatchRepoWithOptions开头 |
| G4 | [services/pull/pull.go](https://github.com/go-gitea/gitea/blob/1065f03454dac629a84eadd8b46876f66f0092b3/services/pull/pull.go#L1-L225) | NewPullRequest及前后权限、数据库、Git和通知步骤 |
| G5 | [services/pull/merge.go](https://github.com/go-gitea/gitea/blob/1065f03454dac629a84eadd8b46876f66f0092b3/services/pull/merge.go#L250-L520) | recordMergeIntent/hasPullRequestCommitBeenMerged/Merge/doMergeAndPush前半 |
| G6 | [services/notify/notify.go](https://github.com/go-gitea/gitea/blob/1065f03454dac629a84eadd8b46876f66f0092b3/services/notify/notify.go#L1-L195) | RegisterNotifier/NewPullRequest/MergePullRequest；遍历已注册通知器 |
| G7 | [services/uinotification/notify.go](https://github.com/go-gitea/gitea/blob/1065f03454dac629a84eadd8b46876f66f0092b3/services/uinotification/notify.go#L1-L200) | NewNotifier/handler/NewPullRequest；队列、收件人集合、通知计数更新 |
| G8 | [models/activities/notification_list.go](https://github.com/go-gitea/gitea/blob/1065f03454dac629a84eadd8b46876f66f0092b3/models/activities/notification_list.go#L1-L240) | CreateOrUpdateIssueNotifications；订阅合并、静音优先、权限检查与写入 |
| G9 | [models/user/follow_test.go](https://github.com/go-gitea/gitea/blob/1065f03454dac629a84eadd8b46876f66f0092b3/models/user/follow_test.go#L1-L23) | TestIsFollowing；查询样例测试，不是并发新增/取消证明 |

## 9. 与当前施工范围的关系

这一份是代码参考和后端方向说明，不把Follow/Star/Watch/通知偷偷加入Agent步骤0—18的交付承诺。通用PR已经在既定范围中；其余用户功能这些规则现在已在11–16中固定并并入统一验收。任何引用都不能用“开源项目这么写”代替GitNova自己的并发/故障测试。
