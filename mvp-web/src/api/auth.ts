import type { TokenResponse } from '@/types/api'

/**
 * 站 4：鉴权。
 *
 * <p><b>⚠️ 演示端点，生产不存在</b>：后端 {@code AuthController} 的注释写得很清楚 ——
 * 生产环境由服务端根据账号密码校验后签发令牌，用户 id <b>绝不能让客户端自己报</b>。
 * 这里保留它只是为了让 demo 一键拿到可用令牌。</p>
 *
 * <p><b>对照 customer_work</b>：那边是 {@code UserAuthController} 的真实登录端点（JWT）。</p>
 */

/**
 * 领一个演示令牌。
 *
 * @param userId 演示用户 id；不传则由服务端随机生成
 */
export async function issueToken(userId?: string): Promise<TokenResponse> {
  const response = await fetch('/api/auth/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ userId }),
  })
  if (!response.ok) {
    throw new Error(`发号失败：HTTP ${response.status}`)
  }
  return (await response.json()) as TokenResponse
}
