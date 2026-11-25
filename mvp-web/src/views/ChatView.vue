<script setup lang="ts">
import { onMounted, watch } from 'vue'
import ChannelBar from '@/components/ChannelBar.vue'
import ChatInput from '@/components/ChatInput.vue'
import MessageList from '@/components/MessageList.vue'
import { useAuthStore } from '@/store/auth'
import { useChatStore } from '@/store/chat'
import type { ChatMessage } from '@/types/api'

/**
 * 站 4：聊天主视图。
 */
const chat = useChatStore()
const auth = useAuthStore()

// 令牌就绪后立刻把 WS 连上 —— 浏览器 WS API 不支持自定义 header，
// token 只能拼在 query 里，所以连接必须等令牌先拿到。
watch(
  () => auth.token,
  (token) => {
    if (token) {
      chat.connect()
    }
  },
  { immediate: true },
)

onMounted(() => {
  if (auth.token) {
    chat.connect()
  }
})

function onSend(text: string): void {
  void chat.send(text)
}

function onResend(message: ChatMessage): void {
  void chat.resend(message)
}
</script>

<template>
  <div class="view">
    <ChannelBar />

    <div v-if="chat.hint" class="hint">
      {{ chat.hint }}
    </div>

    <MessageList :messages="chat.messages" @resend="onResend" />

    <ChatInput :disabled="!auth.ready" @send="onSend" />
  </div>
</template>

<style scoped>
.view {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  background: var(--c-bg);
}

.hint {
  flex: 0 0 auto;
  padding: 7px 16px;
  font-size: 12.5px;
  background: var(--c-warn-soft);
  color: var(--c-warn);
  border-bottom: 1px solid #f0e2c0;
}
</style>
