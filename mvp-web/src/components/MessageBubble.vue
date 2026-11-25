<script setup lang="ts">
import { computed } from 'vue'
import type { ChatMessage } from '@/types/api'

/**
 * 站 4：消息气泡。
 *
 * <p>气泡上挂了三种「事实标记」，它们都是站 1 / 站 3 的可见形态：</p>
 * <ul>
 *   <li><b>受理号</b> —— 服务端给这次请求的受理凭证（只有 WS 通道有）；</li>
 *   <li><b>幂等命中</b> —— 复用同一个 clientMsgId 重发时服务端判定为重复投递，
 *       <b>没有二次调用模型</b>；</li>
 *   <li><b>错误码</b> —— 被治理件拦截时也会走这里。</li>
 * </ul>
 */
const props = defineProps<{
  message: ChatMessage
}>()

const emit = defineEmits<{
  (e: 'resend', message: ChatMessage): void
}>()

const isUser = computed(() => props.message.role === 'user')
const streaming = computed(() => props.message.status === 'streaming')

/** 状态 → 文案。 */
const statusText = computed(() => {
  switch (props.message.status) {
    case 'pending':
      return '已发出，等待受理'
    case 'streaming':
      return '生成中'
    case 'done':
      return '已完成'
    case 'error':
      return '出错'
    default:
      return ''
  }
})
</script>

<template>
  <div class="row" :class="isUser ? 'row-user' : 'row-assistant'">
    <div class="bubble" :class="[isUser ? 'bubble-user' : 'bubble-assistant', { streaming }]">
      <p class="text">{{ message.content }}<span v-if="streaming" class="caret"></span></p>

      <div v-if="!isUser && (message.acceptance || message.errorCode)" class="meta">
        <span v-if="message.acceptance" class="meta-item">
          受理号 <span class="mono">{{ message.acceptance }}</span>
        </span>
        <span v-if="message.errorCode" class="meta-item meta-error">
          错误码 <span class="mono">{{ message.errorCode }}</span>
        </span>
      </div>

      <div v-if="message.duplicateHit" class="badge badge-duplicate">
        幂等命中 · 未重复调模型
      </div>

      <div v-if="isUser && message.resendCount" class="badge badge-resend">
        已重发 ×{{ message.resendCount }}（复用同一幂等键）
      </div>
    </div>

    <div v-if="isUser" class="actions">
      <button class="btn btn-mini" type="button" title="复用同一个 clientMsgId 重发，演示服务端的幂等判定" @click="emit('resend', message)">
        重发
      </button>
    </div>

    <div v-else class="status mono">{{ statusText }}</div>
  </div>
</template>

<style scoped>
.row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 14px;
}

.row-user {
  flex-direction: row-reverse;
}

.bubble {
  max-width: min(78%, 560px);
  padding: 9px 12px;
  border-radius: var(--radius-lg);
  position: relative;
}

.bubble-user {
  background: var(--c-primary);
  color: #fff;
  border-bottom-right-radius: 4px;
}

.bubble-assistant {
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-bottom-left-radius: 4px;
}

.text {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

/* 流式光标 */
.caret {
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 2px;
  vertical-align: -2px;
  background: var(--c-primary);
  animation: blink 1s steps(2, start) infinite;
}

@keyframes blink {
  to {
    visibility: hidden;
  }
}

.meta {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 11px;
  color: var(--c-text-faint);
}

.meta-error {
  color: var(--c-danger);
}

.badge {
  margin-top: 6px;
  display: inline-block;
  font-size: 11px;
  line-height: 1.5;
  padding: 1px 7px;
  border-radius: 999px;
}

.badge-duplicate {
  background: var(--c-warn-soft);
  color: var(--c-warn);
  border: 1px solid #f0e2c0;
}

.badge-resend {
  background: var(--c-primary-soft);
  color: var(--c-primary);
  border: 1px solid #dcdcfb;
}

.actions {
  flex: 0 0 auto;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.row:hover .actions {
  opacity: 1;
}

.status {
  flex: 0 0 auto;
  font-size: 11px;
  color: var(--c-text-faint);
  padding-top: 3px;
}

@media (max-width: 640px) {
  .bubble {
    max-width: 86%;
  }
}
</style>
