// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { extractData, parseSseStream } from './sse'

/**
 * 站 4：SSE 解析器测试。
 *
 * <p><b>这个解析器是手写的，理由见 {@code utils/sse.ts} 的开头</b>
 * （原生 EventSource 只支持 GET，而后端流式端点是 POST）。
 * 手写的东西必须配测试 —— 它出错的形态是「悄悄少几个字」，肉眼很难发现。</p>
 */

/** 造一个由若干字符串块组成的流。 */
function streamOf(...chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder()
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk))
      }
      controller.close()
    },
  })
}

/** 造一个按字节切开的流（用来模拟 UTF-8 多字节字符被切在 chunk 边界）。 */
function byteStreamOf(bytes: number[]): ReadableStream<Uint8Array> {
  return new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new Uint8Array(bytes))
      controller.close()
    },
  })
}

async function collect(stream: ReadableStream<Uint8Array>): Promise<string[]> {
  const out: string[] = []
  for await (const item of parseSseStream(stream)) {
    out.push(item)
  }
  return out
}

describe('SSE 解析器', () => {
  it('单帧：解出 data 内容', async () => {
    expect(await collect(streamOf('data:你好\n\n'))).toEqual(['你好'])
  })

  it('多帧：按 \\n\\n 切分，逐帧产出', async () => {
    const out = await collect(streamOf('data:第一\n\ndata:第二\n\ndata:第三\n\n'))
    expect(out).toEqual(['第一', '第二', '第三'])
  })

  it('一帧被拆到多个 chunk 里：跨 chunk 边界也要拼回来', async () => {
    const out = await collect(streamOf('data:前半', '段后半\n\n'))
    expect(out).toEqual(['前半段后半'])
  })

  it('冒号后有一个空格时，空格被吃掉', async () => {
    expect(await collect(streamOf('data: 内容\n\n'))).toEqual(['内容'])
  })

  it('⚠️ Spring 原样写出内容里的换行 → 裸行当作续行接回去', async () => {
    // Spring 的 SSE writer 不转义 data 里的换行，于是「第一行\n第二行」
    // 会变成 data:第一行 / 第二行（裸行）/ 空行。
    // 严格按 SSE 规范解析，第二行会被丢掉 —— 答复里的换行就没了。
    const out = await collect(streamOf('data:第一行\n第二行\n\n'))
    expect(out).toEqual(['第一行\n第二行'])
  })

  it('多个 data: 行按 SSE 规范用换行连接', async () => {
    expect(await collect(streamOf('data:甲\ndata:乙\n\n'))).toEqual(['甲\n乙'])
  })

  it('注释行（以 : 开头）被忽略', async () => {
    expect(await collect(streamOf(': keep-alive\n\ndata:正文\n\n'))).toEqual(['正文'])
  })

  it('没有 data 行的帧不产出任何东西', async () => {
    expect(await collect(streamOf('\n\n'))).toEqual([])
  })

  it('⚠️ UTF-8 多字节字符被切在 chunk 边界上也不能解出乱码', async () => {
    // 「你」是 3 字节，故意从第 2 字节处切开
    const bytes = Array.from(new TextEncoder().encode('data:你\n\n'))
    const cut = bytes.indexOf(0xe5) + 1
    const out = await collect(byteStreamOf([...bytes.slice(0, cut), ...bytes.slice(cut)]))
    expect(out).toEqual(['你'])
  })

  it('流结束时残留的半帧也会被解出来', async () => {
    expect(await collect(streamOf('data:末尾没有空行'))).toEqual(['末尾没有空行'])
  })
})

describe('extractData', () => {
  it('没有 data 行时返回 null', () => {
    expect(extractData(': comment')).toBeNull()
    expect(extractData('')).toBeNull()
  })

  it('data 行的内容原样返回', () => {
    expect(extractData('data:abc')).toBe('abc')
  })
})
