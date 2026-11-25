# Agent 后端全景总结（收官篇）

> **📌 行号引用说明**：本文档的 `文件:行号` 全部指向 **customer_work** 仓库
> （对照项目，AgentScope Java 2.0.4 的企业级智能客服平台）——
> <https://github.com/ZhongZiYao/customer_work>。
> 本 demo 仓库只含 `agent-mvp/` 本身，不含被对照的那些源码文件。

> 这是逐课陪读（第 1～8 课）的收束版。目标不是复述细节，而是给你一张**能自己复述出来的全景图**。
>
> 所有断言带 `文件:行号`，可直接跳进去核对。
> 结论来自静态阅读源码 + 对 `agentscope-core-2.0.4.jar` 的字节码反编译；**未运行构建/测试**。
> 凡框架内部行为未能反编译确认的，本文显式标注「不确定」。

---

## 0. 一张图看完整条链路

```
                          ┌─────────────────────────────────────────┐
   H5 (5174)              │        customer-work-app-server (8080)   │
                          │                                         │
  onMounted ──────────────┼─► POST /customer/user/sessions           │
    ├─ createSession()    │     UserTicketController:92-109           │
    │  ← {sessionId,ticketId}                                      │
    │                     │     ┌── WS 线（用户维度，与会话无关）──┐ │
    └─ connectWs(token) ──┼───►│ WebSocketConfig:28-45 → /ws/user │ │
       ws://…/ws/user     │     │ UserChatWebSocketHandler:84-110 │ │
       ?token=JWT         │     │  握手四检 + epoch 前后双检      │ │
                          │     └──────────────┬───────────────────┘ │
                          │                    │                     │
  发消息 ─────────────────┼─► WS 帧 {type:chat, data:{sessionId,      │
       {content, clientMsgId}}                                    │ │
                          │     UserChatWebSocketHandler:181-203    │ │
                          │       └─► ChatDispatchService:157      │ │
                          │            ├ 归属校验 ownsSession:416  │ │
                          │            ├ 配额 / 幂等 / 事务落库   │ │
                          │            └─► 路由决策（AI 还是人工） │ │
                          │                    │                   │
                          │        ┌───────────┴───────────┐       │
                          │        │ ChatTurnService:36-52 │       │
                          │        │ （编排层：一条流，三出口共用）│
                          │        └───────────┬───────────┘       │
                          │                    │                   │
                          └────────────────────┼───────────────────┘
                                               │
                          ┌────────────────────▼───────────────────┐
                          │      customer-work-starter（无端口）    │
                          │                                        │
                          │  CustomerServiceService:468-491        │
                          │    ├ 配额 check                         │
                          │    ├ 语义缓存 lookup ──► Embedding HTTP │
                          │    │   命中 → 切片吐字（不装配 Agent） │
                          │    └ 未命中 ↓                          │
                          │  resolveAgent (LRU) :890-895           │
                          │  CustomerServiceAgentFactory:220-290  │
                          │    ├ .model(model) ← 唯一的 Model Bean  │
                          │    ├ 工具 7 组 + MCP                    │
                          │    ├ AgentGovernanceAssembler.applyTo  │
                          │    │   （治理唯一入口，27 个中间件）    │
                          │    └ stateStore + RuntimeContext       │
                          │                    │                   │
                          │  ▼ ReAct 循环（框架 jar 内）          │
                          │  ReActAgent$CallExecution             │
                          │    coreAgent()                        │
                          │      ├ reasoning()  → MiddlewareChain│
                          │      ├ modelCallStream()               │
                          │      │   ★ Model.stream(...) ← 调模型  │
                          │      ├ acting()      → 工具执行         │
                          │      └ 回到 reasoning()  ← 再调一次    │
                          │                    │                   │
                          │  ★ Model 装饰器链（5 层）             │
                          │    MutableDelegatingModel（热替换）    │
                          │      └ TieredRoutingModel（分级路由）  │
                          │          └ ResilientChatModel（重试）  │
                          │              └ FailoverModel（主备）  │
                          │                  └ AttributedModel    │
                          │                      └ DashScopeChat…  │
                          │                        （真 HTTP）      │
                          └────────────────────┬───────────────────┘
                                               │
                          事件流出站：过滤 getSource() → 只取 TextBlockDelta
                                               │
                          ChatTurnFinalizer:25-34 → 落库 cw_chat_message
                                               │
  ◄══ chat_accepted ════  （受理回执）
  ◄══ chat_chunk × N ════  （增量，逐字上屏）
  ◄══ chat_done ════════  （终止信封 + messageId）
```

**这张图上只有三个「折叠点」值得记住**：

| 折叠点 | 一句话 |
|---|---|
| **接入层只管受理** | 幂等、配额、归属、落库、路由决策。**不碰模型** |
| **编排层只管跑一轮** | 缓存 → 装配 → 锁 → 流式。**不碰业务语义** |
| **治理层只管加工** | 27 个中间件按契约分层。**不决定走哪条路** |

---

## 1. 五层职责与边界

| 层 | 位置 | 只做这一件事 | 明确不做 |
|---|---|---|---|
| **接入层** | `ChatDispatchService` / `UserChatWebSocketHandler` | 受理：鉴权、归属、配额、幂等、落库、路由决策 | 不调模型、不裁剪上下文、不改答复 |
| **编排层** | `ChatTurnService` / `CustomerServiceService` | 跑一轮：缓存 → 装配 → 锁 → 流式 → 收尾 | 不判断业务意图、不改写正文 |
| **装配层** | `CustomerServiceAgentFactory` / `HarnessAgentFactory` | 把 14 个零件装成一个 Agent | 不执行装配后的逻辑 |
| **治理层** | `AgentGovernanceAssembler.applyTo` | 把 27 个中间件按契约织进去 | 不新增能力、不决定路由 |
| **收尾层** | `ChatTurnFinalizer` / `ReplyFinish` | 封存：落库 + 终止信封 + 缓存准入判定 | 不重跑、不修改正文 |

**四层之间靠四种标识串联**，这是整个架构的骨架：

| 标识 | 传递什么 | 在哪产生 |
|---|---|---|
| `clientMsgId` | 同一条用户消息的所有重发是同一条 | 前端 `crypto.randomUUID()`（`useMessageDelivery.ts:111-112`） |
| `(userId, sessionId)` | 状态落哪、长期记忆归谁 | 会话号格式 `u<userId>:conv-<UUID>`（`UserTicketController:102`） |
| `RuntimeContext` | 状态槽位由可信上下文决定 | `AgentGovernanceAssembler.contextFor:129-156` |
| Reactor Context | 主体/租户/token 记账随流下传 | `TenantContextThreadLocalAccessor.KEY` |

**任何一层都不越界替另一层做决定**——这是它能扛住 7 条并行对话链路的原因。

---

## 2. 三条出口，同一条内核

| 出口 | 入口 | 与内核的关系 |
|---|---|---|
| 同步 HTTP | `CustomerServiceController:62` `POST /chat` | `chatTurnService.chat()` = `stream()` + `.ofType(Completed).single()` |
| SSE | `CustomerServiceController:73` `POST /chat/stream` | `chatTurnService.stream()` 直接映射成 `ServerSentEvent` |
| WebSocket | `UserChatWebSocketHandler:191` 帧 `type=chat` | `ChatDispatchService.streamAi:395` 订阅同一个 `stream()` |

**关键**：同步响应复用流式核心（`ChatTurnService:26-34`），所以两种协议的模型、持久化、终止字段**完全一致**——不会出现「HTTP 能跑通但 WS 字段对不上」这种偏差。

---

## 3. 「调模型」的三层结构（本次新增）

用户提问「具体调模型的代码在哪」，答案分三层：

### 3.1 组装层：在你的仓库里

入口是 `ModelConfig.chatModel()`——starter 里**唯一的 `Model` Bean**：

```java
// infra/config/ModelConfig.java:54-57
@Bean
public MutableDelegatingModel chatModel(CustomerWorkProperties properties) {
    return new MutableDelegatingModel(buildChain(properties.getModel()));
}
```

`buildChain`（`ModelConfig:68-122`）套 5 层装饰器，越外层越先收到调用：

| 层 | 文件:行号 | 做什么 |
|---|---|---|
| 热替换 | `infra/config/MutableDelegatingModel.java:61-64` | `AtomicReference` 持链，`swap()` 原子换；admin 改配置即时生效 |
| 分级路由 | `core/model/tiered/TieredRoutingModel.java:59-80` | 按消息条数/长度选经济档或标准档；**首分片之后不回退** |
| 重试 | `infra/config/ResilientChatModel.java:40-50` | `Flux.defer` + `retryWhen(backoff+jitter)`；只重试瞬时/服务端错误 |
| 主备 | `core/model/failover/FailoverModel.java:81` | 有序候选 + 熔断记忆（连续 3 次失败跳过 60 秒） |
| 归因 | `core/model/attribution/AttributedModel.java:25-30` | 订阅边界把部署/价格快照塞进 Reactor Context |

厂商实例构建在 `ChatModelFactory.build()`（`infra/config/ChatModelFactory.java:74-167`），五路 provider：dashscope / openai / anthropic / gemini / ollama。

> **一个值得注意的设计**：`glm`/`deepseek`/`kimi`/`minimax` 走 OpenAI 兼容协议，但**没走通用 OpenAI 路径**——通用路径会**丢掉 system role**（框架 #2189）。系统提示词是客服智能体全部行为约束的载体，丢了不报错、直接静默失效。所以这四家各带专用 Formatter（`ChatModelFactory:217-232`）。

### 3.2 调用层：在框架 jar 里（不在你的仓库）

`io.agentscope.core.ReActAgent$CallExecution` 反编译结果：

```
coreAgent()                    ← 循环本体
 └─ reasoning(int, boolean)
      └─ MiddlewareChain.build(...)      ← 27 个中间件在这里织入
      └─ ModelCallInput(messages, tools, options, modelForCall())
           └─ reasoningStream(...)
                └─ modelCallStream(...)
                     └─ ★ Model.stream(messages, tools, options)
```

`modelCallStream` 字节码第 45 条指令：

```
45: invokeinterface #736, 4   // InterfaceMethod io/agentscope/core/model/Model.stream:
                                //   (List;List;GenerateOptions;)Lreactor/core/publisher/Flux;
```

**这一行就是「调模型」。** 下面返回 `Flux<ChatResponse>`，每个元素是一段增量文本。

### 3.3 扩展点：中间件在模型边界上织入

`ModelCallInput` 是 record，四个成分：`messages` / `tools` / `options` / `model`。
**中间件可以替换 `input.model()`**——项目里有 4 个中间件用了这个能力：

| 中间件 | 文件:行号 | 在模型边界做什么 |
|---|---|---|
| `ModelCompletionMiddleware` | `core/middleware/ModelCompletionMiddleware.java:29-33` | 换 `RecordingModel`，记录厂商返回的 finishReason |
| `DynamicOptionsMiddleware` | `core/middleware/DynamicOptionsMiddleware.java:50-53` | 按轮次改 `GenerateOptions`（温度、推理强度） |
| `AgentCallTimingMiddleware` | `data/calllog/AgentCallTimingMiddleware.java:206` | token 计量、耗时统计 |
| `ModelReplayCaptureMiddleware` | `data/calllog/ModelReplayCaptureMiddleware.java:26-27` | 捕获模型调用用于回放 |

```java
// core/middleware/ModelCompletionMiddleware.java:29-33
public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext context, ModelCallInput input,
                                    Function<ModelCallInput, Flux<AgentEvent>> next) {
    return next.apply(new ModelCallInput(input.messages(), input.tools(), input.options(),
        new RecordingModel(input.model())));   // ← 换掉 model，其余原样委托
}
```

`RecordingModel`（同文件 `:41-76`）只改 `stream()`，`getModelName()` / `supportsNativeStructuredOutput()` / `getContextWindowSize()` **全部原样委托**——下游会拿这些能力声明做决策，改了就是埋雷。

### 3.4 ⚠️ 一个必须记住的量纲

**一次用户提问，模型可能被调用 N 次**（N = 工具调用轮数 + 1）。

ReAct 循环里，模型每输出一个 tool_call 就**再调一次模型**。所以：
- **token 计量必须按 `Model.stream` 边界算**，不是按「一轮对话」算
- 「一条消息花了多少 token」这个问题，答案取决于走了几轮工具

---

## 4. 「创建完会话之后如何到 WebSocket」（本次新增）

### 4.1 ⚠️ 先纠正心智模型

**「创建完会话之后跳到 WebSocket」这个说法会误导。** 实际是**两条完全独立的线**：

| 线 | 协议 | 携带什么 | 生命周期 |
|---|---|---|---|
| HTTP 线 | `POST /customer/user/sessions` | JWT（header） | 一次性请求-响应 |
| WS 线 | `ws://host/ws/user?token=JWT` | JWT（**query**） | 长连接 |

两者唯一的交点是**前端拿到的 JWT token**。

**WS 连接是用户维度的，不是会话维度的**——所以切换会话**不需要重连**（`Chat.vue:852-856` 注释明确写了）。

### 4.2 完整时序

```
onMounted (Chat.vue:904)
 ├─ await initializeChat() → initSession() (Chat.vue:417-441)
 │    ├─ 有 ?ticketId → loadTicketFromRoute() (:402-415)
 │    ├─ 有 localStorage 缓存且工单未结束 → 复用 (:424-439)
 │    └─ 都没有 → openNewSession() (:388-399)
 │          ├─ POST /customer/user/sessions → UserTicketController:92-109
 │          ├─ sessionId.value / ticketId.value 赋值
 │          └─ cacheSession() → localStorage
 │
 └─ connectWs() (Chat.vue:443-458)
       ├─ 注册 10 个帧类型监听器
       └─ chatSocket.connect(auth.token)
             └─ ws.ts:28  url = ws://host/ws/user?token=<JWT>
                   └─ new WebSocket(url) ──► WebSocketConfig:28-45
                                                  SimpleUrlHandlerMapping
                                                  /ws/user → UserChatWebSocketHandler
                                                             │
                                            handle() 握手四道鉴权 (:84-110)
                                              ① token 有效
                                              ② tenantId 非空
                                              ③ tenantId 合法
                                              ④ 凭据未撤销 + 租户访问允许
                                                             │
                                            handleAuthenticated() (:112-144)
                                              ⑤ epoch 前后双检（防轮换竞态）
                                              ⑥ registry.registerUser(userId)
                                                             │
            ◄══════════════ WS 握手完成 ══════════════════════╡
                                                             │
 onWsOpen (Chat.vue:483-488)                                  │
   └─ synchronizeConversation()                              │
         ├─ delivery.reconcile()   ← 核对在途消息受理状态      │
         ├─ loadHistory()          ← 补拉 HTTP 快照与 WS 之间的空隙
         └─ refreshTicket()                                    │
                                                             │
 发消息 → ws.ts:99-101                                        │
   {type:'chat', data:{sessionId, content, clientMsgId}} ────►│
                                                             ▼
                                          handleInbound (:181-203)
                                            switch(type) → dispatch.onUserMessage
```

### 4.3 三个关键设计点

**① 为什么握手要「前后双检」**

`UserChatWebSocketHandler:112-144`，`registry.registerUser` **前后各查一次** `accessEpoch`：

```java
TenantAccessDecision beforeRegistration = currentAccess(user);
if (!sameAllowedEpoch(beforeRegistration, handshakeAccessEpoch) || !isUserSessionActive(user)) {
    return session.close(CloseStatus.POLICY_VIOLATION);
}
Sinks.Many<String> sink = registry.registerUser(user.userId());
TenantAccessDecision afterRegistration = currentAccess(user);
if (!sameAllowedEpoch(afterRegistration, handshakeAccessEpoch)
 || !isUserSessionActive(user)
    || registry.isTenantRestricted(user.tenantId())) {
    registry.unregisterUser(user.userId(), sink);
    return session.close(CloseStatus.POLICY_VIOLATION);
}
```

只查一次，「查完通过 → 登记前租户被撤权 → 登记成功」这个窗口会漏。
**epoch 是单调递增的租户访问世代号**，前后比对能检出「检查期间世代变了」。

**② 为什么 token 放 query 而不是 header**

浏览器 `WebSocket` API **不支持自定义请求头**（`ws.ts:1`、`WsConstants.java:13-14` 都写明）。只能放 URL，所以 `/ws/user` 和 `/ws/agent` 必须用**同一个参数名** `token`——两处不一致，坐席端就连不上。

**③ 会话号不是凭证**

会话号格式 `u<userId>:conv-<UUID>`（`UserTicketController:102`），服务端每帧校验前缀归属：

```java
// ChatDispatchService.java:416-418
private boolean ownsSession(String userId, String sessionId) {
    return sessionId != null && sessionId.startsWith(SESSION_PREFIX + userId + SESSION_DELIMITER);
}
```

不匹配直接回 `CHAT-SESSION-DENIED` + `REJECTED`（`:159-164`）。**用户伪造别人的 sessionId 也过不了这关**——归属靠「WS 认证出的 userId + sessionId 前缀」比对，不靠 sessionId 本身。

### 4.4 出站：模型结果怎么回到浏览器

`ChatDispatchService.streamAi`（`:394-412`）把编排层事件翻译成 WS 帧：

| 事件 | 帧 | 前端处理 |
|---|---|---|
| `ChatTurnEvent.Delta` | `chat_chunk` | `Chat.vue:552` 逐字上屏 |
| `ChatTurnEvent.Completed` | `chat_done` | `Chat.vue:564` 定稿气泡 |
| 异常 | `error`（acceptance=ACCEPTED） | 已受理但 AI 未完成 |
| 受理回执 | `chat_accepted` | `useMessageDelivery.ts:451` → `delivery.accept` |

**增量帧也带 sessionId**（`WsFrame.java:113-120`），前端 `Chat.vue:553` 比对「帧里 sessionId ≠ 当前查看的 sessionId」就丢弃——**同一条 WS 上可能同时有多个会话在流**。

---

## 5. 治理层：27 个中间件的七层契约

**数字订正**：实际是 **27 个**（`MiddlewareOrders` 有 27 个顺序常量）。
`applyTo` 显式 new 3 个 + `@Component` Bean 23 个 + 非 Bean 的 `KnowledgeInjectionMiddleware` 1 个。
`MiddlewareOrders` 的 javadoc 和契约测试里写的「24 个」是历史快照，**已过期**。

七层顺序（`MiddlewareOrders.java`，值越小越先执行）：

| 层 | order 值 | 代表中间件 |
|---|---|---|
| 1 生命周期 | 200 | `AGENT_LIFECYCLE` |
| 2 可观测 | 190 | `OBSERVABILITY` |
| 3 租户上下文 | 180 | `TENANT_CONTEXT` |
| 4 调用计时 | 170 | `AGENT_CALL_TIMING` |
| 5 终态采集 | 165 | `CHAT_TERMINAL_CAPTURE` |
| 6 延迟 | 160 | `LATENCY` |
| 7 回放捕获 | 155 | `MODEL_REPLAY_CAPTURE` |
| 8 审计 | 150 | `AUDIT` |
| 9 注入防护 | 140/137/135 | `PROMPT_INJECTION_GUARD` / `KNOWLEDGE_CITATION` / `INDIRECT_INJECTION_GUARD` |
| 10 敏感词/脱敏 | 130/125 | `SENSITIVE_WORD` / `MASKING` |
| 11 授权 | 120/115/112/110 | `SUBJECT_TOOL_AUTHORIZATION` / `HUMAN_APPROVAL` / `JEV_REFUND_RISK` / `TOOL_GUARD` |
| 12 任务捕获 | 107 | `TASK_PLAN_CAPTURE` |
| 13 循环守卫 | 105/100 | `LOOP_GUARD` / `SELF_CORRECTION` |
| 14 情绪/阶段 | 90/80/75 | `JEV_ESCALATION` / `DIALOG_STAGE` / `JEV_TOOL_SCOPE` |
| 15 RAG 注入 | 70 | `KNOWLEDGE_INJECTION` |
| 16 模型收尾 | 65/60 | `MODEL_COMPLETION` / `DYNAMIC_OPTIONS` |
| 17 预算裁剪 | 50 | `CONTEXT_BUDGET` |

**顺序是契约，不是巧合**——全部集中在 `MiddlewareOrders` 声明，`MiddlewareOrderContractTest` 断言不留默认值、不重复取值、11 条关键相对次序。

---

## 6. 三个工程约定（最值得带走的部分）

### ① 唯一装配入口 + 扫描源码的结构断言测试

**治「能力只接在一条路上」**——这个坑在本仓库**已复发 6 次**：
语义缓存只接非流式、CSAT 挂错钩子、出站过滤只接流式、盲区埋点只覆盖工具路径、附件 OCR 绕开隔离、`/consult` 整条链路没有中间件。

**根因都是「装配」散落在多个入口。**
根治办法：唯一入口（`AgentGovernanceAssembler.applyTo`）+ **扫源码**的结构断言测试（`AgentAssemblyAlignmentTest` 按调用点计数 `ReActAgent.builder()` vs `applyTo`）。

> 运行时断言查不出「没人测的那条路径」。这是关键。

### ② 事实记在「所有源必经的那一个入口」

**判据是：这个事实最终一定会经过哪一步。**

| 事实 | 记录点 |
|---|---|
| 本轮是否转过人工 | `HandoffService#create`（不是 5 个调用方各自上报） |
| 租户隔离 | MyBatis `TenantLineInnerInterceptor` |
| token 计量 | `AgentCallTimingMiddleware` |

### ③ 幂等 = 确定性 ID + 数据库唯一键裁决

`clientMsgId` → 确定性 `message_id` → 唯一 `message_id` 唯一键裁决。
**分布式锁只是性能优化，唯一键才是正确性保证。**

由此才能派生受理三态：
`ACCEPTED`（确认受理）/ `REJECTED`（明确拒绝）/ `UNKNOWN`（不确定，必须回查）。

---

## 7. 一条补充原则：降级而非阻断

**增强型能力失败不拖垮主链路，但失败内容绝不进缓存。**

| 场景 | 降级方式 |
|---|---|
| Skill 失败 | 跳过该技能 |
| 缓存故障 | 视为未命中 |
| RAG 异常 | 照常对话 |
| 模型超时/失败 | 兜底文案 + `markDegraded()`（**不进缓存**） |
| 配额 DEGRADE | 走备用模型（`contextWrite(ModelRoutingContext::preferFallback)`） |
| 配额 BLOCK | 直接拒绝（fail-closed，因为继续调主模型会违反预算边界） |

这条原则和 §6① 是互补的：**能力要多，但每条都要有明确的失败语义。**

---

## 8. 如果只记 10 件事

1. **接入层只管受理**，编排层只管跑一轮，治理层只管加工，收尾只管封存。
2. **四种标识**串起四层：`clientMsgId` / `(userId,sessionId)` / `RuntimeContext` / Reactor Context。
3. **同步、SSE、WS 三条出口复用同一个流式内核**（`ChatTurnService.stream`），字段必然一致。
4. **`Model.stream(...)` 那一行才是「调模型」**，它在框架 jar 里，不在你的仓库。
5. **模型链是 5 层装饰器**，你的仓库只负责组装，真正 HTTP 在厂商 SDK。
6. **一次提问可能调 N 次模型**，token 必须按 `Model.stream` 边界算。
7. **中间件能替换 `input.model()`**——这是模型边界的扩展点，项目里 4 个中间件在用。
8. **WS 是用户维度不是会话维度**，切会话不重连；**token 只能在 query**（浏览器 WS API 不支持自定义 header）。
9. **会话号不是凭证**，归属靠 `u<userId>:` 前缀比对 WS 认证出的 userId。
10. **能力只接在一条路上，是这个仓库反复踩的坑**——根治靠唯一入口 + 结构断言测试。

---

## 9. 两处标注为「不确定」的地方

诚实标注，避免后续误用：

1. **框架 `summarizing()` 的触发条件**：反编译确认了调用顺序（`reasoning` → `reasoningStream` → `modelCallStream`），但 `summarizing()`（`CallExecution:2412`）究竟在什么条件下触发（是否仅在 `maxIters` 用尽后）**没有完整确认**。
2. **厂商 SDK 内部是否还有额外重试**：`agentscope-extensions-model-dashscope` 内部（jar 内）未反编译，**不确定**它是否自带重试。若自带，可能与项目的 `ResilientChatModel` **叠加**——排查「重试次数异常」类问题时要注意这一层。

---

## 10. 相关文档

| 文档 | 用途 |
|---|---|
| `docs/learning/agent-message-lifecycle.md` | 逐站精读版（12 站，全链路 `文件:行号`）——**本文的前置读物** |
| `docs/新人必读.md` | 项目入口导览 |
| `docs/interview/01-项目全景与架构设计.md` | 同一批设计决策的面试向复述 |
| `docs/多租户架构设计.md` | 租户模型权威定义 |
| `docs/详细技术文档.md` / `docs/生产接口使用手册.md` | 时序图与接口契约 |
