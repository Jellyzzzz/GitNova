# GitNova 设计基准

> 整理日期：2026-11-10  
> 性质：需求与逻辑数据模型基准，不是最终数据库 DDL，也不是必须一次性实现的功能清单。  
> 状态说明：**已确定**＝已经达成产品/业务共识；**建议**＝当前合理的建模起点，编码时可调整；**待定**＝需要实际选型或实现验证；**延期**＝当前开发主线不实现。

## 1. 目标与架构边界

GitNova 是面向个人与小团队的轻量级 Git 代码托管与协作平台。主要能力包括账号、仓库、原生 Git Clone/Fetch/Push、同仓库 PR、社区关系、通知，以及独立 Coding Agent **Owl** 的云端接入。

### 1.1 不变的责任划分（已确定）

| 部分 | 权威数据或主要职责 | 明确不做的事 |
|---|---|---|
| GitNova Backend（拟用 Go） | 用户与权限、仓库业务元数据、PR/Review/Comment、社区关系与通知；对外 API、Git 服务授权、Sandbox 准备 | 不维护第二份 Git Commit/Tree/Blob 真相；不管理 AgentLoop 内部状态 |
| 原生 Git | Git 对象、Commit 图、Tree/Blob、Tag、分支 Ref、Git 合并结果 | 不承载 PR 审查、通知或用户账号业务 |
| Owl Harness（现有 TypeScript 内核） | `AgentSession`、对话、模型、工具、Bash、历史恢复 | 不依赖 GitNova MySQL 管理 Agent 会话与每轮执行 |
| Sandbox + Worker | 隔离工作目录、启动 Harness、通过 RPC/事件流通信；执行环境恢复和回收 | 不决定 PR 业务状态；不要求每轮 Prompt 创建业务 Task 记录 |
| 独立会话/快照存储 | Owl 的会话消息、事件与工作区快照（包括未提交和未跟踪文件） | 不因此自动变成 MySQL 关系表 |

**核心原则**：只有独立的业务事实才值得持久化；可由原生 Git 可靠获得的代码状态不重复写入业务数据库。业务对象、数据库表、运行时对象三者不必一一对应。

### 1.2 有意不做或延期

- 不自行实现 Gitlet，不复制完整 Gitea，不做 Fork 跨仓库 PR。
- 不提供 Git LFS、在线 IDE、内置 CI、复杂推荐算法；不以这些能力作为当前开发前提。
- 仓库所有权转移、软删除恢复、账号找回、复杂分支保护扩展均延期。
- **仓库 Rename 暂为可延期/可删除功能**：若保留，需维护旧 URL 的兼容解析，但不保证永久有效；不应为了低频 Rename 阻碍核心开发。
- SSH Git 认证在产品范围内，但实现顺序晚于 HTTPS + PAT。
- Owl 不建立 `OwlTask`、`OwlSession`、`Sandbox` 的 GitNova MySQL 表；需要什么运行索引由真实使用需求再决定。

## 2. 产品规则与跨模块约束

### 2.1 用户与认证（已确定）

1. 开放注册，邮箱验证后才能正常使用；`username` 在当前范围内不可修改；邮箱默认不公开。`username` 保留输入大小写，唯一性与 URL 查找区分大小写及重音；当前只允许 3–64 位 ASCII 字母、数字、`_`、`-`，首位须为字母或数字。注册时 `display_name` 默认使用该用户名，后续展示名不参与账号或仓库 URL 的身份解析。
2. 用户可以用用户名或已验证邮箱登录；支持多设备 Web 登录，可撤销指定会话。
3. 登出要使服务端 Web Session 失效；修改密码要求旧密码并撤销全部 Web Session，但**不自动撤销 PAT、SSH Key**。
4. 管理员可以封禁、解封账号，并留存关键管理操作记录；账号被禁用后，Web/PAT/SSH 均不得继续获得授权。
5. 管理员没有默认读取所有私有仓库代码的权限。
6. Web 认证**倾向 Opaque Session Token**；Session Store 介质尚未确定，不默认绑定 MySQL。密码只存安全密码哈希；PAT 只显示一次并保存单向校验值；SSH 只存公钥。
7. Git HTTPS 认证使用 PAT；SSH 使用登记的公钥。Git Commit 的 author 字段不等于实际认证的 Push 用户。
8. 公有仓库允许匿名 Clone/Fetch；私有仓库必须认证且有权限；Push 必须认证并授权。

### 2.2 仓库与协作者（已确定）

1. 一个 Repository 恰有一个 Owner；受邀并接受邀请的其他用户才成为 Collaborator。
2. 当前仓库角色只有 Owner、Collaborator；Collaborator 拥有普通读写与 PR 权限，Owner 另有仓库配置、成员管理、删除等权限。
3. **Owner 不重复出现在 `repository_members` 中**。邀请和已生效的协作关系分开表达。
4. 用户被封禁并不删除仓库；其他有效协作者保留已有权限；禁止账号自动恢复已撤销的会话。
5. 私有仓库的存在、名称、动态和代码不应向无权用户泄露。公开/私有可切换，查看时按**当前**可见性判断。
6. 仓库内部关联稳定的 `repository_id`，不依赖可变 URL 或显示名称。
7. 删除仓库时，删除或失效相关成员、邀请、PR 及订阅，停止关联 Owl 执行并清理 Session、快照和凭据；不保留可继续执行的孤立任务。

### 2.3 Git（已确定）

1. 创建 Bare Repository；默认分支名 `main`；空仓库可能尚无实际 `refs/heads/main`，允许首个 Push 创建。
2. 支持标准 Clone、Fetch、Push、分支与 Tag 的必要操作、文件树、文件内容、Commit 历史及 Diff。Git 对象与 Ref 由原生 Git 管理。
3. 禁止强制改写和删除默认分支；默认分支强制 PR 是 Owner 可配置的保护选项，默认关闭。
4. 可配置“合并前必须得到审批”。**启用审批要求必须同时启用通过 PR 合并**，否则直接 Push 会绕过审查。
5. Git 端更新 Ref 时需要处理并发写入；不得依靠 MySQL 事务假装锁定 Git Ref。

### 2.4 Pull Request（已确定）

1. PR 只支持**同一仓库不同分支**，不支持 Fork PR。
2. PR 创建后，**源分支身份固定，但其 HEAD 可随 Push 变化**；Open PR 的目标分支允许修改。目标变化需要重新计算 Diff/冲突并使旧审批失效。
3. PR 是业务对象，分支和 Commit 是 Git 对象/引用。PR 的标题、作者、Open/Closed/Merged、评论、审查、合并记录由 GitNova 保存；Commit 列表、HEAD、Merge Base、Diff 与冲突情况从 Git 获取。
4. 审查可 Approve / Request Changes；自己不能批准自己创建的 PR；新的源代码版本使原批准失效，历史 Review 保留。
5. Owner 和 Collaborator 可在满足约束时 Merge；只支持 **Merge Commit** 与 **Squash Merge**，不提供 Rebase Merge。
6. Owner 可明确绕过普通 PR 配置的**强制审批**（需要留痕），但不因此获得直接 Push 绕过分支保护的权利。
7. **Owl [bot] 创建的 PR 必须由具备权限的人类明确批准**，Owner 不能绕过此要求；Owl 不可审批、Merge 或绕过分支保护。Owner 可审查 Owl 的 PR。
8. PR 源分支删除后，保留 PR 讨论与业务记录，但不承诺分支不可恢复时仍可 Merge；关闭 PR 不自动删除源分支；合并结果记录最终 Commit OID。
9. Git 成功更新目标 Ref 与 PR 业务记录写入不是同一事务；Merge 需要可识别的操作结果和失败后协调，**不把整段 Git 操作包进 MySQL 事务**。

### 2.5 Owl 云端接入（已确定）

1. Owl 是独立的 Harness，部署在 Sandbox 中；Worker/RPC 的实现语言与具体协议待定，只要求指令、事件、取消等通信能力。
2. GitNova 仓库页的 Agents 页面按 `repository_id` 展示关联 Session；Session 默认发起者可见，可主动共享给当前有仓库权限的人；被共享者**只读**，不能控制执行。Owner 不自动拥有他人私人 Session 的阅读权限。
3. 打开历史 Session 只读取历史，不启动 Sandbox；发送 Prompt 才准备、复用或恢复 Sandbox。关闭浏览器不自动取消已经运行的工作。
4. Session 历史与完整工作区快照保存在 Sandbox 之外；回收前保存工作区，恢复时包含未提交、未跟踪文件；旧凭据不能直接作为有效授权从快照恢复。
5. 同一 Session 的工作应保持连续性；不预设持久化的 `Task #1/#2` 等业务实体，也不强行要求“一 Prompt 一 Sandbox”。
6. Owner 启用 Owl 后提供**仓库级、范围受限**的系统 Bot 授权，通常限其 `owl/*` 工作分支；可提交代码和创建 PR，不可直接 Push 默认分支、审批或合并。取消授权/移除发起者权限时停止受影响的执行并撤销有效访问。
7. 创建 PR 是通过 GitNova 业务能力；普通 `git commit` / `git push` 可以由 Sandbox 内的 Bash 执行，但必须经 Git 服务端授权。

### 2.6 社区与通知（已确定）

1. Follow（User→User）、Star（User→Repository）、Watch（User→Repository）三种关系相互独立，不授予仓库权限。
2. Follow 含简易公开活动 Feed，初期从现有 Repository、PR、Star 等记录聚合，按时间展示；**不建立独立 ActivityEvent 表**。取消 Star 后历史 Star 动态可能消失，当前可接受。
3. Star 数与热门仓库可从关联关系计算，暂不维护冗余计数表。
4. Watch 产生有限的相关仓库通知，不对每次 Push 无差别通知。
5. Notification 是按接收者保存的独立事实，支持未读/已读；消息可以关联 PR、Invitation、Repository 或 Owl Session，不重复存完整目标业务数据。
6. 邀请是否有效由 `repository_invitations` 决定，Notification 仅是提醒；通知丢失不应导致邀请不可处理。
7. Feed 和通知目标内容必须在读取时重新执行权限检查；私有仓库权限撤销或转私有不能泄露历史内容。

## 3. 逻辑数据模型：稳定关系与建议属性

> **重要**：下文是关系模式草案，不是最终建表清单。`PK/FK/UNIQUE` 以及关系基数相对稳定；常规字段、类型、可空规则与索引可随编码和迁移调整。一个关系模式未必对应当前阶段的一张实体表或一个 Go 结构体。

### 3.1 用户与仓库

| 关系 | 必需/关键属性（建议） | 关键约束 |
|---|---|---|
| `users` | `id`, `username`, `email`, `password_hash`, `display_name`, `avatar_url`, `bio`, `email_verified_at`, `disabled_at`, `created_at` | PK `id`；UNIQUE `username`、`email` |
| `repositories` | `id`, `owner_id`, `name`, `description`, `visibility`, `default_branch`, `created_at` | PK `id`；FK `owner_id → users.id`；UNIQUE `(owner_id, name)` |
| `repository_members` | `repository_id`, `user_id`, `joined_at` | PK `(repository_id, user_id)`；两列 FK；Owner 不在此关系中 |
| `repository_invitations` | `id`, `repository_id`, `inviter_id`, `invitee_id`, `status`, `created_at`, `responded_at` | PK `id`；相关 FK；同仓库/被邀人同时至多一条有效待处理邀请 |

**Rename（延期）**：若保留，当前 URL 从 Owner + 仓库当前名称生成。历史地址是一个 Repository 对应 0..N 个旧名称的概念关系；不预设 `repository_redirects` 一定是 MySQL 表，也不重复保存完整 HTTPS/SSH URL。`(owner_id, old_name)` 可唯一定位旧映射；重新使用旧名称时，当前仓库地址优先。

### 3.2 PR 与分支保护

| 关系 | 必需/关键属性（建议） | 关键约束 |
|---|---|---|
| `pull_requests` | `id`, `repository_id`, `number`, `author_kind`, `author_user_id?`, `title`, `description`, `source_branch`, `target_branch`, `state`, `created_at`, `updated_at`, `merge_commit_oid?`, `merge_method?`, `merged_by?`, `merged_at?`, `closed_at?` | PK `id`；UNIQUE `(repository_id, number)`；作者为 User 或系统 Owl Bot |
| `pull_request_reviews` | `id`, `pull_request_id`, `reviewer_user_id`, `decision`, `reviewed_head_oid`, `target_revision?`, `created_at` | PK `id`；审查者为有效人类用户；自己不能 Approve 自己的 PR；有效性由代码/目标版本判断 |
| `pull_request_comments` | `id`, `pull_request_id`, `author_kind`, `author_user_id?`, `body`, `created_at`, `updated_at?`, `review_id?`, `commit_oid?`, `file_path?`, `diff_location?` | PK `id`；一般评论不要求 Diff 定位；行级评论需保留历史定位依据 |
| `branch_protection`（候选） | `repository_id`, `branch_name`, `require_pr`, `require_approval` | 当前至少支持默认分支；可根据配置范围选择独立关系或仓库配置属性，不提前锁定表结构 |

**Review 注意**：`target_revision` 是一种可选实现思路，不是已确认必须存储的字段。需要保证“目标分支更改后旧审批失效”；若仅凭源 HEAD OID 比较，在回退后可能再次认可旧审批，需在实现时明确语义。不要为了每次 Push 同步 SHA 而提前建立 PR HEAD 镜像表。

**Git 边界**：不建 `git_commits`、`git_branches`、`pull_request_diffs`、`merge_conflicts` 的业务镜像表。若要长期展示源分支已删除后的历史 Diff，考虑通过 Git 层的额外 Ref 保留相关对象可达性，而不是把源码复制进 MySQL；具体方式待技术设计验证。

### 3.3 社区与通知

| 关系 | 必需/关键属性（建议） | 关键约束 |
|---|---|---|
| `user_follows` | `follower_id`, `followee_id`, `created_at` | PK `(follower_id, followee_id)`；禁止自关注 |
| `repository_stars` | `user_id`, `repository_id`, `created_at` | PK `(user_id, repository_id)` |
| `repository_watches` | `user_id`, `repository_id`, `created_at` | PK `(user_id, repository_id)`；不授权访问代码 |
| `notifications` | `id`, `recipient_user_id`, `type`, `actor_kind?`, `actor_user_id?`, `target_kind`, `target_ref`, `created_at`, `read_at?` | PK `id`；每位接收者分别保留阅读状态；目标引用的权限由业务层验证 |

`target_kind + target_ref` 是跨不同对象体系的引用，不能假装它同时是 PR、Invitation 和外部 Owl Session 的普通数据库外键。删除目标后的通知清理或隐藏行为由业务处理，不为此建立统一 Event 主表。

### 3.4 用户凭据与会话

| 对象或关系 | 必需/关键属性（建议） | 数据归属 |
|---|---|---|
| Password | `users.password_hash` | User 的当前密码属性，非独立表 |
| `personal_access_tokens` | `id`, `user_id`, `name`, `token_verifier`, `scopes`, `created_at`, `expires_at`, `revoked_at?` | 账号级持久化；高熵 Token 不存明文 |
| `ssh_keys` | `id`, `user_id`, `title`, `public_key`, `fingerprint`, `created_at` | 账号级持久化；仅公钥，公钥身份去重 |
| WebSession（逻辑对象） | `session_identity`, `user_id`, `token_verifier`, `created_at`, `expires_at`, `revocation/validity` | **独立 Session Store，介质未定**；1 User : N Session |
| EmailVerification（临时凭据） | 用户关联、单次校验值、有效期、消耗状态 | 通过数据库进行存储|

**不建立通用的 `credentials` 大表**。凭据有效不等于具备仓库权限。PAT 的读写 Scope、当前账号状态、仓库权限、分支保护必须综合验证。修改密码撤销所有 Web Session，而 PAT/SSH Key 保留；封禁用户会阻断三种认证路径。若密码状态与 Session Store 分开，如何保证立即撤销应在实现前验证。

### 3.5 另需记录但不提前设计重型实体

- 管理员封禁与解封的关键操作留痕（具体日志存储形式待定）。
- Git 合并操作的可识别成功结果与失败恢复，不引入默认的 MQ/Outbox 框架。
- Owl Session、Prompt、执行事件、工作区快照在独立存储和运行系统中处理；不预设 GitNova MySQL 中的 Owl 业务表。

## 4. 用真实请求检查数据边界

| 场景 | 持久化/读取边界 | 必须避免 |
|---|---|---|
| Web 登录 | 校验 User，创建独立服务端 Web Session；浏览器使用 Opaque Token | 认为 JWT 或 MySQL 会话表天然必需 |
| Clone/Fetch | URL → Repository 身份 → 公私可见性/凭据/权限 → Git 上传对象 | 将 Git 对象复制进业务库；私有仓库因 DB 故障而默认放行 |
| Push | 校验认证、Scope、成员关系、分支保护 → 原生 Git 更新 Ref | 为每次 Push 同步 PR 的 HEAD SHA 或持有长 MySQL 事务 |
| 创建 PR | 查 Git 当前源/目标 Ref → 写 PR 业务记录 | 在 MySQL 复制 Commit 历史、Diff、Merge Base |
| Review | 查 Git 当前版本 → 存审查者、决定与审查版本 | 只存 `approved=true` 而无法识别旧版审查 |
| Merge | 校验权限/审批 → 用 Git 生成提交并条件更新 Ref → 写 PR 合并结果 | 假设 Git 与 MySQL 共用原子事务；忽视 Git 成功/DB 失败 |
| Follow Feed | 查询关注关系，聚合现有公开仓库/PR/Star 记录，按当前权限过滤 | 提前设计 ActivityEvent/Feed 复制表 |
| 站内通知 | 业务事件成功后为接收者保存通知及 `read_at` | 将 Notification 当成 Invitation/PR 状态的权威来源 |
| Owl 云端 Prompt | GitNova 校验仓库权限并准备 Sandbox → Worker RPC → Owl Harness；会话与工作区独立持久化 | 将每轮 Prompt 强行映射到 MySQL Task 表或让 Go 管理 AgentLoop |
| 只读打开 Owl Session | 独立存储读取历史并检查所有者/共享权限 | 唤醒 Sandbox 或执行模型调用 |

## 5. 数据表字段能否在开发中演进？

**可以，而且应该演进。** 逻辑模型现在固定的是主要业务事实与约束，不是所有列名。建议遵循以下原则：

1. **先稳住关系，再补字段**：实体身份、所有权、引用对象、唯一约束、权限规则优先确定；展示字段、时间戳、审计细节可随真实用例补充。
2. **用数据库迁移管理变化**：新增/删除/修改列，通过可追踪的迁移脚本完成；不要手动修改线上表而不记录。
3. **优先增加必要字段，而非一次性设计全部字段**：例如 PR 最初只需正确创建与展示；代码审查实现时再补对应的定位及版本信息。
4. **区分轻易可改与高代价变更**：新增可空列通常容易；修改主键、重分配仓库身份、改变唯一约束或把 Git 代码真相迁入 MySQL 的代价高，必须谨慎。
5. **不要以“以后可能优化”为由预建缓存计数、镜像表、任务状态表**。
6. **任何模型变更都要写明具体业务场景**：为什么现有关系表达不了？新增字段是否真的不可从已有权威来源推导？

## 6. 实现前的待决与延期清单

| 项目 | 当前判断 | 何时决定 |
|---|---|---|
| Go HTTP 处理方式、数据访问与迁移工具 | 待技术选型 | 编写第一批接口/DDL 前 |
| Git HTTPS 协议接入、服务器端 Push 授权与 Ref 并发控制 | 待技术设计 | 实现 Clone/Fetch/Push 前 |
| WebSession Store | 倾向 Opaque Token；MySQL/Redis/其他介质未定 | 实现登录、撤销链路前 |
| 邮件验证令牌介质与投递服务 | 待选型 | 注册功能落地前 |
| BranchProtection 关系或仓库属性 | 根据第一批配置范围确定 | 分支保护开发前 |
| PR 合并的 Git 成功、业务记录写入失败的协调办法 | 必须有明确可靠行为，但不预设分布式事务框架 | Merge 开发前 |
| PR 的 Review 版本规则与行级评论定位 | 已有基本约束，细节待验证 | Review/Comment 开发前 |
| Git 历史 PR 引用保留 | 待验证 | 删除源分支并保留历史展示时 |
| Repository Rename 与旧 URL | 可延期或移除；不预设别名表 | 若确定开发 Rename 时 |
| Owl RPC 协议、Sandbox 生命周期、快照介质 | 保持 Harness in Sandbox；不要先建 Task 表 | Owl 云端接入时 |
| SSH Git 认证 | 功能范围内，晚于 PAT | HTTPS Git 稳定后 |

## 7. 建议开发顺序与验收场景

1. **账号与凭据**：注册/邮件验证、Web 登录、多会话撤销、PAT 基础；管理员禁用需确实阻断授权。
2. **仓库与 Git**：创建仓库并得到可用 HTTPS Clone URL；公开匿名 Clone、私有鉴权、授权 Push、分支保护。
3. **PR 主流程**：从真实分支创建 PR，展示 Git Diff，Review、Comment、Merge Commit/Squash Merge；并发与异常路径按需补充。
4. **社区和通知**：Follow/Star/Watch，按当前权限展示 Feed、站内通知与邀请处理。
5. **Owl 云端接入**：复用已有 TypeScript Harness 的历史恢复能力；验证 Sandbox 内执行、RPC 事件流、快照与重新 Prompt 恢复、Owl PR 的人工审查。

**至少要能验证的交叉场景**：用户被封禁后 PAT/SSH 不能继续访问；被移除协作后不能操作原私有仓库和 Owl 工作区；PR 作者不能自审；新 Push 或目标分支变更使旧审批失效；Owl PR 必须获人类批准；PR Merge 不得覆盖并发 Push；重开 Session 只读不唤醒 Sandbox。

---

## 维护原则

这份文档是**决策记录**，不是先于代码而独立生长的架构工程。实现过程中允许通过迁移与小范围修订改进字段和关系，但变更应有明确用例与理由。应优先交付真实可用的 Git 协议链路与 PR 流程，而不是提前设计没有实际失败案例支撑的组件。
