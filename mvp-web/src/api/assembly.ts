import type { AssemblySnapshot } from '@/types/api'
import { authHeaders, describeFailure } from './http'

/**
 * 站 4：装配快照。
 *
 * <p><b>这是整个前端唯一能拿到「Agent 内部结构」的接口</b>。</p>
 *
 * <p>框架 core <b>不提供</b>任何「把装配结果导出来」的能力（边界清单站 2 判定：全自建）。
 * 后端把生效链、权限模式、状态存储、工具清单、执行参数一并摊开，
 * 前端据此渲染右侧面板 —— <b>让「框架给了什么、哪些是自建的」从文档变成能点的界面</b>。</p>
 *
 * <p>注意快照里的 {@code chain} 段（站 3 新增）：
 * 它是<b>框架排序之后的生效链</b>，不是注册顺序。索引 0 是最外层。</p>
 */
export async function fetchAssembly(
  token: string,
  userId: string,
  sessionId: string,
): Promise<AssemblySnapshot> {
  const query = new URLSearchParams({ userId, sessionId })
  const response = await fetch(`/api/agent/assembly?${query.toString()}`, {
    method: 'GET',
    headers: authHeaders(token, false),
  })
  if (!response.ok) {
    throw new Error(`拉取装配快照失败：${await describeFailure(response)}`)
  }
  return (await response.json()) as AssemblySnapshot
}
