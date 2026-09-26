<script setup lang="ts">
import { computed } from 'vue'
import { findDictOption } from '../../registry/dict'

/**
 * 字典标签：把存储值渲染成带颜色的标签。
 *
 * <pre>{@code
 * <dict-tag dict-type="sys_user_status" :value="row.status" />
 * }</pre>
 *
 * <h3>⚠️ 刻意不用 n-tag（性能约束）</h3>
 * 前端架构规范明确要求：<b>表格单元格内禁止放 Naive 组件</b>。
 * 一个 50 行的表格、每行 3 个状态列，就是 150 个 Naive 组件实例 +
 * 各自的 CSS-in-JS 计算 —— 这是大列表卡顿的主要来源之一。
 *
 * <p>所以这里渲染的是一段 {@code <span>} + 类名。视觉上通过
 * {@code color-mix()} 从 Token 派生出浅色底，与 n-tag 观感一致，
 * 但没有组件实例开销。<b>需要"看起来像标签"，不需要"是个组件"。</b>
 *
 * <h3>三种状态都必须可见</h3>
 * <ol>
 *   <li><b>命中字典</b> → 带颜色的标签</li>
 *   <li><b>未命中但值存在</b> → 显示原始值 + 虚线提示可 hover。
 *       <b>绝不静默显示空白</b> —— 那会让"字典少配了一项"退化成
 *       "页面上有个格子是空的"，是最难归因的一类问题</li>
 *   <li><b>值为空</b> → 占位符 {@code -}，与"未匹配"明确区分</li>
 * </ol>
 *
 * <h3>样式名映射而非直接使用后端值</h3>
 * 后端字典里存的是 <b>语义</b>（success / warning / danger），
 * 而具体颜色由前端映射。<b>数据库存语义、前端负责翻译</b> ——
 * 这条边界让"换 UI 库"或"改配色"都不需要动数据。
 * 注意 {@code danger} 是跨 UI 库更通用的写法（Element/Bootstrap 均如此），
 * 这里把它归一化到内部的 error 语义。
 */
const props = withDefaults(
  defineProps<{
    /** 字典类型编码。 */
    dictType: string
    /** 存储值。 */
    value?: string | number | boolean | null
    /** 尺寸。 */
    size?: 'small' | 'medium'
    /** 值缺失时的占位文案。 */
    placeholder?: string
    /**
     * 未命中字典时是否显示原始值。
     *
     * <p>默认 {@code true}。仅在明确知道"这个码值不需要给用户看"
     * （如内部技术标识）时才关掉。
     */
    showRawWhenMissed?: boolean
  }>(),
  {
    size: 'small',
    placeholder: '-',
    showRawWhenMissed: true
  }
)

/** 语义别名 → 内部样式语义。 */
const SEMANTIC_ALIASES: Record<string, string> = {
  danger: 'error',
  critical: 'error',
  warn: 'warning',
  informational: 'info',
  ok: 'success',
  brand: 'primary'
}

const VALID_SEMANTICS = ['default', 'primary', 'info', 'success', 'warning', 'error'] as const

const normalizedValue = computed(() =>
  props.value === null || props.value === undefined ? '' : String(props.value)
)

const option = computed(() => findDictOption(props.dictType, props.value).value)

const semantic = computed<string>(() => {
  const cssClass = option.value?.cssClass
  if (!cssClass) {
    return 'default'
  }
  const aliased = SEMANTIC_ALIASES[cssClass] ?? cssClass
  // 只接受白名单内的语义值：字典里可能被填上任意的字符串，
  // 直接拼进 class 名会让无效值产生一个"看起来没样式"的标签，
  // 排查时很难想到是字典配置里多了个空格或拼错了
  return (VALID_SEMANTICS as readonly string[]).includes(aliased) ? aliased : 'default'
})

const isEmpty = computed(() => normalizedValue.value === '')
</script>

<template>
  <span v-if="isEmpty" class="dict-tag dict-tag--placeholder">{{ placeholder }}</span>

  <span
    v-else-if="option"
    class="dict-tag"
    :class="[`dict-tag--${semantic}`, `dict-tag--${size}`]"
  >
    {{ option.label }}
  </span>

  <span
    v-else-if="showRawWhenMissed"
    class="dict-tag dict-tag--raw"
    :class="`dict-tag--${size}`"
    :title="`字典「${dictType}」中未找到值为「${normalizedValue}」的项`"
  >
    {{ normalizedValue }}
  </span>

  <span v-else class="dict-tag dict-tag--placeholder">{{ placeholder }}</span>
</template>

<style scoped>
/*
  尺寸与形态全部引用 Token，无一处硬编码色值/间距（ui-component-policy 强行约束第 5 条）。
  颜色用 color-mix 从语义色派生浅底：
    · 只维护一个「语义色」变量，浅底自动跟随 —— 换品牌色不需要同时改两个值
    · 暗色模式下因为混合的是当前主题的语义色，自动得到合适的对比度
*/
.dict-tag {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  padding: 0 var(--wa-spacing-sm);
  border: 1px solid transparent;
  border-radius: var(--wa-radius-sm);
  font-size: var(--wa-font-size-xs);
  line-height: 18px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.dict-tag--medium {
  padding: 0 var(--wa-spacing-md);
  font-size: var(--wa-font-size-sm);
  line-height: 22px;
}

.dict-tag--default {
  color: var(--wa-text-secondary);
  background: var(--wa-bg-hover);
  border-color: var(--wa-border);
}

.dict-tag--primary {
  color: var(--wa-color-primary);
  background: color-mix(in srgb, var(--wa-color-primary) 12%, transparent);
  border-color: color-mix(in srgb, var(--wa-color-primary) 28%, transparent);
}

.dict-tag--info {
  color: var(--wa-color-info);
  background: color-mix(in srgb, var(--wa-color-info) 12%, transparent);
  border-color: color-mix(in srgb, var(--wa-color-info) 28%, transparent);
}

.dict-tag--success {
  color: var(--wa-color-success);
  background: color-mix(in srgb, var(--wa-color-success) 12%, transparent);
  border-color: color-mix(in srgb, var(--wa-color-success) 28%, transparent);
}

.dict-tag--warning {
  color: var(--wa-color-warning);
  background: color-mix(in srgb, var(--wa-color-warning) 14%, transparent);
  border-color: color-mix(in srgb, var(--wa-color-warning) 30%, transparent);
}

.dict-tag--error {
  color: var(--wa-color-error);
  background: color-mix(in srgb, var(--wa-color-error) 12%, transparent);
  border-color: color-mix(in srgb, var(--wa-color-error) 28%, transparent);
}

/* 未命中字典：虚线 + 虚线下划线，是"数据可能有问题"的提示而不是错误状态 */
.dict-tag--raw {
  color: var(--wa-text-secondary);
  border-style: dashed;
  border-color: var(--wa-text-disabled);
  cursor: help;
}

.dict-tag--placeholder {
  color: var(--wa-text-disabled);
}
</style>
