# 14｜主页与三类用户关系

## 1. 先画使用场景，避免一个relation万能表

Follow是用户→用户：关注作者；Star是用户→仓库：收藏；Watch是用户对仓库活动的订阅偏好。用户给仓库Star不自动Watch，Follow作者也不等于订阅他全部私有活动。

本次OneDev/Gitea是参考，不是功能来源的强制全集。Gitea的拉黑、组织成员、Issue/Release订阅等已读到，但GitNova没有这些已确认业务时不凭空把它们都加进来。**要实现的是三个动作各自闭环，以及权限变化后的正确列表/通知。**

## 2. 施工卡 U1：先完成个人主页

**文件**：`service/social/UserProfileService.java`、`controller/UserProfileController.java`、`mapper/social/SocialMapper.java`。已有用户/仓库服务继续复用，不另建账号系统。

**输入**：被查看用户id、当前viewer身份、仓库列表cursor/filter。**输出**：公开profile字段、可見仓库列表、当前viewer与其关系、允许公开的统计。

**先思考**：主页看的是“viewer有权看到的作者信息”，不是User实体的JSON。邮箱、密码hash、token和内部状态绝不自动输出。本人能看自己的私有仓库，并不意味着别人的主页也可以。

**大致逻辑**：解析用户→查允许公开字段→仓库查询在SQL中加入(public OR owner/member可读)条件→稳定排序/分页→批量查viewer对这些仓库的star/watch状态→映射DTO。列表不逐行查Redis再决定权限。每页limit最多50，排序采用(id DESC)或(createdAt,id)确定复合顺序，cursor绑定筛选条件与viewer。

**统计**：总粉丝/关注数可来自关系统计；“我收藏的仓库数”对第三人必须按可见仓库重新计算，不能直接展示包含私有内容的总计。主页仓库数也不能显示“总共8个，其中只给你看2个”来暴露不该公开的私有活动。

**第一项测试**：同一个主页，owner、collaborator、陌生登录用户得到不同仓库集合，但公开字段相同且无敏感字段。私有化后刷新所有入口都不可泄漏。

**查什么**：现有 `RepositoryAccessService`、`RepoMemberMapper`；`SQL EXISTS permission filter keyset pagination`；Gitea列表只是关系参考，不照搬完整用户模型。

## 3. 施工卡 U2：Follow/Unfollow

**表**：user_follow(follower_id,followee_id,created_at)，唯一/主键(follower_id,followee_id)，反向索引(followee_id,follower_id)。同用户不允许关注自己；目标不存在/不可用明确拒绝。动作人来自登录身份。

**API**：PUT /api/users/{id}/follow，DELETE同路径；GET followers/following。PUT/DELETE表示最终关系状态，不做“toggle”：重复请求应得到同样状态。

**思考路径**：先回答什么算一次真正关系变化。两次网络重试不能让followingCount增加两次；查询失败不是“未关注”。真正的计数增减必须依赖本事务中关系行是否变动。

**数据库方案**：关系是事实；统计行user_social_stats只用于精确同步维护、可重算。在事务内按userId升序锁两个用户统计行（预建或对已验证用户幂等建行），查当前关系；不存在才插入并增加两方计数，已存在直接返回。取消使用DELETE实际影响行数=1才减；0就是已处于目标状态。用户删除/禁用与关系写入的处理必须沿现有用户服务明确门槛，不能向不存在账号建关系。

整个关系变更、统计与USER_FOLLOWED业务事件同事务。取消不发给目标用户“你被取消关注”通知；可以留内部审计但不进入公开动态。本次关注事件不进入公开Feed，避免没有确认的用户关系隐私策略。

并发死锁：A关注B、B关注A都按较小userId先锁；不根据“我是操作者”决定锁顺序。MySQL deadlock只能重试整个短事务且受限，不绕开关系检查只重试count++。

**第一项测试**：连续两次PUT仅一行，取消两次只减一次；两个并发取消同样成立；事务在关系插入后失败时统计和事件都回滚。

**查源码**：Gitea `FollowUser/UnfollowUser`；借鉴关系/事务，不照抄未检查影响行数的路径。**查API**：MyBatis update返回int、MySQL FOR UPDATE、唯一索引。

## 4. 施工卡 U3：Star/Unstar

**表**：repository_star(user_id,repo_id,created_at)，联合唯一；反向(repo_id,user_id)。API PUT/DELETE `/api/repos/{repoId}/star`；GET `/api/users/{id}/stars`；GET repo stargazers只向有仓库访问权限的人提供。

**权限**：有读权限的登录用户可Star；不是只有仓库写者才允许收藏。取消自己的既有Star允许在已失去仓库读取权限后执行，但只按自己的关系删，不回传仓库标题/存在细节；统一返回目标状态，避免成为探测私有仓库的接口。

**处理**：先验证actor和仓库当前可读；短事务锁repo（与私有化/撤权同门闩）→锁repo_social_stats→查/改关系→只有实际变化时改starCount→写必要审计/业务事件→提交。统计从关联关系可重算。没有必要给用户单独维护一个会泄漏私有收藏总量的全局numStars。

**私有化规则，本次明确选定**：保留历史Star关系，不自动清空；没有权限的viewer在收藏列表、个人主页、动态、stargazers中都看不到该仓库。以后重新获得权限关系可再次显示。通知和缓存不提供绕过。**这与Gitea某些清理函数的用途不同，是GitNova自己的产品选择。**

**列表**：SQL JOIN repository和当前ACL过滤后分页，不能先查20条Star再Java删15条且仍返回原count。DTO中starred=true只对当前viewer；别返回他人私有收藏关系。

**测试**：同请求并发只增加一次；私有化与Star并发形成明确先后；权限撤销后取消不泄漏；统计修复由COUNT事实重建，缓存清空后行为正确。

**查**：Gitea `StarRepo/GetStargazers/ClearRepoStars`；MySQL受约束关系DELETE affected rows；本地旧 `evictMemberAfterCommit`。

## 5. 施工卡 U4：Watch偏好

**模型**：repository_watch(user_id,repo_id,mode,include_pr,include_comments,include_branch_updates,version,created_at,updated_at)。mode=PARTICIPATING/ALL/CUSTOM/IGNORE。无行等于PARTICIPATING：只按实际参与/提及规则接收，而非自动接收全仓库活动。ALL/CUSTOM/IGNORE存显式行。

不要把IGNORE当不存在：它要压过参与者和提及等其他通知来源。CUSTOM全false等于没有主动订阅，但仍受参与规则；UI给出清晰说明，不偷偷转换成IGNORE。

**API**：GET/PUT `/api/repos/{repoId}/watch`。PUT携带完整mode和flags、expectedVersion；第一次版本0。更新不存在用唯一键插入，有行用version条件更新，冲突409让前端刷新，不最后写者无条件覆盖另一个设备的选择。本次Watch PUT不携带requestKey：在同repo短事务内先比较当前完整期望状态；已经相同则返回当前状态且不增版本，不同才校验expectedVersion并更新。网络重发若状态已被另一设备改动则409，不自动重试覆盖；这里不承诺保存每次偏好编辑的历史回执。

**权限**：读取及设置主动订阅要求当前仓库可读；失去权限后可删除/忽略自己的旧订阅而不获取仓库信息。投递通知时再查当前权限和订阅；曾经Watch不代表永远获准。

**粒度选择**：本次只订阅PR生命周期、PR评论、分支更新；不在Schema硬塞尚未实现的Issue/Release。未来增加事件类型走配置/字段明确迁移，不把枚举值含义偷偷改掉。

**思考例子**：A既是PR作者，又Watch仓库，又被提及，应该只得到一条通知；A选择IGNORE后不再收到。说明Watch输出只是候选接收人，真正去重和权限归并发生在15的NotificationPolicy。

**第一项测试**：ALL→IGNORE后新事件无通知；两标签页同时改偏好，一个收到409；Star仓库不会产生Watch行。

**查源码**：Gitea `WatchRepoWithOptions/GetRepoWatchersIDs/GetRepoIgnorersIDs`。它的GetRepoWatchersIDs注释明确权限需在其他位置验证，不可将返回的IDs直接无条件投递。

## 6. 施工卡 U5：缓存与统计修复

先用MySQL证明三组事务正确。缓存热点公开profile和统计时用短TTL，写成功后afterCommit删除；删除失败记录并靠TTL/修复回收，不能回滚已成功的业务事实。Redis宕机走数据库查询或明确限流降级，不授权放开。

私有列表、权限和通知内容不缓存成不区分viewer的公共JSON。RepositoryAccessService现有私有读取角色缓存可能过时，新增权威敏感读取应查MySQL当前成员关系；若继续使用权限缓存，需要ACL版本失效方案与测试，不声称仅删除缓存就彻底消除旧读回填竞态。

统计校验：在维护命令/测试中COUNT user_follow和repository_star，与stats比较；修复时与同一统计行锁串行更新。不能在写入不停的情况下用旧快照COUNT无条件覆盖新计数。此工具仅内部使用，不开放给普通用户传任意表名。

## 7. 页面组件和验收

UserProfile、RepositoryList、FollowButton、StarButton、WatchMenu、FollowingList/StarredRepositoryList复用现有登录/API client。按钮有请求中状态但不靠disabled代替服务端幂等；请求失败回滚本地乐观显示。分页加载去重用稳定id。

至少演示：A关注B→B创建公开PR→A在关注动态看到；A仅Star不Watch时不会因此收到PR通知；AWatch后可收到；A失去私有仓库权限后列表/通知均受限。这些行为是后端闭环，不是按钮能变色就算完成。

## 10. 并发与撤权的实现补充

Follow没有repo锁，按userId升序锁定两名用户的真实行，确认仍存在/允许交互，再按同一顺序处理user_social_stats；用户删除/停用若支持，必须遵循同一顺序。用户表的实际列名从H0取得，不凭文档另造disabled字段。不存在统计行时创建零值行后重读锁定。DELETE返回影响行数为1才扣统计；数据库读取失败不是关系不存在。

Star/Watch先锁repository再查当前角色。取消Star可以只删除自己的已存在关系而不返回私有标题，避免用户撤权后无法取消；增加Star和任何敏感读取仍需当前读取权。计数采用实际关系变更，而不是执行过某条SQL就加减。PUT/DELETE表达本次希望的状态，不声称能自动判断跨客户端过时意图；UI对同一关系串行发送，在前一请求未知时先核对，不自动反复切换。

Watch第一次没有行的version=0仅表示初始状态；创建后改成PARTICIPATING也保留行并增加version，不删除再用0复活，防止旧设置覆盖新选择。已读状态与Watch等更新都返回新的version，具体CAS及重复规则见16。

读取权限：敏感列表使用SQL当前ACL过滤后再分页，禁止通过旧正向角色缓存授予访问。写入最终授权在同repo门闩内使用当前锁定读；不能先在REPEATABLE READ事务建立旧快照，最后用普通SELECT假装检查到了最新撤权。Redis故障不得降级为允许访问。已授权返回过的内容不能在事后收回，不作这种承诺。
