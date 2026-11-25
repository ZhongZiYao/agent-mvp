# agent-mvp —— AgentScope Java 落地边界清单（可运行 demo）

> 这个 demo 的**唯一目的**：用能跑的代码，逐项回答「AgentScope Java 到底给了什么，
> 哪些是框架直接给、哪些半自建、哪些全自建」。
>
> 判定结论与证据整理在：
> `customer_work/docs/learning/agentscope-java-boundary-checklist.md`

---

## 分站路线

| 站 | 内容 | 状态 |
|---|---|---|
| 0 | 纯框架最小 Agent（先当一次框架使用者） | ✅ 完成 |
| 1 | 接入层（WS 帧 → 受理 → 编排） | ✅ 完成 |
| 2 | 装配层（手写 AgentFactory + 治理装配器） | ✅ 完成 |
| 3 | 治理层（中间件链 + `MiddlewareOrders` 契约） | ⬜ 待做 |
| 4 | 记忆 + RAG | ⬜ 待做 |
| 5 | 出站（过滤 + 缓存） | ⬜ 待做 |
| 6 | 多租户 + admin | ⬜ 待做 |

**每站结束都能跑**，每站结束往边界清单追加一节。

---

## 仓库结构

```
agent-mvp/
├── pom.xml                    parent（Bom + 依赖管理 + Java 21 编译基线）
├── .env                       密钥（本地，已 gitignore）—— 从 .env.example 复制
├── .env.example               密钥模板（入库）
├── mvp-common/                能力层：模型装配 / Agent 装配 / 治理中间件 / 工具 / 令牌
│   ├── config/                ModelConfig · ModelConfiguration · MvpProperties · AgentProperties
│   ├── model/                 SwappableModel（自建 Model 装饰器：热替换 + 归因）
│   ├── agent/                 MvpAgentFactory · MvpGovernanceAssembler · InMemoryAgentStateStore
│   │                          ToolAuditMiddleware · OrderTools · SimpleAgentFactory（站 0 裸版）
│   └── auth/                  TokenService（零依赖自签 HMAC）
├── mvp-app/                   运行层：HTTP / SSE / WebSocket 接入
│   ├── auth/                  AuthController · AuthWebFilter
│   ├── chat/                  ChatDispatchService（受理 + 幂等 + 归属）
│   ├── ws/                    UserChatWebSocketHandler · WsFrame · WsSessionRegistry · WebSocketConfig
│   ├── outbound/              OutboundFilter（出站过滤规则）
│   ├── agent/                 AgentAssemblyController（站 2 装配快照）
│   └── config/                JacksonConfig（Boot 4 的 Jackson 3 兼容）
├── scripts/                   check-minimax.ps1（密钥自查）· mock_openai.py（本地 mock）· WsProbe.java（WS 探针）
└── docs/                      （站 3 起补：每站的学习笔记）
```

**刻意不用官方 `agentscope-spring-boot-starter`** —— 它只给 3 个 bean
（`Memory` / `Toolkit` / `ReActAgent`），且不提供 `Model` bean，
装配层全部手写才看得出边界在哪。

**分层约定**：`mvp-common` **不依赖** `mvp-app`，只依赖框架与 Spring；
`mvp-app` 只做「运行面」（协议 / 鉴权 / 受理 / 出站），**不含任何业务规则**。
所以能力层可以被换一个前端或换一种接入协议复用。

> **关于根目录的 `*.log`**：后台启动应用 / mock 时重定向 stdout-stderr 产生的。
> 它们落在 `E:\Program Files\` 下，**物理删除被安全策略拦截**，
> 所以靠 `.gitignore` 的 `*.log` 排除 —— 不会进仓库。

---

## 跑

```powershell
Set-Location '<到 agent-mvp 的路径>'
$env:JAVA_HOME='D:\Program Files\JAVA\jdk-21'      # 本机 JDK 21（Boot 4.1.3 最低 17，此处取 21）
$env:Path="$env:JAVA_HOME\bin;$env:Path"

# 只测框架行为，不烧 token（假模型）
mvn -B clean test

# 接真模型
$env:MINIMAX_API_KEY='<你的 key>'
mvn -B spring-boot:run -pl mvp-app
```

> **Java 版本**：Spring Boot 4.1.3 的**最低要求是 Java 17**（官方 System Requirements：
> `requires at least Java 17 and is compatible with versions up to and including Java 27`），
> parent pom 的编译基线也是 17。本 demo 取 **21**（4.x 对虚拟线程支持最好，
> 且与主仓库 `customer_work` 不共享代码、不混用编译产物）。
> 要退回 17：改 `pom.xml` 里 `<java.version>` 一行即可。

起在 `8088`。**站 1 起，HTTP 端点需要鉴权**：

```
① POST /api/auth/token   {"userId":"u1001"}     →  {"token":"...","userId":"u1001","expiresInMillis":...}
② POST /api/chat/sync    Authorization: Bearer <token>   {"message":"我的订单 SO202611250001 到哪了？"}
③ POST /api/chat/stream  Authorization: Bearer <token>   （SSE）
④ WS   ws://localhost:8088/ws/user?token=<token>        （token 在 query：浏览器 WS API 不支持自定义 header）
⑤ GET  /api/agent/assembly                              （站 2：装配快照，看这个 Agent 身上装了什么）
```

`mvp-api.http` 里每条都写好了，直接在 IDEA 里点绿三角。

> **⑤ `/api/agent/assembly` 是站 2 的关键出口**：
> 装配层的错误几乎全是**不报错的静默错误**（中间件漏挂、权限没生效、
> `maxRetries` 被 `modelExecutionConfig` 静默丢弃……），
> 唯一的发现手段就是**把运行期的实际装配结果打出来看**。
> 它还会暴露**框架自动注入的东西**（见下文 `GracefulShutdownMiddleware`）。

---

## 配置密钥：`.env` 文件（不用碰 IDEA 环境变量）

项目根目录有 `.env`，格式就是 `KEY=VALUE`：

```
MINIMAX_API_KEY=你的key
```

`application.yml` 里这行把它导进 Spring：

```yaml
spring:
  config:
    import:
      - "optional:file:.env[.properties]"      # 从项目根启动时
      - "optional:file:../.env[.properties]"   # 从 mvp-app 启动时
```

两个路径都写，是因为启动目录可能是项目根（`java -jar`）也可能是 `mvp-app`（`mvn spring-boot:run -pl mvp-app`）。
`optional:` 前缀保证文件不存在时应用照常启动，只是没有 Key。

> **实测**：清空进程环境变量后，应用照样从 `.env` 读到 Key 并成功打出真实请求
> （`https://api.minimaxi.com/v1/chat/completions`）—— 两种启动目录都验过。
>
> 改 Key 只需编辑 `.env` 保存后**重启应用**，不用重新构建。

---

## 不用 Postman 的三种测试方式

### ① IDEA 自带 HTTP Client（最省事）

打开 `mvp-app/src/test/http/mvp-api.http`，每条请求左边有个绿色三角，**点一下就跑**，
结果直接显示在 IDEA 里。不用装任何插件。

### ② curl（命令行）

> ⚠️ **PowerShell 5.1 会把传给原生 exe 的参数里的双引号剥掉**。
> 所以 `-d '{"userId":"u1001"}'` 实际发出的是 `{userId:u1001}`（少了 4 个字节）→ 服务端 400 Bad Request。
> **两种正确写法**：

```powershell
# 写法 1（推荐）：body 放文件，文件用 UTF-8 无 BOM
[System.IO.File]::WriteAllText('req.json', '{"message":"我的订单 SO202611250001 到哪了？"}',
    (New-Object System.Text.UTF8Encoding($false)))
curl.exe -X POST http://localhost:8088/api/chat/sync `
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json; charset=utf-8' `
  --data-binary "@req.json"

# 写法 2：转义双引号（curl 会把 \" 还原成 "）
curl.exe -X POST http://localhost:8088/api/chat/sync `
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json' `
  --data-raw '{\"message\":\"hi\"}'
```

> ⚠️ **中文一定要显式指定 UTF-8**。用 `Invoke-WebRequest -Body '中文'` 时
> PowerShell 5.1 会按 ANSI 编码发送，模型收到的是 `???? ?? SO202611250001 ????`。
> 用 curl 记得 `--data-raw` 并带 `charset=utf-8`，或用 `-d @file.json`（文件存 UTF-8）。

### ③ 本地 mock 端点（不发外网、不花钱，看清真实请求体）

```powershell
python scripts\mock_openai.py 9999
# 另一个终端
mvn -B spring-boot:run -pl mvp-app -Dspring-boot.run.arguments=--mvp.model.base-url=http://localhost:9999/v1
```

mock 会把 AgentScope 发出的**完整请求体**打印出来。用它验证过一次，实际发出的是：

```json
{
  "model": "MiniMax-M3.1-Flash",
  "messages": [
    { "role": "system", "content": "你是电商平台的智能客服助手。…", "name": "system" },
    { "role": "user",   "content": "我的订单 SO202611250001 到哪了？" }
  ],
  "stream": true, "stream_options": { "include_usage": true },
  "temperature": 0.3,
  "max_completion_tokens": 1024,
  "tools": [{ "type": "function", "function": {
      "name": "query_order_status",
      "description": "根据订单号查询订单的当前状态（…）",
      "parameters": { "type": "object",
        "properties": { "orderId": { "type": "string", "description": "订单号，形如 SO202611250001" } },
        "required": ["orderId"] } } }],
  "reasoning_split": true
}
```

**这四条是框架直接给的硬证据**：
① `model` 用我们配的值；② **system role 完整保留**（通用 OpenAI Formatter 会丢，框架 #2189）；
③ `max_completion_tokens` 而非弃用的 `max_tokens`（`MiniMaxFormatter` 内部转换）；
④ tools 的 JSON Schema 由框架从 `@Tool`/`@ToolParam` 自动生成，一个字没手写。

模型名以 MiniMax 控制台为准；国际站把 `mvp.model.base-url` 换成
`https://api.minimax.io/v1`。

---

## 在 IDEA 里跑（与 Maven 的关系）

### 绿三角运行 = IDEA 自己编译，**不是** Maven

Spring Boot 运行配置默认带 `Before launch → Build`。这个 Build 走的是 **IDEA 内建的增量编译器**，
**不执行 Maven 生命周期**，等价于 Maven 的 `compile` 阶段 —— 不跑 `test`、不跑 `package`、不跑任何 Maven 插件。

| 配置项 | 在哪设 | 说明 |
|---|---|---|
| Project SDK | `File → Project Structure → Project` | 选 **21** |
| Language level（Project） | 同上 | 选 **21** |
| Language level（Module） | `Project Structure → Modules → mvp-common / mvp-app` | 各选 **21** |

> ⚠️ pom 里的 `<java.version>21</java.version>` / `maven.compiler.release` **对 IDEA 不生效** ——
> IDEA 只认上面的 SDK 与 Language level。不设 Language level 时 IDEA 会用默认值，
> 报 "source/target 17 is obsolete" 警告，**21 的语法用不了**。

### 手动 Maven 构建点哪个生命周期

| 场景 | 在 IDEA Maven 面板点 | 等价命令 |
|---|---|---|
| **推荐**：改完代码要跑起来 | `agent-mvp-parent` → `clean`，再 → `install` | `mvn -B clean install` |
| 只改 `mvp-app` | `mvp-app` → `clean` → `package` | `mvn -B clean package -pl mvp-app -am` |
| 跑测试 | `agent-mvp-parent` → `test` | `mvn -B test` |
| 起服务 | 终端 | `mvn -B spring-boot:run -pl mvp-app` |

**关键：`-am`（also make）不能省。** `mvp-app` 依赖 `mvp-common`，而
`mvn spring-boot:run -pl mvp-app` **不会重新编译依赖模块** —— 改了 `mvp-common` 的源码再跑，
用的还是旧的 class，症状是「代码改了但行为没变」。加上 `-am` 才会一起重编。

---

## 排错速查

### 先跑这个自查脚本

```powershell
powershell -ExecutionPolicy Bypass -File scripts\check-minimax.ps1
# 或指定 key / 端点
powershell -ExecutionPolicy Bypass -File scripts\check-minimax.ps1 -ApiKey 'sk-xxx' -BaseUrl 'https://api.minimax.io/v1'
```

脚本做三件事，并把结论直接打出来：

| 步骤 | 请求 | 能证明什么 |
|---|---|---|
| 1 | `GET /v1/models` | **只验鉴权，完全不携带模型名** → 401 就是 Key 的问题，与模型 ID 无关 |
| 2 | 解析返回列表 | 列出**你账号实际可用**的模型 ID（比查文档准） |
| 3 | `POST /v1/chat/completions` | 端点 / 协议 / 模型 ID 全对 → 返回 200 |

> 脚本文件带 UTF-8 BOM —— PowerShell 5.1 读 `.ps1` 时按 ANSI 解码，
> 无 BOM 的中文会乱码并直接导致解析失败。

### 现象对照表

| 现象 | 根因 | 处理 |
|---|---|---|
| **模型收到 `???? ?? SO202611250001 ????`** | **测试端编码问题，不是应用**：PowerShell 5.1 的 `-Body '中文'` 按 ANSI 发送 | 用 `--data-raw` + `charset=utf-8`，或 `-d @file.json`（文件存 UTF-8） |
| **400 Bad Request，且 body 明显短了几字节** | **PowerShell 5.1 剥掉了传给原生 exe 的双引号**：`{"userId":"u1001"}` 变成 `{userId:u1001}` | 用 `--data-binary "@file.json"`，或 `--data-raw '{\"userId\":\"u1001\"}'` |
| 401 unauthorized | 令牌无效/过期，或没带 `Authorization: Bearer` / `?token=` | 先调 `/api/auth/token` 拿新令牌 |
| WS 握手 404 | 路径不对（应为 `/ws/user`），或端口不是 8088 | 核对路径与端口 |
| 500 + 日志 `authorized_error` / `login fail ... (1004)` / HTTP 401 | **Key 不被服务端接受**（不是模型 ID 问题） | 跑自查脚本；重置 Key、确认来自开放平台、检查账号权限与欠费 |
| 500 + 日志 `缺少模型 API Key` | 环境变量和 `.env` 都没读到。IDEA 里必须写 **`MINIMAX_API_KEY=xxx`**，只填值不写变量名 = 没设 | 运行配置 → 环境变量，写全 `变量名=值`；或直接改 `.env` |
| 启动失败 `No qualifying bean of type 'ObjectMapper'` | **Boot 4 迁到 Jackson 3**（`tools.jackson.*`），自动配置给的是 Jackson 3 的 ObjectMapper | demo 已显式声明 Jackson 2 的 bean（`JacksonConfig`）；你的工程按需选 Jackson 2 或 3 |
| 500 + `block()/blockFirst()/blockLast() are blocking ... reactor-http-nio-2` | 在 WebFlux 事件循环线程里阻塞 | 已修：改用 `Mono` + `subscribeOn(Schedulers.boundedElastic())`，全程不 `.block()` |
| 500 + `model not found` / 404 | 模型 ID 不在账号可用列表 | 跑自查脚本第 2 步，照抄列表里的 ID 填 `mvp.model.model` |
| Postman 报 `Cannot send requests to localhost` | **不是应用的问题** —— Postman 的 Cloud Agent 在云上，访问不到你的 localhost | 点错误提示里的 **Use Postman's Desktop Agent** |
| `Unable to access jarfile E:\Program` | 路径含空格，`Start-Process -ArgumentList` 不会自动加引号 | 自己加：`'"' + $jarPath + '"'` |

### 模型名只配一处就够

`OpenAIChatModel` 有**两个**模型名字段，框架用 `mergeOptions(configuredOptions, requestOptions)` 合并：
请求时传的 `GenerateOptions.modelName` 优先，没有则回退到 builder 的 `modelName`。
**两处都没设**才会抛 `modelName must be specified in GenerateOptions or configured in builder`。

本 demo 只在 builder 设（`ModelConfig.build()` 的 `.modelName(model)`），另一处不用管：

```java
// mvp-common/.../config/ModelConfig.java
OpenAIChatModel.builder()
    .apiKey(apiKey.trim())
    .modelName(model)          // ← 模型名就这一处
    .stream(stream)
    .generateOptions(buildOptions())   // 这里只放 temperature / maxCompletionTokens
    .formatter(new MiniMaxFormatter())
    .baseUrl(url);
```

`mvp.model.model` → `ModelConfig` record 的 `model` 字段 → `.modelName(model)`，一条线。
**最终该填哪个 ID，以 `/v1/models` 返回的列表为准**（见自查脚本第 2 步）。

### MiniMax 端点与模型

| 项 | 值 |
|---|---|
| 国内端点 | `https://api.minimaxi.com/v1` |
| 国际端点 | `https://api.minimax.io/v1` |
| Key 环境变量 | `MINIMAX_API_KEY`（框架的 `MiniMaxModelProvider` 也用这个名） |
| chat 模型 | `MiniMax-M2.7`（当前旗舰）/ `M2.5` / `M2.1` / `M2`（历史版本，仍可用）/ `M2-her` |
| 长度参数 | 用 **`max_completion_tokens`**（官方已弃用 `max_tokens`；框架的 `MiniMaxFormatter` 内部也会把 `max_tokens` 转成前者） |
| Formatter 会删掉的参数 | `thinkingBudget` / `reasoningEffort` / `frequencyPenalty` / `presencePenalty` / `parallelToolCalls` / `responseFormat` / `seed` —— MiniMax 不支持，框架直接置 null 不发 |

---

## 站 0 验证的三个框架事实

| 用例 | 钉住的事实 |
|---|---|
| `builderProducesAgent` | `ReActAgent.builder().build()` 直接产出可用 Agent |
| `toolIsRegisteredWithGeneratedSchema` | `@Tool` 反射注册 + 参数 Schema 自动生成，零手工代码 |
| `reactLoopRunsToolAndFeedsBack` | **一次提问，模型被调用两次** —— ReAct 闭环是框架做的 |

第 3 条是站 0 最有价值的：它直接决定后面所有关于**限流 / 计费 / 幂等 / 缓存**的判断
——「一次用户提问」不等于「一次模型调用」。

---

## 站 2 验证的五个框架事实（装配层）

### ① `maxRetries` 有三个坑叠在一起

```
Builder.maxRetries(n)
  → ModelConfig.maxRetries = n            ← 为 null 时框架填 3
  → GenerateOptions.executionConfig.maxAttempts = n
  → OpenAIChatModel 内部 ModelUtils.applyTimeoutAndRetry(...)
  → Reactor Retry.backoff(maxAttempts, initialBackoff).jitter(0.5)
```

1. **模型调用级**，不是 Agent 级 —— 重试发生在「一次模型调用」这层；
2. **语义是「总尝试次数」** —— `maxRetries(2)` = 最多试 2 次（1+1），不是 3 次。**名字骗人**；
3. **不配时默认就是 3** —— 「没配 = 不重试」是错的。

### ② `maxRetries` 与 `modelExecutionConfig` **互斥**

`GenerateOptions.mergeOptions(a, b)` 是**字段级**合并（a 优先、b 兜底），
而 `executionConfig` 是**一个原子字段** —— `modelExecutionConfig` 一旦非 null，
`maxRetries` 推导出来的那个 `executionConfig` 就被**整体替换**。

**两个都配 = 只有一个生效，且不报任何错。**
`application.yml` 里刻意把 `max-retries: 2` 与 `model-max-attempts: 5` 设成不同值，
跑 `/api/agent/assembly` 就能一眼看出**生效的是 5**。

### ③ 框架**没有任何 Model 装饰器基类**

`io.agentscope.core.model.Model` 共 6 个方法，**只有 2 个是抽象的**
（`stream` + `getModelName`）。
→ 写装饰器成本极低（见 `SwappableModel`：热替换 + 调用归因）；
→ 但框架**不提供** RetryModel / FallbackModel / DelegatingModel，
**热替换、归因、多候选主备 + 熔断、分级路由这四件必须自建**。

### ④ 状态槽位是三段式 `(userId, sessionId, key)`

参数名取自官方 `MysqlAgentStateStore` 的 LocalVariableTable（javap 实测，非猜测）。
**任一不同就是另一个槽位** —— 写成常量会让所有用户共用一份对话历史。

框架只给 `AgentStateStore` **接口**，存储实现要自己写（或用官方
`extensions-mysql` / `extensions-redis`）。

### ⑤ 框架会**偷偷往你的链上插东西**

`/api/agent/assembly` 的返回里有一个我们没装过的中间件：

```json
"middlewares": ["GracefulShutdownMiddleware@1", "ToolAuditMiddleware@100", "FinalAnswerFilterMiddleware@1"]
```

`GracefulShutdownMiddleware` 住在 `io.agentscope.core.shutdown` 包，
由 `ReActAgent.Builder#build()` **自动插到链首**（你删不掉）；
有 `stateStore` 时还会自动绑定状态保存 → **优雅停机时自动存会话状态**。

> **读文档读不出来这个** —— 只有把运行期的实际装配结果打出来才看得见。
> 这就是 `/api/agent/assembly` 存在的理由。

### 站 2 踩的两个坑

| 现象 | 根因 | 修法 |
|---|---|---|
| 启动失败 `NoUniqueBeanDefinitionException: Model` | 同时声明 `@Bean Model` 和 `@Bean SwappableModel`，后者**也是** `Model` 的实现 → 两个候选 | 只暴露**具体类型**一个 Bean（子类型可注入父类型） |
| **守卫测试自己红了** | 朴素字符串计数把 **javadoc 里的 `ReActAgent.builder()`** 也算成调用点 | **先剥注释再计数**，而不是改注释凑数字 |

> 第 2 条是本批次最值钱的一条：**测试基础设施自己出错时，红的可能是测试而不是代码。**
> 断言红了，先问「是代码错了还是断言错了」。

### 装配对齐守卫

`AgentAssemblyAlignmentTest` 扫生产源码，断言：

> 每个出现 `ReActAgent.builder()` 的文件，其 `.applyTo(` 次数 **不少于**建 Agent 的次数。

两条设计要点：**按调用点计数**（不按文件判定 —— customer_work 踩过「漏掉同文件第二个调用点」的坑）；
**额外断言「至少扫到 1 个调用点」**（否则源码路径写错会**空集成立 → 假绿**）。

站 0 的 `SimpleAgentFactory` 也被这条纪律改了 ——
它原本是「不挂治理链的裸版」，现在也接到了 `governance.applyTo(builder)`：
**裸版看起来只是教学样本，但它同样是一条能建出 Agent 的生产代码路径。**
