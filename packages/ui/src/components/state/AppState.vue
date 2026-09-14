<script setup lang="ts">
// 显式引入 Vue API（而非依赖 unplugin-auto-import 的隐式注入）：
// 显式导入让 vue-tsc 无需生成 auto-imports.d.ts 即可类型检查，
// 也避免「这个 ref 从哪来的」这类阅读成本。
import { onUnmounted, ref, watch } from 'vue'
import type { AppStateStatus } from '../../types'

/**
 * 状态矩阵容器（设计文档 §11.9）。
 *
 * 统一承载「加载 / 空 / 错误 / 无权限 / 正常」五态，避免每个页面各写一遍
 * `v-if="loading"` / `v-else-if="!data.length"` 的碎片逻辑 —— 那些重复代码
 * 正是「空数据只显示暂无数据、出错就白屏」这类体验问题的根源。
 *
 * 设计要点：
 * - 加载超过 delay 毫秒才显示骨架，避免闪一下又消失（闪烁比等待更让人烦躁）
 * - 空状态与错误状态都提供**可操作**的出口（按钮），而不是一句冷冰冰的提示
 * - 错误状态展示 traceId 并支持复制，便于用户报障时提供有效信息
 */
const props = withDefaults(
  defineProps<{
    status: AppStateStatus
    /** 空状态标题。 */
    emptyTitle?: string
    /** 空状态描述。 */
    emptyDescription?: string
    /** 空状态操作按钮文案；为空则不显示按钮。 */
    emptyActionText?: string
    /** 错误信息。 */
    errorMessage?: string
    /** 链路 ID，便于报障。 */
    traceId?: string
    /** 无权限提示。 */
    deniedDescription?: string
    /** 加载延迟显示阈值（毫秒），避免闪烁。 */
    loadingDelay?: number
    /** 骨架屏行数。 */
    skeletonRows?: number
  }>(),
  {
    emptyTitle: '暂无数据',
    emptyDescription: '',
    emptyActionText: '',
    errorMessage: '数据加载失败',
    traceId: '',
    deniedDescription: '你没有访问该资源的权限',
    loadingDelay: 200,
    skeletonRows: 6
  }
)

const emit = defineEmits<{
  (e: 'retry'): void
  (e: 'empty-action'): void
}>()

const showLoading = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined

watch(
  () => props.status,
  (status) => {
    if (timer) {
      clearTimeout(timer)
      timer = undefined
    }
    if (status === 'loading') {
      // 延迟显示：快请求不闪骨架
      timer = setTimeout(() => {
        showLoading.value = true
      }, props.loadingDelay)
    } else {
      showLoading.value = false
    }
  },
  { immediate: true }
)

onUnmounted(() => {
  if (timer) {
    clearTimeout(timer)
  }
})

async function copyTraceId(): Promise<void> {
  if (!props.traceId) {
    return
  }
  try {
    await navigator.clipboard.writeText(props.traceId)
  } catch {
    // 剪贴板不可用（非 HTTPS / 无权限）时静默失败 —— 不为此打断用户
  }
}
</script>

<template>
  <div class="app-state">
    <!-- 加载中 -->
    <div v-if="status === 'loading'" class="app-state__panel">
      <div v-if="showLoading" class="app-state__skeleton">
        <div v-for="row in skeletonRows" :key="row" class="app-state__skeleton-row" />
      </div>
    </div>

    <!-- 无权限：说明缺什么、找谁，而不是白屏 -->
    <div v-else-if="status === 'denied'" class="app-state__panel">
      <div class="app-state__icon app-state__icon--denied">
        <svg viewBox="0 0 24 24" width="48" height="48" aria-hidden="true">
          <path
            fill="currentColor"
            d="M12 1a5 5 0 0 0-5 5v3H6a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-9a2 2 0 0 0-2-2h-1V6a5 5 0 0 0-5-5Zm-3 8V6a3 3 0 1 1 6 0v3H9Z"
          />
        </svg>
      </div>
      <p class="app-state__title">无权访问</p>
      <p class="app-state__description">{{ deniedDescription }}</p>
      <p class="app-state__hint">如需开通权限，请联系系统管理员</p>
    </div>

    <!-- 错误：给出原因 + 重试 + traceId -->
    <div v-else-if="status === 'error'" class="app-state__panel">
      <div class="app-state__icon app-state__icon--error">
        <svg viewBox="0 0 24 24" width="48" height="48" aria-hidden="true">
          <path
            fill="currentColor"
            d="M12 2 1 21h22L12 2Zm1 14h-2v2h2v-2Zm0-7h-2v5h2V9Z"
          />
        </svg>
      </div>
      <p class="app-state__title">加载失败</p>
      <p class="app-state__description">{{ errorMessage }}</p>
      <button class="app-state__action" type="button" @click="emit('retry')">重试</button>
      <p v-if="traceId" class="app-state__trace">
        <span>问题追踪码：<code>{{ traceId }}</code></span>
        <button type="button" class="app-state__trace-copy" @click="copyTraceId">复制</button>
      </p>
    </div>

    <!-- 空数据：必须带可操作引导 -->
    <div v-else-if="status === 'empty'" class="app-state__panel">
      <div class="app-state__icon app-state__icon--empty">
        <svg viewBox="0 0 24 24" width="48" height="48" aria-hidden="true">
          <path
            fill="currentColor"
            d="M3 5h18v3H3V5Zm0 5h18v9H3v-9Zm2 2v5h14v-5H5Z"
          />
        </svg>
      </div>
      <p class="app-state__title">{{ emptyTitle }}</p>
      <p v-if="emptyDescription" class="app-state__description">{{ emptyDescription }}</p>
      <button
        v-if="emptyActionText"
        class="app-state__action"
        type="button"
        @click="emit('empty-action')"
      >
        {{ emptyActionText }}
      </button>
    </div>

    <!-- 正常 -->
    <slot v-else />
  </div>
</template>

<style scoped>
.app-state {
  width: 100%;
}

.app-state__panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 240px;
  padding: var(--wa-spacing-xxl, 32px) var(--wa-spacing-lg, 16px);
  text-align: center;
}

.app-state__skeleton {
  width: 100%;
}

.app-state__skeleton-row {
  height: 16px;
  margin-bottom: var(--wa-spacing-md, 12px);
  border-radius: var(--wa-radius-md, 4px);
  background: linear-gradient(
    90deg,
    var(--wa-bg-hover, #f0f2f5) 25%,
    var(--wa-divider, #ebeef5) 37%,
    var(--wa-bg-hover, #f0f2f5) 63%
  );
  background-size: 400% 100%;
  animation: app-state-shimmer 1.4s ease infinite;
}

@keyframes app-state-shimmer {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}

.app-state__icon {
  margin-bottom: var(--wa-spacing-lg, 16px);
}

.app-state__icon--empty {
  color: var(--wa-text-disabled, #a8b0ba);
}

.app-state__icon--error {
  color: var(--wa-color-error, #dc2626);
}

.app-state__icon--denied {
  color: var(--wa-color-warning, #d97706);
}

.app-state__title {
  margin: 0 0 var(--wa-spacing-xs, 4px);
  font-size: var(--wa-font-size-lg, 16px);
  font-weight: 500;
  color: var(--wa-text-primary, #1f2329);
}

.app-state__description {
  margin: 0 0 var(--wa-spacing-md, 12px);
  font-size: var(--wa-font-size-md, 14px);
  color: var(--wa-text-secondary, #5c6570);
}

.app-state__hint {
  margin: var(--wa-spacing-sm, 8px) 0 0;
  font-size: var(--wa-font-size-xs, 12px);
  color: var(--wa-text-disabled, #a8b0ba);
}

.app-state__action {
  padding: var(--wa-spacing-xs, 4px) var(--wa-spacing-lg, 16px);
  border: 1px solid var(--wa-color-primary, #2563eb);
  border-radius: var(--wa-radius-md, 4px);
  background: transparent;
  color: var(--wa-color-primary, #2563eb);
  font-size: var(--wa-font-size-md, 14px);
  cursor: pointer;
  transition: opacity var(--wa-duration-normal, 0.2s);
}

.app-state__action:hover {
  opacity: 0.8;
}

.app-state__trace {
  display: flex;
  align-items: center;
  gap: var(--wa-spacing-sm, 8px);
  margin-top: var(--wa-spacing-lg, 16px);
  font-size: var(--wa-font-size-xs, 12px);
  color: var(--wa-text-disabled, #a8b0ba);
}

.app-state__trace code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--wa-text-secondary, #5c6570);
}

.app-state__trace-copy {
  padding: 0;
  border: none;
  background: transparent;
  color: var(--wa-color-primary, #2563eb);
  font-size: inherit;
  cursor: pointer;
}
</style>
