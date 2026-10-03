# OpenSandbox安装接线：正式Linux部署与Mac＋Windows双机开发

本章保留既定Harness-in-Sandbox架构，把设备分工、CLI入口、镜像架构、双向网络与跨设备访问写成具体操作。**双机主线默认Mac编辑/调试GitNova Server，Windows 32G提供Linux执行环境。** Windows版本、WSL模式、网卡地址和实际空闲内存尚未取得，以下私网地址与资源值是需在本机填写/验证的部署参数，不是已发现的设备信息。源码核对与本包静态检查不等于Windows/Mac实机通过，证据范围见07。

## 1. 锁定的接入基线，不自动追latest

| 项目 | 本手册选择 | 说明 |
|---|---|---|
| OpenSandbox源码 | `release-1.1.0` | 与SDK同一发布线 |
| JVM SDK | `com.alibaba.opensandbox:sandbox:1.1.0` | Java API来自该tag的README和实际Sandbox.kt |
| execd镜像 | `opensandbox/execd:release-1.1.0` | 不把旧独立`v1.1.0`标签混为同一物 |
| OpenSandbox运行 | 从该tag源码启动Server | 不直接安装已知有打包缺失问题的1.1.0 wheel |
| GitNova Worker | JDK21 Linux镜像；Intel Windows使用`linux/amd64` | protocol保留Java17，Server保持现有Java17兼容 |
| 数据存储 | 每Session一只Docker命名卷 | `PVC`在Docker后端映射命名卷；不是要求Kubernetes |

发布说明`release-1.1.1-rc.1`记载1.1.0 wheel缺失FastPath stubs导致导入失败。这里**从固定源码目录运行**绕开该wheel缺包路径，不宣称未经实测的容器组合已稳定。参考源码：

- [JVM SDK固定版本说明](https://github.com/opensandbox-group/OpenSandbox/blob/release-1.1.0/docs/sdks/kotlin.md)
- [SDK真实Builder](https://github.com/opensandbox-group/OpenSandbox/blob/release-1.1.0/sdks/sandbox/kotlin/sandbox/src/main/kotlin/com/alibaba/opensandbox/sandbox/Sandbox.kt)
- [PVC/Volume真实类型](https://github.com/opensandbox-group/OpenSandbox/blob/release-1.1.0/sdks/sandbox/kotlin/sandbox/src/main/kotlin/com/alibaba/opensandbox/sandbox/domain/models/sandboxes/SandboxModels.kt)
- [Server样例配置](https://github.com/opensandbox-group/OpenSandbox/blob/release-1.1.0/server/opensandbox_server/examples/example.config.toml)
- [后续修复说明](https://github.com/opensandbox-group/OpenSandbox/releases/tag/release-1.1.1-rc.1)

“锁定基线”不是安装保证。若你的机器在这个组合上未通过下面探针，不继续业务联调；保留输出再判断配置/依赖，而不是静默切main导致手册无法复现。

## 2. 先选部署位置，再安装；两种环境不是两个产品版本

### 2.1 正式Linux服务器部署

正式环境不依赖开发Mac或Windows笔记本保持开机。GitNova Server/Model Proxy及持久数据库、仓库对象存储部署在常驻运行端；OpenSandbox Server与Docker Engine部署在Linux执行宿主。可以先同机，也可以通过私网分开。两者保持本章相同的HTTP命令/SSE/封存协议。

同一Linux机器上Server访问OpenSandbox，可使用`127.0.0.1:8090`；分机就填写执行宿主的私网地址并收紧管理端口来源。Agent连接模型代理使用容器可达的服务地址，不写容器localhost。模板见[正式Linux配置](deploy/opensandbox.linux.example.toml)。`proxy.resolve_internal=true`只在管理进程确实能路由到Sandbox容器IP时使用；否则按实际路由设置false及docker.host_ip。[D-S06]

这不是一套完整生产加固脚本。守护启动、TLS、备份、配额与不可信代码隔离仍按原验收要求完成；Windows笔记本开发成功不等于正式服务器已验收。

### 2.2 本次默认：Mac开发＋Windows 32G执行

| 位置 | 默认负责 | 不负责 |
|---|---|---|
| Mac | IDE、源码、JDK21编译/纯Java测试、GitNova Server调试与Model Proxy、浏览器 | 不运行Docker Sandbox，不需要安装完整容器环境 |
| Windows 32G | WSL2 Ubuntu、Docker Desktop Linux Engine、OpenSandbox Server、Agent镜像/容器/会话卷、Linux进程测试 | 不是第二套业务Session数据库，不把Windows命令行当Agent工作环境 |
| 任一设备浏览器 | 登录同一个GitNova服务，查看/提交/取消同一Session下任务 | 不持有Sandbox管理API key，不直接操作容器 |

现有MySQL/Redis/仓库对象存储先保持一个明确部署位置。若这些也导致Mac内存不足，可整体迁到Windows运行端，但必须先备份、填写唯一Server的数据源和持久存储地址，不能静默重建空库。RabbitMQ是否运行取决于旧链退役/其他业务，不因为双机部署重新接回Agent主链。

**需要Mac合盖仍工作时：** 将GitNova Server、Model Proxy及其持久依赖也运行在Windows的Linux环境，Mac仅保留编辑器/浏览器；两台浏览器改连同一个Windows平台入口。保留同一数据而不是各起一份Server+空库。先让活动Task收口，再切服务地址/部署并验证绑定，本文不承诺活跃任务跨部署热迁移。仅把Sandbox搬到Windows，无法保证Mac上的Model Proxy离线后还能继续调用模型。

### 2.3 Windows执行宿主的前置检查

以下`powershell`在Windows PowerShell中执行；其余`bash`均明确标注运行位置。Intel CPU的镜像平台是`linux/amd64`，`amd64`并不表示必须使用AMD处理器。

```powershell
# Windows PowerShell；安装/升级WSL按系统提示使用管理员权限。
wsl --version
wsl --status
wsl --list --verbose
Get-CimInstance Win32_OperatingSystem | Select-Object Caption, Version
Get-CimInstance Win32_Processor | Select-Object Name, AddressWidth
# 仅在尚未安装Ubuntu时执行：wsl --install -d Ubuntu
```

启用Docker Desktop的WSL2后端及Ubuntu集成，使用**Linux containers**。不要同时在Ubuntu里另装一套docker-ce daemon；容易把镜像、卷和创建请求送给不同Engine。[D-S01]

```bash
# Windows的Ubuntu/WSL终端；不是Mac终端。
uname -s -m
# 预期 Linux x86_64
docker info --format '{{.OSType}}/{{.Architecture}}'
docker context show
docker context inspect "$(docker context show)" --format '{{json .Endpoints.docker.Host}}'
```

Engine应报告Linux及x86_64/amd64。不要将Mac本地Docker context、Docker Desktop的另一个Engine与OpenSandbox实际访问的Engine混用；禁止为了远程访问开放无认证的Docker TCP 2375。

**内存初值是工程建议，不是性能保证：** 32G机器可先给WSL2设16GB memory、4GB swap，从一个活动Sandbox开始，每Sandbox先按原有2Gi上限验证，再据构建峰值调整到4Gi等明确预算。给Windows、Docker、数据库、构建进程和磁盘缓存留余量；Worker的`-Xmx`不能占满整个容器，因为测试Java进程也会用内存。镜像架构正确也不代表内存足够。

将[WSL设置片段](deploy/windows/.wslconfig.example)合并到Windows `%UserProfile%\.wslconfig`，不要覆盖已有配置。配置后重启WSL/Docker使其生效：**`wsl --shutdown`会中断所有运行中的WSL任务，必须在无活动Task时做。** 镜像网络是可选配置，只有符合Windows版本要求时启用，见第9章。[D-S02][D-S04]

WSL源码放在`~/code/GitNova`，供应商源码放`~/gitnova-infra`。活动Session继续使用Docker命名卷，避免从`/mnt/c/...`高频读写，也不要把Mac目录通过SMB/NFS当活动工作树。[D-S02]

### 2.4 安装固定源码，并修正CLI入口

正式Linux宿主和Windows的Ubuntu/WSL都按下列命令执行；在Mac上无需安装OpenSandbox Server。

```bash
mkdir -p ~/gitnova-infra
cd ~/gitnova-infra
git clone --branch release-1.1.0 --depth 1 https://github.com/opensandbox-group/OpenSandbox.git
cd OpenSandbox
python3.12 -m venv .venv
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install -e ./server
PYTHONPATH="$PWD/server" .venv/bin/python -c 'import opensandbox_server; print(opensandbox_server.__file__)'
git rev-parse HEAD
```

缺Python3.12时先安装该解释器和venv支持，不把安装失败当Agent问题。供应商依赖仍须能下载；保留固定源码、不要随意改main。安装后保存`pip freeze`作为这台环境的依赖记录。

**已核对`release-1.1.0/server/pyproject.toml`：命令`opensandbox-server`映射到`opensandbox_server.cli:main`。** `init-config`与`--config`由CLI解析；旧文档把它们交给`python -m opensandbox_server.main`的写法撤销。[D-S05]

```bash
# 仍在 ~/gitnova-infra/OpenSandbox
PYTHONPATH="$PWD/server" .venv/bin/opensandbox-server --help
PYTHONPATH="$PWD/server" .venv/bin/opensandbox-server \
  init-config "$PWD/gitnova-sandbox.toml" --example docker
```

配置存在时先备份并手工合并，不加`--force`覆盖。Windows/WSL默认选[Windows配置片段](deploy/opensandbox.windows-wsl.example.toml)；正式Linux选[Linux配置片段](deploy/opensandbox.linux.example.toml)。旧[同机loopback样例](deploy/opensandbox.example.toml)保留作同机参考，不可直接充当Mac→Windows部署配置。

Windows片段里`server.host=0.0.0.0`是WSL内监听地址，必须同时设置随机API key与第9章的防火墙/端口入口；它不是客户端访问地址。`proxy.resolve_internal=false`与`docker.host_ip`用于OpenSandbox到Docker映射端口的内部通路，不能仅因为客户端在Mac就机械选择配置。[D-S06]

```bash
# 验证Python Docker SDK与CLI确实看到同一个Engine。
PYTHONPATH="$PWD/server" .venv/bin/python -c \
 'import docker; c=docker.from_env(); print(c.ping(), c.info()["OSType"], c.info()["Architecture"])'

# 修改配置中的api_key后，在执行宿主启动；该终端暂时保持开启。
PYTHONPATH="$PWD/server" .venv/bin/opensandbox-server \
  --config "$PWD/gitnova-sandbox.toml"
# 另一个同宿主终端
curl --fail http://127.0.0.1:8090/health
```

如果CLI有镜像而Python连接不到daemon，检查`DOCKER_HOST`和WSL集成，不假设Python自动读取Docker CLI context。health通过仅表示管理服务可用；还要执行第9、10章的跨机探针。

## 3. 在Windows的Engine准备自己的Worker镜像

源码与产物必须具有相同基线。Mac手写代码后，通过当前重构分支的明确commit同步到Windows的`~/code/GitNova`；尚未提交的改动应先保存成可记录基线，或者仅传输已构建JAR并记录SHA256。不要用共享活动Session目录同步开发源码。

**推荐在Windows的Ubuntu/WSL构建Linux/AMD64镜像：** 按02步骤1复制POM，手写Worker完成后执行。

```bash
# Windows Ubuntu/WSL；当前目录是GitNova仓库根，使用JDK21。
java -version
./mvnw -f agent-runtime/pom.xml clean install
docker build --platform linux/amd64 -f deploy/agent/Dockerfile \
  -t gitnova-agent:handwritten .
docker image inspect gitnova-agent:handwritten --format '{{.Os}}/{{.Architecture}} {{.Id}}'
```

本包`deploy/agent.Dockerfile`、`worker-entrypoint.sh`、`process-launch.py`复制到仓库`deploy/agent/`下。保持LF换行和脚本执行位；容器内`/session`由UID1000写入。普通Java JAR不是按CPU架构编译，但镜像基础层/JDK/工具/本地库必须匹配执行宿主，不能把Mac ARM64镜像默认拿来用于Intel Windows。[D-S07]

Mac编译的JAR可以传到Windows同一相对target路径后由Windows构建镜像；传输前后分别用`shasum -a 256`/`sha256sum`核对。若在Mac构建镜像，必须显式`--platform linux/amd64`且通过buildx/加载流程把正确镜像送到Windows；这会增加Mac负担，因此不是内存紧张时的默认路径。无论采用哪条，**Mac本地有镜像不等于Windows Engine也有**。

镜像由Mac预构建后迁移的显式操作可以是`docker save`导出归档、复制归档到Windows后`docker load`，随后在Windows检查OS/架构/ID；不得省掉传输/加载。生产时固定镜像digest，不在活动Session途中把同一tag偷换成另一个实现。

新Worker在容器内请求模型。默认双机配置使用 `http://<MAC_PRIVATE_IP>:8080/internal/agent/model/chat/completions`；只有GitNova Server实际运行在Windows宿主可达入口时，才考虑指向该宿主的地址。容器`localhost`不是平台，`host.docker.internal`也不是另一台Mac。网络矩阵见第9章。

## 4. Java真实SDK调用：创建、连接和端点

只在`OpenSandboxControlAdapter`引用下列SDK类。独立探针可建在Server的`src/test/java/com/gitnova/service/agent/control/OpenSandboxControlIT.java`；不要把SDK依赖加进agent-core。

```java
import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.SandboxManager;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.*;
import com.alibaba.opensandbox.sandbox.transport.RetryPolicy;
import java.time.Duration;
import java.util.List;
import java.util.Map;

ConnectionConfig config = ConnectionConfig.builder()
    .domain(System.getenv("OPEN_SANDBOX_DOMAIN"))
    .protocol("http")
    .apiKey(System.getenv("OPEN_SANDBOX_API_KEY"))
    .useServerProxy(true)
    .retryPolicy(RetryPolicy.disabled())
    .requestTimeout(Duration.ofSeconds(60))
    .build();

Volume sessionVolume = Volume.builder()
    .name("session-data")
    .pvc(PVC.builder().claimName(volumeName)
        .createIfNotExists(true).deleteOnSandboxTermination(false).build())
    .mountPath("/session").readOnly(false).build();

Sandbox sandbox = Sandbox.builder()
    .connectionConfig(config)
    .image("gitnova-agent:handwritten")
    .platform(PlatformSpec.builder().os("linux").arch("amd64").build())
    .entrypoint(List.of("/opt/gitnova/worker-entrypoint.sh"))
    .resource(Map.of("cpu", "1", "memory", "2Gi"))
    .volume(sessionVolume)
    .env("GITNOVA_SESSION_ID", sessionId)
    .env("GITNOVA_RUNNER_EPOCH", Long.toString(epoch))
    .env("GITNOVA_WORKER_TOKEN", workerToken)
    .env("GITNOVA_MODEL_ENDPOINT", modelProxyEndpoint)
    .env("GITNOVA_MODEL_TOKEN", modelToken)
    .env("GITNOVA_RUNTIME_CONFIG_JSON", spec.runtimeConfigJson())
    .metadata("gitnova.session", sessionId)
    .metadata("gitnova.create", createOperationId)
    .timeout(Duration.ofMinutes(30))
    .readyTimeout(Duration.ofMinutes(2))
    .build();
// getId是平台Binding保存的真实sandboxId
String sandboxId = sandbox.getId();
SandboxEndpoint endpoint = sandbox.getEndpoint(8081);
String base = config.getProtocol() + "://" + endpoint.getEndpoint();
Map<String,String> requiredHeaders = endpoint.getHeaders();
```

上述`OPEN_SANDBOX_DOMAIN`须在启动前验证非空：默认双机为Windows私网地址:8090，同宿主管理时才用127.0.0.1:8090。生产Adapter要显式把部署配置接到ConnectionConfig，不能假设环境变量自动映射到任意Spring字段；模型地址由LaunchSpec.modelEndpoint传入Worker。

以上构造/volume/entrypoint/resource/endpoint字段已逐项对照固定tag源代码，但**未在本环境下载SDK JAR编译**。`RetryPolicy.disabled()`只关闭SDK策略重试，源码说明仍可能保留OkHttp连接恢复；因此不能据此宣称create exactly-once。创建前保存操作身份；metadata列表可能发现重复实例，先确认唯一实例和其他副本清理，再INITIALIZE。Worker在BOOTING先FileLock且未初始化不启动任务，重复实例不能各自对共享卷运行Task。响应不明期间不盲发新create；操作最终无法裁决则保持UNKNOWN交运维，不用一次空列表证明未来不会创建。

连接已有实例：

```java
try (Sandbox connected = Sandbox.connector().sandboxId(sandboxId)
        .connectionConfig(config).connect()) {
    SandboxEndpoint ep = connected.getEndpoint(8081);
    // close只关本地客户端，不销毁远端实例
    connected.renew(Duration.ofMinutes(30));
}
```

renew以now+duration重设到期，不一定是在原到期时间上累加。活动会话每5分钟检查，剩余不足15分钟才renew30分钟，避免不小心缩短更长TTL。[S10]

## 5. Endpoint不是只有host和port

`getEndpoint()`返回值可能无scheme并含代理前缀。用config.protocol补一次scheme；如果已有完整scheme则先核实并只规范化一次。调用路径使用“去掉末尾/ + /commands”，不要`base.resolve("/commands")`截掉代理路径。

代码示例：

```java
URI commandUri = URI.create(base.replaceAll("/+$", "") + "/commands");
HttpRequest.Builder r = HttpRequest.newBuilder(commandUri)
    .timeout(Duration.ofSeconds(10))
    .header("Content-Type", "application/json")
    .header("X-GitNova-Worker-Token", workerToken)
    .POST(HttpRequest.BodyPublishers.ofByteArray(commandJson));
requiredHeaders.forEach(r::header);
```

不要打印完整endpoint headers（可能有凭据）。下游Worker控制token使用独立header，避免覆盖OpenSandbox需要的认证header。验收中检查管理API key是否被不适当地转发/记录到工作负载；接入层不能因此把平台管理权限暴露给用户代码。

获取端点≠Agent业务ready。先GET `/health/live`；上传固定bootstrap，再POST INITIALIZE；最后GET `/health/ready`核对session/epoch/configDigest/HEAD。SDK默认ping只验证execd，不可以把应用ready设成“请求INITIALIZE前必须IDLE”，否则启动死锁。

## 6. Bootstrap上传、Task下发与SSE

本次补齐一个明确的私有初始化上传入口：`PUT /bootstrap/{bootstrapId}`，只在BOOTING/INITIALIZING、控制token匹配时接受。body=ZIP流，header=`X-Content-SHA256`；Worker写唯一临时文件、校验大小/hash、atomic rename后返回201。重复相同包200，不同内容409。它只初始化，不是平台在运行期间修改工作树的RPC。

之后POST INITIALIZE指向该bootstrapId/hash。Worker校验包scope并导入，最后写WORKER_READY进入IDLE。正常任务只通过`POST /commands`送JSON；用户message中的引号、中文、换行不进入shell。

SSE使用独立Java HTTP client/连接，不设置整个流30秒超时；通过心跳计时检测停滞并主动close重连。验证至少两个事件相隔1秒真实到达，不能只看最终一次收到全部内容。网络代理不能缓冲整个响应。Server按DB已归档cursor重连，页面则按公开archiveOffset重连。

本包 `probes/TransportProbe.java` 是**独立教学探针**：JDK HttpServer接收任务文本、用独立执行器产生SSE，并验证去重和中文原样传输。它不实现真实Agent、不证明OpenSandbox代理已通过，不能复制成生产受理器替代本地日志。

## 7. Linux进程与目录：可直接照着验收

可信入口 `deploy/process-launch.py`：启动后调用setsid、记录pid/pgid/proc starttime，再exec具体argv。Java ProcessBuilder不拼接用户Task；shell工具收到的script通过bash -lc作为单一参数传入。启动器记录在state/processes/<processId>，命令输出由两个并行Reader排空。

取消必须先核对pid+starttime，TERM进程组→等待2秒→KILL→再次核对。进程已退出只返回对应状态，不用旧PID误杀别的程序。没有结果的工具事件标UNKNOWN；host/namespace外或自行脱离进程组的恶意代码并不受这份单组模型完全保证，正式多租户仍需更强进程隔离/整个实例终止策略。

`read_file/edit_file`使用可信real workRoot，逻辑`/session/worktree`路径在工具层映射；不要通过一个不受保护symlink让shell任意改state。开发基线只跑可信测试仓库，生产多租户能力未验收。进程读者不复制Worker认证环境给子进程：ProcessBuilder.environment.clear，只补PATH/LANG/HOME等所需白名单。单独清环境仍不能抵御同UID恶意进程读取Worker内存/文件，因此不声称凭据完全隔离。

## 8. 接入检查与排错表

| 观察 | 首先检查 | 禁止的“修复” |
|---|---|---|
| Server无法启动 | 固定源码目录、Python版本、依赖/打包stubs | 随便升级main又不记录 |
| SDK create成功但应用不ready | Worker进程、/session权限、env、端口、初始化状态 | 直接把ping当ready |
| endpoint404 | scheme/代理前缀/headers/port | 截掉前缀硬拼localhost |
| SSE只在结尾到 |应用flush、代理buffer/read timeout |改成每tool往返平台 |
| Worker模型请求失败 |容器到ModelProxy路由/认证/固定upstream |把provider主key烘进镜像 |
| 重建后文件丢 |volume名、删除策略、是否挂同卷、是否用归档恢复 |用旧聊天历史伪造恢复 |
| kill后仍占资源 |查询sandbox状态/标签、daemon返回、旧端点 |提前标GONE继续共挂卷 |
| Maven依赖下载失败 |registry出口、预装cache、当前网络 |让模型无限试pip/npm/mvn |

步骤 9只有下列结果均真实成立才通过：私有控制可达、bootstrap校验、ready身份、SSE即时/重连、命名卷重挂、续期、kill确认、错误身份拒绝、同Task重复不重跑。失败留在步骤 9解决，不能靠后续业务掩盖。

## 9. 双机地址矩阵、Windows入口和休眠边界

### 9.1 填写一次真实地址，不猜IP

本章用`MAC_PRIVATE_IP`、`WINDOWS_PRIVATE_IP`表示你实测可达的局域网/可信私网地址，示例不提供你的真实IP。建议DHCP保留地址；不同网络时需先建立可信网络连接，不能把私网IP当公网地址，也不要把管理端口直接映射到公网。

默认双机必须同时成立的三条链：

| 发起方 | 目标 | 填写/检查 |
|---|---|---|
| 两端浏览器 | 同一个GitNova Server | `http://MAC_PRIVATE_IP:8080`；前端是独立dev server时将API代理转到此处 |
| Mac GitNova/SDK探针 | Windows的OpenSandbox | `OPEN_SANDBOX_DOMAIN=WINDOWS_PRIVATE_IP:8090`，`useServerProxy=true` |
| Windows Sandbox中的Worker | Mac的模型代理 | `http://MAC_PRIVATE_IP:8080/internal/agent/model/chat/completions`，不是Windows的host.docker.internal |
| Windows WSL中的OpenSandbox | Docker映射端口 | `proxy.resolve_internal=false`时用WSL确实可达的docker.host_ip；与Mac访问地址不是一个概念 |

`OPEN_SANDBOX_DOMAIN`不含scheme；Worker模型URL包含`http://`或`https://`。所有端点headers和代理路径继续由SDK/WorkerEndpoint处理，不把8081作为固定Windows主机公开端口。

两个代理开关不要混淆：`useServerProxy=true`决定**Mac SDK通过OpenSandbox服务访问沙箱**；`proxy.resolve_internal=false`决定**OpenSandbox服务在它所在主机怎样抵达容器**。[D-S06]

### 9.2 Windows入口：镜像网络或NAT端口转发，二者择一

WSL默认NAT不保证Mac可以直连WSL服务。先查看Windows/WSL版本：Windows11 22H2及适用WSL版本可选mirrored；其他情况沿NAT配置转发。不假设用户已经有某个版本，也不通过关闭全部防火墙来试通。[D-S03][D-S04]

**镜像网络路径：** 合并`networkingMode=mirrored`到`.wslconfig`，在无活动任务时重启WSL/Docker。WSL内OpenSandbox监听0.0.0.0:8090；Windows防火墙和适用的Hyper-V防火墙允许管理请求入站。只放行所需端口和可信来源，不采用全局DefaultInboundAction Allow。然后从Mac验证Windows私网8090。

**NAT路径：** Windows管理员PowerShell转发Windows私网8090到Ubuntu的WSL IP。以下只处理这一端口；IP用本机实际值，不覆盖其他服务已有的转发。

```powershell
# 管理员PowerShell；请输入实际私网地址，勿填0.0.0.0。
$WindowsIp = Read-Host 'Windows private IPv4'
$MacIp = Read-Host 'Mac private IPv4'
$WslIp = ((wsl.exe -d Ubuntu -- hostname -I).Trim() -split '\s+')[0]
$WslIp  # 多地址时先核实这是Ubuntu可达的IPv4
netsh interface portproxy show all
netsh interface portproxy add v4tov4 listenaddress=$WindowsIp listenport=8090 connectaddress=$WslIp connectport=8090
New-NetFirewallRule -Name 'GitNova-OpenSandbox-Dev' -DisplayName 'GitNova OpenSandbox dev' -Direction Inbound -Action Allow -Protocol TCP -LocalAddress $WindowsIp -LocalPort 8090 -RemoteAddress $MacIp -Profile Private
```

已有同名规则时查看并修改那一条，不反复创建；WSL重启可能改变IP，需要重新核对转发。只清理本次规则的逆操作为`netsh interface portproxy delete v4tov4 listenaddress=$WindowsIp listenport=8090`和`Remove-NetFirewallRule -Name 'GitNova-OpenSandbox-Dev'`。不要删全部portproxy或关闭Windows防火墙。

如Hyper-V防火墙仍阻止WSL入站，可按Microsoft文档为WSL创建仅TCP8090的规则：

```powershell
# 仅在此命令/WSL防火墙受当前Windows支持时，由管理员执行。
New-NetFirewallHyperVRule -Name 'GitNovaWSL8090' -DisplayName 'GitNova WSL 8090' -Direction Inbound -VMCreatorId '{40E0AC32-46A5-438A-A0B2-2B479E8F2E90}' -Protocol TCP -LocalPorts 8090
```

这里放行端口并不替代前面的可信来源约束。Wi-Fi来宾隔离、VPN或安全软件也可能阻断双机，测试时分别定位，不通过扩大公网开放范围解决。

### 9.3 管理服务到Docker发布端口也必须可达

Windows片段设置`proxy.resolve_internal=false`，但`docker.host_ip`不能盲填MacIP。在WSL mirrored模式下可先验证Windows映射端口能否经127.0.0.1到达；NAT模式下可先查看Windows在WSL侧的网关：

```bash
# Windows Ubuntu/WSL；候选地址，不是未经测试就确定可用。
ip route show default | awk '/default/ {print $3; exit}'
```

将**已验证**可达Windows Docker发布端口的地址写入`docker.host_ip`。若OpenSandbox确实能直达容器bridge IP，也可选择`resolve_internal=true`并保留证据；不要混用两种路由。配置变更后重启管理服务需避开活动创建/回收操作。

防火墙如阻止WSL访问动态映射端口，只为WSL实际来源/本地路径放行所选范围，不能把40000—41000开放给任意LAN/公网用户。Mac使用8090上的代理，不需要直接访问这些端口。[D-S06][D-S07]

从Mac先检查：

```bash
# Mac终端：值由你填写。
export OPEN_SANDBOX_DOMAIN="${WINDOWS_PRIVATE_IP}:8090"
curl --fail "http://${OPEN_SANDBOX_DOMAIN}/health"
# 管理API的认证验证用第10章SDK探针；health本身通常无需key。
```

health成功但SDK探针失败，优先分清是认证、内部Docker路由、image拉取还是应用端口；不是直接重写Agent协议。

### 9.4 Sandbox回连Mac：不能只测试单向可达

GitNova Server调试进程必须在Mac允许的网卡监听，例如在原启动配置增加`server.address=0.0.0.0`、保留`server.port=8080`，并通过Mac防火墙/网络限制可信来源。双机只是开发环境，不意味着认证可以删除。

在还没实现Model Proxy之前，可以用第10章临时回连HTTP探针先证明网络；实现后必须用真正的受限Session身份调用`/internal/agent/model/chat/completions`，验证401/403、取消及额度，不因临时探针成功就标记模型接线完成。

前端API使用相对路径或统一配置，不把`http://localhost:8080`打包为所有客户端的地址：Windows浏览器的localhost是Windows，不是Mac。跨源开发需沿现有前端代理/CORS与认证方案接线，不新增公开Worker入口。

### 9.5 开关机、数据与32G资源

关页面不取消Task；但Windows睡眠、WSL shutdown、Docker退出是执行宿主中断。Mac承载Server/Proxy时，Mac睡眠是控制/模型服务中断。即使Worker还能完成当前子进程，也不能保证后续模型请求或发布继续。跨设备访问不改变故障域。

开发期间可在接电状态关闭自动睡眠，并明确保留WSL/OpenSandbox前台进程；正式持续运行要做受控服务托管和重启验证。不要把SSH/终端窗口一关闭导致管理服务退出，误解成Session属于那台客户端。

会话卷在Windows Docker Engine，代码归档和数据库按平台既定存储保存。换设备无需复制Session目录；换执行宿主则是原恢复流程，需要归档/确认旧实例，不属于浏览器续接。磁盘和Docker数据目录也需观察；32G内存不保证足够的磁盘空间。

## 10. 提前执行独立探针；生产Worker仍按步骤9验收

这里区分三种检查。只有第一个可在完全无Docker的本机完成；后两项需要Windows真实执行环境。本包不声明已在你的双机环境通过。

### 10.1 纯Java通信与Linux启动器

```bash
# Mac或Linux：在本手册目录执行纯Java探针。
mkdir -p /tmp/gitnova-doc-probe
javac --release 17 -d /tmp/gitnova-doc-probe probes/TransportProbe.java
java -cp /tmp/gitnova-doc-probe TransportProbe
# Windows Ubuntu/WSL：在已同步的手册目录执行Linux进程检查。
python3 scripts/test_process_launcher.py
```

前者验证loopback HTTP、代理前缀/headers、输入字节与SSE分片；后者验证Linux启动器。它们都不是OpenSandbox跨机验证，更不是生产TaskInbox。

### 10.2 从Mac创建Windows Sandbox，并测试回连

先按第2、9章启动Windows的OpenSandbox。提前探针只依赖随包`probes/opensandbox/pom.xml`与源码，不需要agent-runtime模块已经写好。

为验证反向通路，可在Mac另开终端运行一个仅提供合成标记的临时HTTP服务：

```bash
# Mac终端A；MAC_PRIVATE_IP先设置为实际地址，不用空值或0.0.0.0当客户端地址。
: "${MAC_PRIVATE_IP:?set actual Mac private IP}"
PROBE_DIR=$(mktemp -d)
printf 'gitnova-dual-host-probe' > "$PROBE_DIR/probe.txt"
python3 -m http.server 18080 --bind "$MAC_PRIVATE_IP" --directory "$PROBE_DIR"
# 测完Ctrl+C；仅删除这次mktemp创建的目录，不服务整个home/仓库。
```

Mac终端B设置客户端配置，然后用同一探针访问Windows。不要把真实key提交到Git；可从私有终端环境/凭据管理注入。

```bash
: "${WINDOWS_PRIVATE_IP:?set Windows private IP}"
: "${MAC_PRIVATE_IP:?set Mac private IP}"
export OPEN_SANDBOX_DOMAIN="${WINDOWS_PRIVATE_IP}:8090"
export OPEN_SANDBOX_PROTOCOL=http
export OPEN_SANDBOX_ARCH=amd64
# 设置OPEN_SANDBOX_API_KEY为Windows配置中的同一秘密；不在本手册给固定key。
export GITNOVA_PROBE_CALLBACK_URL="http://${MAC_PRIVATE_IP}:18080/probe.txt"

# 在Mac的GitNova仓库根执行；Maven下载只用于独立SDK探针。
./mvnw -f docs/harness-refactor/probes/opensandbox/pom.xml package dependency:build-classpath \
  -Dmdep.outputFile=target/classpath.txt
java -cp "docs/harness-refactor/probes/opensandbox/target/classes:$(cat docs/harness-refactor/probes/opensandbox/target/classpath.txt)" OpenSandboxProbe
```

只先验证Mac→Windows时可不设置GITNOVA_PROBE_CALLBACK_URL，但报告必须注明反向未测试。PowerShell原生Java classpath用`;`，本手册上述命令指定Mac/WSL Bash，不能直接粘到PowerShell期待同样语义。

探针创建独立Python Linux环境，访问应用、读取实际`platform.machine()`、按帧计时SSE，并在设置回连URL时由**沙箱内部**请求Mac合成标记。它不访问用户源码、不调用模型、不创建GitNova业务Task。已发送kill请求不等于已经确认删除；按管理API确认终止后，才按输出的精确测试卷名清理，不使用通配符批量删卷。

此探针的SDK调用依据固定tag核对，修改后的版本仍需要在你的机器编译和运行。SSE耗时阈值是测试阈值，可显式调整并记录，不是保证生产延迟。命名卷重挂、Server重启、Host睡眠与产品多客户端用例需另做。

### 10.3 与正式Worker汇合

步骤8完成生产Worker后，在Windows构建镜像，按步骤9验证真实INITIALIZE、ready身份、两Task连续、seal/ACK、模型回连与取消；随后步骤10—18连接平台数据库、页面与PR。独立探针通过，不允许提前删除旧链。

多客户端运行验收按03的D05—D12：两台设备均访问同一个Server、同一数据源中的Session。增加浏览器订阅不增加Worker，关闭浏览器不取消任务。环境准备和源码手写可以并行推进，但放行条件不降低。

## 11. 最终运行目录与配置输入（消除示意图歧义）

本包镜像的真实根为`/session`，代码目录固定为`/session/worktree`，Agent状态为`/session/state`，其他目录为`/session/baseline`、`/session/bootstrap`、`/session/exports`。不额外建立/workspace符号链接，避免SafePaths与真实挂载路径矛盾。以上路径来自WorkerConfig.sessionRoot派生，不让每条Task传任意目录。

创建API还必须设置 `.env("GITNOVA_RUNTIME_CONFIG_JSON", spec.runtimeConfigJson())`。WorkerConfig.fromEnvironment先解码这份非秘密运行配置，验证结构和SHA256；INITIALIZE只核对同一configDigest，不需要在尚未ready时回调平台取配置。样例字段见examples/runtime-config.json；其中model ID和thinkingJson必须换成你当前已验证的供应商配置。这是两个明确的用户环境参数，不是新架构决策。

## 12. 本次修改的事实依据与自检边界

CLI入口、代理配置、SDK基线来自固定`release-1.1.0`源码，已在本次重新核对；Windows/WSL网络与资源说明来自Microsoft和Docker官方资料。[D-S01]—[D-S07]链接集中在03第11章。设备分工、16GB WSL初值和先运行单Sandbox是本项目的部署建议，不是对你机器的测量结论。

本轮的配置解析、链接、CLI命令静态检查、目标文件对齐与本地探针证据写在07和audit中。Windows/Mac连通、SDK编译、容器SSE与模型访问、实际内存/磁盘峰值均需由本章实机步骤确认；不能把文档校验PASS写成双机部署已运行。
