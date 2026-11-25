import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { issueToken } from '@/api/auth'

/**
 * 站 4：登录态。
 *
 * <p><b>会话号为什么由前端生成</b>：后端 {@code SessionIds#resolve} 的归属校验是
 * <b>前缀校验</b> —— 会话号必须以 {@code {userId}-} 开头。
 * 前端知道自己的 userId，所以能造出一个<b>合法</b>的会话号；而且必须由前端生成，
 * 否则 SSE 那条路拿不到会话号（{@code /api/chat/stream} 只返回正文增量，不返回会话号），
 * 每条消息都会变成一个新会话。</p>
 *
 * <p>⚠️ <b>这不是绕过校验</b>：前缀校验的语义本来就是「你只能在自己名下建会话」。
 * 它的局限（知道 userId 就能伪造任意后缀）在后端注释里已经写明了 ——
 * 生产上真正的归属依据是数据库里那条会话记录。</p>
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref('')
  const userId = ref('')
  const sessionId = ref('')
  const expiresInMillis = ref(0)
  const ready = ref(false)
  const error = ref('')

  const hasToken = computed(() => token.value.length > 0)

  /** 领一个演示令牌并生成会话号。 */
  async function bootstrap(requestedUserId = 'u1001'): Promise<void> {
    error.value = ''
    try {
      const response = await issueToken(requestedUserId)
      token.value = response.token
      userId.value = response.userId
      expiresInMillis.value = response.expiresInMillis
      sessionId.value = `${response.userId}-${Math.random().toString(36).slice(2, 8)}`
      ready.value = true
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      ready.value = false
    }
  }

  return { token, userId, sessionId, expiresInMillis, ready, error, hasToken, bootstrap }
})
