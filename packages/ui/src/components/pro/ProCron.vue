<script setup lang="ts">
import { computed, ref, shallowRef, watch, type Component } from 'vue'
import { NAlert, NSpin } from 'naive-ui'
import type { CronFormat } from '@vue-js-cron/core'
import { ensureCron } from '../../adapters/lazy'

/**
 * cron 表达式编辑器（内核：@vue-js-cron）。
 *
 * <pre>{@code
 * <ProCron v-model:value="form.cron" format="crontab" />
 * }</pre>
 *
 * <h3>「中文描述」由内核提供，我们没有自研</h3>
 * 这个内核的设计本身就与常见的 cron 控件不同：它不是"一个输入框 + 正则校验"，
 * 而是<b>用本地化片段把选择项拼成一句可读的话</b> ——
 * 例如选项读起来是「每天 / 12 / 时 / 0 / 分」。
 * 因此把 `locale` 设为 `cn` 即得到中文界面，
 * 文案全部来自内核自带的语言包（`locale/cn.ts`）。
 *
 * <p>⚠️ 但要明确一条边界：内核<b>没有</b>「整条表达式 → 一句话摘要」的接口
 * （`L10nEngine.render` 是按字段渲染的，不是整句生成）。
 * 若将来需要"每天 12:00 执行"这种摘要，那是另做一个描述器的事，
 * <b>属于自研描述逻辑</b>，需要单独评估 —— 不能顺手补在这里假装是内核能力。
 * 所以本组件额外做的是把<b>原始表达式</b>显示出来（见 `showExpression`），
 * 那是"把值贴出来"，不是"翻译值"，两者不该混为一谈。
 *
 * <h3>校验：内核给信号，我们只补「进来之前」的那一段</h3>
 * 内核会在无法解析时 emit `error`，这是它的职责，我们不重写。
 * 但若把一个段数就不对的值交给它，编辑器会渲染出一个错乱的状态 ——
 * 因此本组件先做一次<b>结构性检查</b>（段数），不合格时显示原始值 + 提示，
 * 且<b>不加载内核</b>（编辑器根本用不上，没必要为它下载）。
 *
 * <h3>⚠️ locale 码是 `cn`，不是 `zh-CN`</h3>
 * 可用码就是内核语言包的<b>文件名</b>（cn / en / de / ja / ko / ru …）。
 * 传 `zh-CN` 不报错，会静默回退英文 —— 这正是"配了中文却还是英文"的成因。
 */

const props = withDefaults(
  defineProps<{
    /** cron 表达式（v-model:value）。 */
    value?: string
    /**
     * 表达式格式。
     *
     * <ul>
     *   <li>{@code crontab} —— 5 段（分 时 日 月 周），Linux 常规</li>
     *   <li>{@code spring} —— 6 段（多一个秒）</li>
     *   <li>{@code quartz} —— 6 或 7 段（年份可选）</li>
     * </ul>
     * 选错的表现是"保存后调度时间不对"，所以表单里应当让用户显式选。
     */
    format?: CronFormat
    /** 语言包码。默认 `cn`。传错会静默回退英文（见类注释）。 */
    locale?: string
    /**
     * 是否禁用。
     *
     * <p>内核只提供 {@code disabled}，没有独立的只读态 ——
     * 因此这里也不造一个假的 readonly，避免"看起来两种状态、实际一个行为"。
     */
    disabled?: boolean
    /** 是否在编辑器下方显示原始表达式（便于复制、便于核对）。 */
    showExpression?: boolean
  }>(),
  {
    value: '',
    format: 'crontab',
    locale: 'cn',
    disabled: false,
    showExpression: true
  }
)

const emit = defineEmits<{
  (e: 'update:value', value: string): void
  /** 内核报告的错误（无法解析当前值）。 */
  (e: 'error', error: unknown): void
}>()

/**
 * 各格式允许的段数。
 *
 * <p>quartz 允许 6 或 7 段：年份是可选的。若只认 7 段，
 * 会把大量合法的 quartz 表达式判成非法 ——
 * 而这类"合法值被拦下"的问题比"非法值没拦住"更难被接受。
 */
const EXPECTED_SEGMENTS: Record<CronFormat, number[]> = {
  crontab: [5],
  spring: [6],
  quartz: [6, 7]
}

const segments = computed(() => props.value.trim().split(/\s+/).filter(Boolean))

/** 空值不算错（"还没填"与"填错了"是两件事）。 */
const isEmpty = computed(() => segments.value.length === 0)

const allowedCounts = computed(() => EXPECTED_SEGMENTS[props.format])

const structureOk = computed(
  () => isEmpty.value || allowedCounts.value.includes(segments.value.length)
)

const structureHint = computed(
  () =>
    `当前值有 ${segments.value.length} 段，但 ${props.format} 格式应为 ` +
    `${allowedCounts.value.join(' 或 ')} 段。`
)

const cronComponent = shallowRef<Component | null>(null)
const kernelLoading = ref(false)
const kernelError = ref<unknown>(null)

async function loadKernel(): Promise<void> {
  kernelLoading.value = true
  try {
    const mod = await ensureCron()
    cronComponent.value = mod.CronNaive as Component
  } catch (error) {
    kernelError.value = error
    emit('error', error)
  } finally {
    kernelLoading.value = false
  }
}

watch(
  structureOk,
  (ok) => {
    // 段数不对时不该加载内核：编辑器用不上，而且把坏值喂给它只会更乱
    if (ok && !cronComponent.value && !kernelLoading.value && !kernelError.value) {
      void loadKernel()
    }
  },
  { immediate: true }
)

function handleUpdate(next: string): void {
  emit('update:value', next)
}

function handleKernelError(error: unknown): void {
  emit('error', error)
}
</script>

<template>
  <div class="pro-cron">
    <!-- 结构性不合格：显示原始值 + 原因，而不是渲染一个错乱的编辑器 -->
    <n-alert v-if="!structureOk" type="warning" :show-icon="true" class="pro-cron__alert">
      <p class="pro-cron__alert-title">{{ structureHint }}</p>
      <p class="pro-cron__alert-value">当前值：{{ value }}</p>
      <p class="pro-cron__alert-tip">
        请先修正段数，或切换「格式」以匹配表达式的实际形式。
      </p>
    </n-alert>

    <n-alert v-else-if="kernelError" type="error" class="pro-cron__alert">
      调度编辑器加载失败，请刷新重试。
    </n-alert>

    <div v-else-if="kernelLoading || !cronComponent" class="pro-cron__loading">
      <n-spin />
    </div>

    <component
      :is="cronComponent"
      v-else
      :model-value="value"
      :format="format"
      :locale="locale"
      :disabled="disabled"
      @update:model-value="handleUpdate"
      @error="handleKernelError"
    />

    <!-- 原始表达式：便于复制与核对。这是"把值贴出来"，不是"翻译值" -->
    <p v-if="showExpression" class="pro-cron__expression">
      <span class="pro-cron__expression-label">表达式</span>
      <code>{{ value || '（空）' }}</code>
    </p>
  </div>
</template>

<style scoped>
/* 只做结构与尺寸，样式引用 Token（ui-component-policy 强行约束第 5 条） */
.pro-cron__alert {
  margin-bottom: var(--wa-spacing-sm);
}

.pro-cron__alert-title {
  margin: 0;
  font-weight: 600;
}

.pro-cron__alert-value {
  margin: var(--wa-spacing-sm) 0 0;
  font-family: var(--wa-font-family-mono, monospace);
  word-break: break-all;
}

.pro-cron__alert-tip {
  margin: var(--wa-spacing-sm) 0 0;
  color: var(--wa-text-secondary);
}

.pro-cron__loading {
  display: flex;
  justify-content: center;
  padding: var(--wa-spacing-xl) 0;
}

.pro-cron__expression {
  display: flex;
  align-items: center;
  gap: var(--wa-spacing-sm);
  margin: var(--wa-spacing-sm) 0 0;
  color: var(--wa-text-secondary);
  font-size: var(--wa-font-size-sm);
}

.pro-cron__expression-label {
  flex: none;
}

.pro-cron__expression code {
  font-family: var(--wa-font-family-mono, monospace);
  word-break: break-all;
}
</style>
