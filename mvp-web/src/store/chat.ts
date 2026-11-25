import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { buildWsUrl, streamChatSse } from '@/api/chat'
import { newClientMsgId } from '@/api/http'
import {
  AcceptanceStatus,
  WsFrameType,
  type ChatAcceptedData,
  type ChatChunkData,
  type ChatDoneData,
  type ChatMessage,
  type ChannelKind,
  type ConnectionState,
  type WsErrorData,
  type WsFrame,
} from '@/types/api'
import { ChatWsClient } from '@/utils/ws'
import { useAuthStore } from './auth'

/**
 * 站 4：聊天状态机。
 *
 * <h3>本文件是「站 1 幂等机制」在前端的落点</h3>
 *
 * <p><b>幂等键 = {@code clientMsgId}</b>。后端 {@code ChatDispatchService#accept} 按
 * {@code userId + clientMsgId} 去重：命中时回一个 {@code DUPLICATE} 受理帧
 * （受理号与会话号都是首次的），<b>并且不再触发模型调用</b>。</p>
 *
 * <p>前端必须做对三件事，缺一件都会出错：</p>
 * <ol>
 *   <li><b>重发要复用同一个 clientMsgId</b> —— 否则那就是一条新消息，幂等无从谈起；</li>
 *   <li><b>收到 DUPLICATE 时不能新建气泡</b> —— 气泡是按 clientMsgId 找的，
 *       新建就会出现两个一模一样的助手气泡；</li>
 *   <li><b>DUPLICATE 不能覆盖已有正文</b> —— 首次的答复还在，不能被第二次的空内容冲掉。</li>
 * </ol>
 */
export const useChatStore = defineStore('chat', () => {
  const auth = useAuthStore()

  const messages = ref<ChatMessage[]>([])
  const channel = ref<ChannelKind>('ws')
  const connection = ref<ConnectionState>('idle')
  /** 顶部提示条：放「幂等命中」这类需要被看见、但不属于某一条消息的事实。 */
  const hint = ref('')

  /** WS 客户端不进响应式状态 —— 它是可变对象，塞进 ref 只会带来无意义的深度代理。 */
  let ws: ChatWsClient | null = null
  let abortController: AbortController | null = null

  /**
   * 是否还有请求在飞。
   *
   * <p><b>刻意做成派生值而不是手工维护的布尔</b>：WebSocket 允许<b>多条请求同时在飞</b>
   * （各带各的 clientMsgId），用布尔量记「是否在发送」会漏掉并发 ——
   * 第一条还在流式的时候，第二条就发不出去了。
   * 「在飞」这个事实本来就写在每条助手气泡的 status 上，直接从那里算最准。</p>
   */
  const sending = computed(() =>
    messages.value.some(
      (m) => m.role === 'assistant' && (m.status === 'pending' || m.status === 'streaming'),
    ),
  )

  const lastAssistant = computed<ChatMessage | null>(() => {
    for (let i = messages.value.length - 1; i >= 0; i -= 1) {
      if (messages.value[i].role === 'assistant') {
        return messages.value[i]
      }
    }
    return null
  })

  const duplicateHits = computed(() => messages.value.filter((m) => m.duplicateHit).length)

  // ────────────────────────── 连接 ──────────────────────────

  /** 建立 WS 连接（WS 通道才需要）。 */
  function connect(): void {
    if (channel.value !== 'ws' || !auth.token) {
      return
    }
    if (!ws) {
      ws = new ChatWsClient({
        url: buildWsUrl(auth.token),
        onFrame: handleFrame,
        onStateChange: (state) => {
          connection.value = state
        },
      })
    }
    ws.connect()
  }

  /** 断开 WS。 */
  function disconnect(): void {
    ws?.close()
    ws = null
    connection.value = 'closed'
  }

  /** 切换接入通道。切到 SSE 时断开 WS（省得留着一条没人用的连接）。 */
  async function switchChannel(next: ChannelKind): Promise<void> {
    if (next === channel.value) {
      return
    }
    channel.value = next
    if (next === 'ws') {
      connect()
    } else {
      disconnect()
      connection.value = 'idle'
    }
  }

  // ────────────────────────── 发送 ──────────────────────────

  /**
   * 发一条新消息。
   *
   * <p>发送时<b>先建好两条气泡</b>（用户 + 助手占位），再发帧 ——
   * 这样增量到达时直接往占位气泡上追加，界面不会闪。</p>
   */
  async function send(text: string): Promise<void> {
    const content = text.trim()
    if (!content) {
      return
    }
    const clientMsgId = newClientMsgId()
    appendUserMessage(content, clientMsgId)
    const assistant = appendAssistantPlaceholder(clientMsgId)

    if (channel.value === 'ws') {
      if (!ws || !ws.connected) {
        connect()
      }
      // ⚠️ 必须等连接就绪：new WebSocket() 是异步的，构造函数返回时还没握手完。
      //    直接看 readyState 会把「正在连接」误判成「没连上」，首条消息必失败。
      const ready = ws ? await ws.whenOpen(5_000) : false
      if (!ready || !ws) {
        hint.value = 'WS 还没连上，请稍候再试（或切到 SSE 通道）'
        assistant.status = 'error'
        assistant.errorCode = 'NOT_CONNECTED'
        return
      }
      ws.sendChat({ clientMsgId, sessionId: auth.sessionId, message: content })
    } else {
      await sendViaSse(content, clientMsgId)
    }
  }

  /**
   * <b>复用同一个幂等键重发</b> —— 这是把幂等机制<b>演出来</b>的唯一办法。
   *
   * <p>真实用户不会手动复用 clientMsgId，但网络重发、服务端超时重试、
   * 用户手滑连点两下都会产生「同一个逻辑消息被投递多次」的情况。
   * 这里把它做成一个显式按钮，好让受理号与 DUPLICATE 回执看得见。</p>
   */
  async function resend(message: ChatMessage): Promise<void> {
    if (message.role !== 'user') {
      return
    }
    const target = findAssistant(message.clientMsgId)
    if (!target) {
      return
    }
    // 计数挂在【用户气泡】上 —— 徽章也渲染在用户气泡上（MessageBubble 里
    // `v-if="isUser && message.resendCount"`）。挂到助手气泡上就永远看不见。
    message.resendCount = (message.resendCount ?? 0) + 1
    hint.value = ''

    if (channel.value === 'ws') {
      if (!ws || !ws.connected) {
        connect()
      }
      const ready = ws ? await ws.whenOpen(5_000) : false
      if (!ready || !ws) {
        hint.value = 'WS 未连接，无法演示幂等'
        return
      }
      // ⚠️ 关键：复用的是【原来的 clientMsgId】，不是新的
      ws.sendChat({
        clientMsgId: message.clientMsgId,
        sessionId: auth.sessionId,
        message: message.content,
      })
    } else {
      hint.value = 'SSE 通道没有受理回执，幂等命中在这条路上看不出来（见 api/chat.ts 的对照表）'
    }
  }

  /** 清空会话（只清界面，不动后端状态）。 */
  function clear(): void {
    messages.value = []
    hint.value = ''
  }

  // ────────────────────────── SSE 通道 ──────────────────────────

  async function sendViaSse(content: string, clientMsgId: string): Promise<void> {
    abortController = new AbortController()
    const target = findAssistant(clientMsgId)
    if (target) {
      target.status = 'streaming'
    }
    try {
      for await (const delta of streamChatSse(
        auth.token,
        auth.sessionId,
        content,
        abortController.signal,
      )) {
        applyChunk({ content: delta, sessionId: auth.sessionId, clientMsgId })
      }
      applyDone({ sessionId: auth.sessionId, clientMsgId, content: '' })
    } catch (e) {
      applyError({
        code: 'SSE_FAILED',
        message: e instanceof Error ? e.message : String(e),
        sessionId: auth.sessionId,
        clientMsgId,
      })
    } finally {
      abortController = null
    }
  }

  // ────────────────────────── 帧处理 ──────────────────────────

  function handleFrame(frame: WsFrame): void {
    switch (frame.type) {
      case WsFrameType.CHAT_ACCEPTED:
        applyAccepted(frame.data as ChatAcceptedData)
        break
      case WsFrameType.CHAT_CHUNK:
        applyChunk(frame.data as ChatChunkData)
        break
      case WsFrameType.CHAT_DONE:
        applyDone(frame.data as ChatDoneData)
        break
      case WsFrameType.ERROR:
        applyError(frame.data as WsErrorData)
        break
      case WsFrameType.PONG:
        break
      default:
        break
    }
  }

  /**
   * 受理回执。
   *
   * <p><b>DUPLICATE 的三条处理纪律</b>（少一条界面就错）：</p>
   * <ol>
   *   <li>按 clientMsgId 找<b>已有</b>的助手气泡，<b>不新建</b>；</li>
   *   <li><b>不动正文</b> —— 首次的答复还在；</li>
   *   <li>标记 {@code duplicateHit}，让界面显示「已合并、未重复调模型」。</li>
   * </ol>
   */
  function applyAccepted(data: ChatAcceptedData): void {
    const target = findAssistant(data.clientMsgId)
    if (!target) {
      return
    }
    target.acceptance = data.acceptance
    target.acceptanceStatus = data.status

    if (data.status === AcceptanceStatus.DUPLICATE) {
      target.duplicateHit = true
      hint.value = `重复投递已合并（受理号 ${data.acceptance}）—— 服务端没有二次调用模型`
    } else if (data.status === AcceptanceStatus.ACCEPTED) {
      target.status = 'streaming'
    }
  }

  /** 正文增量。 */
  function applyChunk(data: ChatChunkData): void {
    const target = findAssistant(data.clientMsgId)
    if (!target) {
      return
    }
    target.content += data.content
    target.status = 'streaming'
  }

  /** 流式结束。 */
  function applyDone(data: ChatDoneData): void {
    const target = findAssistant(data.clientMsgId)
    if (target) {
      // 服务端累加的全文为准（它才是真正下发给用户的那份）
      if (data.content) {
        target.content = data.content
      }
      target.status = 'done'
    }
  }

  /** 错误帧。 */
  function applyError(data: WsErrorData): void {
    const target = data.clientMsgId ? findAssistant(data.clientMsgId) : null
    if (target) {
      target.status = 'error'
      target.errorCode = data.code
      target.content = data.message
    } else {
      hint.value = `错误：${data.code} ${data.message}`
    }
  }

  // ────────────────────────── 内部工具 ──────────────────────────

  function findAssistant(clientMsgId: string): ChatMessage | undefined {
    return messages.value.find((m) => m.role === 'assistant' && m.clientMsgId === clientMsgId)
  }

  function appendUserMessage(content: string, clientMsgId: string): ChatMessage {
    const message: ChatMessage = {
      id: `u-${clientMsgId}`,
      clientMsgId,
      role: 'user',
      content,
      status: 'done',
      createdAt: Date.now(),
    }
    messages.value.push(message)
    return message
  }

  function appendAssistantPlaceholder(clientMsgId: string): ChatMessage {
    const message: ChatMessage = {
      id: `a-${clientMsgId}`,
      clientMsgId,
      role: 'assistant',
      content: '',
      status: 'pending',
      createdAt: Date.now(),
    }
    messages.value.push(message)
    return message
  }

  return {
    messages,
    channel,
    connection,
    sending,
    hint,
    lastAssistant,
    duplicateHits,
    connect,
    disconnect,
    switchChannel,
    send,
    resend,
    clear,
    // 导出给测试用：帧处理是纯函数式的一段，测它比测整条链路精确得多
    handleFrame,
    applyAccepted,
    applyChunk,
    applyDone,
    applyError,
  }
})
