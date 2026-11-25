/**
 * 站 4：HTTP 小工具。
 *
 * <p><b>为什么不用 axios</b>：本前端一共只有三个普通 HTTP 调用
 * （发号 / 同步聊天 / 装配快照），加一个 HTTP 客户端库的收益低于它的依赖成本；
 * 而流式聊天<b>本来就必须</b>用 {@code fetch}（{@code EventSource} 只支持 GET，
 * 而后端的流式端点是 POST —— 见 {@code utils/sse.ts}）。
 * 统一用 fetch 反而少一套心智模型。</p>
 */

/** 带令牌的请求头。 */
export function authHeaders(token: string, json = true): Record<string, string> {
  const headers: Record<string, string> = {
    Authorization: `Bearer ${token}`,
  }
  if (json) {
    // ⚠️ 必须显式写 charset=utf-8：中文 body 不带 charset 时，
    //    某些服务端会按 ISO-8859-1 解码，中文变成乱码。
    headers['Content-Type'] = 'application/json; charset=utf-8'
  }
  return headers
}

/** 统一的失败信息：把状态码与响应体一起带上，便于排错。 */
export async function describeFailure(response: Response): Promise<string> {
  let body = ''
  try {
    body = await response.text()
  } catch {
    body = '<响应体读取失败>'
  }
  return `HTTP ${response.status} ${response.statusText}${body ? ` — ${body}` : ''}`
}

/** 生成一个幂等键（clientMsgId）。 */
export function newClientMsgId(): string {
  const random = Math.random().toString(36).slice(2, 10)
  return `c-${Date.now().toString(36)}-${random}`
}
