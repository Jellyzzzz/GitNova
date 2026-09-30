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

### 基础设施类（优先实现）

以下两个类是实现接口的前置依赖，必须最先完成：

---

#### 0-A. UserContext — ThreadLocal 用户上下文

**文件位置**：`com.gitnova.common.UserContext`（已创建，直接填充实现）

**核心字段与方法**：

```java
public class UserContext {
    private static final ThreadLocal<Long>   USER_ID_HOLDER   = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME_HOLDER  = new ThreadLocal<>();

    public static void setUserId(Long userId)       { USER_ID_HOLDER.set(userId); }
    public static Long   getUserId()                { return USER_ID_HOLDER.get(); }
    public static void   setUsername(String name)   { USERNAME_HOLDER.set(name); }
    public static String getUsername()              { return USERNAME_HOLDER.get(); }

    public static void clear() {
        USER_ID_HOLDER.remove();
        USERNAME_HOLDER.remove();
    }
}
```

> ⚠️ **Warning — ThreadLocal 清理必须严格执行**
> `JwtInterceptor.afterCompletion()` 中必须调用 `UserContext.clear()`。
> 即使请求中途抛异常，`afterCompletion` 也会执行（Spring MVC 保证），
> 所以不会泄漏。但如果 future 有人用 `@Async` 或自定义线程池，
> 子线程拿不到父线程的 ThreadLocal 值 → 这是预期行为，异步场景下
> 应该显式传递 userId。

**验收标准**：拦截器写入 → Controller 能读取到 → 请求结束后 ThreadLocal 为空。

---

#### 0-B. JwtInterceptor — JWT 认证拦截器

**文件位置**：`com.gitnova.interceptor.JwtInterceptor`

**配置类**：`com.gitnova.config.JwtInterceptorConfig`（已注册，拦截 `/api/**`，排除 `/api/auth/**`）

**JWT 依赖**：jjwt 0.12.x 已在 `pom.xml` 中引入（`jjwt-api` + `jjwt-impl` + `jjwt-jackson`）

**实现流程**：

```
preHandle(request, response, handler)
    │
    ├── 1. 取 Header "Authorization"
    │     若为 null 或不以 "Bearer " 开头 → 401 "Missing or invalid Authorization header"
    │
    ├── 2. 截取 token = header.substring(7)
    │
    ├── 3. Jwts.parser()
    │        .verifyWith(key)          // 用 HS256 + secret 校验签名
    │        .build()
    │        .parseSignedClaims(token)
    │
    ├── 4. 从 claims 中取 "userId" (Long) 和 "username" (String)
    │
    ├── 5. UserContext.setUserId(userId)
    │        UserContext.setUsername(username)
    │
    └── 6. return true（放行）
```

**异常处理（放在 try-catch 中）**：

| 异常类型 | 含义 | HTTP 状态 |
|----------|------|-----------|
| `JwtException`（含 `ExpiredJwtException`、`MalformedJwtException`、`SignatureException` 等子类） | Token 过期 / 格式错误 / 签名不匹配 | 401 |
| `IllegalArgumentException` | Token 为空字符串 | 401 |

```java
// 异常统一处理：
catch (JwtException | IllegalArgumentException e) {
    response.setContentType("application/json;charset=UTF-8");
    response.setStatus(401);
    response.getWriter().write("{\"code\":401,\"message\":\"" + e.getMessage() + "\"}");
    return false;
}
```

**afterCompletion 清理**：

```java
@Override
public void afterCompletion(...) {
    UserContext.clear();  // ⚠️ 必须执行，防止线程池污染
}
```

**JWT 密钥与过期时间**：从 `application.yml` 注入：

```java
@Value("${gitnova.jwt.secret}")
private String secret;

@Value("${gitnova.jwt.expire}")
private long expire;  // 毫秒，默认 86400000（24h）
```

**验收标准**：
- 无 token → 401
- 过期 token → 401 "Token expired"
- 签名错误 token → 401
- 有效 token → Controller 中 `UserContext.getUserId()` 返回正确值
- 请求结束后 `UserContext.getUserId()` 返回 null

---

### 接口详细 Spec

---

#### 1. POST /api/auth/register — 用户注册

**Controller**：`AuthController.register()`
**Service**：`AuthService.register()`
**鉴权**：无（被 JwtInterceptor 排除）

**请求参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `username` | `@RequestParam String` | ✅ | 3~50 字符，仅允许字母数字下划线 |
| `password` | `@RequestParam String` | ✅ | 6~100 字符 |
| `email` | `@RequestParam(required=false) String` | ❌ | 若不为空需校验格式 |

**Service 层实现流程**：

```
register(username, password, email)
    │
    ├── 1. 参数校验
    │      username == null || blank          → 400 "用户名不能为空"
    │      username.length < 3 || > 50        → 400 "用户名长度应在 3~50 之间"
    │      username 含非法字符                 → 400 "用户名仅允许字母、数字、下划线"
    │      password == null || blank          → 400 "密码不能为空"
    │      password.length < 6 || > 100       → 400 "密码长度应在 6~100 之间"
    │      email != null && 不含 '@'           → 400 "邮箱格式错误"
    │
    ├── 2. 唯一性校验
    │      LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
    │      wrapper.eq(User::getUsername, username);
    │      User exist = userMapper.selectOne(wrapper);
    │      if (exist != null) → 400 "用户名已存在"
    │
    ├── 3. BCrypt 加密
    │      String encodedPassword = new BCryptPasswordEncoder().encode(password);
    │      （BCryptPasswordEncoder 来自 spring-security-crypto，已在 pom.xml 中）
    │
    ├── 4. 写入数据库
    │      User user = new User();
    │      user.setUsername(username);
    │      user.setPassword(encodedPassword);
    │      user.setEmail(email);
    │      user.setCreatedAt(LocalDateTime.now());
    │      userMapper.insert(user);  // MyBatis-Plus 自动回填 user.id
    │
    └── 5. 返回
           return ApiResponse.success(Map.of("id", user.getId(), "username", user.getUsername()));
```

**响应格式**：

```json
// 成功 200
{ "code": 200, "message": "success", "data": { "id": 1, "username": "zhangsan" } }

// 失败 400
{ "code": 400, "message": "用户名已存在" }
```

> 🔍 **Implementation Note — 为什么注册不返回 JWT？**
> RESTful 惯例：POST /register 创建资源，返回 201 + 资源本身。
> JWT 应该在 POST /login 中返回。注册后需要主动登录，这与
> OAuth2 的 "授权码 → token" 两步分离思路一致。

---

#### 2. POST /api/auth/login — 用户登录

**Controller**：`AuthController.login()`
**Service**：`AuthService.login()`
**鉴权**：无

**请求参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `username` | `@RequestParam String` | ✅ | |
| `password` | `@RequestParam String` | ✅ | |

**Service 层实现流程**：

```
login(username, password)
    │
    ├── 1. 参数校验
    │      username 为空 → 400 "用户名不能为空"
    │      password 为空 → 400 "密码不能为空"
    │
    ├── 2. 查用户
    │      User user = userMapper.selectOne(
    │          new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    │      if (user == null) → 401 "用户名或密码错误"（不要区分"用户不存在"vs"密码错误"，防枚举）
    │
    ├── 3. 密码比对
    │      BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    │      if (!encoder.matches(password, user.getPassword())) → 401 "用户名或密码错误"
    │
    ├── 4. 生成 JWT
    │      SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    │      String token = Jwts.builder()
    │          .claim("userId", user.getId())
    │          .claim("username", user.getUsername())
    │          .issuedAt(new Date())
    │          .expiration(new Date(System.currentTimeMillis() + expire))
    │          .signWith(key)
    │          .compact();
    │
    └── 5. 返回
           return ApiResponse.success(token);
```

**响应格式**：

```json
// 成功 200
{ "code": 200, "message": "success", "data": "eyJhbGciOiJIUzI1NiJ9..." }

// 失败 401
{ "code": 401, "message": "用户名或密码错误" }
```

> 💡 **Design Note — 为什么不返回 userId + username 而是只返回 token？**
> JWT 本身就是自包含令牌，前端解码 payload 即可拿到 userId/username。
> 只返回 token 减少冗余信息，且强迫前端养成"解码 JWT 取用户信息"的习惯，
> 避免安全问题（若前端用返回的 userId 做权限判断，可能被篡改）。

---

#### 3. POST /api/repos — 创建仓库

**Controller**：`RepoController.createRepo()`
**Service**：`RepoService.createRepo()`
**鉴权**：✅ 需要有效 JWT

**请求参数**：

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `name` | `@RequestParam String` | ✅ | — | 3~100 字符，字母数字 + `-` `_` `.` |
| `description` | `@RequestParam(required=false) String` | ❌ | null | 最多 255 字符 |
| `isPrivate` | `@RequestParam(defaultValue="true") boolean` | ❌ | true | 是否私有仓库 |

**Service 层实现流程**：

```
createRepo(name, description, isPrivate)
    │
    ├── 0. 获取当前用户
    │      Long userId = UserContext.getUserId();
    │      if (userId == null) → 401（正常情况拦截器已拦截，这是防御性代码）
    │
    ├── 1. 参数校验
    │      name == null || blank              → 400 "仓库名不能为空"
    │      name.length < 3 || > 100           → 400 "仓库名长度应在 3~100 之间"
    │      name 含非法字符 (正则 [a-zA-Z0-9\-_\.]+)
    │                                         → 400 "仓库名仅允许字母、数字、-、_、."
    │      description != null && len > 255   → 400 "描述不能超过 255 个字符"
    │
    ├── 2. 唯一性校验（同一 owner 下不可重名）
    │      LambdaQueryWrapper<Repository> wrapper = new LambdaQueryWrapper<>();
    │      wrapper.eq(Repository::getOwnerId, userId)
    │             .eq(Repository::getName, name);
    │      if (repositoryMapper.selectCount(wrapper) > 0)
    │                                         → 400 "仓库名已存在"
    │
    ├── 3. 写入 repository 表
    │      Repository repo = new Repository();
    │      repo.setName(name);
    │      repo.setOwnerId(userId);
    │      repo.setIsPrivate(isPrivate ? 1 : 0);
    │      repo.setDescription(description);
    │      repo.setCreatedAt(LocalDateTime.now());
    │      repositoryMapper.insert(repo);  // repo.id 自动回填
    │
    ├── 4. 写入 repo_member 表（owner 角色）
    │      RepoMember member = new RepoMember();
    │      member.setRepoId(repo.getId());
    │      member.setUserId(userId);
    │      member.setRole("owner");
    │      repoMemberMapper.insert(member);
    │
    ├── 5. 调用 GitletService.init() 初始化对象库
    │      String repoPath = userId + File.separator + repo.getId();
    │      // 实际路径：{REPO_BASE_PATH}/1/5/.gitlet/
    │      gitletService.init(repoPath);
    │      // 如果 init 抛 GitletException → GlobalExceptionHandler → 400
    │
    └── 6. 返回
           return ApiResponse.success(Map.of(
               "id", repo.getId(),
               "name", repo.getName(),
               "ownerId", repo.getOwnerId(),
               "isPrivate", repo.getIsPrivate(),
               "createdAt", repo.getCreatedAt()
           ));
```

> ⚠️ **Warning — 事务边界**
> Step 3~5 必须在同一事务中。若 `gitletService.init()` 抛异常（如磁盘写失败），
> MySQL 中已插入的 `repository` 和 `repo_member` 行必须回滚。
> 实现方式：在 `createRepo()` 方法上加 `@Transactional`。
>
> 注意：`@Transactional` 回滚不删除磁盘上已创建的 `.gitlet` 目录。
> 这是一个已知的工程折衷——磁盘回滚需手动清理，概率极低（磁盘满），
> 面试时主动提及这一点（说明你对分布式事务的边界有认知）。

> 💡 **Design Note — repoPath 为什么用 userId/repoId 而非 userId/repoName？**
> - `userId/repoId` 是 immutable 的（repo 创建后 id 不变，但 name 可能改名）
> - 后续若支持 rename 功能，不需要移动磁盘上的 .gitlet 目录
> - MySQL 中 `repository.name` 可随意改，磁盘路径不变

**响应格式**：

```json
// 成功 200
{
  "code": 200, "message": "success",
  "data": { "id": 5, "name": "my-project", "ownerId": 1, "isPrivate": 1, "createdAt": "2025-07-01 12:00:00" }
}

// 失败 400
{ "code": 400, "message": "仓库名已存在" }
```

---

#### 4. GET /api/repos — 仓库列表

**Controller**：`RepoController.listRepos()`
**Service**：`RepoService.listUserRepos()`
**鉴权**：✅ 需要有效 JWT

**Service 层实现流程**：

```
listUserRepos()
    │
    ├── 1. 获取当前用户
    │      Long userId = UserContext.getUserId();
    │
    ├── 2. 查询仓库列表（两种策略，推荐方案 A）
    │
    │      【方案 A — JOIN 查询（推荐）】
    │      // 用 @Select 自定义 SQL 或 MyBatis-Plus 的 @Select 注解
    │      // SELECT r.* FROM repository r
    │      //   JOIN repo_member rm ON r.id = rm.repo_id
    │      //   WHERE rm.user_id = #{userId}
    │      //   ORDER BY r.created_at DESC
    │
    │      【方案 B — 两步查询（不推荐，多一次 DB 往返）】
    │      // 1. 查出用户所有 repo_member 记录 → List<Long> repoIds
    │      // 2. repositoryMapper.selectBatchIds(repoIds)
    │
    └── 3. 返回
           return ApiResponse.success(repoList);
```

**实现细节**：在 `RepoMemberMapper` 中新增自定义查询方法，或者在 `RepoService` 中直接使用 MyBatis-Plus 的 `baseMapper` 配合自定义 wrapper。推荐在 `RepoMemberMapper` 中加一条注解 SQL：

```java
// RepoMemberMapper.java
@Select("SELECT r.* FROM repository r "
      + "JOIN repo_member rm ON r.id = rm.repo_id "
      + "WHERE rm.user_id = #{userId} "
      + "ORDER BY r.created_at DESC")
List<Repository> selectReposByUserId(@Param("userId") Long userId);
```

**响应格式**：

```json
{
  "code": 200, "message": "success",
  "data": [
    { "id": 5, "name": "my-project", "ownerId": 1, "isPrivate": 1, "description": "test", "createdAt": "..." },
    { "id": 3, "name": "hello-world", "ownerId": 1, "isPrivate": 0, "description": null, "createdAt": "..." }
  ]
}
```

> 🔍 **Implementation Note — 空列表**
> 新用户可能没有任何仓库，返回 `"data": []` 而非 404。
> 空列表是合法的业务状态，不是错误。

---

#### 5. GET /api/repos/{repoId} — 仓库详情

**Controller**：`RepoController.getRepo(repoId)`
**Service**：`RepoService.getRepoDetail(repoId)`
**鉴权**：✅ 需要有效 JWT

**Service 层实现流程**：

```
getRepoDetail(repoId)
    │
    ├── 1. 获取当前用户
    │      Long userId = UserContext.getUserId();
    │
    ├── 2. 查仓库
    │      Repository repo = repositoryMapper.selectById(repoId);
    │      if (repo == null) → 404 "仓库不存在"
    │
    ├── 3. 权限校验
    │      私有仓库 (isPrivate = 1)：
    │          RepoMember member = repoMemberMapper.selectOne(
    │              new LambdaQueryWrapper<RepoMember>()
    │                  .eq(RepoMember::getRepoId, repoId)
    │                  .eq(RepoMember::getUserId, userId));
    │          if (member == null) → 403 "无权访问该仓库"
    │
    │      公开仓库 (isPrivate = 0)：直接放行
    │
    └── 4. 返回
           return ApiResponse.success(repo);
```

**响应格式**：同 POST /api/repos 返回体。

**错误码设计**：

| 情况 | HTTP | message |
|------|------|---------|
| repoId 不存在 | 404 | "仓库不存在" |
| 私有仓库 + 非成员 | 403 | "无权访问该仓库" |
| 正常 | 200 | 仓库详情 |

> 🔍 **Implementation Note — 为什么不用 @ExceptionHandler 统一处理 403/404？**
> 与 `GlobalExceptionHandler` 不同，这两种是**业务状态**而非系统异常。
> 直接在 Service 中返回 `ApiResponse.error(404, ...)` 更直观。
> 若后续类似逻辑增多，可定义 `BizException(code, message)` 统一抛。

---

#### 6. DELETE /api/repos/{repoId} — 删除仓库

**Controller**：`RepoController.deleteRepo(repoId)`
**Service**：`RepoService.deleteRepo(repoId)`
**鉴权**：✅ 需要有效 JWT + owner 角色

**Service 层实现流程**：

```
deleteRepo(repoId)
    │
    ├── 1. 获取当前用户
    │      Long userId = UserContext.getUserId();
    │
    ├── 2. 查仓库
    │      Repository repo = repositoryMapper.selectById(repoId);
    │      if (repo == null) → 404 "仓库不存在"
    │
    ├── 3. 权限校验 — 必须为 owner
    │      RepoMember member = repoMemberMapper.selectOne(
    │          new LambdaQueryWrapper<RepoMember>()
    │              .eq(RepoMember::getRepoId, repoId)
    │              .eq(RepoMember::getUserId, userId));
    │      if (member == null || !"owner".equals(member.getRole())) → 403 "仅仓库所有者可删除"
    │
    ├── 4. 删除数据（@Transactional）
    │      repoMemberMapper.delete(
    │          new LambdaQueryWrapper<RepoMember>().eq(RepoMember::getRepoId, repoId));
    │      // 删除所有成员记录
    │
    │      repositoryMapper.deleteById(repoId);
    │      // 删除仓库记录
    │
    ├── 5. 删除磁盘上的 .gitlet 目录（可选，Phase 1 可先跳过）
    │      磁盘删除失败不应回滚 DB 事务 → 记录 WARN 日志即可
    │
    └── 6. 返回
           return ApiResponse.success(null);
```

> ⚠️ **Warning — 磁盘删除与 DB 删除的一致性**
> Phase 1 可以先只做 DB 软删除（逻辑删除），或在 DB 事务外执行磁盘
> 删除。原因是 DB 回滚无法回滚文件系统操作。面试时主动提这个问题，
> 说明你对分布式事务的 ACID 边界有认知，是加分项。

**响应格式**：

```json
// 成功 200
{ "code": 200, "message": "success", "data": null }
```

---

### 全局异常映射补充

在 `GlobalExceptionHandler` 中追加以下异常处理（Phase 1 新增）：

```java
// 参数校验失败（后续可接入 @Valid 自动抛出）
@ExceptionHandler(IllegalArgumentException.class)
@ResponseStatus(HttpStatus.BAD_REQUEST)
public ApiResponse<?> handleIllegalArgument(IllegalArgumentException ex) {
    return ApiResponse.error(400, ex.getMessage());
}

// 未登录（JWT 拦截器直接 write 401，此 handler 作为兜底）
@ExceptionHandler(ResponseStatusException.class)
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public ApiResponse<?> handleUnauthorized(ResponseStatusException ex) {
    return ApiResponse.error(401, ex.getMessage());
}
```

---

### 依赖注入速查表

| 类 | 需要注入的依赖 |
|----|---------------|
| `AuthService` | `UserMapper` |
| `RepoService` | `RepositoryMapper`、`RepoMemberMapper`、`GitletService` |
| `JwtInterceptor` | `@Value("${gitnova.jwt.secret}")`、`@Value("${gitnova.jwt.expire}")` |
| `GitletService` | `@Value("${gitnova.repo.base-path}")` |

---

### 实现顺序建议

```
Step 1  UserContext           (5 min  — 填空，方法已写好)
Step 2  JwtInterceptor        (30 min — 核心逻辑：parse + validate + set ThreadLocal)
Step 3  AuthService.register  (20 min — 校验 + BCrypt + insert)
Step 4  AuthService.login     (15 min — 查用户 + BCrypt.matches + 生成 JWT)
Step 5  RepoService.createRepo(30 min — 校验 + insert repo/member + @Transactional + gitlet init)
Step 6  RepoService.listUserRepos  (10 min — JOIN 查询)
Step 7  RepoService.getRepoDetail  (15 min — 查仓库 + 权限校验)
Step 8  RepoService.deleteRepo     (15 min — 权限 + 删除)
```

### Milestone 验收

- Postman 能注册、登录拿到 JWT
- 携带 JWT 创建仓库后，服务器磁盘出现 `{REPO_BASE_PATH}/{userId}/{repoId}/.gitlet/` 目录结构
- 无效/过期/篡改 JWT 请求返回 401
- 私有仓库非成员访问返回 403
- 仓库列表接口正确返回用户有权限的所有仓库
- 删除仓库仅 owner 可操作

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

*SPEC v3.1 · 新增双机开发环境章节 + 骨架搭建流程*
