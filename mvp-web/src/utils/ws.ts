import type { ChatRequestData, ConnectionState, WsFrame } from '@/types/api'

/**
 * 站 4：**WebSocket 客户端**（手写，零依赖）。
 *
 * <h3>为什么走 WS 而不是 SSE</h3>
 * <p>两条通道后端都有，但 WS 多了一样东西：<b>受理回执</b>。
 * SSE 只有正文增量，而 WS 有 {@code chat_accepted} 帧 —— 带受理号与受理状态
 * （{@code ACCEPTED} / {@code DUPLICATE} / {@code REJECTED}）。
 * <b>幂等机制只能通过它被看见</b>：同一个 {@code clientMsgId} 发两次，
 * SSE 那条路看不出任何区别，WS 这条路会回一个 {@code DUPLICATE}。</p>
 *
 * <h3>三条平台约束（都不是框架给的）</h3>
 * <ol>
 *   <li><b>token 只能在 query 里</b>：浏览器 WS API 不支持自定义 header，
 *       所以握手地址是 {@code ws://host/ws/user?token=xxx}。这是平台约束，不是偷懒；</li>
 *   <li><b>浏览器 API 不允许自定义子协议以外的握手头</b>：同上，只能靠 query 传凭据；</li>
 *   <li><b>断线要自己重连</b>：浏览器不会替你重连，而 WS 断了服务端那边
 *       {@code doFinally} 会注销连接 —— 前端不重连就等于会话静默失效。</li>
 * </ol>
 *
 * <p><b>对照 customer_work</b>：那边是 {@code utils/ws.ts} + {@code composables/useMessageDelivery.ts}，
 * 结构同构。本文件是站 4 的「框架给了 0 个东西」的又一处证据 ——
 * core 的 20 个顶层包里没有任何网络协议相关的东西。</p>
 */

/** 客户端参数。 */
export interface WsClientOptions {
  /** 握手地址（token 已在 query 里）。 */
  url: string
  /** 帧回调 —— 所有帧都会走这里，类型判定交给上层。 */
  onFrame: (frame: WsFrame) => void
  /** 连接状态变化。 */
  onStateChange?: (state: ConnectionState) => void
  /** 心跳间隔（毫秒），默认 30s。设 0 关闭。 */
  pingIntervalMs?: number
  /** 是否自动重连，默认 true。 */
  autoReconnect?: boolean
  /** 重连退避基数（毫秒），默认 1000。 */
  reconnectBaseDelayMs?: number
  /** 最大重连次数，默认 5。 */
  maxReconnectAttempts?: number
}

/**
 * 站 4 的 WS 客户端。
 *
 * <p><b>为什么不用 socket.io / sockjs</b>：后端是 Spring 原生 {@code WebSocketHandler}
 * （没有 STOMP、没有 SockJS），协议就是裸 WebSocket + JSON 帧。
 * 套一个库反而要处理它自己的帧格式与心跳约定 —— 直接用浏览器原生 {@code WebSocket}
 * 是最少的代码。</p>
 */
export class ChatWsClient {
  private socket: WebSocket | null = null
  private readonly options: Required<Omit<WsClientOptions, 'onFrame' | 'onStateChange'>> &
    Pick<WsClientOptions, 'onFrame' | 'onStateChange'>

  private reconnectAttempts = 0
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private pingTimer: ReturnType<typeof setInterval> | null = null
  /** 主动关闭时置位 —— 用于区分「该重连」与「不要重连」。 */
  private closedByUser = false
  /** 等待「连接就绪」的回调队列。 */
  private openWaiters: Array<(ok: boolean) => void> = []

  constructor(options: WsClientOptions) {
    this.options = {
      pingIntervalMs: 30_000,
      autoReconnect: true,
      reconnectBaseDelayMs: 1_000,
      maxReconnectAttempts: 5,
      ...options,
    }
  }

  /** 当前是否已连接。 */
  get connected(): boolean {
    return this.socket !== null && this.socket.readyState === WebSocket.OPEN
  }

  /**
   * 等待连接就绪。
   *
   * <p><b>为什么需要它</b>：浏览器 {@code new WebSocket(url)} 是<b>异步</b>的 ——
   * 构造函数返回时 socket 还处于 {@code CONNECTING}，要等 {@code onopen} 才真的通。
   * 用户在页面刚打开、连接还在握手的瞬间就点「发送」，如果直接检查
   * {@code readyState === OPEN} 就会误判成「没连上」。
   * 所以这里把「等连接」显式化成可等待的 Promise。</p>
   *
   * @param timeoutMs 最长等待时间；超时 resolve(false)
   */
  whenOpen(timeoutMs = 5_000): Promise<boolean> {
    if (this.connected) {
      return Promise.resolve(true)
    }
    return new Promise<boolean>((resolve) => {
      const onResult = (ok: boolean) => {
        clearTimeout(timer)
        resolve(ok)
      }
      const timer = setTimeout(() => {
        this.openWaiters = this.openWaiters.filter((w) => w !== onResult)
        resolve(false)
      }, timeoutMs)
      this.openWaiters.push(onResult)
    })
  }

  /** 建立连接。重复调用是无害的（已连接时直接返回）。 */
  connect(): void {
    if (this.socket && (this.socket.readyState === WebSocket.OPEN
      || this.socket.readyState === WebSocket.CONNECTING)) {
      return
    }
    this.closedByUser = false
    this.setState('connecting')

    const socket = new WebSocket(this.options.url)
    this.socket = socket

    socket.onopen = () => {
      this.reconnectAttempts = 0
      this.setState('open')
      this.startPing()
      this.notifyOpen(true)
    }

    socket.onmessage = (event: MessageEvent) => {
      this.dispatch(event.data)
    }

    socket.onerror = () => {
      this.setState('error')
      this.notifyOpen(false)
    }

    socket.onclose = () => {
      this.stopPing()
      this.socket = null
      this.setState('closed')
      this.notifyOpen(false)
      if (!this.closedByUser && this.options.autoReconnect) {
        this.scheduleReconnect()
      }
    }
  }

  /**
   * 发送一条对话请求。
   *
   * @returns 是否成功发出（连接未就绪时返回 false，调用方应先等 onStateChange）
   */
  sendChat(data: ChatRequestData): boolean {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      return false
    }
    this.socket.send(JSON.stringify({ type: 'chat', data }))
    return true
  }

  /** 主动关闭（不再重连）。 */
  close(): void {
    this.closedByUser = true
    this.clearReconnectTimer()
    this.stopPing()
    if (this.socket) {
      this.socket.close()
      this.socket = null
    }
    this.setState('closed')
  }

  /** 解析并分发一帧。解析失败只记日志，不炸连接。 */
  private dispatch(raw: unknown): void {
    if (typeof raw !== 'string') {
      return
    }
    let frame: WsFrame
    try {
      frame = JSON.parse(raw) as WsFrame
    } catch {
      // 后端保证发的是合法 JSON；解析失败说明有人手拼了字面量。
      // 这里不抛 —— 一帧坏掉不该让整条连接死掉。
      console.warn('[ws] 收到无法解析的帧', raw)
      return
    }
    if (!frame || typeof frame.type !== 'string') {
      console.warn('[ws] 收到结构不对的帧', frame)
      return
    }
    this.options.onFrame(frame)
  }

  private startPing(): void {
    this.stopPing()
    if (this.options.pingIntervalMs <= 0) {
      return
    }
    this.pingTimer = setInterval(() => {
      if (this.socket && this.socket.readyState === WebSocket.OPEN) {
        this.socket.send(JSON.stringify({ type: 'ping', data: null }))
      }
    }, this.options.pingIntervalMs)
  }

  private stopPing(): void {
    if (this.pingTimer) {
      clearInterval(this.pingTimer)
      this.pingTimer = null
    }
  }

  private scheduleReconnect(): void {
    if (this.reconnectAttempts >= this.options.maxReconnectAttempts) {
      this.setState('error')
      return
    }
    const delay = this.options.reconnectBaseDelayMs * 2 ** this.reconnectAttempts
    this.reconnectAttempts += 1
    this.clearReconnectTimer()
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, delay)
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
  }

  private setState(state: ConnectionState): void {
    this.options.onStateChange?.(state)
  }

  /**
   * 通知所有「等连接」的人。
   *
   * <p>连接成功 → resolve(true)；出错或断开 → resolve(false)。
   * 断开时是 false 而不是「继续等」：连接已经断了，等下去也没有意义，
   * 让调用方自己决定是重试还是报错。</p>
   */
  private notifyOpen(ok: boolean): void {
    const waiters = this.openWaiters
    this.openWaiters = []
    for (const waiter of waiters) {
      waiter(ok)
    }
  }
}
