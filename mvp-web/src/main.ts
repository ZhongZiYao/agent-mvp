import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import './style.css'

/**
 * 站 4：前端入口。
 *
 * <p><b>刻意保持极简</b>：没有路由（只有一个视图）、没有 UI 库、没有 HTTP 客户端库。
 * 这个 demo 要证明的是「前端接入层自己写要写哪些东西」，
 * 框架/库给得越多，这一站就越看不出来。</p>
 */
createApp(App).use(createPinia()).mount('#app')
