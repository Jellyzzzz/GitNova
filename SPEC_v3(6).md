# GitNova — 智能代码托管与审查平台
## Project Specification v3.0

> **风格说明**：本文档格式参照 CS61B Project Spec 惯例，包含
> Overview、Skeleton Description、Implementation Notes 与
> Grading / Milestone 四类标注。所有 `⚠️ Warning`、
> `💡 Design Note`、`🔍 Implementation Note` 均为给自己写的
> 开发备忘，面试时可直接作为"我当时的设计思考"展示。

---

## Overview

GitNova 是一个**轻量级私有代码托管平台**，其核心引擎直接复用
CS61B Gitlet 项目中自实现的版本控制逻辑（Blob/Tree/Commit
对象模型、SHA-1 内容寻址、分支指针管理），并在此之上构建：

1. **RESTful HTTP 服务层**（Spring Boot 3.x）
2. **基于对象协商的增量传输协议**（简化版 Git Smart Protocol）
3. **CAS 乐观锁并发控制**（替代分布式锁）
4. **事件驱动的 LLM Agent 钩子**（Code Review / Commit Message / RAG）

### 面试口径（请背熟）

> "摒弃了常规的 CRUD 架构，深入版本控制底层。基于 DAG 与 CAS
> 乐观锁实现了无锁并发冲突控制；基于简化的 Git Smart Protocol
> 实现了端到端的增量对象协商传输；并通过 Spring Event 机制解耦
> 核心流程，集成了基于 ReAct 范式的 Code Review Agent——Agent
> 能自主决定是否需要查看变更文件的上下文代码，实现了自适应深度
> 的异步代码审查，通过 WebSocket 实时推送审查结果。"

### 技术栈

| 层次           | 选型                                      |
|----------------|-------------------------------------------|
| 核心引擎       | Gitlet（自实现，Java）                    |
| Web 框架       | Spring Boot 3.x                           |
| 认证           | JWT（手写拦截器，不用 Spring Security）   |
| 数据库         | MySQL 8 + MyBatis-Plus                    |
| 并发控制       | CAS 校验（数据库乐观锁语义）              |
| 传输协议       | HTTP + 自定义 Have/Want 对象协商          |
| 实时通信       | WebSocket（SSE 可选）                     |
| Agent          | ReAct 范式 + DeepSeek API（OkHttp 直调）  |
| 存储抽象       | 策略模式（LocalObjectStorage / MinIO 可扩展）|
| 压测           | JMeter                                    |
| 文档           | Swagger / Apifox                          |

> 💡 **Design Note — 为什么 JWT 手写拦截器而非 Spring Security？**
> Spring Security 配置复杂，对小项目来说是过度设计。手写
> `HandlerInterceptor` 做 Token 校验更轻量，且面试时能完整讲清楚
> 鉴权流程，不会被"你知道 Security 底层怎么工作的吗"问倒。

---

## 项目结构（Skeleton）

```
gitnova/
├── src/main/java/com/gitnova/
│   ├── config/
│   │   ├── JwtInterceptorConfig.java   # 注册拦截器
│   │   └── WebSocketConfig.java
│   ├── controller/
│   │   ├── AuthController.java         # 注册 / 登录
│   │   ├── RepoController.java         # 仓库 CRUD
│   │   ├── CommitController.java       # 提交记录查询
│   │   ├── TransferController.java     # push / pull（核心）
│   │   └── AgentController.java        # Agent 相关接口
│   ├── service/
│   │   ├── AuthService.java
│   │   ├── RepoService.java
│   │   ├── GitletService.java          # 包装 Gitlet 核心引擎
│   │   ├── ObjectNegotiationService.java  # Have/Want 协商逻辑
│   │   ├── TransferService.java        # 增量打包 / 解包
│   │   └── agent/
│   │       ├── AgentTool.java          # 工具接口定义
│   │       ├── ToolRegistry.java       # 工具注册与分发
│   │       ├── CodeReviewAgentLoop.java # ReAct 核心循环
│   │       └── tools/                  # 具体工具实现
│   │           ├── GetDiffTool.java
│   │           ├── ReadFileContentTool.java
│   │           ├── ListChangedFilesTool.java
│   │           └── SubmitReviewTool.java
│   ├── event/
│   │   ├── PostReceiveEvent.java       # push 成功后发布
│   │   └── CodeReviewListener.java     # 异步监听，触发 Agent
│   ├── websocket/
│   │   └── ReviewPushHandler.java      # 推送 review 结果
│   ├── gitlet/                         # Gitlet 核心引擎（原代码）
│   │   ├── Repository.java
│   │   ├── Commit.java
│   │   ├── Blob.java
│   │   └── ...
│   ├── storage/
│   │   ├── ObjectStorage.java          # 存储抽象接口（策略模式）
│   │   └── LocalObjectStorage.java     # 本地磁盘实现（默认）
│   ├── mapper/
│   ├── entity/
│   └── dto/
│       ├── PushRequest.java            # push 协商请求体
│       └── ReviewCommentDTO.java
├── src/main/resources/
│   ├── application.yml
│   └── sql/init.sql
└── README.md                           # 含架构图、快速启动
```

---

## 数据库设计

```sql
-- 用户表
CREATE TABLE user (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    username    VARCHAR(50)  UNIQUE NOT NULL,
    password    VARCHAR(100) NOT NULL,           -- BCrypt
    email       VARCHAR(100),
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 仓库表
-- ⚠️ Warning：head_commit_sha1 是整个并发控制的核心字段
--    所有 CAS 校验都基于这一列，不要随意加索引或触发器修改它
CREATE TABLE repository (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    name             VARCHAR(100) NOT NULL,
    owner_id         BIGINT NOT NULL,
    is_private       TINYINT  DEFAULT 1,
    description      VARCHAR(255),
    head_commit_sha1 VARCHAR(40),                -- CAS 乐观锁目标列
    created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_owner_name (owner_id, name)
);

-- 仓库成员表
CREATE TABLE repo_member (
    id      BIGINT PRIMARY KEY AUTO_INCREMENT,
    repo_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role    ENUM('owner', 'collaborator') NOT NULL,
    UNIQUE KEY uk_repo_user (repo_id, user_id)
);

-- Commit 元数据索引层
-- 💡 Design Note：Gitlet 的 Commit 对象本身序列化存磁盘，
--    这张表只是"索引"，目的是让前端展示 commit log 时不用
--    扫描磁盘文件，直接走 SQL 查询。两者必须保持同步写入。
CREATE TABLE commit_record (
    sha1        VARCHAR(40) PRIMARY KEY,
    repo_id     BIGINT      NOT NULL,
    parent_sha1 VARCHAR(40),                     -- 单亲；merge 暂不支持
    message     VARCHAR(255),
    author_id   BIGINT,
    branch_name VARCHAR(100),
    created_at  DATETIME,
    INDEX idx_repo_branch (repo_id, branch_name)
);

-- 分支表
CREATE TABLE branch (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    repo_id     BIGINT      NOT NULL,
    name        VARCHAR(100) NOT NULL,
    head_commit VARCHAR(40)  NOT NULL,
    UNIQUE KEY uk_repo_branch (repo_id, name)
);

-- Code Review 结果表（Agent 模块写入）
CREATE TABLE review_comment (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    commit_sha1 VARCHAR(40)  NOT NULL,
    repo_id     BIGINT       NOT NULL,
    file_path   VARCHAR(255),
    line_number INT,
    severity    ENUM('info', 'warning', 'error'),
    comment     TEXT,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
);
```

---

## Phase 1 — 基建与对象存储层（Week 1）

### 目标

Spring Boot 骨架 + JWT 认证 + 仓库 CRUD + Gitlet 引擎接入。

### 接口清单

```
POST /api/auth/register      # 注册
POST /api/auth/login         # 登录，返回 JWT

POST /api/repos              # 创建仓库（内部调用 GitletService.init()）
GET  /api/repos              # 查询当前用户的仓库列表
GET  /api/repos/{repoId}     # 仓库详情
DELETE /api/repos/{repoId}   # 删除仓库（仅 owner）
```

### Implementation Notes

**1. GitletService 的定位**

不要修改 Gitlet 原有逻辑，只做包装：

```java
@Service
public class GitletService {

    // Gitlet 把状态写到本地文件系统
    // 每个仓库对应一个独立的工作目录，例如：
    // /data/gitnova-repos/{ownerId}/{repoName}/.gitlet/

    public void init(String repoPath) { ... }
    public String commit(String repoPath, String message) { ... }
    // 返回新 commit 的 SHA-1，供 Service 层写 MySQL
}
```

> ⚠️ **Warning — 路径隔离**
> 每个仓库必须有独立的工作目录，否则不同仓库的 `.gitlet` 目录
> 会互相污染。建议以 `{ownerId}/{repoName}` 为路径 key。

**2. JWT 拦截器**

```java
// 拦截除 /api/auth/** 以外的所有请求
// 从 Header Authorization: Bearer <token> 取 token
// 校验签名 → 解析 userId → 放入 ThreadLocal
// 失败直接返回 401
```

**3. ObjectStorage 存储抽象层**

Phase1 结束前完成接口定义，Phase2 开始直接用，不要绕过它直接操作文件：

```java
public interface ObjectStorage {
    void   writeObject(String repoKey, String sha1, byte[] content);
    byte[] readObject(String repoKey, String sha1);
    boolean existsObject(String repoKey, String sha1);  // Phase2 协商核心
    Set<String> listObjects(String repoKey);             // Phase2 协商核心
}
```

默认实现用 `@ConditionalOnProperty` 控制，`application.yml` 加：

```yaml
gitnova:
  storage:
    type: local   # 未来换 minio 只改这一行
```

> 💡 **Design Note — 为什么现在就定接口？**
> Phase2 的 `ObjectNegotiationService` 需要调用 `existsObject()` 和
> `listObjects()`，如果现在不抽象，Phase2 直接写死文件操作，
> 未来加 MinIO 要改业务逻辑。现在定好接口，Phase2~4 全部面向接口编程，
> 存储层可以随时替换，符合开闭原则。面试时这是一个主动展示的设计点。

> 💡 **Design Note — 为什么用 ThreadLocal？**
> Controller → Service 层需要知道"当前是谁在操作"，
> 用 ThreadLocal 传递比每个方法都加 `userId` 参数更干净。
> 注意请求结束后要 `remove()`，防止线程池复用导致数据污染。

### Milestone 验收

- Postman 能注册、登录拿到 JWT
- 携带 JWT 创建仓库后，服务器磁盘出现 `.gitlet` 目录结构
- 无效 JWT 请求返回 401

---

## Phase 2 — 对象协商与增量传输（Week 2）【核心难点 1】

### 目标

实现基于对象的智能传输，push 时只传服务端缺失的 Blob 对象，
而非整个文件包。

### 核心设计：简化版 Git Smart Protocol

真实 Git 的传输协议分两个 RPC：`info/refs`（能力协商）和
`receive-pack`（实际传输）。我们简化为两步 HTTP 交互：

#### Step 1 — 协商（Negotiation）

```
POST /api/repos/{repoId}/push/negotiate

Request Body:
{
  "localHeadSha1": "abc123",   // 客户端当前 HEAD
  "localObjects": ["sha1_a", "sha1_b", "sha1_c"]
                               // 客户端本地所有对象的 SHA-1 列表
}

Response Body:
{
  "remoteHeadSha1": "def456",  // 服务端当前 HEAD
  "missingObjects": ["sha1_b"] // 服务端缺少的对象（需要客户端上传的）
}
```

服务端逻辑（`ObjectNegotiationService`）：

```java
// 1. 拿到客户端的 localObjects 列表
// 2. 遍历，检查每个 SHA-1 在服务端对象库中是否存在
//    （直接检查文件是否存在：/data/.gitlet/objects/{sha1前2位}/{sha1后38位}）
// 3. 返回服务端不存在的 SHA-1 列表 → missingObjects
```

> 💡 **Design Note — 为什么让客户端发送所有对象列表？**
> 真实 Git 用 `have`/`want` 多轮交互缩小范围，对于我们的简化场景，
> 单次全量上报 SHA-1 列表（不是文件内容，只是哈希字符串）开销极小，
> 一次 RTT 完成协商，实现更简单，面试时也更好解释。

#### Step 2 — 传输（Transfer）

```
POST /api/repos/{repoId}/push/transfer

Request Body（multipart）：
- metadata: { "newHeadSha1": "abc123", "baseHeadSha1": "def456" }
- objects: 打包文件（仅包含 missingObjects 中的对象）
```

打包格式（自定义，参考 Git packfile 思路）：

```
[4 bytes: 对象数量 N]
for each object:
    [40 bytes: SHA-1]
    [8 bytes: 内容长度 L]
    [L bytes: 对象内容（Blob 序列化字节）]
```

服务端解包后逐一校验 SHA-1，写入对象库，再进入 Phase 3 的
CAS 指针更新流程。

> ⚠️ **Warning — SHA-1 完整性校验必须做**
> 解包时对每个对象重新计算 SHA-1，与包头声明的 SHA-1 比对。
> 不一致则拒绝整次 push，返回 400 + 具体错误对象。

### Milestone 验收

在日志中验证：修改一行代码后 push，传输的对象数量为 2
（1 个新 Blob + 1 个新 Commit），而非全量文件。

---

## Phase 3 — 并发控制与指针安全（Week 3）【核心难点 2】

### 目标

多人同时 push 同一仓库时，保证 HEAD 指针更新的原子性与一致性。

### 核心设计：数据库 CAS（Compare-And-Swap）

```java
// TransferService.java — updateHead 方法
@Transactional
public void updateHead(Long repoId, String baseHeadSha1, String newHeadSha1) {

    // CAS 更新：只有当前 head == 客户端的 base 时才更新
    int affected = repositoryMapper.casUpdateHead(repoId, baseHeadSha1, newHeadSha1);

    if (affected == 0) {
        // HEAD 已被其他请求修改，当前 push 是 non-fast-forward
        throw new NonFastForwardException(
            "Push rejected: remote contains work that you do not have locally. " +
            "Please pull and rebase before pushing."
        );
    }

    // 同步写 commit_record 和 branch 表
    commitRecordMapper.insert(...);
    branchMapper.updateHead(...);
}
```

对应 SQL：

```sql
UPDATE repository
SET head_commit_sha1 = #{newHeadSha1}
WHERE id = #{repoId}
  AND head_commit_sha1 = #{baseHeadSha1};
-- affected rows = 0 → CAS 失败 → non-fast-forward
```

> 💡 **Design Note — 为什么不用 Redis 分布式锁？**
> 分布式锁是悲观锁思路，会让所有并发 push 串行化，吞吐量低。
> CAS 是乐观锁思路：多数情况下并发冲突罕见，不加锁直接跑，
> 只有真正冲突时才报错让客户端重试，更符合 Git 的使用模型。
> 而且少一个 Redis 中间件依赖，部署更简单。

> ⚠️ **Warning — Merge 的边界说明**
> 当前版本**不支持服务端 merge**。Non-fast-forward push 会被
> 直接拒绝，客户端需要本地 pull + 手动解决冲突后再 push。
> 这与早期 GitHub 的行为一致，是有意为之的设计决策，
> 面试时主动说出来，比被追问到更好。

### Milestone 验收

用两个线程同时向同一仓库 push：一个成功返回 200，
另一个返回 409（Conflict）+ `non-fast-forward` 错误信息。

---

## Phase 4 — ReAct Code Review Agent（Week 4）【核心亮点】

### 目标

实现基于 ReAct（Reasoning + Acting）范式的 Code Review Agent：
Agent 自主推理、自主调用工具、自主决定审查深度，而非固定流水线。

> 💡 **Design Note — 为什么 ReAct 而不是流水线？**
> 流水线（diff → 调LLM → 出结果）对所有代码变更都做同样深度的审查。
> 但真实的 Code Review 不是这样的：改个变量名只需要扫一眼，
> 改了方法签名需要追溯调用方。ReAct 让 Agent 自己决定"要不要
> 继续看"，模拟了人类工程师做 CR 的真实行为。
> 面试时主动说出来——这是对 Agent 设计范式的理解，不是背概念。

### 4.0 前置类型定义

> ⚠️ **说明**：以下类型对应 DeepSeek API（OpenAI 兼容格式）的真实
> JSON 结构，不是凭空设计的。写代码前先建这几个文件。

```java
// ===== Message.java =====
// 对应发给 LLM 的 messages[] 数组里的一项
public class Message {
    private String role;        // "system" | "user" | "assistant" | "tool"
    private String content;     // 文本内容
    private List<ToolCall> toolCalls;  // 仅 role=assistant 且调用了工具时有值
    private String toolCallId;  // 仅 role=tool 时有值，标识这是对哪次调用的回应
}

// ===== ToolCall.java =====
// LLM 决定调用工具时，response 里返回的结构
public class ToolCall {
    private String id;                  // 工具调用的唯一 ID
    private String name;                // 工具名，如 "getDiff"
    private Map<String, String> params; // 工具入参
}

// ===== LLMResponse.java =====
// 调用 LLM API 后，解析出的响应（非流式，一次性拿到完整 JSON）
public class LLMResponse {
    private String stopReason;         // "tool_use" | "stop"
    private String text;               // stopReason=stop 时的纯文本回复
    private List<ToolCall> toolCalls;  // stopReason=tool_use 时的调用列表

    public boolean hasToolCalls() {
        return "tool_use".equals(stopReason);
    }
}

// ===== ToolDefinition.java =====
// 告诉 LLM"你有哪些工具可用"，对应 API 请求体里的 tools 字段
public class ToolDefinition {
    private String name;
    private String description;
    private Map<String, Object> parametersSchema;  // JSON Schema 格式，
                                                      // 描述这个工具接受哪些参数
}
```

> 💡 **Design Note — 为什么用非流式而非流式？**
> DeepSeek 支持流式响应（SSE），但流式场景下 `stop_reason` 可能
> 滞后于内容本身到达（工具调用块先出现，stop_reason 后确认），
> 需要额外维护一个 `needsFollowUp` 标志位来处理时序问题。
> Phase4 的 Code Review 是后台异步任务，没有"打字机效果"的展示
> 需求，用非流式（一次性拿完整 JSON）更简单可控，少一类 bug。
> 面试时如果被问"为什么不用流式"，这就是答案——按需选择，
> 不是不会，是流式在这个场景没有收益反而增加复杂度。

---

### 4.1 工具注册表（Tool Registry）

Agent 可以调用的工具集，每个工具是一个函数：

```java
// 工具接口定义
public interface AgentTool {
    String name();          // 工具名，LLM 用这个名字调用
    String description();   // 工具描述，帮助 LLM 决定是否调用
    Map<String, Object> parametersSchema();  // 参数的 JSON Schema
    String execute(Map<String, String> params);  // 执行，返回文本结果
}
```

#### ToolRegistry：注册与分发中枢

```java
// service/agent/ToolRegistry.java
@Component
public class ToolRegistry {

    // 工具名 → 工具实例，Spring 启动时自动收集所有 AgentTool 实现
    private final Map<String, AgentTool> tools;

    // 利用 Spring 自动注入所有 AgentTool 的实现类
    public ToolRegistry(List<AgentTool> toolList) {
        this.tools = toolList.stream()
            .collect(Collectors.toMap(AgentTool::name, t -> t));
    }

    /**
     * 生成供 LLM 使用的工具定义列表
     * 对应 DeepSeek API 请求体里的 tools 字段
     */
    public List<ToolDefinition> getToolDefinitions() {
        return tools.values().stream()
            .map(t -> new ToolDefinition(t.name(), t.description(), t.parametersSchema()))
            .collect(Collectors.toList());
    }

    /**
     * 分发执行：LLM 返回 ToolCall 后，根据 name 找到对应工具并执行
     */
    public String execute(String toolName, Map<String, String> params) {
        AgentTool tool = tools.get(toolName);
        if (tool == null) {
            return "Error: unknown tool '" + toolName + "'";
            // 不抛异常，把错误信息作为 Observation 返回给 Agent，
            // 让 Agent 自己决定怎么处理（这是 ReAct 优于硬编码的地方）
        }
        try {
            return tool.execute(params);
        } catch (Exception e) {
            return "Error executing " + toolName + ": " + e.getMessage();
        }
    }
}
```

> 💡 **Design Note — 为什么用 Spring 自动注入而非手写 if-else 分发？**
> `learn-claude-code` 教学版用一个 `TOOL_HANDLERS` 字典手动维护
> 映射关系，新增工具要改两处（TOOLS 数组 + HANDLERS 字典）。
> Spring 的做法是让每个工具是一个独立的 `@Component`，实现
> `AgentTool` 接口，`List<AgentTool> toolList` 构造注入时
> Spring 自动收集所有实现类。**新增工具只需要新建一个类并加
> `@Component` 注解，ToolRegistry 不用改一行代码。**
> 这是开闭原则在工具注册场景的具体应用，面试时可以对比讲。

#### 工具调用的执行模式：串行

```java
// CodeReviewAgentLoop.java 里工具调用部分
for (ToolCall call : response.getToolCalls()) {
    if ("submitReview".equals(call.name())) {
        return parseReviewResult(call.params().get("reviewJson"));
    }
    String result = toolRegistry.execute(call.name(), call.params());
    messages.add(toolResultMessage(call.id(), result));
}
```

> 💡 **Design Note — 为什么串行而不并行？**
> CC 的 `StreamingToolExecutor` 会并行执行 concurrency-safe 的工具，
> 因为 CC 的典型场景是"同时读三个独立文件"——工具之间无依赖。
> GitNova 的工具链路是**强依赖关系**：必须先 `getDiff` 才知道要不要
> `readFileContent`，必须先看完内容才能 `submitReview`。
> 任务结构本身决定了没有并行空间，不是主观选择放弃并行。
> 唯一的例外场景——Agent 一次决定读多个独立文件的上下文——
> 目前评估收益有限，优先保证逻辑可调试性，暂不实现并行。
> 面试时这样回答："评估过并行，但工具间是强依赖决策链，
> 串行更利于正确性和可调试性，是优先级判断而非能力缺失。"

#### 并发安全判断标准（isConcurrencySafe）

即使当前串行实现，工具接口也预留这个方法，为未来扩展埋点：

```java
public interface AgentTool {
    String name();
    String description();
    Map<String, Object> parametersSchema();
    String execute(Map<String, String> params);

    // 默认只读安全，写操作的工具覆写为 false
    default boolean isConcurrencySafe() {
        return true;
    }
}
```

| 工具 | isConcurrencySafe | 原因 |
|------|-------------------|------|
| getDiff | true | 只读 |
| readFileContent | true | 只读 |
| listChangedFiles | true | 只读 |
| getHistoryReviews | true | 只读（4-B） |
| checkRepeatIssue | true | 只读（4-B） |
| generateFix | true | 只读，仅生成建议文本（4-A） |
| submitReview | false | 写：终止循环 + 写库，必须独占 |
| applyFix | false | 写：创建新 commit，必须独占（4-A） |

> 💡 **Design Note — 只读并行 vs 多 Agent 并行，不要混淆**
> 未来若要并行，指的是**同一个 Agent 循环内**，LLM 一轮同时发出
> 多个只读工具调用（比如 diff 涉及 2 个独立文件，同时
> readFileContent 两次）。这和 Multi-Agent（多个独立 Agent 实例
> 分工协作，如 s06 Subagent）是完全不同的架构复杂度。
> GitNova 当前场景（单一 Code Review 职责）不需要 Multi-Agent，
> 面试时若被问"有没有考虑多 Agent 协作"，可以说"评估过，
> 但单一职责场景下 Multi-Agent 是过度设计，工具级并行已经
> 覆盖了性能优化空间"。

#### Hook 化改造（可选的后续重构）

> 💡 **Design Note — 现在 SPEC 里其实已经有 Hook 的雏形**
> UserPromptSubmit 对应 `buildSystemPrompt()` 动态拼接 repoId/
> commitSha1；PreToolUse 对应 4-A 里"applyFix 前必须用户确认"。
> 只是目前分散在各处判断，不是统一的注册机制。初版先不做这个
> 抽象，把 Agent Loop 跑通是第一优先级，Hook 化是后续可选的
> 重构方向，面试时可以说"当前是内联实现，理解 Hook 模式，
> 工具数量增长后会重构成统一注册"——诚实说明现状比过度设计更好。

#### 具体的 4 个工具

```java
// 工具 1：获取 commit 的 diff 文本
Tool: getDiff
  params: { repoId, commitSha1 }
  returns: diff 文本（文件路径 + 增删行）
  说明: Agent 第一步一定会调这个

// 工具 2：读取完整文件内容
Tool: readFileContent
  params: { repoId, filePath, commitSha1 }
  returns: 文件完整内容
  说明: Agent 需要看上下文时调用（这是 ReAct 的核心价值）

// 工具 3：列出 commit 涉及的文件列表
Tool: listChangedFiles
  params: { repoId, commitSha1 }
  returns: 变更文件路径列表 + 每个文件的变更类型（新增/修改/删除）

// 工具 4：输出最终 review 结果
Tool: submitReview
  params: { repoId, commitSha1, reviewJson }
  returns: "Review submitted"
  说明: Agent 调用此工具 = 表示审查完成，循环终止
```

> 💡 **Design Note — 为什么需要 readFileContent？**
> 这是区分"流水线"和"Agent"的关键。流水线只看 diff，
> Agent 可以主动决定去读完整文件——比如 diff 里改了一个方法的参数，
> Agent 会想"调用方有没有适配？"，然后主动 readFileContent 查看。
> 这个"自主决策是否需要更多信息"的能力，就是 ReAct 的核心价值。

### 4.2 Agent Loop 核心循环

```java
// service/agent/CodeReviewAgentLoop.java

public ReviewResult runAgentLoop(Long repoId, String commitSha1) {
    // 1. 构造初始 messages
    List<Message> messages = new ArrayList<>();
    messages.add(systemMessage(buildSystemPrompt()));
    messages.add(userMessage(
        "请对仓库 " + repoId + " 的 commit " + commitSha1 + " 进行 Code Review。" +
        "先获取 diff，分析变更，必要时查看相关文件上下文，最后提交 review 结果。"
    ));

    // 2. ReAct 循环
    int maxTurns = 10;  // 安全上限，防止无限循环
    for (int turn = 0; turn < maxTurns; turn++) {

        // 调用 LLM（带工具定义）
        LLMResponse response = callLLM(messages, toolDefinitions);

        if (response.hasToolCalls()) {
            // Thought + Action：LLM 决定调用工具
            for (ToolCall call : response.getToolCalls()) {
                // 执行工具
                String result = toolRegistry.execute(call.name(), call.params());

                // 特殊判断：如果调用了 submitReview，循环终止
                if ("submitReview".equals(call.name())) {
                    return parseReviewResult(call.params().get("reviewJson"));
                }

                // Observation：将工具结果追加到 messages
                messages.add(assistantMessage(response));
                messages.add(toolResultMessage(call.id(), result));
            }
        } else {
            // LLM 输出纯文本，没有工具调用 → 异常情况，强制终止
            break;
        }
    }

    // 超过 maxTurns 仍未完成 → 降级处理
    return ReviewResult.fallback("Agent exceeded max turns");
}
```

> ⚠️ **Warning — maxTurns 安全上限必须有**
> LLM 可能陷入循环（反复调同一个工具），不设上限会无限消耗 token。
> 10 轮足够覆盖"getDiff → readFile × 若干 → submitReview"的典型路径。
> 面试时主动说出来："我设了 maxTurns 防止目标漂移和 token 爆炸。"

### 4.3 System Prompt 设计

```
<role>
你是 GitNova 平台的 Code Review Agent，负责审查代码提交。
你使用 ReAct 模式工作：先推理（Thought），再行动（Action），
观察结果（Observation），然后继续推理，直到审查完成。
</role>

<tools>
你可以使用以下工具：
1. getDiff(repoId, commitSha1) — 获取本次提交的 diff
2. readFileContent(repoId, filePath, commitSha1) — 读取文件完整内容
3. listChangedFiles(repoId, commitSha1) — 列出变更文件
4. submitReview(repoId, commitSha1, reviewJson) — 提交最终审查结果
</tools>

<workflow>
推荐工作流程：
1. 先调用 getDiff 获取变更内容
2. 分析 diff：如果变更简单（重命名、注释修改），直接提交 review
3. 如果变更涉及方法签名、核心逻辑、数据库操作，调用 readFileContent
   查看相关文件的完整上下文，确认是否有潜在风险
4. 审查完成后，调用 submitReview 提交结果
</workflow>

<review_focus>
重点关注：命名规范、空指针风险、SQL 注入、并发安全、资源泄漏
</review_focus>

<output_format>
调用 submitReview 时，reviewJson 必须是以下 JSON 格式：
[
  {
    "file": "文件路径",
    "line": 行号,
    "severity": "info|warning|error",
    "comment": "问题描述"
  }
]
如果没有发现问题，传入空数组 []。
</output_format>
```

> 💡 **Design Note — Prompt 里为什么有 <workflow> 标签？**
> ReAct 的 Thought 是 LLM 自由推理，但完全自由容易跑偏。
> <workflow> 给了一个推荐路径作为"软约束"——不是强制步骤，
> 而是引导 Agent 在大多数情况下走一条高效路径。
> 这是 Anthropic 官方推荐的 Prompt 结构化方法。

### 4.4 事件驱动触发（保留 Spring Event 解耦）

```
Phase 3 CAS 更新成功
    │
    ▼
publishEvent(new PostReceiveEvent(repoId, commitSha1))
    │
    ├──► [主线程] 返回 200 OK（不等 Agent）
    │
    └──► [异步线程池] CodeReviewListener
              │
              ├── 调用 CodeReviewAgentLoop.runAgentLoop()
              ├── 结果写入 review_comment 表
              └── WebSocket 推送给仓库成员
```

> 💡 **Design Note — Spring Event + ReAct Agent 的组合**
> Spring Event 解决的是"何时触发"的问题（push 成功后异步触发）。
> ReAct Agent 解决的是"如何审查"的问题（自主推理+工具调用）。
> 两者正交，互不干扰。面试时分开讲，不要混在一起。

### 4.5 diff 超长截断策略

```java
// Agent 调用 getDiff 时，如果 diff 超过 token 上限
private String truncateDiff(String diff, int maxTokens) {
    if (estimateTokens(diff) <= maxTokens) return diff;

    String[] lines = diff.split("\n");
    int headLines = lines.length / 3;
    int tailLines = lines.length / 3;

    return String.join("\n", Arrays.copyOfRange(lines, 0, headLines))
         + "\n\n... [省略 " + (lines.length - headLines - tailLines) + " 行] ...\n\n"
         + String.join("\n", Arrays.copyOfRange(lines, lines.length - tailLines, lines.length));
}
```

> 💡 **Design Note — 为什么取头尾各 1/3？**
> Diff 的头部通常是文件路径和重要的函数签名变更，
> 尾部通常是新增代码。中间往往是重复的上下文行。
> 这个策略保留了最有信息量的部分。
> 面试时能讲清楚这个 trade-off 就够了。

### 4.6 错误处理与降级

```
Agent 失败的四种情况及处理：

1. LLM API 超时/限流
   → 重试 3 次，每次间隔翻倍（1s → 2s → 4s）
   → 3 次都失败 → 标记 review_status = 'failed'，不影响 push

2. LLM 输出不合法 JSON
   → strip ```json 包裹 → 再次尝试解析
   → 仍然失败 → 原文存入 comment，severity = 'info'

3. Agent 超过 maxTurns（10 轮）
   → 将已有的 Thought 记录存入 review_comment
   → 标记 review_status = 'partial'

4. 工具执行异常（ObjectStorage 读取失败）
   → 返回错误信息作为 Observation 给 Agent
   → Agent 自行决定是跳过该文件还是终止审查
   → 这是 ReAct 的优势：Agent 能处理工具失败
```

> ⚠️ **Warning — 所有错误都不能影响 push 结果**
> push 主流程已经在 PostReceiveEvent 发布前返回 200 了，
> Agent 的任何失败只记录日志，不回滚，不报错给用户。

### 4.7 实现顺序与详细步骤

以下按实现依赖关系排列，每步均可独立编译验证。

---

#### Step 1 — DTO 层（零依赖，5 分钟）

**文件**：`dto/` 包下新建 4 个类（已创建，直接填写）

| DTO | 字段 | 说明 |
|-----|------|------|
| `Message` | `role, content, toolCalls, toolCallId` | 对应 API messages[] 的一项 |
| `ToolCall` | `id, name, params` | LLM 决定调工具时的返回结构 |
| `LLMResponse` | `stopReason, text, toolCalls` | 非流式 API 响应解析结果 |
| `ToolDefinition` | `name, description, parametersSchema` | 工具定义，对应 API 的 tools 字段 |

全部用 `@Data` + `@NoArgsConstructor` + `@AllArgsConstructor`，不涉及任何业务依赖。

---

#### Step 2 — AgentTool 接口更新（已完成）

当前接口需要和已创建的骨架一致：`name()` / `description()` / `parametersSchema()` / `execute()` / `isConcurrencySafe()`。

---

#### Step 3 — ToolRegistry.getToolDefinitions()

**当前状态**：骨架已就绪，`getToolDefinitions()` 返回 `List<ToolDefinition>`。

无需修改。每个工具作为 `@Component` 实现 `AgentTool`，Spring 自动收集。

---

#### Step 4 — 4 个工具的参数 Schema（已完成）

4 个工具的 `parametersSchema()` 已补齐，具体参数见各工具类的 Javadoc。

---

#### Step 5 — callLLM() 方法（核心逻辑 · 自己实现）

**文件**：`CodeReviewAgentLoop.java`

**依赖**：OkHttp 4.12.0（已在 `pom.xml` 中）

**实现流程**：

```
callLLM(messages)
    │
    ├── 1. 构造请求体 JSON
    │      {
    │        "model": "deepseek-chat",
    │        "messages": [...],
    │        "tools": toolRegistry.getToolDefinitions()
    │      }
    │
    ├── 2. 发送 HTTP POST
    │      RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
    │      Request request = new Request.Builder()
    │          .url(baseUrl + "/v1/chat/completions")
    │          .addHeader("Authorization", "Bearer " + apiKey)
    │          .addHeader("Content-Type", "application/json")
    │          .post(body)
    │          .build();
    │      Response response = client.newCall(request).execute();
    │
    ├── 3. 解析响应
    │      JsonNode root = objectMapper.readTree(response.body().string());
    │      String finishReason = root.get("choices").get(0).get("finish_reason").asText();
    │
    ├── 4. 生成 LLMResponse
    │      if "tool_calls".equals(finishReason):
    │          → stopReason = "tool_use"
    │          → 遍历 choices[0].message.tool_calls[] 解析 ToolCall 列表
    │      else:
    │          → stopReason = "stop"
    │          → text = choices[0].message.content
    │      return new LLMResponse(stopReason, text, toolCalls);
    │
    └── 5. 错误处理（重试 3 次，间隔 1s → 2s → 4s）
```

**DeepSeek API 响应 JSON 结构**（关键字段）：

```json
{
  "choices": [{
    "finish_reason": "tool_calls",
    "message": {
      "role": "assistant",
      "content": "我需要先获取 diff...",
      "tool_calls": [{
        "id": "call_abc123",
        "type": "function",
        "function": {
          "name": "getDiff",
          "arguments": "{\"repoId\":\"1\",\"commitSha1\":\"abc123\"}"
        }
      }]
    }
  }]
}
```

> ⚠️ **Warning — tool_calls 中 arguments 是 JSON 字符串，不是 JSON 对象**
> DeepSeek API 返回的 `tool_calls[].function.arguments` 是一个 **JSON 字符串**
>（如 `"{\"repoId\":\"1\"}"`），需要用 `objectMapper.readValue()` 二次解析，
> 不能直接当作 Map 使用。

---

#### Step 6 — buildSystemPrompt() 方法

**文件**：`CodeReviewAgentLoop.java`

**输出**：SPEC 4.3 节的 Prompt 模板字符串。用 `StringBuilder` 拼接或 Java 15+ Text Block：

```java
private String buildSystemPrompt() {
    return """
        <role>
        你是 GitNova 平台的 Code Review Agent，负责审查代码提交。
        你使用 ReAct 模式工作：先推理（Thought），再行动（Action），
        观察结果（Observation），然后继续推理，直到审查完成。
        </role>
        
        <tools>
        你可以使用以下工具：
        1. getDiff(repoId, commitSha1) — 获取本次提交的 diff
        2. readFileContent(repoId, filePath, commitSha1) — 读取文件完整内容
        3. listChangedFiles(repoId, commitSha1) — 列出变更文件
        4. submitReview(repoId, commitSha1, reviewJson) — 提交最终审查结果
        </tools>
        
        <workflow>
        推荐工作流程：
        1. 先调用 getDiff 获取变更内容
        2. 分析 diff：如果变更简单（重命名、注释修改），直接提交 review
        3. 如果变更涉及方法签名、核心逻辑、数据库操作，调用 readFileContent
           查看相关文件的完整上下文，确认是否有潜在风险
        4. 审查完成后，调用 submitReview 提交结果
        </workflow>
        
        <review_focus>
        重点关注：命名规范、空指针风险、SQL 注入、并发安全、资源泄漏
        </review_focus>
        
        <output_format>
        调用 submitReview 时，reviewJson 必须是以下 JSON 格式：
        [{"file":"文件路径","line":行号,"severity":"info|warning|error","comment":"问题描述"}]
        如果没有发现问题，传入空数组 []。
        </output_format>
        """;
}
```

---

#### Step 7 — runAgentLoop() 主循环

**文件**：`CodeReviewAgentLoop.java`

**完整伪代码**：

```
runAgentLoop(repoId, commitSha1)
    │
    ├── 1. 构造初始 messages
    │      messages.add(systemMessage(buildSystemPrompt()))
    │      messages.add(userMessage(
    │          "请对仓库 " + repoId + " 的 commit " + commitSha1 + " 进行 Code Review。"
    │          + "先获取 diff，分析变更，必要时查看相关文件上下文，最后提交 review 结果。"))
    │
    ├── 2. ReAct 循环（maxTurns = 10）
    │      for turn = 0..9:
    │          LLMResponse response = callLLM(messages)
    │
    │          if response.hasToolCalls():
    │              构造 assistantMessage（含 toolCalls）→ 加入 messages
    │              for each ToolCall:
    │                  if call.name == "submitReview":
    │                      return call.params.get("reviewJson")   ← 循环终止
    │                  String result = toolRegistry.execute(call.name, call.params)
    │                  构造 toolMessage(call.id, result) → 加入 messages
    │          else:
    │              break  // LLM 输出了纯文本，没有工具调用
    │
    └── 3. 兜底
           log.warn("Agent exceeded maxTurns, returning fallback")
           return "[]"  // 返回空 review
```

**辅助方法**：

```java
// 构造 system message
private Message systemMessage(String content) {
    return new Message("system", content, null, null);
}

// 构造 user message
private Message userMessage(String content) {
    return new Message("user", content, null, null);
}

// 构造 assistant message（含工具调用决策）
private Message assistantMessage(LLMResponse response) {
    return new Message("assistant", response.getText(), response.getToolCalls(), null);
}

// 构造 tool message（工具执行结果作为 Observation）
private Message toolMessage(String toolCallId, String result) {
    return new Message("tool", result, null, toolCallId);
}
```

> ⚠️ **Warning — tool result 消息必须带 toolCallId**
> DeepSeek API 要求每个 `role: "tool"` 的消息必须有 `tool_call_id` 字段，
> 与 `role: "assistant"` 中 `tool_calls[].id` 对应。否则 API 返回 400。

---

#### Step 8 — CodeReviewListener（无需修改）

当前 `CodeReviewListener.onPostReceive()` 已经调用了 `agentLoop.runAgentLoop()`，
骨架就绪，Step 7 完成后自动生效。

---

### 依赖注入速查表（Phase 4）

| 类 | 需要注入的依赖 |
|----|---------------|
| `CodeReviewAgentLoop` | `ToolRegistry`、`@Value llm.*` |
| `ToolRegistry` | `List<AgentTool>`（Spring 自动收集） |
| `GetDiffTool` | `GitletService` |
| `ReadFileContentTool` | `ObjectStorage` |
| `ListChangedFilesTool` | `GitletService` |
| `SubmitReviewTool` | `ReviewCommentMapper`、`ReviewPushHandler`、`ObjectMapper` |
| `CodeReviewListener` | `CodeReviewAgentLoop` |

---

### 实现顺序总览

```
Step 1   DTO 4 个          (5 min  — 填空，无依赖)
Step 2   AgentTool 接口     (已就绪)
Step 3   ToolRegistry       (已就绪)
Step 4   4 工具 parametersSchema (已就绪)
Step 5   callLLM()          (60 min — 核心：OkHttp + JSON 解析 + 重试)
Step 6   buildSystemPrompt  (15 min — Text Block 填 Prompt)
Step 7   runAgentLoop()     (30 min — 主循环 + 4 个辅助方法)
Step 8   CodeReviewListener (已就绪)
```

### Milestone 验收

- push 代码后，WebSocket 收到 review 结果通知
- 简单变更（改变量名）：Agent 只调 getDiff + submitReview（2步）
- 复杂变更（改方法签名）：Agent 主动调 readFileContent 查看上下文（3-4步）
- maxTurns 测试：构造一个极大 diff，验证 Agent 在 10 轮内终止

---

## Phase 4 扩展 — 三个深度方向（按优先级实现）

> 以下三个方向在基础 ReAct Agent 跑通后实现，每个独立可选。
> 建议顺序：4-A → 4-B → 4-C

---

### 4-A：修复建议 + 用户确认 + 自动 Commit【优先级最高】

**核心思想：** 参照 Claude Code 的交互模式——发现问题后不直接修复，
而是告知用户并询问是否接受修复，用户确认后自动创建新 commit。

#### 新增工具

```java
// 工具 5：生成修复代码
Tool: generateFix
  params: { repoId, filePath, commitSha1, issueDescription, lineNumber }
  returns: {
    "originalCode": "原始代码片段",
    "fixedCode":    "修复后的代码片段",
    "explanation":  "为什么这样修复"
  }
  说明: Agent 对 error/warning 级别问题调用此工具生成修复方案

// 工具 6：应用修复并创建新 commit
Tool: applyFix
  params: { repoId, filePath, originalCode, fixedCode, commitMessage }
  returns: { "newCommitSha1": "xxx", "status": "success" }
  说明: 用户确认后调用，通过 Gitlet 引擎创建新 Blob + 新 Commit
```

#### 完整交互流程

```
Agent 完成 review（submitReview 调用后）
    ↓
对每个 severity=error 或 warning 的问题
    ↓
Agent 调用 generateFix() 生成修复方案
    ↓
WebSocket 推送给用户（含修复建议）：

  ┌─────────────────────────────────────────┐
  │ ⚠️  发现问题（warning）                  │
  │  文件：UserService.java 第 23 行          │
  │  问题：user.getId() 存在空指针风险        │
  │                                          │
  │  建议修复：                               │
  │  - 原代码：user.getId()                  │
  │  + 修复为：user != null ? user.getId()   │
  │            : null                        │
  │                                          │
  │  [✅ 接受修复]  [❌ 忽略]  [📖 查看详情]  │
  └─────────────────────────────────────────┘
    ↓ 用户点击 [✅ 接受修复]
    ↓
前端发送 POST /api/repos/{repoId}/review/apply
body: { reviewCommentId, accepted: true }
    ↓
后端调用 applyFix() 工具
    ↓
Gitlet 引擎：
  1. 读取原文件 Blob
  2. 替换 originalCode → fixedCode
  3. 创建新 Blob（新 SHA-1）
  4. 创建新 Commit（message: "fix: Agent自动修复 - 空指针风险"）
  5. CAS 更新 HEAD
    ↓
WebSocket 推送："✅ 修复已应用，新 commit: abc123"
```

> 💡 **Design Note — 为什么修复要走完整的 commit 流程？**
> 不是直接覆盖文件，而是通过 Gitlet 引擎创建新 Blob 和 Commit，
> 修复记录完整保留在版本历史中，用户可以随时 revert。
> 这和 Claude Code 的行为一致——AI 的操作必须可追溯、可撤销。
> 面试时主动说出来：这是 AI 工具的工程化原则。

> ⚠️ **Warning — generateFix 的局限性**
> LLM 生成的修复代码不一定正确，可能引入新问题。
> 必须告知用户"这是 AI 建议，请人工确认后再接受"。
> 实现时在 WebSocket 消息里加免责说明，面试时主动说出这个边界。

#### 新增接口

```
POST /api/repos/{repoId}/review/apply
  body: { reviewCommentId: Long, accepted: boolean }
  功能: 用户点击接受/忽略后调用
  逻辑: accepted=true → 调 applyFix → 创建 commit
        accepted=false → 更新 review_comment.status = 'ignored'
```

#### 新增数据库字段

```sql
ALTER TABLE review_comment ADD COLUMN fix_suggestion TEXT;
    -- generateFix 返回的 JSON 存这里

ALTER TABLE review_comment ADD COLUMN status
    ENUM('pending', 'accepted', 'ignored') DEFAULT 'pending';
    -- 用户决策状态
```

---

### 4-B：跨 Commit 历史感知【记忆系统】

**核心思想：** Agent 不只看当前 commit，还能感知同一文件的历史
review 记录，对重复出现的问题升级严重程度。

> 💡 **Design Note — 这对应 Agent 面试的记忆系统考点**
> Working Memory（当前 diff）+ Episodic Memory（历史 review 记录）。
> 面试官问"你的 Agent 有没有记忆？"，这就是你的答案。

#### 新增工具

```java
// 工具 7：查询文件的历史 review 记录
Tool: getHistoryReviews
  params: { repoId, filePath, limit=3 }
  returns: 最近 N 次 commit 中该文件的 review_comment 列表
  说明: Agent 在 readFileContent 后可选调用，感知历史问题

// 工具 8：查询某问题是否曾经出现过
Tool: checkRepeatIssue
  params: { repoId, filePath, issueKeyword }
  returns: { "repeatCount": 2, "lastCommit": "abc123", "lastTime": "..." }
  说明: Agent 发现问题后查询是否是重复问题
```

#### Agent 推理示例

```
Thought: "getDiff 显示 UserService.java 第23行有空指针风险"
Action:  checkRepeatIssue(repoId, "UserService.java", "空指针")
Obs:     { repeatCount: 2, lastCommit: "def456" }
Thought: "这个问题已经在过去2次review里提过了，
          开发者没有修复，应该升级 severity 为 error
          并在 comment 里标注这是重复问题"
Action:  submitReview([{file, line, severity: "error",
          comment: "⚠️ 重复问题（已在2次review中提出）：..."}])
```

#### System Prompt 补充

```
<memory_instruction>
在发现问题时，你可以调用 checkRepeatIssue 查询历史。
如果一个问题已经出现过 2 次或以上且未被修复：
- 将 severity 升级为 error（即使原本是 warning）
- 在 comment 中注明"重复问题，已提出 N 次"
这体现了你对开发者行为的感知能力。
</memory_instruction>
```

---

### 4-C：Reflection 自评机制【最高阶】

**核心思想：** Agent 在 submitReview 之前，先对自己的 review 结果
做一次自我批评，减少误报、补充遗漏，再输出最终结果。

> 💡 **Design Note — Reflection vs ReAct**
> ReAct 是"行动→观察→再行动"的外部循环。
> Reflection 是"输出→自我批评→修正输出"的内部循环。
> 两者组合使用，面试时能讲清楚区别是真正理解了 Agent 设计范式。

#### 实现方式

不新增工具，在 Agent Loop 里加一个 Reflection 轮次：

```java
// CodeReviewAgentLoop.java 修改

// 第一阶段：正常 ReAct 循环，得到初步 review 结果
ReviewDraft draft = runReActLoop(repoId, commitSha1, messages);

// 第二阶段：Reflection——让 LLM 批评自己的结果
messages.add(userMessage(
    "你刚才生成了以下 review 结果：\n" + draft.toJson() + "\n\n" +
    "请批判性地评估：\n" +
    "1. 有没有误报？（把正常代码当问题）\n" +
    "2. 有没有遗漏明显的问题？\n" +
    "3. severity 评级是否准确？\n\n" +
    "如果需要修正，请调用 submitReview 提交修正后的结果；" +
    "如果结果准确，直接回复 'LGTM' 即可。"
));

LLMResponse reflection = callLLM(messages, toolDefinitions);

if (reflection.hasToolCalls()) {
    // LLM 决定修正，执行新的 submitReview
    return executeSubmitReview(reflection.getToolCalls());
} else {
    // LLM 说 LGTM，直接用第一阶段结果
    return draft.toResult();
}
```

> ⚠️ **Warning — Reflection 会增加 token 消耗约 50%**
> 对于简单变更（改变量名），Reflection 意义不大反而浪费。
> 建议只对 error 级别问题触发 Reflection，info 级别跳过。
> 面试时主动说出这个 trade-off：这是 Cost Engineering 的考量。

---

## 更新后的工具注册表全览（v3.4）

| 工具编号 | 工具名 | 所属方向 | 说明 |
|----------|--------|----------|------|
| 1 | getDiff | 基础 | 获取 commit diff |
| 2 | readFileContent | 基础 | 读取完整文件 |
| 3 | listChangedFiles | 基础 | 列出变更文件 |
| 4 | submitReview | 基础 | 提交 review 结果 |
| 5 | generateFix | 4-A | 生成修复代码 |
| 6 | applyFix | 4-A | 应用修复创建 commit |
| 7 | getHistoryReviews | 4-B | 查询历史 review |
| 8 | checkRepeatIssue | 4-B | 检查重复问题 |

Reflection（4-C）不新增工具，在 Agent Loop 里加 Reflection 轮次。

---

## 面试升级版话术（v3.4）

> "在 ReAct 基础上扩展了三个方向：
> 一是修复建议闭环——Agent 发现问题后生成修复代码，
> 用户确认后通过 Gitlet 引擎创建新 commit，修复可追溯可撤销；
> 二是历史感知记忆——Agent 能查询同一文件的历史 review 记录，
> 对重复出现的未修复问题自动升级 severity，实现了 Episodic Memory；
> 三是 Reflection 自评——在提交 review 前对结果做一轮自我批评，
> 减少误报，但只对 error 级别触发以控制 token 成本。"

---

### 目标：产出可以写进简历的数字指标

**JMeter 压测脚本设计：**

```
场景 1：并发登录
  - 线程数：200，Ramp-Up：10s
  - 目标指标：QPS > 500，Error Rate < 1%

场景 2：并发 commit（核心接口）
  - 线程数：50，循环 10 次
  - 目标指标：P99 < 200ms

场景 3：文件传输吞吐量
  - 构造 10MB 测试文件，单线程连续 push
  - 目标指标：吞吐量 > 50 MB/s（本地测试）

场景 4：CAS 并发冲突率验证
  - 50 线程同时向同一仓库 push
  - 验证：有且仅有 1 个成功，49 个返回 409
```

> ⚠️ **Warning — 数字要真实**
> 以上是目标值，以实测结果为准。简历上写的数字必须是你能解释的，
> 面试官会追问"你怎么测的"、"瓶颈在哪里"。

**README 必须包含：**
- 架构图（用 Mermaid 或手绘截图）
- 模块说明（每个 Phase 一段）
- 快速启动命令（`docker-compose up` 或手动步骤）
- Swagger 文档地址

---

## Phase 6 — WebSocket 通知（Phase 4 配套，Week 4 末）

```java
// push 成功 + Agent review 完成后，向仓库成员推送：
{
  "event": "review_ready",
  "repoId": 123,
  "commitSha1": "abc123",
  "pusher": "zhaoguodong",
  "commentsCount": 3,
  "timestamp": "2025-xx-xx"
}
```

---

## 高频追问 & 准备答案

| 追问 | 核心答案 |
|------|----------|
| 对象协商为什么不用 have/want 多轮交互？ | 简化场景下，单次全量上报 SHA-1 列表（非文件内容）开销极小，一次 RTT 完成协商更简单可控 |
| CAS 失败了客户端怎么办？ | 客户端收到 409 后，先 pull 拿到最新 HEAD，本地与自己的修改合并，再重新 push |
| 不支持 merge 不是很残缺吗？ | 这是有意为之的 fast-forward only 策略，GitHub 默认的 protected branch 也是这个模式，强迫线性历史 |
| Agent review 挂了影响 push 吗？ | 不影响，Spring Event 异步处理，主流程已返回 200；Agent 失败仅记录错误日志，不回滚 push |
| 为什么用 ReAct 而不是固定流水线？ | 流水线对所有变更做同等深度审查，ReAct 让 Agent 自主决定是否查看上下文文件，模拟人类工程师根据变更复杂度自适应调整审查深度的行为 |
| Agent 陷入循环怎么办？ | maxTurns=10 硬上限，超过后降级为 partial review；同时在 Prompt 的 workflow 里给了推荐路径作为软约束 |
| ReAct 的 Thought-Action-Observation 循环怎么实现？ | LLM 带工具定义调用，返回 tool_calls 就执行工具并追加结果继续循环，返回纯文本就终止；和 Claude Code 的 Agent Loop 是同一个模式 |
| 工具调用失败了 Agent 怎么处理？ | 错误信息作为 Observation 返回给 Agent，Agent 自行决定跳过该文件或终止审查——这是 ReAct 优于流水线的地方，流水线遇到错误只能整体失败 |
| 为什么不用 LangChain4j？ | 4 个工具 + 1 个循环，手写 200 行代码就够了；引入 LangChain4j 反而增加了一个黑盒依赖，面试时讲不清楚底层原理 |
| Gitlet 和真实 Git 的核心差异？ | 无 packfile 压缩、无 reflog、无 remote tracking branch，但 Blob/Tree/Commit 对象模型一致 |
| 为什么不直接用 JGit？ | 核心引擎自实现是项目壁垒，能讲清楚 DAG/CAS 等底层原理；JGit 是黑盒，用了就没故事可讲 |
| ThreadLocal 有什么风险？ | 线程池复用会导致数据污染，必须在拦截器 `afterCompletion` 中 `remove()`；已处理 |
| 存储层为什么用策略模式？ | 面向 ObjectStorage 接口编程，本地磁盘和 MinIO 是两个实现，切换时业务代码零修改，符合开闭原则 |
| 现在是本地存储，怎么平滑迁移到 OSS？ | 实现 MinioObjectStorage，application.yml 改 storage.type=minio，重启即切换，存量数据做一次迁移脚本同步即可 |

---

## 开发时间线

```
Week 1  Spring Boot 骨架 + JWT 拦截器 + Gitlet 引擎接入 + 仓库 CRUD
Week 2  对象协商（Negotiate）+ 增量打包传输（Transfer）
Week 3  CAS 并发控制 + non-fast-forward 处理 + commit_record 落库
Week 4  Spring Event 解耦 + Code Review Agent + WebSocket 推送
Week 5  JMeter 压测 + Swagger 文档 + README 架构图
Week 6  7-B Commit Message Agent → 7-A 完善 → 7-C RAG（按需）
```

---

## 简历描述模板

```
GitNova · 智能代码托管与审查平台                       2025.XX — 2025.XX
技术栈：Spring Boot · MySQL · WebSocket · JWT · DeepSeek API

• 复用 CS61B Gitlet 自实现引擎（SHA-1 内容寻址、DAG 提交图、
  分支指针管理），封装为内部 GitletService，通过 RESTful 接口
  暴露 init / commit / branch / checkout 等核心命令
• 设计简化版 Git Smart Protocol：push 前两步 HTTP 协商确定服务端
  缺失的对象集合，仅传输 missingObjects，实测修改单行代码时
  传输对象数由全量降至 2 个（新 Blob + 新 Commit）
• 基于数据库 CAS 实现无锁并发控制：HEAD 指针更新时校验
  baseHeadSha1，并发冲突时返回 non-fast-forward 错误，
  压测 50 并发 push 场景下冲突检测准确率 100%
• 基于 ReAct + Reflection 范式实现自适应 Code Review Agent：
  手写 Agent Loop + 8 个工具，Agent 自主决定审查深度；
  引入 Episodic Memory 感知历史 review 记录，重复问题自动升级
  severity；发现问题后生成修复建议，用户确认后通过 Gitlet 引擎
  创建新 commit，修复全程可追溯可撤销；通过 WebSocket 实时推送
• JMeter 压测：并发 50 用户 commit 场景 P99 < 200ms，
  登录接口 QPS > 500
```

---

---

## 开发环境配置 — 双机工作流（Win + Mac）

> 💡 **Design Note — 核心原则**
> 环境配置不依赖本机，全部容器化或工具链锁定版本。
> 目标：两台机器 `git pull` 后能立即启动，无需任何手动配置。

### 一、JDK 版本统一

两台机器安装同一版本 JDK，推荐 **Eclipse Temurin 21**（LTS）。

验证两台输出完全一致：

```bash
java -version
# 期望：openjdk version "21.x.x" ...
```

**Maven 用项目内的 Wrapper，不依赖本机 Maven：**

```bash
# Mac / Linux
./mvnw spring-boot:run

# Windows
mvnw.cmd spring-boot:run
```

Spring Initializr 生成的项目自带 `mvnw` / `mvnw.cmd`，直接使用，
两台机器 Maven 版本永远一致。

---

### 二、中间件容器化（Docker Compose）

不在本机直接安装 MySQL，统一用 Docker 容器，保证两台机器环境完全一致。

项目根目录放 `docker-compose.yml`：

```yaml
version: '3.8'
services:
  mysql:
    image: mysql:8.0
    container_name: gitnova-mysql
    environment:
      MYSQL_ROOT_PASSWORD: ${DB_ROOT_PASSWORD:-gitnova123}
      MYSQL_DATABASE: gitnova
    ports:
      - "3306:3306"
    volumes:
      - gitnova-mysql-data:/var/lib/mysql
      - ./src/main/resources/sql/init.sql:/docker-entrypoint-initdb.d/init.sql
    restart: unless-stopped

volumes:
  gitnova-mysql-data:
```

两台机器启动方式完全相同：

```bash
docker-compose up -d        # 后台启动
docker-compose down         # 停止（数据保留在 volume）
docker-compose down -v      # 停止并清空数据（慎用）
```

> ⚠️ **Warning — Windows 上的 Docker**
> Windows 使用 Docker Desktop，需开启 WSL2 后端。
> 若公司网络有代理，需在 Docker Desktop 的 Settings → Resources →
> Proxies 里配置，否则镜像拉取会超时。

---

### 三、配置文件解耦（环境变量）

`application.yml` 中敏感信息全部用环境变量占位，不硬编码：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/gitnova?useUnicode=true&characterEncoding=utf8
    username: root
    password: ${DB_PASSWORD:gitnova123}   # 冒号后为默认值

gitnova:
  jwt:
    secret: ${JWT_SECRET:dev-secret-key-change-in-prod}
    expire: 86400000
  repo:
    base-path: ${REPO_BASE_PATH:/data/gitnova-repos}   # 两台机器可以设不同路径
  llm:
    api-key: ${LLM_API_KEY:}
    base-url: ${LLM_BASE_URL:https://api.deepseek.com}
```

**在 IDE 中设置环境变量（不写入代码）：**

IntelliJ IDEA → Run/Debug Configurations → Environment variables：

```
DB_PASSWORD=gitnova123
JWT_SECRET=your-local-secret
REPO_BASE_PATH=C:\gitnova-repos        # Win 本
REPO_BASE_PATH=/Users/xxx/gitnova-repos  # Mac
LLM_API_KEY=sk-xxxxxxxx
```

> 💡 **Design Note — REPO_BASE_PATH 两台机器设不同值完全没问题**
> 仓库文件存本地磁盘，不进 Git 仓库。Win 用 Windows 路径，
> Mac 用 Unix 路径，互不影响，数据库里存的是相对路径 key
>（`{ownerId}/{repoName}`），拼接时由 `REPO_BASE_PATH` 决定绝对路径。

---

### 四、.gitignore 必备条目

```gitignore
# 编译产物
target/

# IDE 配置（各自本地，不共享）
.idea/
*.iml
.vscode/

# Mac 系统文件（Win 不生成，但提交了会污染仓库）
.DS_Store

# 本地覆盖配置（如果用到）
application-local.yml
application-local.yaml

# 仓库数据目录（不进 Git）
gitnova-repos/
data/

# 环境变量文件（如果用 .env）
.env
.env.local
```

---

### 五、双机 Git 工作流纪律

```
换机器前（必做）：
  git add .
  git commit -m "wip: xxx"
  git push

开机后（必做）：
  git pull

提交规范（小步 commit，让 GitHub 提交记录连续）：
  feat: 实现 JWT 拦截器
  feat: 完成仓库 CRUD 接口
  fix: 修复 ThreadLocal 未清理问题
  test: 添加 CAS 并发测试用例
```

> ⚠️ **Warning — 不要用 WIP commit 当最终记录**
> `wip:` 前缀的 commit 是换机器时的临时保存，
> 在当前机器继续开发时用 `git commit --amend` 或
> `git rebase -i` 整理成有意义的 commit message，
> 再 push。面试官会看 GitHub commit 记录。

---

### 六、骨架搭建流程（AI 辅助）

**Step 1 — Spring Initializr 生成基础结构**

访问 `start.spring.io`，选择：

```
Project:      Maven
Language:     Java
Spring Boot:  3.x（选最新稳定版）
Group:        com.gitnova
Artifact:     gitnova
Dependencies: Spring Web
              MyBatis-Plus（搜索 mybatis）
              MySQL Driver
              Lombok
              WebSocket
              Validation
```

下载解压，这是项目起点。

**Step 2 — 让 AI 生成包结构和空类**

将本 SPEC 的「项目结构（Skeleton）」章节贴给 Claude Code 或 Cursor，
要求：

```
根据以下项目结构，帮我：
1. 创建所有 package 目录
2. 生成所有空类（类名 + 注解 + 空方法签名，不填实现）
3. 生成 src/main/resources/sql/init.sql 建表语句
4. 生成 docker-compose.yml
```

**Step 3 — 核心逻辑自己写（不能 AI 代劳）**

以下模块必须自己实现，能在面试中逐行解释：

| 模块 | 原因 |
|------|------|
| `GitletService` 路径隔离逻辑 | 面试必问：怎么保证仓库隔离 |
| JWT 拦截器 ThreadLocal 生命周期 | 面试必问：ThreadLocal 有什么风险 |
| CAS SQL + 异常处理 | 面试必问：并发冲突怎么处理 |
| 对象协商核心算法 | 面试必问：为什么不用 have/want 多轮 |
| Spring Event 解耦设计 | 面试必问：为什么不直接 @Async |

> ⚠️ **Warning — AI 生成的代码必须能逐行解释**
> 面试官不在乎代码是不是你一个字一个字敲的，
> 但在乎你能不能讲清楚每个设计决策。
> 用 AI 生成骨架没问题，但每一行都要真正理解。

---

*SPEC v3.6 · 补充 isConcurrencySafe 判断标准 + Hook 化改造说明 · s01-s04 学习完整对齐*
