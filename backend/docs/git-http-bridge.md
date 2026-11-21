# Git HTTP 流式桥接

当前入口使用 `net/http`。默认只公开 `/git/100.git/` 的 Clone/Fetch；Push 仍被入口拒绝。桥接器本身支持 receive-pack，集成测试在临时仓库中用固定测试身份验证 Push。正式开放写入前，需要接入 PAT、账号状态、仓库权限和分支保护。

## 边界与调用方式

`gittransport.New(Config)` 在进程启动时创建一次。多个仓库请求共享同一个 Backend，才能共享并发上限。

- 路由/业务层：识别仓库，验证凭据、账号状态和仓库 ACL，再构造 `Invocation`。
- Backend：根据服务端提供的仓库 ID 构造磁盘路径和 CGI 环境，管理双向流与进程生命周期。
- 原生 Git：处理 Pack、对象、Quarantine、Hook 和 Ref 更新。

`Invocation` 不是 HTTP 输入 DTO。尤其不能从请求 JSON、查询参数或客户端伪造的 Header 直接填写 `UserID`。receive-pack 要求正数 UserID，但这只是防误用检查，不能替代上游的 PAT/ACL 校验。

未来接入 Gin 等框架时仍复用 Backend。传入的 ResponseWriter 必须让 ResponseController 能访问全双工、Flush、读写截止时间能力；包装器需要正确提供 Unwrap。Git 路由的中间件应保留流式传输，并让 http.ErrAbortHandler 继续传到 net/http，不得捕获后追加 JSON 错误。

仓库位置固定为 `<RepositoryRoot>/<RepositoryID>.git`。仓库根目录在启动时解析为绝对路径，目标仓库不得通过符号链接跳到其他位置。适配器只构造允许的 Git 服务路径；客户端 URL 不直接变成磁盘路径。

选定仓库的导出由已授权入口控制，CGI 使用 `GIT_HTTP_EXPORT_ALL=1`，不依赖手工添加 `git-daemon-export-ok`。不要用 ReadOnlyRepository 包装私有仓库。

## 双向流

输入方向：

`HTTP Body → 原始大小限制 → 可选 gzip 解压 → 解压后大小限制 → 32 KiB 缓冲 → CGI stdin`

输出方向：

`CGI stdout → 有界 CGI 响应头解析 → 32 KiB 缓冲 → HTTP ResponseWriter/Flush`

stderr 独立持续读取，最多保留配置的字节数，超出部分丢弃但继续排空，避免子进程因 stderr 管道写满而阻塞。stdout 永远不写日志；请求体、Authorization、Cookie 不写入 CGI 环境或日志。保留的 stderr 仅在 Debug 日志中输出，启用 Debug 前应考虑服务端 Hook 可能打印的信息。

HTTP chunk framing 已由 Go 解码，桥接器不解析 Git pkt-line、Pack 或 HTTP chunk 边界。对于无长度的请求，CGI 环境省略 CONTENT_LENGTH，关闭 stdin 表示输入 EOF；已知长度的未压缩请求如实传入长度。gzip 由桥接器流式解压，因此不再把 Content-Encoding 或压缩体长度传给 Git，避免双重解压和错误长度。

这是一项针对 git-http-backend 的兼容约定，不是任意 CGI 程序的通用能力。Git 自己仍会缓冲部分协议协商请求；Go 桥接层不会缓存完整仓库或完整上传。Git 升级需要重新跑真实客户端集成测试。

HTTP/1 显式启用全双工。上传泵、stdout 转发和 stderr 排空同时运行，不先等待整个上传结束。管道写入变慢时，上游读取自然放缓，形成背压。网络读取和写入分别设置截止时间，并在相关操作结束后清除，避免误伤后续 keep-alive 请求。

## 并发与默认限制

| 配置 | 默认 | 含义 |
|---|---:|---|
| MaxConcurrent | 8 | 同时接纳的 Git RPC 数量，含 discovery |
| MaxRequestBytes | 1 GiB | 原始传输体和解压后的体分别受同一上限限制 |
| MaxDuration | 30 分钟 | 整次 RPC 的最长生命周期 |
| IdleTimeout | 5 分钟 | 无协议数据进展的上限，同时用于单次网络读写截止时间 |
| MaxHeaderBytes | 32 KiB | CGI 响应头总大小 |
| MaxStderrBytes | 16 KiB | 每次 RPC 保留的 stderr 上限 |

并发名额使用非阻塞信号量：没有空位就返回 503 和 Retry-After，不建立无限等待队列，也不启动 CGI 进程。名额直到上传泵、stderr 排空、进程等待、取消回调和监视器都结束才释放。

8 个 RPC 不等于 8 个操作系统进程；每个后端还可能启动 upload-pack、receive-pack、pack-objects 或 Hook。该上限是准入控制，不是吞吐量承诺。生产值需要按 CPU、Git 子进程内存、磁盘和网络实测。HTTP 解压体上限也不等于最终 Git 对象占用的磁盘配额；仓库配额和宿主机资源限制需要单独设置。

持续有进展只能避免空闲超时，不能突破 MaxDuration。客户端读得慢时，响应写入截止时间会生效；Git 不读 stdin 时，有限管道会阻塞上传泵，整体超时最终终止执行。

## 生命周期与错误处理

正常路径：

1. 取得并发名额，验证服务端 Invocation、仓库路径和请求格式。
2. 创建独立进程组、stdin/stdout/stderr 管道及请求 Context。
3. 启动上传、响应、stderr 和进程等待任务。
4. 请求 EOF 后关闭 stdin；stdout 继续转发直到 EOF。
5. 无需将 HTTP 200 解释成 Push 成功，Git 协议内仍可能包含拒绝原因。
6. 等待全部任务完成、清理资源后释放并发名额。

异常都汇入一次 Context 取消；第一个取消原因保留用于日志和分类。取消回调设置立即到期的网络截止时间、关闭管道，终止整个进程组并关闭请求体，使阻塞的 Read/Write 能够退出。直接子进程必须经过 Wait 回收。进程组先收到 SIGTERM，短暂宽限后仍存在的成员再收到 SIGKILL。

Linux 通过 waitid/WNOWAIT、macOS 通过 kqueue 观察 CGI 主进程退出，然后先清理进程组，最后才调用 Cmd.Wait 回收主进程。这段期间保留主进程 PID，避免高并发下旧进程号被复用后误向其他进程组发送信号。正常退出也执行进程组收尾，处理子进程仍持有管道的情况。进程组不能约束主动 setsid 脱离的程序；服务器 Hook 应由服务端管理，不得自行守护化，进一步的隔离需要部署层 cgroup/容器。

上传读取结束后，正常请求会清除读取截止时间，让耗时下载继续；取消中的请求则保留立即到期的读取截止时间，直到请求体关闭完成。否则 net/http 的 Body.Close 可能尝试排空尚未上传的 HTTP/1 请求体，再次阻塞清理。准入拒绝和格式错误的未完成上传也会关闭连接并限制收尾读写时间。

CGI 提前退出时，剩余上传只有有界的收尾时间，防止继续等客户端无限上传。即使 HTTP 请求结束，也不会提前释放仍被清理中的进程名额。

| 情况 | 行为 |
|---|---|
| 并发已满或 Backend 已关闭 | 启动前返回 503 |
| 已知体积超限 | 启动前返回 413 |
| 未知长度或 gzip 解压后超限 | 取消执行；尚未提交响应时返回 413 |
| 上传被截断、gzip 损坏或读取失败 | 取消执行；能发送响应时返回 400 |
| 无进展或总时长超限 | 取消执行；尚未提交响应时返回 504 |
| CGI 响应头无效、过大、进程异常 | 取消执行；尚未提交响应时返回 502 |
| 客户端断开/下载写入失败 | 取消执行，回收进程与泵，不继续生成响应 |
| 已提交响应后发生错误 | 中止 HTTP/1 连接或 HTTP/2 Stream，不追加 JSON/HTML 错误到 Git 字节流 |

表中的错误状态只在尚未提交响应且连接/Stream 仍可写时发送。客户端已经断线，或底层 HTTP/2 Stream 已重置时，只能终止和清理，不能保证对端收到新的状态码。

响应使用 CGI Status 和 Content-Type，移除 hop-by-hop 响应头；长度被声明时检查正文长度。普通 Git 拒绝信息原样返回；桥接层不重试 Push。Ref 已更新而最终响应丢失时，客户端结果可能未确认，应重新查询 Ref，不能把网络失败当作 Git 一定回滚。

进入执行阶段的 RPC 会记录结构化日志，包括仓库 ID、服务、身份 ID、耗时、输入/输出字节、HTTP 状态、响应是否已开始、进程退出码和传输错误。未发送响应即断开的状态记为 0；已经提交的状态保持原值，后续异常另记传输错误。日志的“传输完成”不等于“Git 业务操作成功”。准入拒绝等提前返回可由未来的 HTTP 访问日志统一记录。

当前进程组实现支持 Linux/macOS。其他平台构造 Backend 时会明确报错，不会退化成只杀父进程。SIGKILL 或宿主机崩溃可能打断 Git 自身清理，不能承诺所有临时对象都立即消失；仓库存储仍需运维检查和备份。

## 启动与停机

在 backend 目录运行：

```bash
go run ./cmd/gitnova \
  -repository-root /var/lib/gitnova/repositories \
  -git-max-concurrent 8 \
  -git-max-request-bytes 1073741824 \
  -git-max-duration 30m \
  -git-idle-timeout 5m
```

也可用 GITNOVA_REPOSITORY_ROOT 设置仓库根，用 GITNOVA_ADDR 设置监听地址。默认保留本机开发地址 127.0.0.1:8080。持久仓库根应挂载持久磁盘，不能放在容器临时层。

SIGTERM/Ctrl+C 后停止接纳 Git RPC，取消现有 RPC，等待回收，再关闭 HTTP Server。停机等待预算为 10 秒；超时会报告错误并关闭 HTTP 连接。HTTP Server 只设置请求头和空闲连接超时，不用统一的短 ReadTimeout/WriteTimeout 杀掉合法的大型传输。

未来若部署 Nginx，需要同时考虑 proxy_request_buffering、proxy_buffering、HTTP 版本和代理超时；否则代理可能在 Go 之前完整缓存请求，或者延迟进度响应。

## 验证

```bash
go test -count=1 ./...
go test -race -count=1 ./internal/gittransport
go vet ./...
```

测试仓库和辅助 CGI 都放在 testing.T.TempDir 中，不读写现有业务仓库。集成测试用真实 Git CLI 验证分块 Push、Clone、Fetch、gzip、OID/内容一致性、Hook 拒绝和 git fsck；故障测试验证客户端取消、截断、超限、超时、慢下载、进程组回收及并发准入。测试固定身份不代表已经实现正式 PAT 认证。

2026-11-21 本地验证：Go 1.27.3、Git 2.50.1、macOS arm64；全部后端测试、race 检查和 go vet 通过。Linux amd64 已通过交叉编译，尚未在 Linux 上运行集成测试。真实 main 进程已验证 health=200、只读 Git discovery=200、写操作=403、SIGTERM 正常退出。并发故障测试证明准入上限和释放后恢复，不代表生产吞吐量压测。
