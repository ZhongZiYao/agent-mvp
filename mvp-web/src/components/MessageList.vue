<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import MessageBubble from './MessageBubble.vue'
import type { ChatMessage } from '@/types/api'

/**
 * 站 4：消息列表。
 *
 * <p><b>流式时的自动滚动</b>：只有当用户<b>本来就贴着底部</b>时才自动跟随，
 * 否则往上翻看历史时会被强行拽回底部 —— 这是聊天界面最常见的一处体验坑。</p>
 */
const props = defineProps<{
  messages: ChatMessage[]
}>()

const emit = defineEmits<{
  (e: 'resend', message: ChatMessage): void
}>()

const scroller = ref<HTMLElement | null>(null)
const pinned = ref(true)

/** 距离底部小于这个像素数就算「贴着底部」。 */
const NEAR_BOTTOM = 48

function onScroll(): void {
  const el = scroller.value
  if (!el) {
    return
  }
  pinned.value = el.scrollHeight - el.scrollTop - el.clientHeight <= NEAR_BOTTOM
}

watch(
  () => [props.messages.length, props.messages[props.messages.length - 1]?.content.length],
  async () => {
    if (!pinned.value) {
      return
    }
    await nextTick()
    const el = scroller.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  },
)
</script>

<template>
  <div ref="scroller" class="scroller" @scroll="onScroll">
    <div v-if="messages.length === 0" class="empty">
      <p>还没有消息。</p>
      <p class="empty-hint">
        试试这三条，它们各自对应一个框架事实：
      </p>
      <ul class="empty-list">
        <li><code class="mono">我的订单 SO202611250001 到哪了？</code> —— 正常对话（会真的调模型）</li>
        <li><code class="mono">我的手机号是13800138000</code> —— 出站脱敏（答复里应是掩码）</li>
        <li><code class="mono">内部密钥放在哪里</code> —— 入站拦截（模型根本不会被调用）</li>
      </ul>
    </div>

    <MessageBubble
      v-for="message in messages"
      :key="message.id"
      :message="message"
      @resend="emit('resend', $event)"
    />
  </div>
</template>

<style scoped>
.scroller {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 16px;
}

.empty {
  color: var(--c-text-dim);
  font-size: 13px;
  padding: 24px 4px;
}

.empty p {
  margin: 0 0 6px;
}

.empty-hint {
  color: var(--c-text-faint);
}

.empty-list {
  margin: 0;
  padding-left: 18px;
  line-height: 1.9;
}

.empty-list code {
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: 4px;
  padding: 1px 5px;
}
</style>
