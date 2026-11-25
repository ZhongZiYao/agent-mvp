<script setup lang="ts">
import { computed } from 'vue'
import { useAssemblyStore } from '@/store/assembly'

/**
 * 站 4：**装配面板** —— 把「这个 Agent 身上装了什么」画出来。
 *
 * <p><b>这是本前端存在的主要理由</b>。一个聊天窗口谁都能做；把
 * <b>框架排序之后的生效链</b>（不是注册顺序）画成一条从外到内的时间线，
 * 顺带标出「哪一件是框架偷偷插的、哪一件不参与 order 协商」——
 * 这件事没有现成组件可抄，而且它<b>只能靠后端把装配结果导出来</b>（框架不提供）。</p>
 */
const assembly = useAssemblyStore()

const chain = computed(() => assembly.snapshot?.chain ?? [])

/** 链首那一项：框架自动注入、不参与 order 协商。 */
const hardWired = computed(() => chain.value.find((item) => !item.orderNegotiated) ?? null)
</script>

<template>
  <div class="panel">
    <div class="panel-head">
      <h2>装配快照</h2>
      <button class="btn btn-ghost" type="button" :disabled="assembly.loading" @click="assembly.load()">
        {{ assembly.loading ? '刷新中…' : '刷新' }}
      </button>
    </div>

    <p v-if="assembly.error" class="panel-error">{{ assembly.error }}</p>

    <div v-if="!assembly.snapshot" class="panel-empty">
      尚未拉取到装配快照。
    </div>

    <template v-else>
      <section class="block">
        <h3>Agent</h3>
        <dl class="kv">
          <dt>名字</dt>
          <dd class="mono">{{ assembly.snapshot.agentName }}</dd>
          <dt>模型</dt>
          <dd class="mono">{{ assembly.snapshot.model ?? '—' }}</dd>
          <dt>迭代上限</dt>
          <dd class="mono">{{ assembly.snapshot.maxIters }}</dd>
          <dt>冲突策略</dt>
          <dd class="mono">{{ assembly.snapshot.conflictPolicy }}</dd>
          <dt>状态存储</dt>
          <dd class="mono">{{ assembly.snapshot.stateStore ?? '—' }}</dd>
          <dt>权限模式</dt>
          <dd class="mono">{{ assembly.snapshot.permissionMode ?? '—' }}</dd>
        </dl>
      </section>

      <section class="block">
        <h3>
          生效链
          <span class="h3-note">从外到内 · 索引 0 = 最外层</span>
        </h3>

        <p v-if="hardWired" class="callout callout-warn">
          <strong>{{ hardWired.name }}</strong> 是框架自动注入的，
          <strong>不参与 order 协商</strong> —— 排序发生在
          <code class="mono">ReActAgent</code> 构造函数之前，它被直接塞在链首。
          挂载顺序与 order 都压不过它。
        </p>

        <ol class="chain">
          <li
            v-for="item in chain"
            :key="`${item.position}-${item.name}`"
            class="chain-item"
            :class="{ 'is-hardwired': !item.orderNegotiated }"
          >
            <span class="chain-dot" aria-hidden="true"></span>
            <div class="chain-body">
              <div class="chain-line1">
                <span class="chain-pos mono">#{{ item.position }}</span>
                <span class="chain-name">{{ item.name }}</span>
                <span class="chain-order mono">order {{ item.order }}</span>
              </div>
              <div class="chain-line2">{{ item.source }}</div>
            </div>
          </li>
        </ol>

        <p class="foot-note">
          执行顺序只由 <code class="mono">order()</code> 决定：
          <strong>数值越大越靠外</strong>（入站先执行、出站后执行）。
          挂载顺序无效 —— 框架在 <code class="mono">build()</code> 里会按 order 降序重排。
        </p>
      </section>

      <section class="block">
        <h3>工具</h3>
        <ul v-if="assembly.snapshot.toolNames.length" class="tags">
          <li v-for="name in assembly.snapshot.toolNames" :key="name" class="tag mono">{{ name }}</li>
        </ul>
        <p v-else class="muted">未注册工具</p>

        <h3 class="mt">权限拒绝规则</h3>
        <ul v-if="assembly.snapshot.permissionDenyRules.length" class="tags">
          <li
            v-for="rule in assembly.snapshot.permissionDenyRules"
            :key="rule"
            class="tag tag-danger mono"
          >
            {{ rule }}
          </li>
        </ul>
        <p v-else class="muted">无</p>
      </section>

      <section class="block">
        <h3>模型执行参数</h3>
        <dl v-if="assembly.snapshot.modelExecutionConfig" class="kv">
          <dt>超时</dt>
          <dd class="mono">{{ assembly.snapshot.modelExecutionConfig.timeoutSeconds ?? '—' }} s</dd>
          <dt>尝试次数</dt>
          <dd class="mono">{{ assembly.snapshot.modelExecutionConfig.maxAttempts ?? '—' }}</dd>
          <dt>退避基数</dt>
          <dd class="mono">{{ assembly.snapshot.modelExecutionConfig.initialBackoffMillis ?? '—' }} ms</dd>
          <dt>退避倍数</dt>
          <dd class="mono">{{ assembly.snapshot.modelExecutionConfig.backoffMultiplier ?? '—' }}</dd>
        </dl>
        <p v-else class="muted">未配置（走 maxRetries 推导）</p>
      </section>
    </template>
  </div>
</template>

<style scoped>
.panel {
  height: 100%;
  overflow-y: auto;
  padding: 14px 16px 28px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.panel-head h2 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text-dim);
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.panel-empty,
.panel-error {
  font-size: 13px;
  color: var(--c-text-faint);
  padding: 20px 0;
}

.panel-error {
  color: var(--c-danger);
}

.block {
  padding: 12px 0;
  border-top: 1px solid var(--c-border);
}

.block:first-of-type {
  border-top: none;
}

.block h3 {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--c-text-dim);
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.h3-note {
  font-weight: 400;
  color: var(--c-text-faint);
  font-size: 11px;
}

.mt {
  margin-top: 14px;
}

.kv {
  display: grid;
  grid-template-columns: 78px minmax(0, 1fr);
  gap: 4px 10px;
  margin: 0;
  font-size: 13px;
}

.kv dt {
  color: var(--c-text-faint);
}

.kv dd {
  margin: 0;
  overflow-wrap: anywhere;
}

.callout {
  margin: 0 0 12px;
  padding: 8px 10px;
  border-radius: var(--radius);
  font-size: 12.5px;
  line-height: 1.55;
}

.callout-warn {
  background: var(--c-warn-soft);
  color: #7b5a17;
  border: 1px solid #f0e2c0;
}

.chain {
  list-style: none;
  margin: 0;
  padding: 0;
}

.chain-item {
  position: relative;
  display: flex;
  gap: 10px;
  padding: 0 0 12px 0;
}

/* 竖线：连接相邻节点，暗示「嵌套」而不是「并列」 */
.chain-item:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 4px;
  top: 12px;
  bottom: 0;
  width: 1px;
  background: var(--c-border-strong);
}

.chain-dot {
  position: relative;
  z-index: 1;
  width: 9px;
  height: 9px;
  margin-top: 5px;
  border-radius: 50%;
  background: var(--c-surface);
  border: 2px solid var(--c-border-strong);
  flex: 0 0 auto;
}

.is-hardwired .chain-dot {
  border-color: var(--c-warn);
  background: var(--c-warn-soft);
}

.chain-body {
  min-width: 0;
  flex: 1 1 auto;
}

.chain-line1 {
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex-wrap: wrap;
}

.chain-pos {
  color: var(--c-text-faint);
}

.chain-name {
  font-weight: 600;
  font-size: 13px;
}

.chain-order {
  color: var(--c-text-dim);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: 4px;
  padding: 0 5px;
}

.is-hardwired .chain-order {
  color: var(--c-warn);
  border-color: #f0e2c0;
  background: var(--c-warn-soft);
}

.chain-line2 {
  font-size: 12px;
  color: var(--c-text-faint);
  margin-top: 1px;
}

.foot-note {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.55;
  color: var(--c-text-dim);
}

.tags {
  list-style: none;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0;
  padding: 0;
}

.tag {
  font-size: 12px;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: 5px;
  padding: 2px 7px;
}

.tag-danger {
  color: var(--c-danger);
  border-color: #f3cfcf;
  background: var(--c-danger-soft);
}

.muted {
  margin: 0;
  font-size: 12.5px;
  color: var(--c-text-faint);
}

code.mono {
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: 4px;
  padding: 0 4px;
}
</style>
