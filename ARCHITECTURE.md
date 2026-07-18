# GitNova — 架构全貌与开发参考

> 轻量级私有代码托管平台 + 智能 Code Review。核心引擎复用 CS61B Gitlet（自实现），上层构建 REST API + CAS 并发控制 + ReAct Agent。

---

## 整体数据流

```
Gitlet客户端
    │
    ▼
POST /api/repos/{repoId}/push/negotiate   ← Phase 2  对象协商
    │  Client上报所有SHA-1 → Server返回missingObjects
    ▼
POST /api/repos/{repoId}/push/transfer     ← Phase 2  增量传输
    │  Client打包missingObjects上传 → Server SHA-1校验 → 写入ObjectStorage
    ▼
TransferService.updateHead()               ← Phase 3  CAS并发控制
    │  ① CAS UPDATE repository.head_commit_sha1
    │  ② INSERT commit_record
    │  ③ UPDATE branch.head_commit
    │  ④ publishEvent(PostReceiveEvent)
    ├──► [主线程] return 200 OK
    └──► [异步] CodeReviewListener
              └── CodeReviewAgentLoop.runAgentLoop()  ← Phase 4
                    │  ReAct 循环: Thought → Action → Observation
                    │  调用4个工具: getDiff / readFileContent / listChangedFiles / submitReview
                    ▼
                  review_comment表 + WebSocket推送
```

---

## 包结构与职责

```
com.gitnova
│
├── GitNovaApplication.java              Spring Boot入口, @EnableAsync
│
├── common/
│   └── UserContext.java                 ThreadLocal<UserId, Username>, JwtInterceptor写入, afterCompletion清理
│
├── config/
│   ├── JwtInterceptorConfig.java        拦截 /api/**, 排除 /api/auth/**
│   ├── GlobalExceptionHandler.java      GitletException→400, Exception→500
│   ├── SecurityConfig.java             (预留)
│   └── WebSocketConfig.java            注册 /ws/review → ReviewPushHandler
│
├── controller/
│   ├── AuthController.java             POST /api/auth/register, /login        ✅ Phase1
│   ├── RepoController.java             POST/GET/GET{id}/DELETE /api/repos     ✅ Phase1
│   ├── CommitController.java           GET /api/repos/{id}/commits            ✅ 读路径
│   ├── TransferController.java         POST negotiate + transfer              ✅ Phase2/3
│   └── AgentController.java            POST suggest-message, chat             ◐ 骨架
│
├── service/
│   ├── AuthService.java                注册+登录+JWT生成                       ✅ Phase1
│   ├── RepoService.java                仓库CRUD+权限+GitletService.init()     ✅ Phase1
│   ├── GitletService.java              包装gitlet.Repository, 路径={basePath}/{ownerId}/{repoId}
│   ├── ObjectNegotiationService.java   negotiate(repoKey, request) → NegotiationResponse  ✅ Phase2
│   ├── TransferService.java            unpackAndStore() + updateHead(CAS)     ✅ Phase2/3
│   └── agent/
│       ├── AgentTool.java              接口: name/description/parametersSchema/execute/isConcurrencySafe
│       ├── ToolRegistry.java           Spring自动收集@Component AgentTool, getToolDefinitions()
│       ├── CodeReviewAgentLoop.java    ReAct主循环, maxTurns=10                ◐ 骨架
│       ├── CommitMessageService.java   独立Service, POST /suggest-message触发  ◐ 骨架
│       ├── RepoQAService.java          独立Service, POST /chat触发            ◐ 骨架
│       └── tools/
│           ├── GetDiffTool.java        工具1: 获取commit diff                  ◐ 骨架
│           ├── ReadFileContentTool.java 工具2: 读取文件完整内容(ReAct核心价值) ◐ 骨架
│           ├── ListChangedFilesTool.java 工具3: 列出变更文件列表               ◐ 骨架
│           └── SubmitReviewTool.java   工具4: 提交review→终止循环, isConcurrencySafe=false ◐ 骨架
│
├── event/
│   ├── PostReceiveEvent.java           push成功后发布, 携带repoId/commitSha1/pusherId
│   └── CodeReviewListener.java         @Async监听PostReceiveEvent→调agentLoop.runAgentLoop()
│
├── dto/
│   ├── ApiResponse.java                统一响应 {code, message, data}
│   ├── PushRequest.java                negotiate请求: localHeadSha1 + localObjects
│   ├── NegotiationResponse.java        negotiate响应: remoteHeadSha1 + missingObjects
│   ├── TransferMetadata.java           transfer metadata: newHeadSha1/baseHeadSha1/branchName/commitMessage
│   ├── ReviewCommentDTO.java           (预留)
│   ├── Message.java                    LLM消息: role/content/toolCalls/toolCallId
│   ├── ToolCall.java                   LLM工具调用: id/name/params
│   ├── LLMResponse.java                LLM响应: stopReason/text/toolCalls
│   └── ToolDefinition.java            工具定义: name/description/parametersSchema
│
├── entity/                              MyBatis-Plus实体, 一一对应MySQL表
│   ├── User.java                       user表
│   ├── Repository.java                 repository表 (head_commit_sha1 = CAS核心字段)
│   ├── RepoMember.java                 repo_member表 (owner/collaborator)
│   ├── CommitRecord.java               commit_record索引表 (sha1 PK, 磁盘Commit的MySQL镜像)
│   ├── Branch.java                     branch表
│   └── ReviewComment.java              review_comment表 (Agent审查结果)
│
├── mapper/                              MyBatis-Plus BaseMapper
│   ├── UserMapper.java
│   ├── RepositoryMapper.java           + casUpdateHead(repoId, baseSha1, newSha1) ← CAS核心SQL
│   ├── RepoMemberMapper.java
│   ├── CommitRecordMapper.java
│   ├── BranchMapper.java               + updateHead(repoId, branchName, headCommit)
│   └── ReviewCommentMapper.java
│
├── gitlet/                              CS61B Gitlet引擎 (改造: static→实例, System.exit→GitletException)
│   ├── Repository.java                 核心: init/add/commit/log/checkout/branch/reset...
│   ├── Commit.java                     DAG节点: parentCommit/mapping/timestamp/message, getTimestamp()已补
│   ├── StagingArea.java                暂存区: addition(Map)+removal(Set)
│   ├── Utils.java                      SHA-1/serialize/deserialize/readContents/writeContents/parseTimestamp
│   ├── GitletException.java            RuntimeException, GlobalExceptionHandler→400
│   ├── Main.java                       CLI入口(本地测试用)
│   └── DumpObj.java / Dumpable.java    调试工具
│
├── storage/                             存储抽象层(策略模式)
│   ├── ObjectStorage.java             接口: writeObject/readObject/existsObject/listObjects
│   └── LocalObjectStorage.java        @ConditionalOnProperty(type=local), 扁平objects/{sha1}
│
├── interceptor/
│   └── JwtInterceptor.java            preHandle校验JWT→UserContext, afterCompletion清理ThreadLocal ✅ Phase1
│
├── websocket/
│   └── ReviewPushHandler.java         repoId→Sessions映射, pushToRepo()推送review结果 ◐ 骨架
│
└── util/
    └── JwtUtil.java                    JWT生成+校验工具
```

---

## 关键设计决策速查

| 决策 | 原因 |
|------|------|
| JWT手写拦截器而非Spring Security | 轻量，面试能讲清底层 |
| repoKey = "{ownerId}/{repoId}" | repoId不变，repoName可能改名；由Controller拼接防路径伪造 |
| ObjectStorage扁平objects/{sha1} | 与Gitlet的objects/blobs/+objects/commits/隔离，互不干扰 |
| CAS而非Redis分布式锁 | 乐观锁>悲观锁，少一个中间件 |
| 不支持服务端merge | fast-forward only，与GitHub protected branch一致 |
| ReAct而非流水线Agent | Agent自主决定审查深度，模拟人类CR行为 |
| 手写Agent Loop而非LangChain4j | 4工具+1循环200行足够，框架是黑盒 |
| 非流式LLM调用 | Code Review是后台任务，无打字机需求，非流式更简单 |
| 工具串行执行 | 工具间强依赖(getDiff→readFileContent→submitReview)，无并行空间 |

---

## 实现状态

| Phase | 内容 | 状态 |
|-------|------|:----:|
| Phase 1 | JWT+注册登录+仓库CRUD | ✅ |
| Phase 2 | negotiate协商+transfer增量传输+unpackAndStore | ✅ |
| Phase 3 | CAS并发控制+commit_record/branch双表写入+PostReceiveEvent | ✅ |
| Phase 4 | AgentTool/ToolRegistry/4工具/CodeReviewAgentLoop骨架 | 📐 骨架就绪 |
| Phase 4 | callLLM()+buildSystemPrompt()+runAgentLoop()业务逻辑 | ❌ 待实现 |
| Phase 4-A | generateFix+applyFix(修复闭环) | ⏳ 扩展 |
| Phase 4-B | getHistoryReviews+checkRepeatIssue(历史记忆) | ⏳ 扩展 |
| Phase 4-C | Reflection自评机制 | ⏳ 扩展 |
| Phase 5 | JMeter压测+Swagger+README | ⏳ |
| Phase 6 | WebSocket订阅推送 | ⏳ |

---

## 数据库表

6张表: `user` `repository` `repo_member` `commit_record` `branch` `review_comment`

`commit_record` 是磁盘Commit对象的MySQL索引层，两者必须同步写入（TransferService.updateHead中@Transactional保证）。

`repository.head_commit_sha1` 是CAS乐观锁核心字段——所有并发push都通过 `WHERE head_commit_sha1 = #{baseHeadSha1}` 校验。

---

## 配置要点

```yaml
gitnova.repo.base-path: ${REPO_BASE_PATH:...}   # Mac: ~/gitnova-repos, Win: D:/gitnova-repos
gitnova.storage.type: local                       # 未来换minio只改这里
gitnova.llm.api-key: ${LLM_API_KEY:}             # DeepSeek API Key
```

---

## SPEC文件

- `SPEC_v3(1).md` — Phase 1 详细实现 Spec
- `SPEC_v3(2).md` — Phase 2/3 详细实现 Spec
- `SPEC_v3(3).md` — Phase 4 ReAct Agent 初始版
- `SPEC_v3(6).md` — **最新版**, Phase 4 含 8 步实现指南 + 4-A/B/C 扩展
