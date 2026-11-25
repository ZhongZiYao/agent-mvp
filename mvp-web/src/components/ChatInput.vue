<script setup lang="ts">
import { ref } from 'vue'

/**
 * 站 4：输入框。
 *
 * <p><b>Enter 发送 / Shift+Enter 换行</b> —— 这是聊天界面的通用约定。
 * 中文输入法组合态（{@code compositionstart} / {@code compositionend}）时
 * <b>不能</b>把 Enter 当成发送，否则选词确认会误发一条空消息。</p>
 */
const props = defineProps<{
  disabled?: boolean
  placeholder?: string
}>()

const emit = defineEmits<{
  (e: 'send', text: string): void
}>()

const text = ref('')
/** 中文输入法组字中。 */
const composing = ref(false)

function submit(): void {
  if (props.disabled) {
    return
  }
  const value = text.value.trim()
  if (!value) {
    return
  }
  emit('send', value)
  text.value = ''
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Enter' || event.shiftKey) {
    return
  }
  // 组字中：Enter 是「选词」，不是「发送」
  if (composing.value || event.isComposing) {
    return
  }
  event.preventDefault()
  submit()
}
</script>

<template>
  <div class="composer">
    <textarea
      v-model="text"
      class="input"
      rows="1"
      :placeholder="placeholder ?? '说点什么…（Enter 发送，Shift+Enter 换行）'"
      :disabled="disabled"
      @keydown="onKeydown"
      @compositionstart="composing = true"
      @compositionend="composing = false"
    ></textarea>
    <button class="btn btn-primary" type="button" :disabled="disabled" @click="submit">
      发送
    </button>
  </div>
</template>

<style scoped>
.composer {
  flex: 0 0 auto;
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 10px 12px;
  background: var(--c-surface);
  border-top: 1px solid var(--c-border);
}

.input {
  flex: 1 1 auto;
  resize: none;
  max-height: 140px;
  min-height: 38px;
  padding: 9px 11px;
  border: 1px solid var(--c-border-strong);
  border-radius: var(--radius);
  background: var(--c-surface-2);
  outline: none;
  line-height: 1.5;
}

.input:focus {
  border-color: var(--c-primary);
  background: var(--c-surface);
  box-shadow: 0 0 0 3px var(--c-primary-soft);
}

.input:disabled {
  opacity: 0.6;
}
</style>
