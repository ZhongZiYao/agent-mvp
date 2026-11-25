# AgentScope Java 落地边界清单

> **📌 行号引用说明**：本文档的 `文件:行号` 全部指向 **customer_work** 仓库
> （对照项目，AgentScope Java 2.0.4 的企业级智能客服平台）——
> <https://github.com/ZhongZiYao/customer_work>。
> 本 demo 仓库只含 `agent-mvp/` 本身，不含被对照的那些源码文件。

> **这份文档要回答一个问题**：在一个真实的 Spring Boot 智能客服项目里，AgentScope Java 到底给了什么，
> 哪些是「框架直接给」、哪些是「半自建」、哪些是「全自建」。
>
> **它不是结论，是一份可被推翻的对照表。** 每一行都有两栏证据：
> 左栏是框架 jar 里的真实类名（javap / jar tf 实测，不靠印象），
> 右栏是 `customer_work` 仓库里的真实文件与行号。
> 任何一栏对不上，这条判定就作废，改代码去改它。

---

## 0. 基线与口径

| 项 | 值 | 证据来源 |
|---|---|---|
| 框架版本 | `agentscope 2.0.5`（release） | `agentscope-bom:2.0.5`，`mvn dependency:tree` 实测 |
| Spring Boot | `4.1.3` | `spring-boot-starter-parent:4.1.3` |
| JDK | **21**（本 demo） | `maven.compiler.release=21` → 字节码 major 65 |
| Java 版本要求 | Boot 4.1.3 **最低 17，兼容到 27** | 官方 System Requirements 原文；parent pom 编译基线为 17 |
| 框架字节码 | agentscope 2.0.5 = **Java 17**（major 61） | `javap -v` 实测，JDK 21 运行无兼容问题 |
| 模型 | MiniMax（OpenAI 兼容 + `MiniMaxFormatter`） | `mvp-common/.../config/ModelConfig.java:68-82` |
| 对照仓库 | `customer_work`（本仓库，锁 2.0.4 + JDK 17） | `git branch=main` |
| 文档产出日 | 2026-11-25 | — |

**三档判定口径**（全文统一，不含糊）：

| 判定 | 含义 | 判据 |
|---|---|---|
| **框架直接给** | 框架 jar 里有完整实现，我们**零业务代码** | 框架类能直接用，只需传参 |
| **半自建** | 框架给了**扩展点/接口/基类**，实现要我们写 | 框架定契约，我们填内容 |
| **全自建** | 框架**根本没有这个概念**，必须从零写 | 框架 core 顶层包/类清单里查无此物 |

> **判定只看框架 core + 已发布 extension 的公开类**，不看官方文档宣传。
> 文档宣传过但 jar 里没有的（例如 `agentscope-extensions-judge` 2.0.5 在两个镜像上都取不到 jar），
> 一律标注「未核实」，不计入「框架直接给」。

**口径修正记录**（站 2 实测发现后回改的早期结论）：

| 原结论 | 更正为 | 怎么发现的 |
|---|---|---|
| core 有 **21** 个顶层包 | **20** 个 | 证据命令 `jar tf` 的输出里混进了 `io/agentscope/core/` 这个目录条目，切分后第 4 段是**空串** —— 把它数进去了。真实包数 20 |
| core 自带中间件**只有 2 个** | **3 个**（多一个 `GracefulShutdownMiddleware`） | 真机跑 `/api/agent/assembly`，链首出现一个我们没装过的中间件；反编译 `Builder#build()` 定位到它被**自动插入** |
| `maxRetries` 是模型级还是 Agent 级（**待验证**） | **模型调用级**，语义是**总尝试次数**，**默认 3**，且与 `modelExecutionConfig` **互斥** | `ExecutionConfigSemanticsTest` 4 条用例 + javap 反编译 `assembleModelConfig` / `buildGenerateOptions` / `ModelUtils.applyTimeoutAndRetry` |
| 「框架没给 Model 装饰器」→ 写得多 | 框架**一个装饰器基类都没有**，但 `Model` 只有 **2 个抽象方法** → 自建成本极低 | `javap io.agentscope.core.model.Model` |
| `GracefulShutdownMiddleware` 由 **`Builder#build()`** 自动插到链首 | 由 **`ReActAgent` 构造函数**插到链首，**不参与 order 协商**（排序发生在构造函数之前 ⇒ 你注册 order=999 也压不过它） | `javap` 反编译 `ReActAgent.<init>` offsets 171-212；运行时快照里它 order=1 却排第一 |
| 框架自带 **`FinalAnswerFilterMiddleware`** 会被自动挂上 | **不会** —— 全 core jar 类扫描显示除它自身外**无人引用**，**必须显式注册** | 全 jar 引用扫描 + `javap` 常量池核对 |
| `order()` 是「大的在外」还是「小的在外」（**待验证**） | **数值大的在外**（`Builder#build()` 降序排序 + `MiddlewareChain.build` 倒序包裹 ⇒ 列表首元素 = 最外层） | `javap` 反编译两处字节码 + `MiddlewareChainOrderTest` 运行时实证（入站降序 / 出站逆序） |

> **这七条修正有个共同形状**：**早期结论都来自「读清单/读文档」，
> 而修正全部来自「把运行期的东西打出来看」**。
> 与站 2 的 `AgentAssemblyAlignmentTest` 那条教训同源：
> **断言红了先问「是代码错了还是断言错了」；结论背了先问「这是实测的还是推出来的」。**
> 站 3 那条「`order()` 语义」尤其典型 —— 它是**唯一一条猜错就会让整套治理顺序反转**的结论，
> 而它只靠 `javap` 反编译 + 运行时探针两次独立验证才敢写进文档。

---

## 1. 一句话结论

> **AgentScope Java 给的是「Agent 运行时 + 数据契约 + 扩展插槽」，
> 不给「企业级客服的业务闭环」。**
>
> 框架 core 的 20 个顶层包里，装的是 `agent / model / tool / message / event / middleware / state / memory /
> rag / permission / skill / hook / tracing / shutdown / interruption / credential / formatter / exception / util / workspace`——
> **没有一个包叫 `ticket`、`handoff`、`semanticcache`、`tenant`、`audit`、`chatlog`、`csat`。**
>
> 这七个正是 `customer_work` 最值钱的业务能力，也正是框架完全不碰的地方。

**三条量化对照**：

| 对照项 | 框架 2.0.5 | customer_work | 判定 |
|---|---|---|---|
| 中间件 | **3 个**具体实现（`FinalAnswerFilterMiddleware`、`TaskReminderMiddleware`、**`GracefulShutdownMiddleware`**，后者由框架**自动注入**）+ 5 个插槽 | **27 个**实现 + 27 个顺序常量（一一对应） | 框架给插槽，业务全自建 |
| admin 控制面 | `agentscope-admin-spring-boot-starter` 2.0.5：13 个 endpoint + registry + 审计 + 状态快照 | 自建 `customer-admin-server` + `customer-admin-web`（2288 测试） | **框架已给，评估自建必要性** |
| 会话状态持久化 | `RedisAgentStateStore`（jedis/lettuce/redisson）、`MysqlAgentStateStore` | 自建 Redis/MyBatis-Plus 双库 | **框架已给** |

---

## 2. 全景矩阵

**图例**：`●` 框架直接给　`◐` 半自建　`○` 全自建　`—` 本 demo 不做

| 站 | 能力域 | 判定 | 框架侧关键类 | customer_work 侧 |
|---|---|---|---|---|
| **0** | Agent 运行时 | ● | `ReActAgent` | `SimpleAgentFactory.java:50-63`（demo） |
| 0 | 模型 SPI | ● | `io.agentscope.core.model.Model` | demo `ScriptedModel` |
| 0 | OpenAI 兼容模型 | ● | `OpenAIChatModel` + `Builder` | demo `ModelConfig.java:68-82` |
| 0 | 厂商协议适配 | ● | `compat/minimax/MiniMaxFormatter`（另有 deepseek/glm/kimi） | demo `ModelConfig.java:76` |
| 0 | 工具注解 + Schema | ● | `@Tool` / `@ToolParam` / `Toolkit` | demo `OrderTools.java:27-31` |
| 0 | ReAct 闭环 | ● | `ReActAgent` 内部循环 | demo 测试 `SimpleAgentFactoryTest.java:100-114` |
| 0 | 事件流 | ● | `AgentEvent` / `TextBlockDeltaEvent` | demo `ChatController.java:62-65` |
| 0 | 配置绑定 | ◐ | 官方 starter 只给 `AgentscopeProperties` | demo `ModelConfig` record |
| 0 | 唯一 Model Bean | ○ | 官方 starter **不提供** Model bean | demo `ModelConfiguration.java:33-36` |
| **1** | WS 接入 | ○ | core 无 ws 包 | `UserChatWebSocketHandler.java:84-110,112-144` |
| 1 | 握手鉴权 | ○ | — | `UserChatWebSocketHandler.java:181-203` |
| 1 | 消息受理 / 幂等 | ○ | — | `ChatDispatchService.java:157-197` |
| 1 | 会话归属校验 | ○ | — | `ChatDispatchService.java:394-418` |
| 1 | 会话锁 | ◐ | `ConflictPolicy` / `ConcurrentSessionModificationException`（是状态冲突，非分布式锁） | `CustomerServiceService.java:959-974` |
| 1 | 出站事件过滤 | ◐ | 框架给事件，过滤规则自建 | `CustomerServiceService.java:559-631` |
| 1 | AG-UI 协议 | ● | `agentscope-extensions-agui` 2.0.5（50+ 类） | 自建 `AguiService`（落库） |
| **2** | Agent 工厂 | ◐ | core 无 factory，但 `ReActAgent.Builder` 给了 **50+ 格** | demo `MvpAgentFactory` / `CustomerServiceAgentFactory` |
| 2 | 治理装配器 | ◐ | `middleware(...)` 插槽 + `MiddlewareBase`（6 方法全 default） | demo `MvpGovernanceAssembler` / `AgentGovernanceAssembler#applyTo` |
| 2 | 模型装饰器链 | ◐ | `Model` 接口只有 **2 个抽象方法**，但**无任何装饰器基类** | demo `SwappableModel` / `ModelConfig.java:68-122`（5 层） |
| 2 | **模型调用重试 / 超时** | **●** | `maxRetries` + `modelExecutionConfig(ExecutionConfig)`（含 retryOn / backoffMultiplier） | 自建 `ResilientChatModel` → **重复，且会叠乘** |
| 2 | **兜底模型** | ◐ | `fallbackModel(Model)` + `failoverListener`（**单候选**） | 自建 `FailoverModel`（多候选 + 熔断 + 健康剔除） |
| 2 | 多厂商路由 | ◐ | `MiniMaxModelProvider` + 各厂商 Provider | `ChatModelFactory.java:74-167,217-232` |
| 2 | 状态槽位 / 冲突 | ◐ | `AgentStateStore` **接口** + `AgentState` + `ConflictPolicy`×3 | demo `InMemoryAgentStateStore` / customer_work `stateStore` |
| 2 | 工具权限 | ◐ | `PermissionEngine` / `PermissionRule` / `PermissionContextState` / `PermissionMode`×5 | customer_work ask 规则（**只覆盖退款与转人工**） |
| 2 | 装配快照 | ○ | 框架**不提供**「把装配结果导出来」的能力 | demo `AgentAssemblyController`（`GET /api/agent/assembly`） |
| **3** | 中间件插槽 | ● | `MiddlewareBase` 5 个 default 方法 | demo 3 个自建治理件 |
| 3 | 中间件排序机制 | ● | `order()` + `Builder#build()` 降序重排 + `MiddlewareChain` 倒序包裹（**数值大的在外**，字节码+运行时双证） | `MiddlewareOrders.java` 27 常量 |
| 3 | 自动注入的中间件 | ● | `GracefulShutdownMiddleware`（**链首硬编码，不参与 order 协商**） | — |
| 3 | 具体中间件 | ○ | 框架仅 **3 个**（其中 1 个需显式注册、1 个条件自动挂） | 27 个自建实现，**无一为框架那 3 个的变体** |
| 3 | 顺序契约 | ○ | 框架**不提供**「顺序约定」这种概念 | `MiddlewareOrders` + `MiddlewareOrderContractTest` |
| 3 | 可观测 | ◐ | `tracing` 包：`Tracer` / `OtelTracingMiddleware` / `TracerRegistry` | `ObservabilityMiddleware` 等 |
| **4** | 短期状态 | ◐ | `AgentState` / `AgentStateStore` / `InMemoryAgentStateStore` / `JsonFileAgentStateStore` | 自建 Redis 持久化 |
| 4 | 长期记忆 | ◐ | `LongTermMemory` / `LongTermMemoryMode` / `LongTermMemoryTools` | 自建记忆分层 |
| 4 | RAG | ◐ | `rag` 包：`Knowledge` / `RAGMode` / `GenericRAGHook` | 自建知识库 + 分类复核 |
| 4 | Skill | ● | `skill` 包：`SkillBox` / `AgentSkill` / `SkillRegistry` / `DynamicSkillMiddleware` | — |
| 4 | 上下文压缩 | ◐ | `agentscope-harness`（`io.agentscope.harness.agent`） | `HarnessAgentFactory` 路由 |
| **5** | 语义缓存 | ○ | core 无 cache 包 | `ReplyFinish.java:66-69`（`replayable()`） |
| 5 | 缓存准入 | ○ | — | `CustomerServiceService.java:522-529` |
| 5 | 落库 / 审计 | ○ | core 无 chatlog/audit 包 | `ChatTurnService.java:26-52` |
| **6** | 多租户隔离 | ○ | core 无 tenant 包 | `TenantContextMiddleware` + 独立库 |
| 6 | 工单 / 转人工 | ○ | core 无 ticket/handoff 包 | `HandoffService` |
| 6 | 渠道接入 | ● | `extensions-channel-*`（feishu/wecom/dingtalk/weixin/github/gitlab） | `customer-channel` 模块 |

---

## 3. 逐站详表

### 站 0 — 纯框架最小 Agent（✅ 已完成并验证）

**这一站的目的不是写业务，是「先当一次框架使用者」，把「框架替我做了什么」看干净。**

**实测结论**：demo 只写了 4 个文件（`pom.xml` × 3 + 8 个 Java 类），就拿到了一个能调模型、能调工具、能流式输出的 Agent。
**我们没写的部分，就是框架直接给的部分。**

| 我们写的 | 框架替我们做的 |
|---|---|
| `OrderTools` 里一个查订单的方法 | `@Tool` 扫描、参数 Schema 生成、`ToolResultBlock` 包装、工具执行、结果回灌 |
| `ReActAgent.builder()` 五行 | ReAct 循环本体、`maxIters` 迭代上限、`Toolkit` 装配、`AgentState` 生命周期 |
| `OpenAIChatModel.builder()` 七行 | HTTP 传输、SSE 解析、重试、usage 统计、多模态消息结构 |
| `new MiniMaxFormatter()` | MiniMax 的 system role 正确序列化（通用 OpenAI Formatter 会丢 system role，框架 #2189） |
| `RuntimeContext.builder().build()` | 会话号、租户、slot 等上下文的载体 |
| `agent.streamEvents(...)` | 事件类型体系（`TextBlockDeltaEvent` / `ToolCallEvent` / `AgentResultEvent` …） |

**验证方式**：`SimpleAgentFactoryTest` 3 个用例（假模型，不烧 token）

1. `builderProducesAgent` — Builder 直接产出可用 Agent
2. `toolIsRegisteredWithGeneratedSchema` — 反射注册生效、Schema 自动生成
3. `reactLoopRunsToolAndFeedsBack` — **ReAct 闭环成立：一次提问，模型被调用两次**（推理 → 调工具 → 再推理）

> 用例 3 是站 0 最有价值的一条：它证明「工具调用结果回灌」这件事是框架做的，
> 而「调几次模型」是 ReAct 语义决定的 —— **一次用户提问 ≠ 一次模型调用**。
> 这个认知直接影响后面所有关于「限流 / 计费 / 幂等」的判断。

**执行记录**：`mvn -B clean test` → `BUILD SUCCESS`，`Tests run: 3, Failures: 0, Errors: 0`。

**站 0 的三次真机联调**（这是清单里最有价值的部分——都是实测，不是推断）：

| 轮次 | 动作 | 结果 | 暴露的事实 |
|---|---|---|---|
| ① | 不设 `MINIMAX_API_KEY` 启动 | **应用照常启动、端口正常监听**，首次请求 500 | **Spring Boot 4 默认懒加载**：`Model` bean 在第一次注入时才创建 → 「启动成功」不代表「配置正确」 |
| ② | 用假 key + 正确变量名 | 启动正常，请求真实打出 `https://api.minimaxi.com/v1/chat/completions`，服务端 401 | 端点、OpenAI 兼容协议、`MiniMaxFormatter`、鉴权头格式**全部正确**；只差真 key |
| ③ | 修掉 `.block()` 后重测 | 异常落在 `boundedElastic-3` 线程 | 订阅链已挪出事件循环 |

> **②证明了一件反直觉的事**：`ModelConfig.build()` 里「Key 为空就抛异常」这段校验，
> **在启动阶段根本不会触发**（懒加载），只在第一个请求时才炸。
> 也就是说——**「服务起来了」不能作为「配置对了」的证据**，
> 必须真的打一次请求、看服务端回什么。这条已经写进 demo 的 README 排错表。

**站 0 修掉的两个真实 bug**：

1. **WebFlux 里 `.block()`**（`ChatController` 初版）：
   ```
   IllegalStateException: block()/blockFirst()/blockLast() are blocking,
   which is not supported in thread reactor-http-nio-2
   ```
   REST 端点跑在 Netty 事件循环线程上，Reactor 明令禁止阻塞 —— 一阻塞整条 IO 线程卡死。
   改为 `Mono.defer(...).subscribeOn(Schedulers.boundedElastic())`，全程不 `.block()`。
   > 讽刺的是 `customer_work` 的 `CLAUDE.md` 里明明写着「响应式里别用 `.block()`」，
   > 我在 demo 里自己踩了一遍。**同一份知识，换个工程就会忘——这就是为什么要写成守卫测试。**

2. **`repackage` 跑了两次**：`spring-boot-starter-parent` 的 pluginManagement 里已有
   `id=repackage` 的 execution，子模块又声明了一遍 → 日志里 `(repackage)` 和 `(default)` 各一次。
   第二次是对已经是 fat jar 的产物再 repackage。删掉子模块的 `<executions>` 即修好。

**站 0 顺带确认的厂商侧事实**（官方文档核实）：

| 项 | 值 |
|---|---|
| 国内端点 | `https://api.minimaxi.com/v1`（框架 `MiniMaxModelProvider` 的内置默认值一致） |
| 国际端点 | `https://api.minimax.io/v1` |
| Key 环境变量 | `MINIMAX_API_KEY`（框架与本项目用同一个名） |
| chat 模型 | `MiniMax-M2.7`（当前旗舰）/ `M2.5` / `M2.1` / `M2`（历史版本，仍可用）/ `M2-her` |
| 长度参数 | **`max_completion_tokens`** —— 官方已把 `max_tokens` 标为 deprecated |
| 框架支持 | `GenerateOptions` 两个字段都有（`maxTokens` / `maxCompletionTokens`） |

> **修正一处早期判断**：曾以为「设 `maxTokens` 还是 `maxCompletionTokens` 由我们在 `ModelConfig` 里决定，
> 选错的字段名只会被服务端忽略」——**不对**。实测反编译
> `MiniMaxFormatter#applyMaxTokens` → `applyMiniMaxMaxTokens(request, maxCompletionTokens, maxTokens)`：
> 逻辑是「`maxCompletionTokens` 优先，否则回退 `maxTokens`」，**两者最终都写到请求体的
> `max_completion_tokens` 字段**。转换发生在 Formatter 层。
> **教训**：字段名的最终形态由框架的 Formatter 决定，不能只看 `GenerateOptions` 的字段列表就下结论。
>
> 同一个 Formatter 的 `removeUnsupported` 还会把 `thinkingBudget` / `reasoningEffort` /
> `frequencyPenalty` / `presencePenalty` / `parallelToolCalls` / `responseFormat` / `seed`
> 全部置 null —— **MiniMax 不支持这些参数，框架直接不发（不报错、不告警）**。
> 换厂商时这块要重新核对，不能拿通用 OpenAI 的参数习惯套。

### 模型名只配一处就够（实测）

`OpenAIChatModel` 有**两个**模型名字段，框架用 `mergeOptions(configuredOptions, requestOptions)` 合并：
请求时传的 `GenerateOptions.modelName` 优先，没有则回退 builder 的 `modelName`；
**两处都没设**才抛 `IllegalArgumentException: modelName must be specified in GenerateOptions or configured in builder`。

本 demo 只在 builder 设（`ModelConfig.build()` 的 `.modelName(model)`），
`GenerateOptions` 里只放 temperature / maxCompletionTokens —— 一条线，不重复配。

### 抓包实证：框架到底往厂商发了什么（mock 端点实测）

用本地 mock OpenAI 端点截获 `OpenAIChatModel` 发出的**真实请求体**（不发外网、不花钱），
实际内容如下：

```json
{
  "model": "MiniMax-M3.1-Flash",
  "messages": [
    { "role": "system", "content": "你是电商平台的智能客服助手。…", "name": "system" },
    { "role": "user",   "content": "我的订单 SO202611250001 到哪了？" }
  ],
  "stream": true,
  "stream_options": { "include_usage": true },
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

**这张抓包是「框架直接给」最硬的证据**，一次证明四件事：

| 观察项 | 结论 |
|---|---|
| `model` 是我们配的值 | 模型名注入链路通（builder → `configuredOptions` → merge → 请求体） |
| **system role 完整保留**（含 `name: "system"`） | **`MiniMaxFormatter` 解决了通用 OpenAI Formatter 丢 system role 的问题（框架 #2189）** —— system prompt 是 Agent 全部行为约束的载体，丢了不报错、直接静默失效 |
| `max_completion_tokens` 而非 `max_tokens` | 弃用字段的转换发生在 **Formatter 层**，不是我们在 `ModelConfig` 决定的 |
| `tools` 的完整 JSON Schema（`type`/`properties`/`description`/`required`） | **工具 Schema 是框架从 `@Tool`/`@ToolParam` 反射生成的，一个字没手写** |

**另有一条 MiniMax 专有字段**：`reasoning_split: true` —— 由 `MiniMaxFormatter#applyReasoningSplit` 注入。

> **方法论**：想确认「到底发出去了什么」，别猜、别看文档，直接**起一个 mock 端点接住请求**。
> 这一招同时排除了「框架没带对头」「字段名不对」「Schema 缺失」三类猜测，且零成本。
> demo 里留下了 `scripts/mock_openai.py`，换任何厂商都能复用。

---

### 站 1 — 接入层（✅ 已完成并真机验证）

**这一站的命题：「框架给了 0 个东西」。**

AgentScope core 的 20 个顶层包里没有任何网络协议、鉴权、会话、幂等的概念。
这一站做的每一件事 —— 握手、令牌、帧协议、受理、去重、归属校验、出站过滤 ——
**框架一个字都没提供**，全部是 Spring WebFlux 的机制 + 我们写的策略。

| 能力 | 框架侧 | 我们侧 | 判定 |
|---|---|---|---|
| WebSocket 端点 | core 无 ws 包 | `UserChatWebSocketHandler` + `WebSocketConfig`（手写 `SimpleUrlHandlerMapping`） | ○ |
| 握手鉴权 | — | token 校验 + **epoch 前后双检** | ○ |
| HTTP 鉴权 | — | `AuthWebFilter`（`/api/**` 与 `/ws/**` 一并收口） | ○ |
| 令牌签发 | — | `TokenService`（JDK 自带 HMAC，零依赖） | ○ |
| 帧协议 | — | `WsFrame`（`{type, data}` 信封 + 6 种类型） | ○ |
| 受理 / 幂等 | — | `ChatDispatchService`（幂等键 = `userId + clientMsgId`） | ○ |
| 会话归属 | — | `SessionIds.belongsTo`（前缀校验） | ○ |
| 出站过滤 | ◐ 给事件，规则自建 | `OutboundFilter`（3 条规则） | ◐ |
| AG-UI 协议 | ● `extensions-agui` 50+ 类 | 落库/业务投影 | ● |

**对照 customer_work**：结构逐段对应 —— `UserChatWebSocketHandler`（握手四道鉴权 → epoch 双检 →
`registry.registerUser` → `session.receive()` + `concatMap` → `firstWithSignal` → `doFinally` 注销）
与 `ChatDispatchService#onUserMessage`（受理号 + 幂等）、`streamFromAgent`（3 条出站规则）。
demo 把它们做小，但**骨架是同一套**。

#### 真机验证：WS 端到端帧序列

用 JDK 自带的 `java.net.http.WebSocket` 探针连上去，完整跑通一轮：

```
[PROBE] 已连接
[RECV] {"type":"chat_accepted","data":{"clientMsgId":"cm-probe-1","acceptance":"ACC-1",
        "sessionId":"u1001-58ee818f39e8","status":"ACCEPTED"}}
[RECV] {"type":"chat_chunk","data":{"content":"【MOCK】已收到请求","sessionId":"u1001-58ee818f39e8",…}}
[RECV] {"type":"chat_chunk","data":{"content":"，这是本地 mock 回复。","sessionId":"u1001-58ee818f39e8",…}}
[RECV] {"type":"chat_done","data":{"sessionId":"u1001-58ee818f39e8","clientMsgId":"cm-probe-1",
        "content":"【MOCK】已收到请求，这是本地 mock 回复。"}}
[PROBE] 重复投递同 clientMsgId
[RECV] {"type":"chat_accepted","data":{"acceptance":"ACC-1","sessionId":"u1001-58ee818f39e8","status":"DUPLICATE"}}
```

**注意最后一行**：重复投递返回的是**同一个受理号、同一个会话号**，且**没有第二次模型调用**。

#### 站 1 挖到的 4 个真实 bug（都不是框架的错）

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | **WS 路径完全没被鉴权拦** | `AuthWebFilter` 只拦 `/api/**`，而 WS 是 `/ws/user` | 把 `/ws/**` 一并纳入拦截；**鉴权只留一个入口**（customer_work 反复强调「能力只接在一条路上」） |
| 2 | **幂等命中时返回了 error 帧、且会话号变了** | 幂等表只存受理号；DUPLICATE 走的是 error 分支 | 幂等表改存「受理号 + 会话号」，DUPLICATE 回 `chat_accepted` + `status=DUPLICATE`，**复用首次的会话号** |
| 3 | `chat_done` 的全文是空串 | 没累积正文 | 用 `StringBuilder` 在 `doOnNext` 里累积 |
| 4 | **启动失败：`No qualifying bean of type 'ObjectMapper'`** | **Spring Boot 4 迁到 Jackson 3**（`tools.jackson.*`）了 | 显式声明 bean；详见下 |

> **第 2 条的教训**：幂等不只是「不重复调用」，**返回的标识也必须与首次一致**。
> 否则前端看到「同一个 `clientMsgId` 却是另一个 `sessionId`」，历史就对不上 ——
> 这类 bug 不报错、不断言失败，只是让用户的历史记录错位。

#### 顺带挖到的 Boot 4 大变化：Jackson 3

```
spring-boot-starter-jackson:4.1.3
  └── spring-boot-jackson:4.1.3
        └── tools.jackson.core:jackson-databind:3.1.8      ← Jackson 3，坐标前缀变了
```

- Boot 4 把 Jackson 自动配置从 `spring-boot-autoconfigure` **拆到了独立的 `spring-boot-jackson` 模块**；
- 那个 `JacksonAutoConfiguration` 产出的是 **Jackson 3 的 `tools.jackson.databind.ObjectMapper`**；
- 而 `com.fasterxml.jackson.core:jackson-databind`（Jackson 2）在这里只是
  **agentscope-harness 传递进来的共存产物**，没有任何自动配置会给它建 bean。

**对照 customer_work**：那边是 **Boot 3.2.5**（Jackson 2 时代），同一个类名
`com.fasterxml.jackson.databind.ObjectMapper` 能直接注入。
**同一个类名，在 Boot 3 与 Boot 4 下由不同的自动配置提供** —— 升级时的高发坑。

#### 另外两个值得记住的坑

1. **不要用 `@EnableWebSocket`**：那套 `WebSocketHandlerRegistry` 是 Servlet 风格的，
   依赖 `spring-websocket`；而 `spring-boot-starter-websocket` 会连带引入 tomcat，
   Spring Boot 见到 Tomcat 就会把应用类型判定成 `SERVLET` 而非 `REACTIVE`，**WebFlux 直接失效**。
   正确做法是手写 `SimpleUrlHandlerMapping` + `WebSocketHandlerAdapter`（两者都在 `spring-webflux` 里，零额外依赖）。
2. **PowerShell 5.1 会剥掉传给原生 exe 的双引号**：`curl -d '{"userId":"u1001"}'` 实际发出的是
   `{userId:u1001}`（`Content-Length` 少了 4 字节）→ 400 Bad Request。
   用 `--data-binary "@file.json"`（文件 UTF-8 无 BOM）或 `--data-raw '{\"...\"}'`。

#### 站 1 要钉住的三个具体事实

1. **会话号不是凭证** —— 拿到别人的 `sessionId` 就能顶替，必须另设归属校验。
2. **WS 是用户维度不是会话维度** —— 一个用户一条连接挂着 N 个会话，会话切换不重连。
3. **token 只能走 query** —— 浏览器 WS API 不支持自定义 header，这不是设计选择，是平台约束。

#### 补一张「框架给了什么」的对照表

| 能力 | 框架有吗 | 我们必须写什么 |
|---|---|---|
| WebSocket 端点 | ✗ 无 ws 包 | `UserChatWebSocketHandler`（握手 + 帧解析 + 生命周期） |
| 握手鉴权 | ✗ | token 校验、epoch 前后双检 |
| 消息受理 / 幂等 | ✗ | 受理号 → 幂等键 → 去重（`ChatDispatchService.java:157-197`） |
| 会话归属 | ✗ | `ownsSession` 前缀校验（`ChatDispatchService.java:394-418`） |
| 会话级串行 | ◐ | 框架的 `ConflictPolicy` 只管**状态并发冲突**，不是分布式锁；锁要自己写（`CustomerServiceService.java:959-974`） |
| 出站过滤 | ◐ | 框架给事件流，**3 条过滤规则全自建**（`CustomerServiceService.java:559-631`） |
| AG-UI | ● | 用 `agentscope-extensions-agui`，但**落库仍要自己写** |

---

### 站 2 — 装配层（✅ 已完成并真机验证）

**这一站的命题：框架给了多少零件？我们还要写多少？**

`ReActAgent.Builder` 一共 **50+ 个方法**，逐件对照下来：
**框架给的是「零件」，不给的是「装什么」与「怎么存」。**

#### 逐件判定表

| 装配零件 | 框架给了什么 | 判定 |
|---|---|---|
| Agent 身份 | `name` / `description` / `sysPrompt` | ● |
| 模型注入 | `model(Model)` / `model(String)` | ● |
| 工具集 | `toolkit(Toolkit)` + `registerTool` 反射注册 + Schema 自动生成 | ● |
| 迭代上限 | `maxIters(int)` | ● |
| **模型调用重试 / 超时** | `maxRetries(int)` + `modelExecutionConfig(ExecutionConfig)`（timeout / maxAttempts / initialBackoff / maxBackoff / backoffMultiplier / retryOn） | ● **重复造轮子** |
| **兜底模型** | `fallbackModel(Model)` + `failoverListener(FailoverListener)` | ◐ 只有单候选，无候选表 / 熔断 / 健康剔除 |
| **状态槽位** | `stateStore(AgentStateStore)` 接口 + `conflictPolicy`（OVERWRITE / FAIL / APPEND_MERGE）+ `defaultSessionId` | ◐ **存储实现要自己写** |
| **工具权限** | `permissionContext(PermissionContextState)` + `PermissionEngine` + `PermissionRule` + `PermissionMode`（DEFAULT / ACCEPT_EDITS / EXPLORE / BYPASS / DONT_ASK） | ● 引擎给，**规则语义自建** |
| 治理中间件 | `middleware(...)` / `middlewares(List)` + `MiddlewareBase`（**6 个方法全是 default**） | ● 插槽给，**策略自建** |
| 可观测 | `hook(Hook)` / `hooks(List)` | ● |
| 任务清单 | `enableTaskList()`（框架内置 TodoTools） | ● |
| 元工具 | `enableMetaTool(boolean)` | ● |
| 中断恢复 | `enablePendingToolRecovery(boolean)` | ● |
| 长期记忆 / RAG / Skill | `longTermMemory` / `longTermMemoryMode` / `knowledge` / `ragMode` / `skillBox` / `skillRepository` | ● |
| 执行配置 | `modelExecutionConfig` / `toolExecutionConfig` | ● |
| **Model 装饰器基类** | ✗ **一个都没有**（无 RetryModel / FallbackModel / DelegatingModel） | ○ 自建，但**只要实现 2 个方法** |

> **对照 customer_work 的 5 层装饰器链**（`ModelConfig.java:68-122`：
> 热替换 / 分级路由 / 重试 / 主备 / 归因）——
> **框架实际只提供了其中两件**：
> 「重试 + 超时」（`modelExecutionConfig`，**比自建的 `ResilientChatModel` 还全**）
> 与「单候选兜底」（`fallbackModel`）。
> **热替换、归因、多候选主备 + 熔断、分级路由，这四件框架完全没给。**

#### `maxRetries` 定论（清单里原本挂着的「待验证」项）

实测链条（javap 反编译 + `ExecutionConfigSemanticsTest` 双向确认）：

```
Builder.maxRetries(n)
  → ModelConfig.maxRetries = n            ← assembleModelConfig；为 null 时填 3
  → buildGenerateOptions() → GenerateOptions.executionConfig.maxAttempts = n
  → CallExecution 用 buildGenerateOptions() 的结果去调模型
  → OpenAIChatModel 内部 ModelUtils.applyTimeoutAndRetry(...)
  → Reactor Retry.backoff(maxAttempts, initialBackoff).jitter(0.5)
```

**四条结论**：

1. **模型调用级**，不是 Agent 级 —— 重试发生在「一次模型调用」这一层，
   **不是整个 Agent 轮次这一层**。
2. **语义是「总尝试次数」，不是「额外重试次数」** —— `maxRetries(2)` = 最多试 2 次
   （1 次原始 + 1 次重试），不是 3 次。**名字骗人。**
3. **不配时框架默认就是 3** —— `assembleModelConfig` 里 `flatMaxRetries` 为 null 时填 3。
   **「没配 = 不重试」是错的**：线上每个模型调用都在悄悄重试两次。
4. **与 `modelExecutionConfig` 同时设时，后者赢，前者被整体丢弃** ——
   因为 `GenerateOptions.mergeOptions(a, b)` 是**字段级**合并（a 优先、b 兜底），
   而 `executionConfig` 是**一个原子字段**：`modelExecutionConfig` 一旦非 null，
   `maxRetries` 推导出来的那个 `executionConfig` 就被整体替换掉了。
   **两个都配 = 只有一个生效，且不报任何错。**

**→ 重复造轮子的代价被量化了**：
customer_work 自建 `ResilientChatModel`（Model 装饰器层重试），
而底层 `OpenAIChatModel` 又会按 `executionConfig` 重试 —— **两层叠乘**，
总尝试数 = 外层 × 内层，且**每一次重试都是一次完整的 HTTP 请求**（token 成本同步放大）。
框架一个 `modelExecutionConfig` 就把这两层都覆盖了。

#### 框架会偷偷往你的链上插东西（装配快照抓出来的）

真机跑 `/api/agent/assembly` 时，链首出现了一个**我们没装**的中间件：

```json
"middlewares": ["GracefulShutdownMiddleware@1", "ToolAuditMiddleware@100", "FinalAnswerFilterMiddleware@1"]
```

反编译定位：`ReActAgent.Builder#build()` 里先 `new ArrayList()` →
`new GracefulShutdownMiddleware(shutdownManager)` → **add 在用户中间件之前**。
它住在 `io.agentscope.core.shutdown` 包（core 的 shutdown 包），配套还有：

- `stateStore != null` 时自动 `shutdownManager.bindStateSaver(agent, saver)`
  → **优雅停机时自动保存会话状态**；
- `agent.close()` → `unbindStateSaver` + `clearStateCache`。

> **⚠️ 这修正了本清单此前的一处说法**：
> 「core 自带中间件只有 2 个」→ **实际是 3 个**
> （`FinalAnswerFilterMiddleware`、`TaskReminderMiddleware`、**`GracefulShutdownMiddleware`**），
> 且第三个是**框架自动注入的**，你既不能删也不能改它的位置。
>
> **这本身就是「装配快照」这个出口的价值**：
> 框架自动加的东西，靠读文档是读不出来的 —— 只有把运行期的实际装配结果打出来才看得见。

#### 站 2 挖到的 2 个 bug（都不是框架的错）

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | 启动失败 `NoUniqueBeanDefinitionException: No qualifying bean of type 'Model'` | 同时声明了 `@Bean Model` 和 `@Bean SwappableModel`，而后者**也是** `Model` 的实现 → **两个候选** | 只暴露**具体类型** `SwappableModel` 一个 Bean（子类型可注入父类型，注入 `Model` 仍唯一命中），需要 `swap` 的调用方直接注入具体类型 |
| 2 | **守卫测试自己红了**（报「建 Agent 3 处、装配标记 2 处」） | 朴素字符串计数把 **javadoc 里的 `ReActAgent.builder()`** 也算成了调用点（1 处真实调用 + 2 处注释提及 = 3） | **先剥注释再计数**（`stripComments`），而不是去改注释凑数字 |

> **第 2 条是本批次最值钱的一条**：**测试基础设施自己出错时，红的可能是测试而不是代码。**
> 这与 customer_work「变异测试用 `mv` 还原源码会保留旧 mtime，增量编译不重编、
> 跑的还是变异字节码」是同一类教训：
> **断言红了，先问「是代码错了还是断言错了」。**
> 修法也同源：把断言的输入变成「真正要测的东西」（代码而非注释）。

#### 真机验证

```
GET /api/agent/assembly  →  装配快照（含实际生效的 executionConfig）
POST /api/chat/sync     →  {"sessionId":"u1001-aab075de2530","reply":"【MOCK】已收到请求，这是本地 mock 回复。"}
WS 探针                 →  chat_accepted(ACCEPTED) → chat_chunk×2 → chat_done → 重复投递 DUPLICATE（同受理号同会话号）
```

快照原文（节选）：

```json
{
  "agentName": "MvpCustomerServiceAgent",
  "model": "MiniMax-M3.1-Flash",
  "maxIters": 5, "maxRetries": 2,
  "conflictPolicy": "OVERWRITE",
  "stateStore": "InMemoryAgentStateStore",
  "permissionMode": "DEFAULT", "permissionDenyRules": [],
  "middlewares": ["GracefulShutdownMiddleware@1", "ToolAuditMiddleware@100", "FinalAnswerFilterMiddleware@1"],
  "toolNames": ["query_order_status"],
  "modelExecutionConfig": {"timeoutSeconds":60, "maxAttempts":5, "initialBackoffMillis":500, "backoffMultiplier":2.0}
}
```

⚠️ 注意这一行：**`maxRetries` 是 2，而实际生效的 `modelExecutionConfig.maxAttempts` 是 5** ——
**两个都配了，只有 5 生效，而且不报错**。这就是第 4 条结论的真机证据。

#### 站 2 钉住的 5 个事实

1. **`maxRetries` 是模型调用级、语义是「总尝试次数」、默认 3** —— 三个坑叠在一起。
2. **`maxRetries` 与 `modelExecutionConfig` 互斥** —— 后者赢，前者被静默丢弃。
3. **框架不提供任何 Model 装饰器基类** —— 但 `Model` 接口只有 2 个抽象方法，
   自建装饰器成本极低（真正的成本在命名与归因口径）。
4. **状态槽位是 `(userId, sessionId, key)` 三段式**（参数名取自官方 `MysqlAgentStateStore`
   的 LocalVariableTable，javap 实测）—— 任一不同就是另一个槽位。
5. **框架会往你的中间件链上自动插东西** —— 装配快照是唯一能看见它的出口。

#### demo 侧新增了什么

| 文件 | 作用 |
|---|---|
| `mvp-common/.../agent/MvpAgentFactory.java` | 装配层主体：装 11 个零件 |
| `mvp-common/.../agent/MvpGovernanceAssembler.java` | 治理装配**唯一入口**（冲突策略 + 中间件链 + `contextFor`） |
| `mvp-common/.../agent/InMemoryAgentStateStore.java` | **自建** `AgentStateStore`（框架给接口不给实现）+ 真 CAS 版本号 |
| `mvp-common/.../model/SwappableModel.java` | **自建** Model 装饰器：热替换 + 调用归因 |
| `mvp-common/.../agent/ToolAuditMiddleware.java` | 自建中间件最小示例（`onActing` 插槽 + `order` 覆盖） |
| `mvp-common/.../config/AgentProperties.java` | 装配参数（10 个开关） |
| `mvp-app/.../agent/AgentAssemblyController.java` | 装配快照出口 `GET /api/agent/assembly` |
| `mvp-common/.../agent/AgentAssemblyAlignmentTest.java` | **装配对齐守卫**（剥注释后按调用点计数） |

**测试**：mvp-common 25 条（原 10 + 新增 15）、mvp-app 19 条，**合计 44 条全绿**。

#### 一个纪律：唯一装配入口是被测试钉住的，不靠自觉

`AgentAssemblyAlignmentTest` 扫**生产源码**，断言：

> 每个出现 `ReActAgent.builder()` 的文件，其 `.applyTo(` 标记次数 **不少于**建 Agent 的次数。

两条设计要点：

1. **按调用点计数，不按文件判定** —— customer_work 踩过这个坑：
   「门禁按文件判定会漏掉同文件的第二个调用点」。
2. **额外断言「至少扫到 1 个建 Agent 的调用点」** —— 否则源码路径写错时
   「每个含 builder 的文件都合格」会**空集成立** → 测试绿但毫无意义（假绿）。

**站 0 的 `SimpleAgentFactory` 也被这条纪律改了**：它原本是「不挂治理链的裸版」，
站 2 把它也接到了 `governance.applyTo(builder)` ——
**「所有建 Agent 的点必须经过同一个入口」这条纪律，比「保留一个最小示例」更重要**：
裸版看起来只是教学样本，但它**同样是一条能建出 Agent 的生产代码路径**，
哪天有人复用它，治理能力就会静默失效。

---

### 站 3 — 治理层（✅ 已完成并真机验证）

**这是 7 站里框架参与最少、我们代码最多的一站。**
框架给的是**插槽**（5 个 default 方法的接口）+ **排序契约**，
「塞什么、什么顺序、怎么协作」全是业务决策。

#### 3.1 `order()` 语义：实测定论（这一站最不能猜的一点）

> **结论：数值越大 = 越靠外。靠外 = 入站先执行、出站后执行。相同数值保持注册顺序。默认值 1。**

**证据链（字节码，可复现）**：

1. `ReActAgent$Builder#build()` 的倒数第二步：

```java
middlewares.sort(Comparator.comparingInt(MiddlewareBase::order).reversed());
```

排序键就是 `MiddlewareBase.order()`（BootstrapMethods #2 → `REF_invokeInterface MiddlewareBase.order:()I`）；
`.reversed()` = **降序** ⇒ **order 越大越靠列表前部**。

2. `MiddlewareChain.build(...)` 的循环是**倒序**的：

```java
Function<I, Flux<AgentEvent>> chain = terminal;   // 最内层 = 终端
for (int i = size - 1; i >= 0; i--) {              // 从列表尾部往前包
    chain = input -> method.apply(middlewares.get(i), agent, ctx, input, chain);
}
return chain;                                      // 列表第 0 个 = 最外层
```

列表最后一个先包住终端（最内层），第一个最后包（最外层）⇒ **列表第 0 个 = 最外层**。

3. 两条合起来：**order 越大 ⇒ 列表越靠前 ⇒ 越靠外**。

**运行时实证**（demo `MiddlewareChainOrderTest`，探针 order = 200 / 120 / 50）：

| 观测 | 实际值 | 含义 |
|---|---|---|
| 入站记录 | `[in:200, in:120, in:50]` | 降序 ⇒ 大的先执行 ⇒ 更靠外 |
| 出站记录 | `[out:50, out:120, out:200]` | 逆序 ⇒ 内层先收尾 ⇒ 大的后收尾 ⇒ 更靠外 |

#### 3.2 三个「顺序」必须分清

| 顺序 | 谁决定 | 实际作用 |
|---|---|---|
| **挂载顺序** | 你的代码 | **几乎无效** —— 框架 `build()` 会按 `order()` 降序重排 |
| **生效顺序** | `order()` 值 | **唯一**决定因素 |
| **同值时的相对次序** | 挂载顺序 | `List.sort` 是稳定排序 ⇒ 同 `order` 保持注册顺序 |

⇒ **自己预排序是白写的**。demo 站 2 那版 `MvpGovernanceAssembler` 里有一段
`.sorted(Comparator.comparingInt(MiddlewareBase::order))` 升序预排序 ——
站 3 查明它会被框架整体覆盖，**已删除**。
customer_work 的 `AgentGovernanceAssembler#applyTo` 里
`pluggableMiddlewares.orderedStream().forEach(builder::middleware)` 也没排序，**是对的**。

#### 3.3 框架自带 3 个中间件：逐个查清（含 2 条早期结论修正）

| 类 | 所在包 | 自动注入？ | `order()` | 实际行为（反编译结论） | 覆写的插槽 |
|---|---|---|---|---|---|
| `GracefulShutdownMiddleware` | `core.shutdown` | ✅ **硬编码在链首** | 1（默认，**不参与协商**） | 停机时拒绝继续推理 / 执行 | `onReasoning` + `onActing` |
| `FinalAnswerFilterMiddleware` | `core.middleware` | ❌ **否，必须显式注册** | 1（默认） | 缓冲文本块，丢弃「调用工具前的中间叙述」，只在 `ModelCallEnd` 一次性放出 | `onReasoning` |
| `TaskReminderMiddleware` | `core.middleware` | ⚠️ 仅当 `taskListEnabled=true` | 1（默认） | 把待办清单注入系统提示词 | `onSystemPrompt` + `onReasoning` |

**两条修正**（站 2 的早期结论 → 站 3 实测）：

1. 站 2 记的是「`GracefulShutdownMiddleware` 由 `Builder#build()` 自动插到链首」——
   **位置对，机制错**。它不是 `build()` 插的，是 **`ReActAgent` 构造函数**插的：

```java
// ReActAgent 构造函数（字节码 offsets 171-212）
List<MiddlewareBase> all = new ArrayList<>();
all.add(new GracefulShutdownMiddleware(GracefulShutdownManager.shutdownManager));  // ← 链首
all.addAll(builder.middlewares);   // ← build() 里已按 order 降序排好的列表
this.middlewares = List.copyOf(all);
```

而**排序发生在构造函数之前**。⇒ **它带着默认 `order()=1` 硬编码在链首，
你注册 `order=999` 也压不过它。**（demo 站3-4 用例钉住）
全 jar 扫描同时确认：**只有 `ReActAgent` 引用 `GracefulShutdownMiddleware`**，没有第二个注入方。

2. **`FinalAnswerFilterMiddleware` 框架不自动注入**，必须显式注册。
全 jar 扫描：除它自身外**无人引用**。

**`FinalAnswerFilterMiddleware` 的代价**（customer_work 已经踩过）：
它要等到 `ModelCallEndEvent` 才把缓冲的文本一次性放出 ⇒ **流式打字机效果完全消失**，
而且「调用工具前的中间叙述」在**有工具调用**时会被**彻底丢弃**。
customer_work 的 `AgentProperties#finalAnswerFilterEnabled` **默认 `false`**，
注释里明写「默认关闭，因为代价对本项目的 C 端不可接受」。
demo 挂它只为对照，不依赖它的行为。

**框架给的是插槽，不是策略。** `MiddlewareBase` 是**接口**，全部方法都是 `default`：

```java
public interface io.agentscope.core.middleware.MiddlewareBase {
  default int order();
  default Flux<AgentEvent> onAgent(Agent, RuntimeContext, AgentInput, Function<...>);
  default Flux<AgentEvent> onReasoning(Agent, RuntimeContext, ReasoningInput, Function<...>);
  default Flux<AgentEvent> onActing(Agent, RuntimeContext, ActingInput, Function<...>);
  default Flux<AgentEvent> onModelCall(Agent, RuntimeContext, ModelCallInput, Function<...>);
  default Mono<String> onSystemPrompt(Agent, RuntimeContext, String);
}
```

**5 个插槽 = 5 个可插拔点，但框架自己只实现了 3 个**（见 3.3 的表），
且**其中只有 1 个会自己出现在链上**。

#### 3.4 27 常量 ↔ 27 实现类，一一对应（含钩子覆盖，实测核对）

| 顺序 | 常量 | 实现类 | 覆写的插槽 |
|---|---|---|---|
| 200 | `AGENT_LIFECYCLE` | `AgentLifecycleMiddleware` | `onAgent` |
| 190 | `OBSERVABILITY` | `ObservabilityMiddleware` | `onAgent` `onReasoning` `onActing` |
| 180 | `TENANT_CONTEXT` | `TenantContextMiddleware` | `onSystemPrompt` |
| 170 | `AGENT_CALL_TIMING` | `data/calllog/AgentCallTimingMiddleware` | `onAgent` `onModelCall` `onActing` |
| 165 | `CHAT_TERMINAL_CAPTURE` | `ChatTerminalCaptureMiddleware` | `onAgent` |
| 160 | `LATENCY` | `LatencyMiddleware` | `onAgent` `onReasoning` `onActing` |
| 155 | `MODEL_REPLAY_CAPTURE` | `data/calllog/ModelReplayCaptureMiddleware` | `onModelCall` |
| 150 | `AUDIT` | `AuditMiddleware` | `onActing` `onAgent` |
| 140 | `PROMPT_INJECTION_GUARD` | `PromptInjectionGuardMiddleware` | `onAgent` |
| 137 | `KNOWLEDGE_CITATION` | `KnowledgeCitationMiddleware` | `onActing` |
| 135 | `INDIRECT_INJECTION_GUARD` | `IndirectInjectionGuardMiddleware` | `onSystemPrompt` `onReasoning` |
| 130 | `SENSITIVE_WORD` | `SensitiveWordMiddleware` | `onAgent` |
| 125 | `MASKING` | `MaskingMiddleware` | `onAgent` |
| 120 | `SUBJECT_TOOL_AUTHORIZATION` | `SubjectToolAuthorizationMiddleware` | `onActing` |
| 115 | `HUMAN_APPROVAL` | `HumanApprovalMiddleware` | `onActing` |
| 112 | `JEV_REFUND_RISK` | `JevRefundRiskMiddleware` | `onAgent` `onActing` |
| 110 | `TOOL_GUARD` | `ToolGuardMiddleware` | `onActing` |
| 107 | `TASK_PLAN_CAPTURE` | `TaskPlanCaptureMiddleware` | `onAgent` |
| 105 | `LOOP_GUARD` | `LoopGuardMiddleware` | `onAgent` |
| 100 | `SELF_CORRECTION` | `SelfCorrectionMiddleware` | `onAgent` |
| 90 | `JEV_ESCALATION` | `JevEscalationMiddleware` | `onAgent` `onReasoning` |
| 80 | `DIALOG_STAGE` | `DialogStageMiddleware` | `onSystemPrompt` `onActing` `onAgent` |
| 75 | `JEV_TOOL_SCOPE` | `JevToolScopeMiddleware` | `onAgent` `onReasoning` |
| 70 | `KNOWLEDGE_INJECTION` | `data/rag/search/KnowledgeInjectionMiddleware` | `onReasoning` `onSystemPrompt` |
| 65 | `MODEL_COMPLETION` | `ModelCompletionMiddleware` | `onModelCall` |
| 60 | `DYNAMIC_OPTIONS` | `DynamicOptionsMiddleware` | `onModelCall` |
| 50 | `CONTEXT_BUDGET` | `ContextBudgetMiddleware` | `onReasoning` |

**插槽使用分布**（27 个实现类、41 次钩子覆写）：

| 插槽 | 使用数 | 用途归纳 |
|---|---|---|
| `onAgent` | **16** | 包住整轮 —— 准入、审计、计量、拦截都走这里 |
| `onActing` | 10 | 工具治理 —— 授权、审批、入参护栏 |
| `onReasoning` | 7 | 出站加工 —— 脱敏、敏感词、知识注入、预算裁剪 |
| `onModelCall` | 4 | 模型调用侧 —— 动态参数、结束原因、回放快照 |
| `onSystemPrompt` | 4 | 入站加工 —— 租户上下文、阶段提示词、注入防护 |

> **`onAgent` 是用得最多的插槽**（16/27），也是最容易写错的一个 ——
> 它包住的是**整轮**，而不是某个事件。想「拦下整轮」就该用它，
> 想「改一个答复块」就得更深（`onReasoning`）。

#### 3.5 框架那 3 个，customer_work 的 27 个里有没有变体？—— **结论：一个都没有**

| 框架件 | customer_work 里最接近的 | 判定 | 理由 |
|---|---|---|---|
| `GracefulShutdownMiddleware` | `GracefulShutdownService` | **不是变体，是正确使用框架能力** | 那边直接用框架的 `GracefulShutdownManager`，不是重写 |
| `TaskReminderMiddleware` | `TaskPlanCaptureMiddleware` | **互补，不是变体** | 框架件在**提示词侧**（把待办注入系统提示词给模型看）；customer_work 那个在**输出侧**（从事件流采集计划给用户看 + 度量模型用不用）。**但两者共用同一底座**（框架的 `TodoTools` / `todo_write`） |
| `FinalAnswerFilterMiddleware` | `MaskingMiddleware` / `SensitiveWordMiddleware` | **都不是变体** | 框架件决定「**哪段文本**才算最终答复」；后两者是「已确定的最终答复**要不要脱敏/拦词**」。同一车道、不同职责 |

⇒ **27 个全是自建**。框架给的是**插槽 + 排序契约**，不是策略。
唯一「白拿」的是 `GracefulShutdownMiddleware` —— 而且它是**自动注入**的，
customer_work 用 `GracefulShutdownManager` 把它接进自己的生命周期，属正例。

#### 3.6 顺序契约的三条纪律（demo 与 customer_work 同款）

| 纪律 | 守卫手段 | 违反后果 |
|---|---|---|
| 每个中间件**必须显式**声明 `order()` | 扫源码找 `implements MiddlewareBase` 且无 `public int order()` | 落回框架默认值 1 —— 而 1 是**框架自带件的取值**，顺序被悄悄改掉 |
| `order()` 取值**不得重复** | 反射 `MiddlewareOrders` 静态 int 字段 | 重复 = 退回按注册顺序 = 回到这套契约要解决的问题本身 |
| **关键相对次序**必须成立 | 断言大小关系 | 排错了**不报错**，只会以「未脱敏内容进了审计」的形式某天出现 |

#### 3.7 demo 站 3 的实现

| 文件 | 作用 |
|---|---|
| `agent/MvpMiddlewareOrders.java` | 顺序契约常量：`AUDIT=150` / `REDACTION=125` / `GUARD=120` |
| `agent/AuditTrailMiddleware.java` | 自建治理件（最外）：审计最终答复 |
| `agent/RedactionMiddleware.java` | 自建治理件（内于审计）：出站脱敏手机号 |
| `agent/InputGuardMiddleware.java` | 自建治理件（内于脱敏）：入站拦截，命中即短路 |
| `agent/MvpGovernanceAssembler.java` | 唯一装配入口；**删掉了无效的预排序** |
| `agent/MvpAgentFactory.java#describe` | 装配快照新增 `chain` 段（位置 / order / 来源 / 是否参与协商） |

**装配快照的 `chain` 段**（真机 `GET /api/agent/assembly` 实测输出）：

| # | 中间件 | order | 来源 | 参与 order 协商？ |
|---|---|---|---|---|
| 1 | `GracefulShutdownMiddleware` | 1 | 框架自动注入（链首硬编码） | **否** |
| 2 | `AuditTrailMiddleware` | 150 | 自建 | 是 |
| 3 | `RedactionMiddleware` | 125 | 自建 | 是 |
| 4 | `InputGuardMiddleware` | 120 | 自建 | 是 |
| 5 | `ToolAuditMiddleware` | 100 | 自建 | 是 |
| 6 | `FinalAnswerFilterMiddleware` | 1 | 框架自带（需显式注册） | 是 |

> 第 1 项 **order=1 却排在 order=150 前面** —— 这一行就是「链首硬编码、不参与协商」的
> 直接可视化。`orderNegotiated=false` 这个字段是专门为它加的。

#### 3.8 真机验证（本地 mock，零费用）

**① 运行时链序**（`MiddlewareChainOrderTest` 5 条）：入站降序、出站逆序、
注册顺序不影响生效顺序、链首硬编码、生效链严格降序。

**② 治理语义**（`GovernanceSemanticsTest` 5 条）—— 关键在**对照组**：

| 用例 | 断言 |
|---|---|
| 站3-11 | 审计留痕里是**脱敏后**的 `138****8000`，答复里也不残留原手机号 |
| 站3-12 | **对照组**：把审计与脱敏的 `order` 对调 ⇒ 留痕里**确实**出现未脱敏手机号 |
| 站3-13 | 拦截命中时模型**调用次数 = 0**，且给的是拒绝答复而不是空流 |
| 站3-14 | 短路发生在拦截层，外层审计**仍然留痕** |
| 站3-15 | 正常输入不受影响 —— 治理件不能变成拦路虎 |

> 站3-12 是这一站**最有价值的一条**：它把「审计必须比脱敏更外」从一句没人验证过的说法，
> 变成一个**可复现的反例**。没有它，站3-11 绿了也只能说明「当前顺序下恰好是对的」。

**③ 端到端**（mock 回显手机号 + 真机 HTTP）：

```
[redaction] outbound reply redacted
[audit] final reply recorded: 【MOCK】已收到请求，回显您的手机号 138****8000，这是本地 mock 回复。
[guard] input rejected, hit=内部密钥
[audit] final reply recorded: 抱歉，这个问题超出了我能处理的范围。
```

用户拿到的答复：`【MOCK】已收到请求，回显您的手机号 138****8000，这是本地 mock 回复。`
（已脱敏）；被拦截那条的模型调用次数为 **0**。

#### 3.9 站 3 踩的坑

1. **改写答复消息要用 `Msg#withContent`，不能用 `Msg.builder()` 重建** ——
   `withContent` 只换 content，`id / name / role / metadata / timestamp / usage` 全部保留；
   重建则要手工补 metadata，还会丢 `usage`。
   而框架把「结束原因」就存在 metadata 里（`METADATA_GENERATE_REASON`），
   重建会**静默退回**「正常结束」，外层的终止信封跟着错。
2. **框架自动注入的链首件不参与 order 协商** —— 见 3.3。快照里 `orderNegotiated=false` 就是为此加的字段。
3. **自己的预排序是无效的** —— 框架会重排。写了不但没用，还会让人误以为「顺序由我控制」。
4. **短路 ≠ 空流** —— 治理件要表达「我拒绝了你」，必须自己造一个 `AgentResultEvent`；
   返回 `Flux.empty()` 等于「模型不回复」，调用方看到的是另一种故障。
5. **钩子体在订阅时执行，不是装配时** —— 必须用 `Flux.defer` 包住，
   否则装配阶段就执行一遍（那时装配还没完成）。
6. **`MiddlewareChain.build` 里没有任何排序逻辑** —— 一开始以为排序在那儿，
   实际上排序在 `ReActAgent$Builder#build()` 里，链实现只负责「按列表倒序包裹」。
   **找框架行为要分清「谁排序」和「谁包裹」两件事。**

#### 3.10 站 3 的边界判定

| 能力 | 框架 2.0.5 给的 | 判定 |
|---|---|---|
| 中间件插槽 | `MiddlewareBase` 5 个 default 方法（可只覆写其中一两个） | **●** |
| 排序机制 | `order()` + `Builder#build()` 里的降序重排 + 稳定的 `List.sort` | **●** |
| 链实现 | `MiddlewareChain.build`（倒序包裹） | **●** |
| 自动注入的停机件 | `GracefulShutdownMiddleware`（链首硬编码） | **●** |
| 最终答复过滤 | `FinalAnswerFilterMiddleware`（需显式注册；**会吃掉流式效果**） | **●**（默认不用） |
| 任务提醒 | `TaskReminderMiddleware`（`taskListEnabled` 时自动挂） | **●** |
| 顺序契约 | — | **○** 自建（`MiddlewareOrders` 27 常量 + 契约守卫测试） |
| 具体治理策略 | — | **○** 27 个自建 |
| 业务审计 | `tracing` 包（`Tracer` / `OtelTracingMiddleware`）**但 span 语义 ≠ 业务审计语义** | **○** 自建 |
| 链序可观测 | — | **○** 自建（快照 `chain` 段） |

> **这 27 个才是 `customer_work` 的真正资产。** 框架给了 5 个插槽让我们往里塞，
> 塞什么、什么顺序、怎么协作 —— 全是我们自己的业务决策。
> 数值从 200 递减到 50（步长 5，个别 2/3 用于细分相邻层），
> 说明这套顺序是**反复调出来的**，不是随便排的 —— 这也是 `MiddlewareOrderContractTest`
> 这个「扫源码 + 反射断言 order() 契约」的守卫测试存在的原因。
>
> **站 3 的实证把这个结论从「推的」变成「验的」**：
> 27 个里**没有一个是框架那 3 个的变体**（见 3.5），
> 而它们之间的相对次序**直接决定安全结论**（见 3.8 的对照组）——
> 「这 27 个才是真正的资产」这句话，靠的不是数量，是**顺序排错了要出事**。

---

### 站 4 — 记忆 + RAG（待实施）

| 能力 | 框架 2.0.5 给的 | 判定 |
|---|---|---|
| 会话状态 | `state` 包：`AgentState` / `AgentStateStore` / `InMemoryAgentStateStore` / `JsonFileAgentStateStore` / `VersionedState` / `ConflictPolicy` / `ListHashUtil` | ◐ |
| 长期记忆 | `memory` 包：`Memory` / `LongTermMemory` / `LongTermMemoryMode` / `LongTermMemoryTools` / `StaticLongTermMemoryHook` / `AgentStateMemoryView` / `StateBackedMemory` | ◐ |
| RAG | `rag` 包：`Knowledge` / `RAGMode` / `GenericRAGHook` / `KnowledgeRetrievalTools` | ◐ |
| Skill | `skill` 包：`SkillBox` / `AgentSkill` / `SkillRegistry` / `DynamicSkillMiddleware` / `SkillToolFactory` / `AgentSkillPromptProvider` | ● |
| 上下文压缩 | `agentscope-harness`（`io.agentscope.harness.agent`） | ◐ |
| 外部记忆后端 | `extensions-reme` / `extensions-mem0` 2.0.5 | ● |

> ⚠️ **弃用警告（实测确认）**：`io.agentscope.core.rag.Knowledge` 在 **2.0.5 里仍然标记
> `@Deprecated` + `forRemoval=true`**（`javap -v` 实测）。
> 早期记录的 `rag.Knowledge` / `memory.LongTermMemory` / `hook.Hook` 三处 `forRemoval` 标记，
> 到 2.0.5 **一个都没撤**。**在 2.0.5 上写 RAG 集成要绕开 `Knowledge`。**

---

### 站 5 — 出站（待实施）

**框架在这一站只给「事件」，不给「出口」。**

| 能力 | 判定 | 说明 |
|---|---|---|
| 事件类型体系 | ● | `event` 包 |
| `call` vs `streamEvents` | ● | 框架层是**同一条流**（`call` 走 `takeLast(1)` 投影） |
| 出站过滤规则 | ○ | 3 条规则自建（`CustomerServiceService.java:559-631`） |
| 语义缓存 | ○ | core 无 cache 包 |
| 缓存准入判定 | ○ | `ReplyFinish#replayable()` 收口（`ReplyFinish.java:66-69`） |
| 缓存切片下发 | ○ | `CustomerServiceService.java:522-529`（24 字符切片） |
| 消息落库 | ○ | `ChatTurnService.java:26-52`（chat 是 stream 的投影） |

> **站 5 要验证的核心事实**：`chat` 和 `chatStream` 在框架层是同一条流，
> 差异全在**消费端**。这意味着「非流式路径漏了出站过滤」这类 bug 是**框架不会帮你兜的**。
> —— 而这正是 `customer_work` 已经踩过的坑（`applyOutboundGuard` 早期只有流式做，
> 非流式命中缓存时原文直出，已修复，代码注释有记录）。

---

### 站 6 — 多租户 + admin（待实施）

**这一站是「框架给了但要不要用」的分水岭。**

| 能力 | 框架 2.0.5 | customer_work | 判定 |
|---|---|---|---|
| 租户隔离 | core **无 tenant 包** | `TenantContextMiddleware` + 独立库 | ○ 框架真空 |
| 审计 | core **无 audit 包** | `AuditMiddleware` | ○ 框架真空 |
| 工单 / 转人工 | core **无 ticket / handoff 包** | `HandoffService` | ○ 框架真空 |
| 满意度 | core **无 csat 包** | 自建 | ○ 框架真空 |
| 语义缓存 | core **无 semanticcache 包** | 自建 | ○ 框架真空 |
| admin 控制台 | **`agentscope-admin-spring-boot-starter` 2.0.5** | 自建 `customer-admin-server` + `customer-admin-web` | ⚠️ **框架已给，需评估** |
| 渠道 | `extensions-channel-*` | `customer-channel` 模块 | ● |

**`agentscope-admin-spring-boot-starter` 2.0.5 实测提供**：

- **13 个运维 endpoint**：`Agentscope` / `Commands` / `Doctor` / `Drain` / `Models` / `Permissions` /
  `Shutdown` / `Status` / `Subagents` / `Tools` / `Usage`
- `AgentRegistry` + `AgentResolver` + `InMemoryAgentRegistry`（Agent 注册与发现）
- `AdminAuditEvent` + `AdminAuditLogger`（**审计**）
- `SnapshotStore` + `AgentStateRestorer`（**状态快照与恢复**）
- `MetricsHook` + `MetricsRecorder` + `UsageStats`（**用量统计**）
- `SessionAdminController` + `SubagentTaskOperations` + `WriteGuard`（**写保护**）
- `AdminProperties` + `AdminOpenApiConfiguration`（配置 + OpenAPI）

> **这是「重复造轮子」最值得复盘的一处。** `customer_work` 的 `customer-admin-server` 2288 个测试
> 里有相当一部分在做「列会话 / 看消息 / 管 Agent」——而框架 starter 已经给了。
> **但**框架给的是**运行时运维面**（单实例 introspect），
> `customer_work` 给的是**业务控制面**（知识库管理、词表、租户、发布流程、多库）。
> **两者不重叠，不能互相替代**。站 6 要做的是划清这条线，而不是二选一。

---

## 4. 复盘 A：框架已经给了，我们又写了一遍（重复造轮子候选）

> 判定标准：**框架 2.0.5 的已发布 extension 里已有对应物**，且我们自建了功能相近的东西。

| 候选 | 框架 2.0.5 提供 | customer_work 自建 | 结论 |
|---|---|---|---|
| **Jev 接入** | `agentscope-extensions-jev`：`JevClient` / `ScoreQuestion` / `ChoiceQuestion` / `NoulQuestion` / `ScoreAnswer` / `ChoiceAnswer` / `NoulAnswer` / `JevRetryPolicy` / `Usage` / `SystemOneRequest` | `JevEscalationMiddleware` / `JevRefundRiskMiddleware` / `JevToolScopeMiddleware` + TypeSafe 接入 | **最典型的重复**。框架连「Noul 没有 confidence 字段」这种坑都已经在 `NoulAnswer` 上体现了 |
| **Redis 状态持久化** | `agentscope-extensions-redis`：`RedisAgentStateStore`（**jedis / lettuce / redisson 三种客户端**）/ `RedisStateVersionSupport` / `RedisAgentStateKeyLayout` / `RedisRemoteSnapshotClient` | 自建 Redis 持久化 | **大概率重复**。三客户端适配 + 版本支持框架都写了 |
| **MySQL 状态持久化** | `agentscope-extensions-mysql`：`MysqlAgentStateStore` / `JdbcStore` + **4 种 dialect**（MySQL / Postgres / SQLite / H2） | 自建 MyBatis-Plus | **大概率重复** |
| **AG-UI 适配** | `agentscope-extensions-agui`：**50+ 类**（adapter / converter / encoder / event / model / processor / registry / runtime），含 `AguiResumeCoordinator`（断点续跑）、`PermissionConfirmEventConverter`（审批）、`SubagentEventConverter` | `AguiService`（落库）+ 自建适配 | **部分重复**。协议层框架给全了，落库/业务投影仍要自建 |
| **admin 运行时运维面** | `agentscope-admin-spring-boot-starter`：**13 endpoint** + registry + 审计 + 快照 + metrics | `customer-admin-server` 2288 测试 | **部分重复**。业务控制面不重叠，运维面重叠 |
| **多厂商模型注册表** | `MiniMaxModelProvider` / `DeepSeekModelProvider` / `GLMModelProvider` / `KimiModelProvider` + `credential` 包（`DeepSeekCredential` / `KimiCredential` / `XAICredential`） | `ChatModelFactory` 五路 switch + 自建凭据解析 | **部分重复**。但 customer_work 还要接阿里云/智谱等非 OpenAI 兼容厂商 |
| **渠道接入** | `extensions-channel-*`：feishu / wecom / dingtalk / **weixin** / github / gitlab | `customer-channel` 模块 82 测试 | **框架已给，需评估** |
| **模型调用重试**（站 2 新增） | `Builder.maxRetries` / `Builder.modelExecutionConfig(ExecutionConfig)`：`timeout` / `maxAttempts` / `initialBackoff` / `maxBackoff` / `backoffMultiplier` / `retryOn`（`RETRYABLE_ERRORS`）+ `jitter(0.5)` | 自建 `ResilientChatModel`（`Retry.backoff` + `jitter(0.3)` + `isRetryable` 过滤） | **重复，而且会叠乘**。框架那层在 `OpenAIChatModel` 内部执行，自建那层在装饰器上 → **总尝试数 = 外层 × 内层**，每次重试都是一次完整 HTTP 请求 |
| **单候选兜底模型**（站 2 新增） | `Builder.fallbackModel(Model)` + `failoverListener(FailoverListener)`（运行期降级） | 自建 `FailoverModel`（主备**有序候选表** + 熔断记忆 + 按部署健康度剔除候选） | **部分重复**。单候选形态框架已给；多候选 / 熔断 / 健康剔除要自建 |
| **工具权限引擎**（站 2 新增） | `PermissionEngine` / `PermissionContextState` / `PermissionRule` / `PermissionMode`（DEFAULT / ACCEPT_EDITS / EXPLORE / BYPASS / DONT_ASK） | Permission ask 规则（**只覆盖退款与转人工，且默认关闭**） | **部分重复**。引擎框架给全了；**规则覆盖面要自己补** —— customer_work 只加了 2 条 ask 规则，其余写操作无闸门 |
| **优雅停机**（站 2 新增） | `GracefulShutdownManager` / `GracefulShutdownMiddleware`（**框架自动注入到链首**）+ `bindStateSaver`（有 stateStore 时自动绑定状态保存） | `GracefulShutdownService`（`SmartLifecycle` 包装） | **不是重复，是正确使用** —— 框架给了内核，customer_work 把它接进 Spring 生命周期。**本清单里少有的正面案例** |

> **站 2 的一条通用判据**：
> 「框架有没有给」要看**调用链的哪一层**。
> 同一个「重试」能力，框架在**模型实现内部**有、`Builder` 上也有 ——
> 而 customer_work 自建的那层在**装饰器上**，于是三者叠成两层。
> **自建之前先问：框架那层是在我这一层的里面还是外面？**

**另外两条 2.1.0-BETA1 才有的（预发布，不进生产，但值得跟踪）**：

- `agentscope-extensions-controlplane` —— 只有 `2.1.0-BETA1`，无 release
- `agentscope-extensions-session-redis` / `session-mysql` —— 只有 `2.0.0-RC1`，无 release

> `session-redis` 和 `extensions-redis` 的 `RedisAgentStateStore` **不是一回事**：
> 前者是「会话存储」，后者是「Agent 状态存储」。名字接近，容易误判为重复。**待站 4 核实。**

---

## 5. 复盘 B：框架完全没给（`customer_work` 的真正价值）

> 判定标准：**框架 core 的 20 个顶层包 + 已发布 extension 里都查无此物。**

| 能力 | 框架有无 | customer_work 实现 |
|---|---|---|
| **多租户隔离** | core 无 `tenant` 包 | `TenantContextMiddleware` + 租户独立库 + 资源位 |
| **业务审计** | core 无 `audit` 包 | `AuditMiddleware`（150 顺序位） |
| **工单 / 转人工** | core 无 `ticket` / `handoff` 包 | `HandoffService`（5 个调用来源） |
| **满意度评价** | core 无 `csat` 包 | 自建 |
| **语义缓存 + 准入** | core 无 `semanticcache` 包 | `ReplyFinish#replayable()` + 降级不缓存原则 |
| **对话落库 / 终端捕获** | core 无 `chatlog` 包 | `ChatTurnService` + `ChatTerminalCaptureMiddleware` |
| **WS 接入 + 受理 + 幂等** | core 无网络协议包 | `UserChatWebSocketHandler` + `ChatDispatchService` |
| **会话级分布式锁** | `ConflictPolicy` 只管状态冲突 | `withSessionLock` / `withSessionLockFlux` |
| **多 Agent 编排** | core 有 `SubagentEventBus` / `AgentRun`，无编排器 | `MultiAgentOrchestrator` |
| **业务闸门（答复安全 / 循环 / 注入）** | 框架只有 `FinalAnswerFilterMiddleware` + 自动注入的 `GracefulShutdownMiddleware` | 27 个自建中间件 |
| **渠道业务适配** | 框架给协议层 | `customer-channel` 的消息模型 / 卡片 / 五套前端 |

> **这张表就是 `customer_work` 相对 AgentScope Java 的全部差异化价值。**
> 面试时能讲清楚「框架给了什么、我们建了什么、为什么这部分框架不会给」，
> 比背框架 API 有用得多。

---

## 6. 框架生态地图（`io.agentscope` 全量，Maven Central 实测）

**版本状态实测（2026-11-25，`repo1.maven.org` metadata）**

| Artifact | latest | 有 2.0.5？ |
|---|---|---|
| `agentscope-core` / `harness` / `bom` | 2.1.0-BETA1 | ✅ |
| `agentscope-extensions-jev` | 2.1.0-BETA1 | ✅ |
| `agentscope-extensions-agui` | 2.1.0-BETA1 | ✅ |
| `agentscope-agui-spring-boot-starter` | 2.1.0-BETA1 | ✅ |
| `agentscope-admin-spring-boot-starter` | 2.1.0-BETA1 | ✅ |
| `agentscope-extensions-channel` (+ weixin) | 2.1.0-BETA1 | ✅ |
| `agentscope-extensions-mysql` / `redis` / `reme` / `mem0` | 2.1.0-BETA1 | ✅ |
| `agentscope-extensions-rag-ragflow` / `rag-haystack` | 2.1.0-BETA1 | ✅ |
| `agentscope-extensions-skill-git-repository` | 2.1.0-BETA1 | ✅ |
| `agentscope-extensions-sandbox-agentrun` | 2.1.0-BETA1 | ✅ |
| `agentscope-extensions-training` / `agent-protocol` | 2.1.0-BETA1 | ✅ |
| `agentscope-extensions-model-openai-official` | 2.1.0-BETA1 | ✅ |
| `agentscope-copilotkit` | **2.0.5** | ✅ |
| `agentscope-extensions-judge` | 2.1.0-BETA1 | ⚠️ metadata 有 2.0.5，**jar 两个镜像都取不到（404）** |
| `agentscope-extensions-controlplane` | 2.1.0-BETA1 | ❌ 仅 BETA |
| `agentscope-extensions-session-redis` / `session-mysql` | 2.0.0-RC1 | ❌ 仅 RC |
| `agentscope-extensions-autocontext-memory` | 1.1.0-RC2 | ❌ 仅 RC |
| `agentscope-extensions-mcp-nacos` | 1.0.3 | ❌ 无 2.x |
| `agentscope-codingagent` | 2.0.0-RC4 | ❌ 仅 RC |

**另有 2.0.5 已发布但本项目未用的高价值 extension**（按用户求职方向排序）：

| Artifact | 说明 | 与求职的关系 |
|---|---|---|
| `agentscope-extensions-judge` | **LLM-as-judge** | 🎯 **AI Coding Agent 评测工程师**直接对口 |
| `agentscope-codingagent` | Coding Agent 运行时（仅 RC4） | 🎯 同上 |
| `agentscope-copilotkit` | CopilotKit 协议 | 🎯 同上 |
| `agentscope-extensions-agent-protocol` | Agent 间协议 | 多 Agent 编排 |
| `agentscope-extensions-training` | 训练 | — |

> ⚠️ `agentscope-extensions-judge` 虽在 metadata 里有 2.0.5，但 **Maven Central 与阿里云镜像下载 jar 均 404**。
> 可能是发布流程问题。**待核实后再用**，本文档不把它算进「框架直接给」。

---

## 7. 证据索引（可复现）

**框架 jar 本地路径**（本机 Maven `localRepository` 被全局 settings.xml 改到了 `D:\Program Files\JAVA\apache-maven-3.9.11\repository`，
**不是**默认的 `~/.m2/repository` —— 这点容易找错 jar）：

```
D:\Program Files\JAVA\apache-maven-3.9.11\repository\io\agentscope\
  agentscope-core\2.0.5\agentscope-core-2.0.5.jar
  agentscope-extensions-model-openai\2.0.5\...
  agentscope-admin-spring-boot-starter\2.0.5\...
  agentscope-extensions-agui\2.0.5\...
```

**核实命令**：

```powershell
$javaHome = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot'
$jarExe = Join-Path $javaHome 'bin\jar.exe'
$javap  = Join-Path $javaHome 'bin\javap.exe'
$core   = 'D:\Program Files\JAVA\apache-maven-3.9.11\repository\io\agentscope\agentscope-core\2.0.5\agentscope-core-2.0.5.jar'

# core 顶层包清单（证明没有 tenant/audit/handoff/ticket 包）
# ⚠️ 必须过滤空串：jar tf 会输出 "io/agentscope/core/" 这类目录条目，
#    切分后第 4 段是空串 —— 把它数进去会得到 21（真实是 20）。
& $jarExe tf $core | Where-Object { $_ -like 'io/agentscope/core/*' -and $_ -like '*/*' } |
  ForEach-Object { ($_ -split '/')[3] } | Where-Object { $_ -notlike '*.*' -and $_ -ne '' } |
  Sort-Object -Unique | Tee-Object -Variable pkgs
"包数: $($pkgs.Count)"

# 中间件插槽
& $javap -classpath $core 'io.agentscope.core.middleware.MiddlewareBase'

# 框架自带中间件实现类（含 shutdown 包里那个「自动注入」的）
& $jarExe tf $core | Where-Object { $_ -like 'io/agentscope/core/*Middleware.class' }
#   → middleware/FinalAnswerFilterMiddleware.class
#     middleware/TaskReminderMiddleware.class
#     shutdown/GracefulShutdownMiddleware.class    ← 由 ReActAgent 构造函数插到链首（不是 build()）

# —— 站 3：order() 语义的两处字节码证据 ——
# ① 排序在 Builder#build() 末尾：comparingInt(order).reversed() = 降序
& $javap -p -c -classpath $core 'io.agentscope.core.ReActAgent$Builder' |
  Select-String 'comparingInt|reversed|List.sort' -Context 2,2
# ② 链实现在 MiddlewareChain.build：从列表尾部往前包裹 ⇒ 列表第 0 个 = 最外层
& $javap -p -c -classpath $core io.agentscope.core.middleware.MiddlewareChain

# ③ 链首硬编码：ReActAgent 构造函数里 all.add(GracefulShutdownMiddleware) 在 addAll 之前
& $javap -p -c -classpath $core io.agentscope.core.ReActAgent |
  Select-String 'GracefulShutdownMiddleware' -Context 12,8

# 弃用标记
& $javap -v -classpath $core 'io.agentscope.core.rag.Knowledge' | Select-String 'forRemoval|Deprecated'
```

**customer_work 侧核实命令**：

```powershell
$root = 'E:\Program Files\java_web\customer_work\customer-work-starter\src\main\java\com\richard\fyoung\customerwork'

# 27 个中间件实现类
Get-ChildItem $root -Recurse -Filter *.java |
  Select-String -Pattern 'implements MiddlewareBase|extends MiddlewareBase' -List

# 27 个顺序常量
Get-Content "$root\core\middleware\MiddlewareOrders.java" -Encoding UTF8 |
  Select-String 'public static final int'
```

---

## 8. 后续计划（站 4 → 站 6）

| 站 | 目标 | 结束时的判定产出 | 状态 |
|---|---|---|---|
| 1 | WS 帧 → 受理 → 编排 | 「框架在接入层给了 0 个东西」的证据链 | ✅ 完成 |
| 2 | 手写 AgentFactory + 治理装配器 | 逐件对照 `CustomerServiceAgentFactory` 的 14 个零件 | ✅ 完成 |
| 3 | 中间件链 + `MiddlewareOrders` 契约 | 27 个中间件里，**框架其实已给的有几个** → 实测结清：**0 个是变体**，框架只给插槽 + 排序契约 + 1 个自动注入件 | ✅ 完成 |
| 4 | 记忆 + RAG | `RedisAgentStateStore` 能否替掉自建持久化 | ⬜ 待做 |
| 5 | 出站 + 缓存 | 缓存准入收口是否只能是 `ReplyFinish#replayable()` | ⬜ 待做 |
| 6 | 多租户 + admin | **框架 admin starter 与自建 admin 的职责边界线** | ⬜ 待做 |

**每站都必须可运行**，并且每站结束追加一节到本文档的「逐站详表」。

**站 3 的三个待解问题已全部实测结清**：

1. **框架自带 3 个中间件，customer_work 那 27 个里有没有哪个其实是它们的变体？**
   → **没有一个是变体**（详见 3.5）。27 个全是自建；框架给的是插槽 + 排序契约。
2. **`MiddlewareOrders` 的 27 个常量与框架默认 `order(1)` 的相对关系？**
   → 27 个治理件全部 `order ≥ 50` ⇒ **稳定落在框架自带件（order=1）之外**；
   而框架自动注入的 `GracefulShutdownMiddleware` **不参与协商、硬编码在链首**（详见 3.3）。
3. **`order()` 到底是「大的在外」还是「小的在外」？**
   → **数值大的在外**。字节码两处 + 运行时探针双重验证（详见 3.1）。

**站 4 已经能带着走的问题**：

1. `AgentStateStore` 框架只给接口，`InMemory` / `JsonFile` 两个实现够不够？
   customer_work 自建的 Redis 版能不能替掉？**版本号 CAS 谁来做**（框架默认实现返回 `-1` = 不支持版本）？
2. 长期记忆：`LongTermMemoryMode` 的几种模式能不能覆盖 customer_work 的记忆分层？
   而 `memory.LongTermMemory` 在 2.0.5 **仍标记 `forRemoval`** —— 绕不绕得过？
3. RAG：`rag.Knowledge` 在 2.0.5 **仍标记 `forRemoval`**，
   `GenericRAGHook` 的检索时机能不能替掉 customer_work 的 `KnowledgeInjectionMiddleware`？
4. 上下文压缩：`agentscope-harness` 的 `HarnessAgent` 与 `ReActAgent` 的切换阈值谁来定？

---

## 附：demo 工程位置与运行方式

```
真实路径：E:\Program Files\java_web\agent-mvp
工作区联接：E:\Program Files\java_web\customer_work\agent-mvp-link  → ..\agent-mvp
```

```powershell
Set-Location 'E:\Program Files\java_web\customer_work\agent-mvp-link'
$env:JAVA_HOME='D:\Program Files\JAVA\jdk-21'      # 本机 JDK 21
$env:Path="$env:JAVA_HOME\bin;$env:Path"

mvn -B clean test                                   # 59 个用例，不烧 token
$env:MINIMAX_API_KEY='<你的 key>'
mvn -B spring-boot:run -pl mvp-app                   # 起在 8088
```

**端点**：

```
POST http://localhost:8088/api/auth/token     {"userId":"u1001"}
POST http://localhost:8088/api/chat/sync      {"message":"我的订单 SO202611250001 到哪了？"}   （带 Bearer）
POST http://localhost:8088/api/chat/stream    （SSE，逐字下发）
GET  http://localhost:8088/api/agent/assembly （站 2：装配快照）
WS   ws://localhost:8088/ws/user?token=<token>
```

**站 2 的实测配置**（`application.yml` 的 `mvp.agent` 段）：
`max-retries: 2` 与 `model-max-attempts: 5` **刻意设成不同值** ——
`/api/agent/assembly` 的返回里 `maxRetries` 是 2，而**实际生效的**
`modelExecutionConfig.maxAttempts` 是 5 —— **一眼看出「两个都配时谁赢」**。
