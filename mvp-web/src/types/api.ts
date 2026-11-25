/**
 * 站 4：**前后端契约的类型落点**。
 *
 * <p><b>这份文件就是「协议」本身</b>。后端 {@code WsFrame} 用字符串常量而不是枚举，
 * 注释里写着一句很重的话：</p>
 * <blockquote>
 * 这些 type 是<b>前后端契约</b>，拼错一个字母前端就静默收不到消息。
 * </blockquote>
 *
 * <p>所以这里的常量必须与后端<b>逐字对齐</b>，不能「反正类型检查过了」：
 * 后端是 Java 字符串常量，前端是 TS 字面量，两边没有任何编译期关联 ——
 * 拼错了两边都绿，跑起来才发现收不到消息。</p>
 *
 * <p><b>对应关系</b>（后端路径均相对 customer-work-starter）：</p>
 * <table border="1">
 *   <caption>契约对照</caption>
 *   <tr><th>前端</th><th>后端</th></tr>
 *   <tr><td>{@link WsFrameType}</td><td>{@code core/ws/WsFrame.java} 的 6 个 TYPE_* 常量</td></tr>
 *   <tr><td>{@link TokenResponse}</td><td>{@code app/auth/AuthController#TokenResponse}</td></tr>
 *   <tr><td>{@link ChatReply}</td><td>{@code app/ChatController#ChatReply}</td></tr>
 *   <tr><td>{@link AssemblySnapshot}</td><td>{@code app/agent/AgentAssemblyController} + {@code MvpAgentFactory#describe}</td></tr>
 * </table>
 */

// ────────────────────────────── 鉴权 ──────────────────────────────

/** 令牌响应 —— 对应后端 {@code AuthController.TokenResponse}。 */
export interface TokenResponse {
  /** 令牌本体。WS 握手时只能放 query（浏览器 WS API 不支持自定义 header）。 */
  token: string
  /** 令牌绑定的用户 id。 */
  userId: string
  /** 剩余有效期（毫秒）。 */
  expiresInMillis: number
}

// ────────────────────────────── 同步聊天 ──────────────────────────────

/** 同步聊天响应 —— 对应后端 {@code ChatController.ChatReply}。 */
export interface ChatReply {
  sessionId: string
  reply: string
}

// ────────────────────────────── WebSocket 帧 ──────────────────────────────

/**
 * 帧类型。**必须与后端 {@code WsFrame} 的常量逐字一致。**
 *
 * <p>后端一共 6 种（demo 只保留对话主链路的，刻意不抄 customer_work 的工单类帧）：</p>
 * <ul>
 *   <li>{@code chat} —— 客户端 → 服务端：发一条对话请求</li>
 *   <li>{@code chat_accepted} —— 服务端 → 客户端：受理回执（带受理号 + 状态）</li>
 *   <li>{@code chat_chunk} —— 服务端 → 客户端：正文增量</li>
 *   <li>{@code chat_done} —— 服务端 → 客户端：流式结束（带全文）</li>
 *   <li>{@code error} —— 服务端 → 客户端：错误</li>
 *   <li>{@code ping} / {@code pong} —— 心跳</li>
 * </ul>
 */
export const WsFrameType = {
  CHAT: 'chat',
  CHAT_ACCEPTED: 'chat_accepted',
  CHAT_CHUNK: 'chat_chunk',
  CHAT_DONE: 'chat_done',
  ERROR: 'error',
  PING: 'ping',
  PONG: 'pong',
} as const

export type WsFrameTypeValue = (typeof WsFrameType)[keyof typeof WsFrameType]

/** 帧信封 —— 后端 {@code WsFrame} 是 {@code record WsFrame(String type, Object data)}。 */
export interface WsFrame<T = unknown> {
  type: WsFrameTypeValue | string
  data: T
}

/** 受理状态。 */
export const AcceptanceStatus = {
  /** 首次受理。 */
  ACCEPTED: 'ACCEPTED',
  /** 重复投递（幂等命中）—— 受理号与会话号都是首次的。 */
  DUPLICATE: 'DUPLICATE',
  /** 被拒绝（如会话归属不匹配）。 */
  REJECTED: 'REJECTED',
} as const

export type AcceptanceStatusValue = (typeof AcceptanceStatus)[keyof typeof AcceptanceStatus]

/** {@code chat_accepted} 帧的 data。 */
export interface ChatAcceptedData {
  /** 客户端消息 id —— <b>幂等键</b>，同 id 重复投递不会二次调模型。 */
  clientMsgId: string
  /** 受理号。 */
  acceptance: string
  /** 会话号（首次受理时确定）。 */
  sessionId: string
  /** 受理状态。 */
  status: AcceptanceStatusValue | string
}

/** {@code chat_chunk} 帧的 data。 */
export interface ChatChunkData {
  content: string
  sessionId: string
  clientMsgId: string
}

/** {@code chat_done} 帧的 data。 */
export interface ChatDoneData {
  sessionId: string
  clientMsgId: string
  /** 完整正文（服务端累加的）。 */
  content: string
}

/** {@code error} 帧的 data。 */
export interface WsErrorData {
  code: string
  message: string
  sessionId: string | null
  clientMsgId: string | null
}

/** 客户端 → 服务端的 {@code chat} 帧 data。 */
export interface ChatRequestData {
  clientMsgId: string
  sessionId?: string
  message: string
}

// ────────────────────────────── 装配快照 ──────────────────────────────

/**
 * 生效链上的一项。
 *
 * <p>{@code orderNegotiated=false} 的那一项是站 3 最重要的发现：
 * 框架自动注入的 {@code GracefulShutdownMiddleware} 带着默认 {@code order()=1}
 * 硬编码在链首，<b>不参与 order 协商</b> —— 挂载顺序与 order 都压不过它。</p>
 */
export interface ChainItem {
  /** 位置（1 = 最外层）。 */
  position: number
  /** 中间件类名。 */
  name: string
  /** order 值。 */
  order: number
  /** 来源说明。 */
  source: string
  /** 是否参与了 order 协商。 */
  orderNegotiated: boolean
}

/** 生效的执行参数（对应后端 {@code ModelExecutionConfig}）。 */
export interface ModelExecutionConfigView {
  timeoutSeconds: number | null
  maxAttempts: number | null
  initialBackoffMillis: number | null
  backoffMultiplier: number | null
}

/**
 * 装配快照 —— 对应后端 {@code GET /api/agent/assembly} 的返回。
 *
 * <p><b>这是整个前端唯一能拿到「Agent 内部结构」的接口</b>：
 * 框架不提供任何「把装配结果导出来」的能力（边界清单站 2 的判定：全自建）。
 * 后端把生效链、权限、状态存储、工具清单一并摊开，前端据此渲染右侧面板。</p>
 */
export interface AssemblySnapshot {
  agentName: string
  model: string | null
  maxIters: number
  maxRetries: number | null
  conflictPolicy: string
  stateStore: string | null
  permissionMode: string | null
  permissionDenyRules: string[]
  /** 扁平视图（站 2 的形状）：{@code "Name@order"}。 */
  middlewares: string[]
  /** 结构化视图（站 3 的形状）：生效链，从外到内。 */
  chain: ChainItem[]
  toolNames: string[]
  modelExecutionConfig: ModelExecutionConfigView | null
  userId: string
  sessionId: string
  systemPrompt: string
}

// ────────────────────────────── 前端内部模型 ──────────────────────────────

/** 消息角色。 */
export type MessageRole = 'user' | 'assistant'

/**
 * 一条消息的生命周期状态。
 *
 * <p>⚠️ <b>「幂等命中」没有单独的状态</b>，而是用 {@link ChatMessage.duplicateHit} 标记。
 * 原因：幂等命中是<b>叠加在一次正常往返之上</b>的事实 ——
 * 第一次发送时消息已经 {@code done} 了，第二次重发只是又收到一个
 * {@code DUPLICATE} 受理帧。如果拿状态位表达，就会把「已完成」这个事实冲掉。</p>
 */
export type MessageStatus = 'pending' | 'streaming' | 'done' | 'error'

/** 前端内部的一条消息。 */
export interface ChatMessage {
  /** 稳定 id（前端生成）。 */
  id: string
  /** 幂等键 —— 与后端的 {@code clientMsgId} 是同一个东西。 */
  clientMsgId: string
  role: MessageRole
  content: string
  status: MessageStatus
  /** 受理号（收到 chat_accepted 后回填）。 */
  acceptance?: string
  /** 受理状态。 */
  acceptanceStatus?: AcceptanceStatusValue | string
  /** 错误码（status = error 时）。 */
  errorCode?: string
  /** 创建时间戳。 */
  createdAt: number
  /**
   * 幂等命中：服务端判定为重复投递。
   *
   * <p>此时<b>没有</b>新的正文增量，也没有二次模型调用 —— 受理号与会话号都复用首次的。
   * 这是站 1 幂等机制在前端的可见形态。</p>
   */
  duplicateHit?: boolean
  /** 重发次数（复用同一幂等键发出的次数）。 */
  resendCount?: number
}

/** 接入通道。 */
export type ChannelKind = 'ws' | 'sse'

/** 连接状态。 */
export type ConnectionState = 'idle' | 'connecting' | 'open' | 'closed' | 'error'
