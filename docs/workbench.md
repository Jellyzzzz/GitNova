# GitNova 薄工作台

范围：登录、仓库列表/创建、Session 列表/创建、CLI push 参数指引。不包含 Task、模型调用、代码编辑或 Git write-back。

## 使用

1. 按现有方式加载本地配置，启动 Spring Boot 及其依赖服务。
2. 访问 `http://localhost:8080/`（也可访问 `/index.html`）。不需要 Node 开发服务器。
3. 使用现有账户登录，选择或创建仓库。
4. 用现有 GitNova CLI 从本地 Gitlet 仓库推送已提交对象。页面只提供 `push <server> <repoId> main` 参数，不能直接当作 shell 命令执行，也不是标准 `git push`。
5. 选择源分支创建 Session，查看真实的状态、base revision 和 Workspace ID。

注册仍使用已有 `/api/auth/register` 接口，本页面暂不提供注册表单。

## 文件与契约

- `src/main/resources/static/index.html`：页面与弹窗。
- `app.css`：GitHub 风格浅色布局，支持手机宽度。
- `api.js`：同源请求、Bearer 认证、表单/JSON 编码、超时和错误处理。
- `app.js`：页面状态、渲染与操作。

JWT 存在当前标签页的 sessionStorage，退出/401 后清除；不使用 localStorage，不向 URL 写入 token。页面没有模型密钥，也不显示 token。使用共享电脑时应主动退出；sessionStorage 并非防 XSS 的安全保险箱，因此所有外部内容使用 textContent 渲染，同时限制 CSP，不引入第三方脚本。非本机部署必须使用 HTTPS。

Session 同一仓库/分支的失败重试沿用幂等键，键在请求前写入 sessionStorage，页面刷新后可继续使用；成功后释放。退出登录会清除未完成请求记录，因此重新登录后应先查询结果再发起新的创建操作。仓库创建接口没有幂等键，失败提示要求先刷新确认，不自动重试写请求。

列表固定请求最近 20 个当前用户创建的 Session，明确标注暂不支持翻页。空数组显示空状态；读取失败单独显示错误，不视为空列表。页面只显示持久化状态，不扫描磁盘、不重新物化已有 Workspace、不推断 Agent 是否完成。

## 测试

Spring MVC 和已有后端边界：

```sh
mvn -q -Dtest=WorkbenchWebTest,AgentSessionControllerTest,AgentSessionServiceTest,MyBatisAgentSessionStoreTest,JwtInterceptorTest test
```

浏览器交互测试需要本机可解析 `playwright` 包和 Chromium。没有浏览器时可使用已安装的 Chrome：

```sh
PLAYWRIGHT_CHANNEL=chrome node --test --test-timeout=20000 src/test/frontend/workbench.test.cjs
```

Playwright 安装在独立目录时通过 `NODE_PATH` 指定其 node_modules。设置 `UI_SCREENSHOT_DIR` 可输出桌面、手机和登录页截图。测试仅监听本机临时端口，API 均为隔离 fixture；不使用真实账户、MySQL、Redis、Workspace 或模型，不等于真实后端 E2E。

手动验收还需：真实登录 → 创建仓库 → 本地 CLI push → 页面创建 Session → 数据库核对 Session/Workspace 状态；检查错误分支、权限失败、登录过期和重复点击。
