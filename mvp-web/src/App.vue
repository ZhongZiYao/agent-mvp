<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AssemblyPanel from '@/components/AssemblyPanel.vue'
import ChatView from '@/views/ChatView.vue'
import { useAssemblyStore } from '@/store/assembly'
import { useAuthStore } from '@/store/auth'

const auth = useAuthStore()
const assembly = useAssemblyStore()

/** 移动端：装配面板是抽屉，默认收起。 */
const panelOpen = ref(false)

onMounted(async () => {
  await auth.bootstrap('u1001')
  await assembly.load()
})
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark" aria-hidden="true"></span>
        <div>
          <h1>agent-mvp · 客服 H5</h1>
          <p class="brand-sub">
            站 4 · 前端接入层 —— 框架给 0 个东西，协议、鉴权、幂等、流式全部自建
          </p>
        </div>
      </div>

      <div class="topbar-right">
        <span v-if="auth.userId" class="chip">
          用户 <span class="mono">{{ auth.userId }}</span>
        </span>
        <span v-if="auth.sessionId" class="chip">
          会话 <span class="mono">{{ auth.sessionId }}</span>
        </span>
        <button
          class="btn btn-ghost panel-toggle"
          type="button"
          @click="panelOpen = !panelOpen"
        >
          {{ panelOpen ? '收起装配面板' : '装配面板' }}
        </button>
      </div>
    </header>

    <div v-if="auth.error" class="banner banner-error">
      登录失败：{{ auth.error }}
    </div>

    <main class="body" :class="{ 'panel-open': panelOpen }">
      <section class="chat-col">
        <ChatView />
      </section>
      <aside class="panel-col" :class="{ open: panelOpen }">
        <AssemblyPanel />
      </aside>
    </main>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--gap);
  padding: 10px 16px;
  background: var(--c-surface);
  border-bottom: 1px solid var(--c-border);
  flex: 0 0 auto;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.brand-mark {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  background: var(--c-primary);
  flex: 0 0 auto;
}

.brand h1 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.brand-sub {
  margin: 0;
  font-size: 12px;
  color: var(--c-text-dim);
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
}

.chip {
  font-size: 12px;
  color: var(--c-text-dim);
  background: var(--c-surface-2);
  border: 1px solid var(--c-border);
  border-radius: 999px;
  padding: 3px 10px;
  white-space: nowrap;
}

.panel-toggle {
  display: none;
}

.banner {
  padding: 8px 16px;
  font-size: 13px;
  flex: 0 0 auto;
}

.banner-error {
  background: var(--c-danger-soft);
  color: var(--c-danger);
  border-bottom: 1px solid var(--c-border);
}

.body {
  flex: 1 1 auto;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) var(--panel-w);
  gap: 0;
}

.chat-col {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.panel-col {
  min-height: 0;
  border-left: 1px solid var(--c-border);
  background: var(--c-surface-2);
  overflow: hidden;
}

/* —— 移动端：单栏，装配面板变抽屉 —— */
@media (max-width: 899px) {
  .body {
    grid-template-columns: minmax(0, 1fr);
  }

  .panel-toggle {
    display: inline-flex;
  }

  .panel-col {
    position: fixed;
    inset: 52px 0 0 auto;
    width: min(420px, 100%);
    z-index: 20;
    background: var(--c-surface);
    box-shadow: -8px 0 24px rgb(0 0 0 / 8%);
    transform: translateX(100%);
    transition: transform 0.18s ease;
    border-left: 1px solid var(--c-border);
  }

  .panel-col.open {
    transform: translateX(0);
  }

  .brand-sub {
    display: none;
  }
}
</style>
