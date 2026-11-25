// @vitest-environment node
/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { AcceptanceStatus, WsFrameType } from './api'

/**
 * 站 4：**前后端契约一致性测试**。
 *
 * <p>后端 {@code WsFrame} 的注释里写着一句很重的话：</p>
 * <blockquote>
 * 这些 type 是<b>前后端契约</b>，拼错一个字母前端就静默收不到消息。
 * </blockquote>
 *
 * <p>而前端常量在 TypeScript 里、后端常量在 Java 里 —— <b>两边没有任何编译期关联</b>，
 * 拼错了两边都绿，跑起来才发现收不到消息。</p>
 *
 * <p><b>所以这条测试直接读后端源码</b>：把 Java 里的 {@code TYPE_*} / {@code KEY_*} /
 * {@code ACCEPTANCE_*} 常量抠出来，跟前端常量逐个比对。
 * 后端改一个字，这条测试就红 —— 这正是「静默失败」该有的样子。</p>
 */

/** 后端 {@code WsFrame.java} 的位置：从 {@code mvp-web/src/types/} 往上三级到仓库根。 */
const BACKEND_WS_FRAME = new URL(
  '../../../mvp-app/src/main/java/com/example/mvp/app/ws/WsFrame.java',
  import.meta.url,
)

const source = readFileSync(BACKEND_WS_FRAME, 'utf-8')

/** 抠出 {@code public static final String NAME = "value"} 形式的常量。 */
function constantsOf(prefix: string): Map<string, string> {
  const map = new Map<string, string>()
  const pattern = new RegExp(`public static final String ${prefix}(\\w+)\\s*=\\s*"([^"]+)"`, 'g')
  for (const match of source.matchAll(pattern)) {
    map.set(match[1], match[2])
  }
  return map
}

describe('前后端帧契约一致', () => {
  const backendTypes = constantsOf('TYPE_')

  it('后端确实抠出了 7 个帧类型常量（防止正则失效导致空集假绿）', () => {
    expect(backendTypes.size).toBe(7)
  })

  it('每一个后端帧类型在前端都有同名同值的常量', () => {
    const frontend: Record<string, string> = { ...WsFrameType }
    for (const [name, value] of backendTypes) {
      expect(frontend[name], `后端 TYPE_${name} 在前端找不到对应常量`).toBe(value)
    }
  })

  it('前端没有多余的帧类型常量', () => {
    const frontend: Record<string, string> = { ...WsFrameType }
    for (const name of Object.keys(frontend)) {
      expect(backendTypes.has(name), `前端多了一个后端没有的常量 ${name}`).toBe(true)
    }
  })

  it('受理状态常量一致', () => {
    const backend = constantsOf('ACCEPTANCE_')
    expect(backend.get('ACCEPTED')).toBe(AcceptanceStatus.ACCEPTED)
    expect(backend.get('DUPLICATE')).toBe(AcceptanceStatus.DUPLICATE)
    expect(backend.get('REJECTED')).toBe(AcceptanceStatus.REJECTED)
  })

  it('帧 data 里的字段名与后端 KEY_* 一致', () => {
    const backend = constantsOf('KEY_')
    // TS 接口里的字段名 → 后端常量后缀
    const mapping: Record<string, string> = {
      clientMsgId: 'CLIENT_MSG_ID',
      acceptance: 'ACCEPTANCE',
      sessionId: 'SESSION_ID',
      status: 'STATUS',
      content: 'CONTENT',
      code: 'CODE',
      message: 'MESSAGE',
    }
    for (const [field, suffix] of Object.entries(mapping)) {
      expect(backend.get(suffix), `后端 KEY_${suffix} 不存在`).toBe(field)
    }
  })
})
