// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/store/auth'
import { useChatStore } from '@/store/chat'
import { AcceptanceStatus, WsFrameType } from '@/types/api'

/**
 * 站 4：**幂等状态机测试**。
 *
 * <p>这是本前端唯一「必须正确」的一段逻辑 —— 错一条，界面上就会出现两个一模一样的
 * 助手气泡，或者首次的答复被第二次的空内容冲掉。<b>而且它错得很安静</b>：
 * 界面不会报错，只会觉得「这聊天界面有点怪」。</p>
 *
 * <p>三条纪律（对应 {@code store/chat.ts} 的注释）：</p>
 * <ol>
 *   <li>重发复用同一个 {@code clientMsgId}；</li>
 *   <li>收到 DUPLICATE 时不新建气泡；</li>
 *   <li>DUPLICATE 不覆盖已有正文。</li>
 * </ol>
 *
 * <p><b>为什么用假 WS 客户端</b>：真连后端会让这组测试依赖网络与模型，
 * 变成「集成测试」而不是「状态机测试」。这里把 {@code ChatWsClient} 换成
 * 一个可控的替身：它记录发出的帧，并允许测试直接把服务端帧推进来 ——
 * 于是每一条分支都能被精确驱动。</p>
 */

const { FakeWs, sseState } = vi.hoisted(() => {
  /** 假 WS 客户端：记录发出的帧 + 允许测试推帧进来。 */
  class FakeWs {
    /** 全部实例（每次 new 一个）。 */
    static instances: FakeWs[] = []

    connected = false
    /** 发出去的帧（已 JSON 化前的 data 部分）。 */
    sent: Array<Record<string, unknown>> = []

    onFrame: (frame: unknown) => void
    onStateChange: ((state: string) => void) | undefined

    constructor(options: {
      onFrame: (frame: unknown) => void
      onStateChange?: (state: string) => void
    }) {
      this.onFrame = options.onFrame
      this.onStateChange = options.onStateChange
      FakeWs.instances.push(this)
    }

    connect(): void {
      this.connected = true
      this.onStateChange?.('open')
    }

    close(): void {
      this.connected = false
      this.onStateChange?.('closed')
    }

    sendChat(data: Record<string, unknown>): boolean {
      this.sent.push(data)
      return true
    }

    /** 假实现：真连接里是「等 onopen」，这里立刻就绪。 */
    whenOpen(): Promise<boolean> {
      return Promise.resolve(this.connected)
    }

    /** 测试用：模拟服务端推一帧。 */
    emit(type: string, data: unknown): void {
      this.onFrame({ type, data })
    }
  }

  /** SSE 通道的可控增量。 */
  const sseState = { chunks: [] as string[] }

  return { FakeWs, sseState }
})

// 替换 WS 客户端：让状态机可以被精确驱动。
vi.mock('@/utils/ws', () => ({ ChatWsClient: FakeWs }))

// SSE 通道：把流式增量换成测试指定的片段。
vi.mock('@/api/chat', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/chat')>()
  return {
    ...actual,
    streamChatSse: async function* streamChatSse() {
      for (const chunk of sseState.chunks) {
        yield chunk
      }
    },
  }
})

/** 装配一个已登录的 pinia 环境。 */
function setup() {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.token = 'test-token'
  auth.userId = 'u1001'
  auth.sessionId = 'u1001-test01'
  const chat = useChatStore()
  chat.connect()
  return { auth, chat }
}

/** 最后一个假 WS 实例。 */
function lastWs(): InstanceType<typeof FakeWs> {
  return FakeWs.instances[FakeWs.instances.length - 1]
}

beforeEach(() => {
  FakeWs.instances.length = 0
  sseState.chunks.length = 0
})

describe('聊天状态机 · 消息创建', () => {
  it('send 建一对气泡：用户在前、助手占位在后', async () => {
    const { chat } = setup()

    await chat.send('我的订单到哪了')

    expect(chat.messages).toHaveLength(2)
    expect(chat.messages[0].role).toBe('user')
    expect(chat.messages[0].content).toBe('我的订单到哪了')
    expect(chat.messages[1].role).toBe('assistant')
    // 占位气泡一开始没有正文，但已经在等回复
    expect(chat.messages[1].content).toBe('')
    expect(chat.messages[1].status).toBe('pending')
  })

  it('两条气泡共享同一个幂等键', async () => {
    const { chat } = setup()

    await chat.send('你好')

    expect(chat.messages[0].clientMsgId).toBe(chat.messages[1].clientMsgId)
    expect(chat.messages[0].clientMsgId).toBeTruthy()
  })

  it('空白消息不发出去，也不建气泡', async () => {
    const { chat } = setup()

    await chat.send('   \n  ')

    expect(chat.messages).toHaveLength(0)
    expect(lastWs().sent).toHaveLength(0)
  })

  it('发出的 WS 帧带幂等键与正文，且与气泡上的幂等键一致', async () => {
    const { chat } = setup()

    await chat.send('退款到账了吗')

    const ws = lastWs()
    expect(ws.sent).toHaveLength(1)
    expect(ws.sent[0].clientMsgId).toBe(chat.messages[0].clientMsgId)
    expect(ws.sent[0].message).toBe('退款到账了吗')
    expect(ws.sent[0].sessionId).toBe('u1001-test01')
  })
})

describe('聊天状态机 · 帧处理', () => {
  it('受理帧回填受理号，状态从 pending 转 streaming', async () => {
    const { chat } = setup()
    await chat.send('hi')
    const clientMsgId = chat.messages[0].clientMsgId

    lastWs().emit(WsFrameType.CHAT_ACCEPTED, {
      clientMsgId,
      acceptance: 'ACC-1',
      sessionId: 'u1001-test01',
      status: AcceptanceStatus.ACCEPTED,
    })

    expect(chat.messages[1].acceptance).toBe('ACC-1')
    expect(chat.messages[1].status).toBe('streaming')
  })

  it('正文增量按 clientMsgId 定位到对应气泡，两条并发消息不串', async () => {
    const { chat } = setup()
    await chat.send('第一问')
    await chat.send('第二问')
    const [firstId, secondId] = [chat.messages[0].clientMsgId, chat.messages[2].clientMsgId]

    lastWs().emit(WsFrameType.CHAT_CHUNK, {
      content: '甲',
      sessionId: 'u1001-test01',
      clientMsgId: firstId,
    })
    lastWs().emit(WsFrameType.CHAT_CHUNK, {
      content: '乙',
      sessionId: 'u1001-test01',
      clientMsgId: secondId,
    })

    expect(chat.messages[1].content).toBe('甲')
    expect(chat.messages[3].content).toBe('乙')
  })

  it('收尾帧用服务端全文覆盖累加结果', async () => {
    const { chat } = setup()
    await chat.send('hi')
    const clientMsgId = chat.messages[0].clientMsgId

    lastWs().emit(WsFrameType.CHAT_CHUNK, {
      content: '你好',
      sessionId: 'u1001-test01',
      clientMsgId,
    })
    lastWs().emit(WsFrameType.CHAT_DONE, {
      sessionId: 'u1001-test01',
      clientMsgId,
      content: '你好，世界',
    })

    expect(chat.messages[1].content).toBe('你好，世界')
    expect(chat.messages[1].status).toBe('done')
  })

  it('错误帧把消息置为 error 并留下错误码', async () => {
    const { chat } = setup()
    await chat.send('hi')
    const clientMsgId = chat.messages[0].clientMsgId

    lastWs().emit(WsFrameType.ERROR, {
      code: 'REPLY_FAILED',
      message: '模型调用失败',
      sessionId: 'u1001-test01',
      clientMsgId,
    })

    expect(chat.messages[1].status).toBe('error')
    expect(chat.messages[1].errorCode).toBe('REPLY_FAILED')
    expect(chat.messages[1].content).toBe('模型调用失败')
  })

  it('错误帧没带幂等键时，落到顶部提示条而不是某条消息', async () => {
    const { chat } = setup()

    lastWs().emit(WsFrameType.ERROR, {
      code: 'BAD_FRAME',
      message: '帧不是合法 JSON',
      sessionId: null,
      clientMsgId: null,
    })

    expect(chat.hint).toContain('BAD_FRAME')
    expect(chat.messages).toHaveLength(0)
  })

  it('未知帧类型被安静忽略，不炸状态机', async () => {
    const { chat } = setup()
    await chat.send('hi')

    expect(() => lastWs().emit('ticket_new', { anything: 1 })).not.toThrow()
    expect(chat.messages[1].content).toBe('')
  })
})

describe('聊天状态机 · 幂等命中', () => {
  it('重发复用同一个 clientMsgId —— 这是幂等成立的前提', async () => {
    const { chat } = setup()
    await chat.send('退款到账了吗')
    const clientMsgId = chat.messages[0].clientMsgId

    await chat.resend(chat.messages[0])

    const ws = lastWs()
    expect(ws.sent).toHaveLength(2)
    // 第二次发出去的幂等键必须与第一次完全一致
    expect(ws.sent[1].clientMsgId).toBe(clientMsgId)
    expect(ws.sent[1].clientMsgId).toBe(ws.sent[0].clientMsgId)
  })

  it('DUPLICATE 不新建气泡', async () => {
    const { chat } = setup()
    await chat.send('退款到账了吗')
    await chat.resend(chat.messages[0])

    lastWs().emit(WsFrameType.CHAT_ACCEPTED, {
      clientMsgId: chat.messages[0].clientMsgId,
      acceptance: 'ACC-1',
      sessionId: 'u1001-test01',
      status: AcceptanceStatus.DUPLICATE,
    })

    // 仍然是两条气泡：用户 + 助手。没有多出第二个助手气泡
    expect(chat.messages).toHaveLength(2)
    expect(chat.messages.filter((m) => m.role === 'assistant')).toHaveLength(1)
  })

  it('DUPLICATE 不覆盖已有正文（首次的答复还在）', async () => {
    const { chat } = setup()
    await chat.send('退款到账了吗')
    const clientMsgId = chat.messages[0].clientMsgId

    // 先走完一次正常往返
    lastWs().emit(WsFrameType.CHAT_CHUNK, {
      content: '已为您查询，退款预计明天到账。',
      sessionId: 'u1001-test01',
      clientMsgId,
    })
    lastWs().emit(WsFrameType.CHAT_DONE, {
      sessionId: 'u1001-test01',
      clientMsgId,
      content: '已为您查询，退款预计明天到账。',
    })

    // 再重发 → 命中幂等
    await chat.resend(chat.messages[0])
    lastWs().emit(WsFrameType.CHAT_ACCEPTED, {
      clientMsgId,
      acceptance: 'ACC-1',
      sessionId: 'u1001-test01',
      status: AcceptanceStatus.DUPLICATE,
    })

    expect(chat.messages[1].content).toBe('已为您查询，退款预计明天到账。')
    expect(chat.messages[1].status).toBe('done')
  })

  it('DUPLICATE 标记幂等命中，并且计数累加', async () => {
    const { chat } = setup()
    await chat.send('退款到账了吗')

    expect(chat.duplicateHits).toBe(0)

    await chat.resend(chat.messages[0])
    lastWs().emit(WsFrameType.CHAT_ACCEPTED, {
      clientMsgId: chat.messages[0].clientMsgId,
      acceptance: 'ACC-1',
      sessionId: 'u1001-test01',
      status: AcceptanceStatus.DUPLICATE,
    })

    expect(chat.messages[1].duplicateHit).toBe(true)
    expect(chat.duplicateHits).toBe(1)
    expect(chat.hint).toContain('重复投递已合并')
  })

  it('重发计数挂在用户气泡上（徽章就渲染在那里）', async () => {
    const { chat } = setup()
    await chat.send('hi')

    await chat.resend(chat.messages[0])

    expect(chat.messages[0].resendCount).toBe(1)
    // 助手气泡不该有重发计数
    expect(chat.messages[1].resendCount).toBeUndefined()
  })

  it('幂等命中不改变「是否在飞」—— 首次那一轮早就结束了', async () => {
    const { chat } = setup()
    await chat.send('hi')
    const clientMsgId = chat.messages[0].clientMsgId

    lastWs().emit(WsFrameType.CHAT_DONE, {
      sessionId: 'u1001-test01',
      clientMsgId,
      content: '答复',
    })
    expect(chat.sending).toBe(false)

    await chat.resend(chat.messages[0])
    lastWs().emit(WsFrameType.CHAT_ACCEPTED, {
      clientMsgId,
      acceptance: 'ACC-1',
      sessionId: 'u1001-test01',
      status: AcceptanceStatus.DUPLICATE,
    })

    expect(chat.sending).toBe(false)
  })
})

describe('聊天状态机 · sending 是派生值', () => {
  it('有一条在飞就是 true', async () => {
    const { chat } = setup()

    await chat.send('第一问')
    expect(chat.sending).toBe(true)
  })

  it('两条并发在飞时仍然是 true —— 这正是布尔量写法会漏掉的情况', async () => {
    const { chat } = setup()

    await chat.send('第一问')
    await chat.send('第二问')
    expect(chat.sending).toBe(true)

    // 第一条结束，第二条还在飞
    lastWs().emit(WsFrameType.CHAT_DONE, {
      sessionId: 'u1001-test01',
      clientMsgId: chat.messages[0].clientMsgId,
      content: '答复一',
    })
    expect(chat.sending).toBe(true)

    lastWs().emit(WsFrameType.CHAT_DONE, {
      sessionId: 'u1001-test01',
      clientMsgId: chat.messages[2].clientMsgId,
      content: '答复二',
    })
    expect(chat.sending).toBe(false)
  })
})

describe('聊天状态机 · SSE 通道', () => {
  it('SSE 正文按增量累加，收尾后状态为 done', async () => {
    const { chat } = setup()
    sseState.chunks.push('第一段', '第二段')
    await chat.switchChannel('sse')

    await chat.send('hi')

    expect(chat.messages[1].content).toBe('第一段第二段')
    expect(chat.messages[1].status).toBe('done')
  })

  it('SSE 通道重发没有受理回执，明确告诉用户「看不出来」', async () => {
    const { chat } = setup()
    sseState.chunks.push('答复')
    await chat.switchChannel('sse')
    await chat.send('hi')

    await chat.resend(chat.messages[0])

    expect(chat.hint).toContain('SSE 通道没有受理回执')
  })
})
