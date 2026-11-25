import type { ChatReply } from '@/types/api'
import { authHeaders, describeFailure } from './http'
import { readSseResponse } from '@/utils/sse'

/**
 * 站 4：聊天接入（同步 / SSE / WS 三条路）。
 *
 * <p><b>三条路的关系</b>（这是站 4 值得讲的一点）：</p>
 * <table border="1">
 *   <caption>接入通道对照</caption>
 *   <tr><th></th><th>同步</th><th>SSE</th><th>WS</th></tr>
 *   <tr><td>请求方向</td><td>请求-响应</td><td>单向流</td><td>双向</td></tr>
 *   <tr><td>能带请求体</td><td>能</td><td>能（POST）</td><td>能</td></tr>
 *   <tr><td>拿得到受理号</td><td>否</td><td>否</td><td><b>能</b></td></tr>
 *   <tr><td>看得出幂等命中</td><td>否</td><td>否</td><td><b>能</b></td></tr>
 *   <tr><td>正文里的换行</td><td>安全</td><td><b>会丢</b>（见 utils/sse.ts）</td><td>安全</td></tr>
 * </table>
 */

/** 同步问一句。 */
export async function chatSync(
  token: string,
  sessionId: string | undefined,
  message: string,
): Promise<ChatReply> {
  const response = await fetch('/api/chat/sync', {
    method: 'POST',
    headers: authHeaders(token),
    body: JSON.stringify({ sessionId, message }),
  })
  if (!response.ok) {
    throw new Error(`同步聊天失败：${await describeFailure(response)}`)
  }
  return (await response.json()) as ChatReply
}

/**
 * 流式问一句（SSE），逐条产出正文增量。
 *
 * <p><b>必须用 POST + fetch</b>：浏览器原生 {@code EventSource} 只支持 GET，
 * 而后端 {@code ChatController#chatStream} 是 {@code @PostMapping}。
 * 详见 {@code utils/sse.ts} 的说明。</p>
 */
export async function* streamChatSse(
  token: string,
  sessionId: string | undefined,
  message: string,
  signal?: AbortSignal,
): AsyncGenerator<string> {
  const response = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: authHeaders(token),
    body: JSON.stringify({ sessionId, message }),
    signal,
  })
  if (!response.ok) {
    throw new Error(`流式聊天失败：${await describeFailure(response)}`)
  }
  yield* readSseResponse(response)
}

/**
 * 拼出 WebSocket 握手地址。
 *
 * <p><b>token 只能在 query 里</b>：浏览器 WS API 不支持自定义 header。
 * 后端 {@code UserChatWebSocketHandler#tokenOf} 就是从 query 里取的，
 * 这是平台约束，不是后端偷懒。</p>
 */
export function buildWsUrl(token: string): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const host = window.location.host
  return `${protocol}//${host}/ws/user?token=${encodeURIComponent(token)}`
}
