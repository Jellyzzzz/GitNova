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
> 核心流程，以 Hook 插件形式集成 LLM Agent，实现异步流式代码
> 审查推送。"

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
| Agent          | 直调 OpenAI-Compatible API（DeepSeek/通义）|
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
│   │       ├── CodeReviewAgent.java
│   │       ├── CommitMessageAgent.java
│   │       └── RepoQAAgent.java
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

### 接口清单

```
POST /api/repos/{repoId}/push/negotiate   # 对象协商
POST /api/repos/{repoId}/push/transfer     # 增量传输
```

### 基础设施类（优先实现）

以下两个类的实现是 Phase 2 接口的前置依赖，必须最先完成：

---

#### 2-A. LocalObjectStorage — ObjectStorage 磁盘实现

**文件位置**：`com.gitnova.storage.LocalObjectStorage`（骨架已创建，`@ConditionalOnProperty` 已配置）

**存储布局**：扁平目录，不区分 blobs/commits——

```
{REPO_BASE_PATH}/{repoKey}/.gitlet/objects/{sha1}
```

> Gitlet 自己维护 `objects/blobs/` 和 `objects/commits/` 两套目录，
> 与 ObjectStorage 的扁平 `objects/` 目录互不干扰。切换 MinIO 时
> 只需改 `application.yml` 的 `gitnova.storage.type=minio`。

**四个方法实现**：

```java
@Component
@ConditionalOnProperty(name = "gitnova.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalObjectStorage implements ObjectStorage {

    @Value("${gitnova.repo.base-path}")
    private String basePath;

    private File resolveObjectFile(String repoKey, String sha1) {
        return Utils.join(basePath, repoKey, ".gitlet", "objects", sha1);
    }

    @Override
    public void writeObject(String repoKey, String sha1, byte[] content) {
        File file = resolveObjectFile(repoKey, sha1);
        if (!file.getParentFile().exists()) { file.getParentFile().mkdirs(); }
        Utils.writeContents(file, (Object) content);
    }

    @Override
    public byte[] readObject(String repoKey, String sha1) {
        File file = resolveObjectFile(repoKey, sha1);
        if (!file.exists()) throw new GitletException("Object not found: " + sha1);
        return Utils.readContents(file);
    }

    @Override
    public boolean existsObject(String repoKey, String sha1) {
        return resolveObjectFile(repoKey, sha1).exists();
    }

    @Override
    public Set<String> listObjects(String repoKey) {
        File dir = Utils.join(basePath, repoKey, ".gitlet", "objects");
        List<String> files = Utils.plainFilenamesIn(dir);
        return files != null ? new HashSet<>(files) : Collections.emptySet();
    }
}
```

> ⚠️ **Warning — writeObject 需要 mkdirs**
> `{REPO_BASE_PATH}/{repoKey}/.gitlet/objects/` 目录链可能全部不存在，
> 必须在写入前 `mkdirs()`。

---

#### 2-B. ObjectNegotiationService — 依赖更新

**文件位置**：`com.gitnova.service.ObjectNegotiationService`

当前骨架只注入了 `GitletService`，Phase 2 需要**加注 `ObjectStorage`**：

```java
@Service
public class ObjectNegotiationService {

    private final ObjectStorage objectStorage;   // existsObject() 逐条比对 —— 新增
    private final GitletService  gitletService;  // getHeadSha1() 取远端 HEAD —— 现有

    public ObjectNegotiationService(ObjectStorage objectStorage,
                                     GitletService gitletService) {
        this.objectStorage = objectStorage;
        this.gitletService = gitletService;
    }
    // ...
}
```

---

### 接口详细 Spec

---

#### 1. POST /api/repos/{repoId}/push/negotiate — 对象协商

**Controller**：`TransferController.negotiate()`
**Service**：`ObjectNegotiationService.negotiate()`
**鉴权**：✅ 需要有效 JWT + 仓库成员

**请求体（`PushRequest`，已有骨架）**：

```json
{
  "localHeadSha1": "abc123...",         // 客户端当前 HEAD
  "localObjects": ["sha1_a", "sha1_b"]  // 客户端本地全部对象的 SHA-1
}
```

> 这是纯字符串数组，每个元素只有 40 个十六进制字符（20 bytes），
> 不包含文件内容。10000 个对象 ≈ 400 KB。

**Service 层实现流程**：

```
negotiate(repoKey, request)
    │
    ├── 1. 取服务端当前 HEAD
    │      String remoteHead = gitletService.getHeadSha1(repoKey);
    │      // null = 空仓库（无任何 commit），此时所有客户端对象都标记 missing
    │
    ├── 2. 比对对象库
    │      List<String> missing = new ArrayList<>();
    │      for (String sha1 : request.getLocalObjects()) {
    │          if (!objectStorage.existsObject(repoKey, sha1))
    │              missing.add(sha1);
    │      }
    │      // 只需检查文件是否存在，无需读取内容
    │
    └── 3. 返回
           return new NegotiationResponse(remoteHead, missing);
```

**返回类型设计**：

| 层 | 返回类型 | 说明 |
|----|----------|------|
| `ObjectNegotiationService.negotiate()` | `NegotiationResponse` | 类型安全，只关心数据 |
| `TransferController.negotiate()` | `ApiResponse<NegotiationResponse>` | 包装 code/message，统一 API 格式 |

Controller 用 `ApiResponse.success(negotiationResponse)` 包裹，客户端最终收到：

```json
{ "code": 200, "message": "success", "data": { "remoteHeadSha1": "...", "missingObjects": [...] } }
```

**响应格式**：

```json
// 有差异 — 200
{
  "code": 200, "message": "success",
  "data": { "remoteHeadSha1": "def456...", "missingObjects": ["sha1_b"] }
}

// 完全同步 — 200（missingObjects 为空，客户端跳过 Step 2）
{
  "code": 200, "message": "success",
  "data": { "remoteHeadSha1": "def456...", "missingObjects": [] }
}

// 空仓库 — 200（remoteHeadSha1 = null）
{
  "code": 200, "message": "success",
  "data": { "remoteHeadSha1": null, "missingObjects": ["sha1_a", "sha1_b", "..."] }
}
```

**错误码**：

| 情况 | HTTP | message |
|------|------|---------|
| repoId 不存在 | 404 | "仓库不存在" |
| 当前用户非仓库成员 | 403 | "无权访问该仓库" |
| localObjects 为空列表 | 200 | missingObjects 返回 `[]` |

> 💡 **Design Note — 为什么让客户端发送所有对象列表？**
> 真实 Git 用 `have`/`want` 多轮交互缩小范围，对于我们的简化场景，
> 单次全量上报 SHA-1 列表开销极小，一次 RTT 完成协商，更简单可控。

---

#### 2. POST /api/repos/{repoId}/push/transfer — 增量传输

**Controller**：`TransferController.transfer()`
**Service**：`TransferService.unpackAndStore()`
**鉴权**：✅ 需要有效 JWT + 仓库成员
**Content-Type**：`multipart/form-data`

**请求格式（multipart）**：

| Part 名 | 类型 | 说明 |
|---------|------|------|
| `metadata` | JSON 字符串 | `TransferMetadata`：`{ newHeadSha1, baseHeadSha1, branchName, commitMessage }` |
| `objects` | 二进制文件 | 自定义打包格式（仅含 missingObjects 中的对象） |

**打包格式设计**：

```
[4 bytes: 对象数量 N (big-endian)]
for each object:
    [40 bytes: SHA-1 十六进制字符串]
    [8 bytes: 内容长度 L (big-endian)]
    [L bytes: 对象内容]
```

> 💡 **Design Note — 为什么自定义格式？**
> 零依赖、格式极简（面试可手绘结构图）、SHA-1 前置支持流式校验。

**Service 层实现流程**：

```
unpackAndStore(repoKey, packBytes)
    │
    ├── 1. 解析包头
    │      ByteBuffer buf = ByteBuffer.wrap(packBytes);
    │      int n = buf.getInt();  // 对象数量（big-endian）
    │      if (n == 0) return 0;  // 空包，直接返回
    │      if (n > maxObjectsPerPush) → 400 "单次 push 对象数超过限制"
    │
    ├── 2. 第一遍遍历 — 全量校验 SHA-1（不写入）
    │      for i = 0..n-1:
    │          byte[] sha1Bytes = new byte[40]; buf.get(sha1Bytes);
    │          long len = buf.getLong();        // 8 字节 → content 长度
    │          if (len > maxObjectSize) → 400 "对象 {sha1} 超过大小限制"
    │          byte[] content = new byte[(int)len]; buf.get(content);
    │
    │          String declared = new String(sha1Bytes, UTF_8);
    │          String actual   = Utils.sha1(content);
    │          if (!actual.equals(declared)) → 400 "SHA-1 mismatch: " + declared
    │
    ├── 3. 第二遍遍历 — 写入 ObjectStorage
    │      buf.rewind(); buf.getInt();  // 重置读取位置
    │      for i = 0..n-1:
    │          // 同上读取 sha1Bytes / len / content
    │          objectStorage.writeObject(repoKey, declared, content);
    │
    └── 4. 返回
           return n;  // 写入对象数量
```

> ⚠️ **Warning — 必须先全校验再写入，不能边校验边写入**
> 边校验边写入会导致中间报错时对象库处于无法回滚的脏状态。
> 两遍遍历的内存开销可接受（N 个 SHA-1 字符串 + content 长度元数据 ≈ 几 KB）。

> ⚠️ **Warning — unpackAndStore 不负责 CAS 更新 HEAD**
> 这是 Phase 3 的职责。`TransferController.transfer()` 在 `unpackAndStore`
> 成功后调用 `transferService.updateHead(...)`，两者在同一 Controller 方法中
> 顺序调用，Phase 3 会加 `@Transactional`。

**Controller 层流程**：

```
transfer(repoId, metadataJson, objectsFile)
    │
    ├── 1. 解析 metadata
    │      TransferMetadata meta = objectMapper.readValue(metadataJson, TransferMetadata.class);
    │      // meta: { newHeadSha1, baseHeadSha1, branchName="main", commitMessage }
    │
    ├── 2. 权限校验 + 路径解析
    │      Long userId = UserContext.getUserId();
    │      Repository repo = repositoryMapper.selectById(repoId);
    │      if (repo == null) → 404
    │      // 校验 repo_member 中存在 (repoId, userId) → 403 若不在
    │      String repoKey = Utils.join(String.valueOf(repo.getOwnerId()),
    │                                   String.valueOf(repoId)).getPath();
    │
    ├── 3. 解包 + 写入对象
    │      int count = transferService.unpackAndStore(repoKey, objectsFile.getBytes());
    │
    ├── 4. CAS 更新 HEAD（进入 Phase 3）
    │      transferService.updateHead(repoId, meta.getBaseHeadSha1(), meta.getNewHeadSha1(),
    │                                  meta.getBranchName(), meta.getCommitMessage(), userId);
    │
    └── 5. 返回
           return ApiResponse.success(Map.of("newHeadSha1", meta.getNewHeadSha1(),
                                              "objectsStored", count));
```

> 🚫 **不要让客户端传 repoKey**——从 `repoId` 查库拼接，防止路径伪造访问他人仓库。

**响应格式**：

```json
// 成功 200
{ "code": 200, "message": "success",
  "data": { "newHeadSha1": "abc123...", "objectsStored": 2 } }

// SHA-1 校验失败 400
{ "code": 400, "message": "SHA-1 mismatch: declared=abc123, actual=def456" }

// 非成员 403
{ "code": 403, "message": "无权访问该仓库" }
```

**错误码**：

| 情况 | HTTP | message |
|------|------|---------|
| metadata JSON 解析失败 | 400 | "metadata 格式错误" |
| 对象数量为 0 | 200 | 空 push，跳过 CAS |
| 对象数量超限（> maxObjectsPerPush） | 400 | "单次 push 对象数超过限制" |
| SHA-1 不匹配 | 400 | "SHA-1 mismatch: {sha1}" |
| 单个对象超限（> maxObjectSize） | 400 | "对象 {sha1} 大小超过限制" |
| repoId 不存在 | 404 | "仓库不存在" |
| 非仓库成员 | 403 | "无权访问该仓库" |

---

### 新增 DTO

```java
// NegotiationResponse.java  —  dto 包下新建
@Data
public class NegotiationResponse {
    private String remoteHeadSha1;       // null = 空仓库，无任何 commit
    private List<String> missingObjects; // [] = 完全同步，跳过 Step 2
}

// TransferMetadata.java  —  dto 包下新建
@Data
public class TransferMetadata {
    private String newHeadSha1;
    private String baseHeadSha1;
    private String branchName = "main";
    private String commitMessage;
}
```

---

### 全局配置补充

在 `application.yml` 中 Phase 1 已添加（确认存在即可）：

```yaml
gitnova:
  storage:
    type: local               # local | minio（未来扩展）
    max-object-size: 524288000  # 500 MB
  transfer:
    max-objects-per-push: 10000
    chunk-size: 4096
```

---

### Milestone 验收

- 空仓库 push：`remoteHeadSha1=null`，所有客户端对象在 missingObjects 中
- 修改一行代码后 push：传输 2 个对象（1 个新 Blob + 1 个新 Commit），日志验证非全量
- 无变更再 push：missingObjects 为空，不进入 transfer
- 篡改 SHA-1 后 push：返回 400 + 具体错误 SHA-1
- 非仓库成员 push：403

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

## Phase 4 — 事件驱动与 Agent Hook（Week 4）【亮点展示】

### 目标

用 Spring Event 解耦 push 主流程与 Agent 处理，
实现工程化的异步 Code Review 流水线。

### 事件流

```
Phase 3 CAS 更新成功
    │
    ▼
ApplicationEventPublisher.publishEvent(new PostReceiveEvent(repoId, commitSha1))
    │
    ├──► [主线程] 返回 push 成功响应（200 OK）—— 不等 Agent 结果
    │
    └──► [异步线程池] CodeReviewListener.onPostReceive()
              │
              ├── 1. GitletService.getDiff(repoId, commitSha1) → diff 文本
              ├── 2. 构造 Prompt（要求 JSON 输出，见下）
              ├── 3. 调用 LLM API（流式响应）
              ├── 4. 解析 JSON → 写入 review_comment 表
              └── 5. WebSocket 推送给仓库成员
```

> 💡 **Design Note — 为什么用 Spring Event 而非直接异步调用？**
> `@Async` 直接调用也能异步，但 Spring Event 的优势是解耦：
> push 主流程完全不知道 Agent 的存在，未来加新的 Hook
> （比如 CI 触发、Webhook 通知）只需新增 Listener，
> 不用改 TransferService 一行代码。这是开闭原则的实际应用，
> 面试时主动提出来。

### Prompt 模板

```
你是一个资深 Java 工程师，请对以下代码变更进行 Code Review。
重点关注：命名规范、潜在空指针、SQL 注入风险、逻辑漏洞。

请严格以 JSON 数组格式返回，不要输出任何其他内容：
[
  {
    "file": "文件路径",
    "line": 行号（整数）,
    "severity": "info|warning|error",
    "comment": "具体问题描述"
  }
]

<diff>
{diff_content}
</diff>
```

> ⚠️ **Warning — LLM 输出不可信**
> 即使 Prompt 要求 JSON，LLM 仍可能输出 Markdown 代码块包裹的 JSON
> 或完全非法的格式。解析前必须：
> 1. Strip ```json ... ``` 包裹
> 2. 用 try-catch 包裹 JSON 解析
> 3. 解析失败时将原始文本存入 `comment` 字段，`severity` 标为 `info`
> 4. 不要因为 Agent 失败而影响 push 结果

### 三个 Agent 方向（按优先级）

**7-A：Code Review Agent**（推荐首选，Week 4 完成）
- 触发：push 成功后异步
- 产出：`review_comment` 表写入 + WebSocket 推送

**7-B：Commit Message 生成 Agent**（最轻量，可提前做）
- 触发：`POST /api/repos/{repoId}/suggest-message`
- 输入：当前 staged 文件 diff
- 产出：返回建议的 commit message 字符串

**7-C：仓库问答 Agent / RAG**（彩蛋，有余力再做）
- 触发：`POST /api/repos/{repoId}/chat`
- 输入：用户自然语言提问
- 产出：基于仓库代码上下文的回答
- 实现：代码文件分块 → Embedding → VectorStore（本地 Chroma）→ RAG

> 💡 **建议实现顺序：7-B → 7-A → 7-C**
> 7-B 半天能跑通，建立信心；7-A 是简历主打；7-C 是演示彩蛋。

---

## Phase 5 — 压测、文档与 README（Week 5）

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
技术栈：Spring Boot · MySQL · WebSocket · JWT · LLM API

• 复用 CS61B Gitlet 自实现引擎（SHA-1 内容寻址、DAG 提交图、
  分支指针管理），封装为内部 GitletService，通过 RESTful 接口
  暴露 init / commit / branch / checkout 等核心命令
• 设计简化版 Git Smart Protocol：push 前两步 HTTP 协商确定服务端
  缺失的对象集合，仅传输 missingObjects，实测修改单行代码时
  传输对象数由全量降至 2 个（新 Blob + 新 Commit）
• 基于数据库 CAS 实现无锁并发控制：HEAD 指针更新时校验
  baseHeadSha1，并发冲突时返回 non-fast-forward 错误，
  压测 50 并发 push 场景下冲突检测准确率 100%
• 引入 Spring Event 机制解耦 push 主流程与 Agent 处理：push 成功
  后异步发布 PostReceiveEvent，CodeReviewListener 提取 diff 并
  调用 LLM API 生成结构化 review 意见，通过 WebSocket 实时推送，
  全程不阻塞主流程
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

*SPEC v3.2 · 新增 ObjectStorage 存储抽象层（策略模式）*
