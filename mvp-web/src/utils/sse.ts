/**
 * 站 4：**手写的 SSE 解析器**。
 *
 * <h3>为什么不能用浏览器原生 `EventSource`</h3>
 * <p>原生 `EventSource` <b>只支持 GET</b>。而 demo 后端的流式端点是：</p>
 * <pre>
 * POST /api/chat/stream   →  produces: text/event-stream
 * </pre>
 * <p>用 POST 装 body 是 SSE 相对 WebSocket 的核心优势（可以带鉴权头、复杂请求体、
 * 不受连接数限制），代价就是<b>放弃原生 EventSource</b>。
 * 于是只剩一条路：{@code fetch} + {@code ReadableStream} + 自己按 SSE 规范切帧。</p>
 *
 * <h3>SSE 帧格式</h3>
 * <p>一个事件由若干行组成，行与行之间用 {@code \n} 分隔，事件之间用 <b>{@code \n\n}</b> 分隔：</p>
 * <pre>
 * data:第一段
 * data:第二段
 * &lt;空行&gt;
 * </pre>
 * <p>逐行规则：{@code data:} 开头的行取其后内容（去掉一个可选的前导空格）；
 * 其它以 {@code :} 开头的行是注释（心跳）；空行代表事件结束。</p>
 *
 * <h3>⚠️ Spring 写 SSE 的一个坑（前端必须兜住）</h3>
 * <p>Spring 的 {@code ServerSentEventHttpMessageWriter} 是把内容<b>原样</b>写进
 * {@code data:} 行的，<b>不会把内容里的换行转义成多行 data</b>。
 * 于是模型吐出的 {@code "第一行\n第二行"} 会变成：</p>
 * <pre>
 * data:第一行
 * 第二行          ← 裸行，不是合法的 data 行
 * &lt;空行&gt;
 * </pre>
 * <p>严格按 SSE 规范解析，第二行会被<b>丢掉</b> —— 答复里的换行就没了。
 * 本实现的处理：<b>把 data 行之后的裸行当作上一条的续行</b>（用 {@code \n} 连回去）。
 * 这是「按 SSE 规范 + 兼容 Spring 的原样写出」双重语义下的正确解法。</p>
 *
 * <p><b>对照 customer_work</b>：那边走 WebSocket，帧是 JSON 编码的，
 * 换行被 JSON 转义（{@code \n} 变成 {@code \\n}），<b>天然没有这个问题</b>。
 * 这也是本前端把 WS 作为默认通道的原因之一。</p>
 */

/**
 * 把 SSE 响应流解析成一个个 <b>data 负载</b>。
 *
 * @param body 响应体流（{@code response.body}）
 */
export async function* parseSseStream(body: ReadableStream<Uint8Array>): AsyncGenerator<string> {
  const reader = body.getReader()
  // 用 stream: true —— 一个 UTF-8 字符可能被拆在两个 chunk 之间
  // （中文一个字 3 字节，chunk 边界切在中间就会解出乱码）。
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }
      buffer += decoder.decode(value, { stream: true })

      // 按 \n\n 切事件。注意：切之前必须先 flush 掉解码器里可能残留的字节。
      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        const raw = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        const payload = extractData(raw)
        if (payload !== null) {
          yield payload
        }
        boundary = buffer.indexOf('\n\n')
      }
    }

    // 流正常结束但最后一块没有以 \n\n 收尾时，把残留部分也解出来
    const tail = extractData(buffer)
    if (tail !== null) {
      yield tail
    }
  } finally {
    // 提前 return（调用方 break）时也要释放底层连接
    reader.releaseLock()
  }
}

/**
 * 解析一个 SSE 响应，逐条产出 data 负载。
 *
 * @param response 已完成 fetch 的响应
 */
export async function* readSseResponse(response: Response): AsyncGenerator<string> {
  if (!response.body) {
    return
  }
  yield* parseSseStream(response.body)
}

/**
 * 从一段事件原文里取出 data 负载；没有 data 行则返回 {@code null}。
 *
 * <p>导出出来是为了能单测 —— 这个函数是整个解析器里唯一有分支的地方。</p>
 */
export function extractData(raw: string): string | null {
  const lines = raw.split(/\r?\n/)
  let out: string | null = null

  for (const line of lines) {
    if (line.startsWith('data:')) {
      // 规范写法是 "data: 内容"，冒号后可以有一个空格
      const content = line.slice(5).replace(/^ /, '')
      out = out === null ? content : out + '\n' + content
    } else if (line.length > 0 && out !== null) {
      // 裸行 = Spring 原样写出内容里的换行 → 当作上一条的续行接回去。
      // 若严格按 SSE 规范，这行会被丢掉，答复里的换行就没了。
      out = out + '\n' + line
    }
    // 其余行（注释行、以 ':' 开头的 keep-alive、空行）忽略
  }

  return out
}
