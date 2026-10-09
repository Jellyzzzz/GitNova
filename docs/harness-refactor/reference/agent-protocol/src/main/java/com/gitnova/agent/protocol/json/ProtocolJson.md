# ProtocolJson：步骤 1.3 方法施工说明

本说明吸收上传补丁的方法细节、旧代码复用点和测试案例，**不替换02的施工顺序**：CommandType → AgentCommand/Payload → ProtocolJson → Submit/Cancel往返 → CommandReceipt → EventType → AgentEvent。此处不等待AgentEvent，不提前接Worker。

生产文件由你手写：`agent-runtime/agent-protocol/src/main/java/com/gitnova/agent/protocol/json/ProtocolJson.java`。这里是Markdown方法说明，**不是已完成的Java实现**。reference的离线纯JDK类型编译也不能代替生产模块的Jackson测试。

## 1. 先确认输入、输出和前置内容

本步已有前置：CommandType、带构造校验的AgentCommand及其八个内部Payload；agent-protocol的POM已经声明jackson-databind、jackson-datatype-jsr310和JUnit。沿用聚合POM的版本，不引入Spring或JSON Schema运行时。

```text
有界fixture字节
  → readCommand：严格JSON解析、按type选择Payload
  → Payload构造校验 → AgentCommand构造校验
  → 合法命令对象
  → canonicalBytes：固定JSON表示
  → sha256：对这份字节生成摘要

后续步骤6才接：HTTP → 同一readCommand → TaskInbox受理
```

| 组件 | 负责什么 | 不负责什么 |
|---|---|---|
| ProtocolJson | 严格解码、固定JSON编码、规范字节和hash | 当前Worker归属、忙闲状态、执行任务、查数据库 |
| AgentCommand/Payload构造器 | 字段值是否合法、type与Payload是否配对 | HTTP状态码、当前时间、Worker是否允许执行 |
| HttpJson（步骤6） | HTTP认证/Content-Type、先限制body、调用解析、映射响应 | 决定同一命令是否重复执行 |
| TaskInbox（步骤6） | 核对Session/Workline/epoch、状态、重复受理、调度 | 猜测JSON子类型、静默改写请求身份 |

协议格式正确不代表业务已受理。ProtocolJson也不是模型输出解析器；不能用它替代ModelGateway的协议处理。

## 2. 旧代码能复用什么

先读已有的`src/main/java/com/gitnova/service/agent/persistence/CanonicalJsonCodec.java`，不是从零发明一套JSON算法。

| 已有方法 | 可复用的逻辑 | 本次不能直接照搬的地方 |
|---|---|---|
| `encodeValue(Object)` | Java对象转JsonNode，再进入统一编码路径 | 不依赖原来的Spring Bean，也不增加EncodedJson包装作为本步前置 |
| `sortObjectFields(JsonNode)` | 对象递归排序；数组保留原序；标量保留类型 | 旧实现用Java naturalOrder；新协议规定字段名按UTF-8无符号字节排序 |
| `sha256(String)` | MessageDigest + 小写HexFormat | 新入口对canonicalBytes直接计算，不绕回任意字符串 |

**只提取纯算法，不引入旧Step/Outbox/Entity，不修改旧Codec。** 已持久化的旧摘要仍按原算法解释，不能为了新协议顺便重算或替换旧摘要。

## 3. 公开方法与异常边界

以下是签名清单，不是可直接编译的完整类；生产类为`public final class ProtocolJson`，私有构造器防止实例化。

```java
public static ObjectMapper mapper();
public static byte[] canonicalBytes(Object value) throws IOException;
public static String sha256(Object value) throws IOException;
public static AgentCommand readCommand(byte[] body) throws IOException;
```

保留四个入口即可。递归排序确实需要时，在同一个类内写一个私有递归方法；不要求再拆JsonFactoryService、EnvelopeValidator或八个Payload Decoder类。readCommand按“信封 → Payload → 命令对象”的顺序直读即可。

- JSON语法、结构和解码错误通过`IOException`族表达，错误信息指出字段，例如`payload.coveredThrough must be an integer`，不拼接完整用户载荷。
- 直接构造值对象违反约束，使用`IllegalArgumentException`；Jackson调用构造器时可能将其包装为映射异常，保留cause，不靠异常文本猜业务状态。
- 编码失败必须传播，不能返回空字节、空hash或null冒充成功。`valueToTree`包装的编码异常按同一IOException边界处理；SHA-256算法不可用属于内部故障，不是用户格式错。
- 步骤6只将**请求解码/构造阶段**的格式错误映射为400；不能把响应编码失败、磁盘I/O或其他内部故障也一概映射为400。超body限制由HTTP入口返回413。

本步不新增自定义协议异常类，沿用02的异常约定。

## 4. 四个方法具体怎么写

### 4.1 mapper()：固定编码规则

1. 在类内一次性建立私有ObjectMapper。注册JavaTimeModule，禁用`SerializationFeature.WRITE_DATES_AS_TIMESTAMPS`；协议时间类型为Instant，以UTC ISO-8601字符串写出，不用机器默认时区。
2. 开启`DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES`、`FAIL_ON_TRAILING_TOKENS`。JsonFactory启用`StreamReadFeature.STRICT_DUPLICATE_DETECTION`，包括嵌套对象的重复键。
3. 不启用default typing；不由输入中的`@class`或Java类名选择运行类型。信封白名单与type分派由readCommand显式完成。
4. 配置解析深度/字符串等资源上限时用Jackson的StreamReadConstraints，不能把HTTP先读完整body再检查当作限额控制。具体限额沿用05与部署配置，本步测试使用有界fixture。
5. canonicalBytes/readCommand始终使用这份私有、初始化后不再修改的Mapper。`mapper()`对外返回已配置的`copy()`；调用方在组件构造时取一次并复用，不要每次请求取副本，也不要改变协议编码规则。这样调用方修改副本不会悄悄改变摘要算法。

注意两个容易误解的地方：`FAIL_ON_UNKNOWN_PROPERTIES`不会替JsonNode检查字段白名单；Jackson也可能把数字强转成String、把字符串强转成数字。不能只开几个feature就跳过下一节的节点类型检查。

### 4.2 canonicalBytes(value)：把同一协议值编码为稳定字节

输入为受支持的协议DTO/record或JSON树，不接收任意业务对象图。依次处理：

1. 用固定Mapper将输入转为JsonNode。字符串是字符串值，不自动把`"{\"a\":1}"`当作一个JSON对象解析。
2. 遇到object：收集字段名，按**UTF-8无符号字节词典序**排序；依次递归处理字段值，放入新的ObjectNode。
3. 遇到array：按原下标递归处理元素，放入新的ArrayNode，不排序元素。
4. 遇到标量：保留字符串、数字、boolean、null的类型和值，不修改原树。
5. 将新树无缩进地序列化为UTF-8字节；保留显式null字段，不能因为使用NON_NULL而丢掉Initialize的taskId/attemptId。

字段名比较可直接使用JDK的`Arrays.compareUnsigned(nameA.getBytes(UTF_8), nameB.getBytes(UTF_8))`。不使用locale排序，也不能默认Java字符串排序与它完全一致。例如键`\uE000`和`\uD800\uDC00`（U+10000），新契约下前者在前，而Java UTF-16自然排序相反。

用户正文不trim、不改换行、不做Unicode归一化。数字先由字段定义解码成约定Java类型；本方法不承诺把任意JSON中的`1`、`1.0`、`1e0`自动归为同一字节表示，也不声称实现了通用JSON规范化标准。

对于每个含k个字段的对象，排序约为O(k log k)次比较，另加遍历与字符串比较成本；副本空间随输入大小增长。本步有界协议消息可以接受，不要因此新建缓存系统。

JSON规范字节与05第15节的TREE二进制摘要是不同契约；不能拿canonicalBytes代替文件树的path/size/blob编码。

### 4.3 sha256(value)：只消费上一步的字节

```text
bytes = canonicalBytes(value)
digest = SHA-256(bytes)
return 小写64位hex
```

不能用hashCode、toString或未经规范化的JSON文本。业务层先选定参与幂等判断的字段，本方法不替业务层删字段，也不自动排除deadline。

### 4.4 readCommand(body)：按type解码，不让Jackson猜Payload

**第一段：解析信封。**

1. 拒绝null/空输入；用严格Mapper读**一份**JSON对象。顶层为数组、标量、null、重复键、非法UTF-8或有尾随内容均拒绝。
2. 根对象必须恰好包含九个键：`schemaVersion / commandId / sessionId / worklineId / runnerEpoch / type / taskId / attemptId / payload`。未知键、缺失键均拒绝；taskId允许null不等于允许省略该键。
3. 先验节点类型再取值：schemaVersion是可表示为int的整数；runnerEpoch是可表示为long的整数；身份与type为字符串；taskId/attemptId为字符串或显式null；payload为object。使用`isIntegralNumber`配合`canConvertToInt/Long`，不能用asInt/asLong/asText把错误值静默转成默认值。
4. 将type解析为CommandType；未知值拒绝。schemaVersion==2、UUID、epoch正值及任务身份配对由已有构造器检查，不把这些规则再复制进一套新Validator。

**第二段：在同一方法内switch(type)，选择Payload。**

下表中的键都必须存在；先检查字段集合与节点类型，再解码到对应内部record。除coveredThrough为可表示为long的整数以外，下表字段均为非null字符串；deadlineAt随后解析为Instant，purpose随后解析为CheckpointPurpose。hash、ID、正负值、非空白等由内部record执行静态校验。

| type | 目标Payload | 必需键（且不允许额外键） |
|---|---|---|
| INITIALIZE | AgentCommand.Initialize | bootstrapId、bootstrapSha256、baseCommit、publishedHead、runtimeConfigDigest |
| SUBMIT_TASK | AgentCommand.Submit | message、expectedPublishedHead、deadlineAt、runtimeConfigDigest、worklineStatus |
| STEER_TASK | AgentCommand.Steer | message |
| CANCEL_TASK | AgentCommand.Cancel | reason |
| PREVIEW_CHANGES | AgentCommand.Preview | expectedPublishedHead |
| CHECKPOINT_SESSION | AgentCommand.Checkpoint | purpose |
| ACK_CHECKPOINT | AgentCommand.CheckpointAck | exportId、archiveId、bundleSha256、coveredThrough |
| STOP_WORKER | AgentCommand.StopWorker | confirmedArchiveId |

按上述检查后的树调用`treeToValue(payloadNode, 对应record.class)`即可；无须建立通用JSON Schema引擎。规范字段来源以command.schema.json与05为准；改字段时这张表也须同步。

**第三段：构造命令并返回。**

从信封显式取公共字段，以刚构造的Payload调用`new AgentCommand(...)`；外层构造器再次保证任务身份有无和type/Payload配对。不要直接`readValue(body, AgentCommand.class)`期待sealed Payload自动完成分派。

此处不能检查“deadline相对现在是否过期”“请求是否指向当前Worker”“是否IDLE”，也不能将session/epoch改成当前值后接收；这些是步骤6的受理条件。readCommand返回也不代表COMMAND_ACCEPTED已写入。

## 5. 写完方法后，按这些案例验证

本类内部可按mapper → canonicalBytes → sha256 → readCommand完成；这是步骤1.3内部顺序。**不把它扩展成“写完所有协议类型再测试”。** 第一次往返在02步骤1.4，Receipt/Event随后再写；HttpJson接线仍在步骤6。

测试文件：`agent-runtime/agent-protocol/src/test/java/com/gitnova/agent/protocol/json/ProtocolJsonTest.java`。将docs里的Submit/Cancel/Initialize fixture复制到本模块src/test/resources，从classpath读，不依赖开发机绝对路径。

| 测试名 | 构造方式与关键断言 |
|---|---|
| canonicalNestedOrder | 外层与嵌套对象都改变插入顺序；canonicalBytes与sha256仍相同 |
| unicodeKeyOrder | 键U+E000与U+10000按新UTF-8规则排序；编码字节有固定预期，不能只比较两次结果相同 |
| arrayOrderMatters | `[1,2]`与`[2,1]`的摘要不同 |
| originalMessagePreserved | Submit含中文、引号、换行和首尾空格；往返message逐字符相等 |
| submitRoundTrip / cancelRoundTrip | fixture → readCommand → canonicalBytes → readCommand；两次命令相等，Payload类型正确 |
| typeMismatch | 将Submit fixture的type改为CANCEL_TASK，拒绝多余/缺失Payload字段 |
| duplicateAndTrailing | 分别在根对象、payload放重复键；合法对象后追加第二份JSON；均拒绝 |
| unknownField | 顶层多字段、Payload多mode/allowedTools，分别拒绝 |
| missingOrNull | 删除必需键、非nullable字段设null；只错taskId或只错attemptId也拒绝；Initialize显式null/null通过，漏掉其中任一键拒绝 |
| scalarTypeAndRange | runnerEpoch传字符串、boolean、1.5或超过long范围；message传数字；coveredThrough漏键/类型错误；均拒绝，不默认为0 |
| noDefaultTyping | 根对象与Payload加入@class等字段，拒绝且不实例化输入指定的类型 |
| parserLimits | 超深JSON与非法UTF-8拒绝；HTTP读取超body上限的413在步骤6另测，不能用这里的测试冒充 |
| hashIsStable | 固定合法命令重复编码一致；SHA为小写64hex，并核对一个固定输入的预期摘要 |
| stringIsNotDocument | `canonicalBytes("{\"a\":1}")`与JSON对象`{"a":1}`的字节不同；不会偷偷二次解析 |
| mapperIsolation | 修改mapper()返回副本的缩进/日期配置；canonicalBytes仍保持原格式，且保留必需null字段 |
| encodingFailure | 用测试专用的编码失败对象触发异常，不能返回空字节或伪造成功hash |

编码预期值应手工写出或用独立标准库复核，不能用待测canonicalBytes自己生成golden值。Java构造器被Jackson包装异常时测试可检查cause；不要强行要求所有失败都是同一个异常子类。

从仓库根执行：

```bash
mvn -f agent-runtime/pom.xml -pl agent-protocol -am test
```

这里提供的是**待实现的测试案例**，不是已有测试通过记录。文档静态自检仅核对入口、顺序、链接与契约一致性；生产方法是否正确，以你实现后的JUnit结果为准。

## 6. 两种幂等摘要不能混为一谈

- **私有命令摘要：** Worker以commandId查受理记录，再比较同一完整AgentCommand的规范化摘要。网络重发沿用原命令身份与deadline，不能重新分配attemptId或续期。
- **公共Task请求摘要：** Server选择repoId、actorId、sessionId、expectedWorklineId、expectedSessionVersion、用户原文及明确用户参数等稳定输入。首次受理生成的deadline/attemptId不在每次重试时重新纳入公共请求身份。

同一个底层sha256方法可以复用，但参与摘要的对象由各自业务边界选择。ProtocolJson既不负责建立唯一索引，也不保证命令执行幂等。
