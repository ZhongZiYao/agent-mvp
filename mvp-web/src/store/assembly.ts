import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchAssembly } from '@/api/assembly'
import type { AssemblySnapshot } from '@/types/api'
import { useAuthStore } from './auth'

/**
 * 站 4：装配快照。
 *
 * <p>右侧面板的数据源。<b>这是整个前端唯一能看到「Agent 内部结构」的地方</b> ——
 * 框架不提供导出装配结果的能力（边界清单站 2：全自建），后端自己摊开，前端只负责画。</p>
 */
export const useAssemblyStore = defineStore('assembly', () => {
  const auth = useAuthStore()

  const snapshot = ref<AssemblySnapshot | null>(null)
  const loading = ref(false)
  const error = ref('')

  /** 拉一次装配快照。 */
  async function load(): Promise<void> {
    if (!auth.token) {
      return
    }
    loading.value = true
    error.value = ''
    try {
      snapshot.value = await fetchAssembly(auth.token, auth.userId, auth.sessionId)
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
    } finally {
      loading.value = false
    }
  }

  return { snapshot, loading, error, load }
})
