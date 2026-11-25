<script setup lang="ts">
import { computed } from 'vue'
import { useChatStore } from '@/store/chat'
import type { ChannelKind, ConnectionState } from '@/types/api'

/**
 * 站 4：通道切换条。
 *
 * <p><b>两个通道的差别，在这一行里就能看到</b>：</p>
 * <ul>
 *   <li><b>WS</b> —— 有受理回执 ⇒ 能看到幂等命中、能拿到受理号；</li>
 *   <li><b>SSE</b> —— 没有受理回执 ⇒ 重发时看不出服务端判没判重。</li>
 * </ul>
 * <p>这条对照就是「为什么客服场景最后选了 WebSocket」的直接答案。</p>
 */
const chat = useChatStore()

const stateText = computed(() => {
  const map: Record<ConnectionState, string> = {
    idle: '未连接',
    connecting: '连接中…',
    open: '已连接',
    closed: '已断开',
    error: '连接出错',
  }
  return map[chat.connection]
})

function pick(kind: ChannelKind): void {
  void chat.switchChannel(kind)
}
</script>

<template>
  <div class="bar">
    <div class="seg" role="group" aria-label="接入通道">
      <button
        class="seg-btn"
        :class="{ active: chat.channel === 'ws' }"
        type="button"
        @click="pick('ws')"
      >
        WebSocket
      </button>
      <button
        class="seg-btn"
        :class="{ active: chat.channel === 'sse' }"
        type="button"
        @click="pick('sse')"
      >
        SSE
      </button>
    </div>

    <span class="state mono" :class="chat.connection">
      <span class="dot" aria-hidden="true"></span>
      {{ stateText }}
    </span>

    <span v-if="chat.channel === 'sse'" class="note">
      SSE 无受理回执 · 幂等命中看不出来
    </span>

    <span class="spacer"></span>

    <span v-if="chat.duplicateHits > 0" class="note note-warn">
      幂等命中 {{ chat.duplicateHits }} 次
    </span>

    <button class="btn btn-ghost" type="button" @click="chat.clear()">清空</button>
  </div>
</template>

<style scoped>
.bar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  background: var(--c-surface-2);
  border-bottom: 1px solid var(--c-border);
  font-size: 12px;
}

.seg {
  display: inline-flex;
  border: 1px solid var(--c-border-strong);
  border-radius: 8px;
  overflow: hidden;
  background: var(--c-surface);
}

.seg-btn {
  border: none;
  background: transparent;
  padding: 4px 11px;
  font-size: 12px;
  color: var(--c-text-dim);
}

.seg-btn.active {
  background: var(--c-primary);
  color: #fff;
}

.state {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--c-text-dim);
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--c-text-faint);
}

.state.open .dot {
  background: var(--c-ok);
}

.state.connecting .dot {
  background: var(--c-warn);
}

.state.error .dot,
.state.closed .dot {
  background: var(--c-danger);
}

.note {
  color: var(--c-text-faint);
}

.note-warn {
  color: var(--c-warn);
}

.spacer {
  flex: 1 1 auto;
}

@media (max-width: 640px) {
  .note {
    display: none;
  }
}
</style>
