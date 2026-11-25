import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// 站 4：前端接入层。
//
// ⚠️ 这里的 proxy 不是「图方便」，而是【必须】：
// 后端 mvp-app 没有配置任何 CORS 头（application.yml 里没有 spring.webflux.cors），
// 浏览器跨源直连会被同源策略拦掉。所以开发期一律走 Vite proxy。
//
// 两条通道分别代理：
//   /api → HTTP 端点（token / 同步聊天 / SSE 流式聊天 / 装配快照）
//   /ws  → WebSocket 端点（ws: true 才会升级协议）
export default defineConfig(({ mode }) => {
  // loadEnv(mode, cwd, '') —— 第三个参数传空串表示「连 VITE_ 前缀之外的也读」，
  // 这样 .env.local 里的任意键都能拿到。
  const env = loadEnv(mode, process.cwd(), '')
  const apiTarget = env.VITE_API_TARGET || 'http://localhost:8088'

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      // 5174 = customer-admin-web，5175 = customer-work-app，这里用 5176 避免打架
      port: 5176,
      host: '0.0.0.0',
      proxy: {
        '/api': {
          target: apiTarget,
          changeOrigin: true,
        },
        // WebSocket 独立通道：ws: true 才会做协议升级
        '/ws': {
          target: apiTarget.replace(/^http/, 'ws'),
          ws: true,
        },
      },
    },
  }
})
