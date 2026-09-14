<script setup lang="ts">
import { computed } from 'vue'
import { NTag } from '@admin/ui'
import { dictOptions, type DictOption } from '@/composables/useDict'

/**
 * 字典标签：把存储值渲染成带颜色的标签。
 *
 * <pre>{@code
 * <dict-tag dict-type="sys_normal_disable" :value="row.status" />
 * }</pre>
 *
 * <h3>三种状态都必须可见（状态矩阵思路在单元格上的延伸）</h3>
 * <ol>
 *   <li><b>命中</b>：渲染成带颜色的标签</li>
 *   <li><b>未命中但值存在</b>：显示原始值 + 明确的"未匹配"提示。
 *       <b>绝不静默显示空白</b> —— 那会让"字典少配了一项"变成
 *       "页面上有个格子是空的"，排查成本极高</li>
 *   <li><b>值为空</b>：显示占位符 {@code -}，与"未匹配"区分开</li>
 * </ol>
 *
 * <h3>为什么用 {@code NTag} 的 type 而不是自定义 CSS 类</h3>
 * 字典的 {@code cssClass} 来自后端配置（success/warning/...）。
 * 若直接把后端值当成 CSS 类名用，就等于<b>让后端数据决定了前端的样式实现</b> ——
 * 一旦换 UI 库，后端配置也要跟着改。这里做一次<b>白名单映射</b>，
 * 把"业务语义"（成功/警告）与"渲染实现"（Naive 的 type）解耦。
 */
const props = defineProps<{
  /** 字典类型编码。 */
  dictType: string
  /** 存储值。 */
  value?: string | number | null
}>()

/** Naive NTag 支持的 type 白名单。 */
const TAG_TYPES = ['default', 'primary', 'info', 'success', 'warning', 'error'] as const
type TagType = (typeof TAG_TYPES)[number]

/**
 * 语义名 → Naive 词汇的映射表。
 *
 * <h3>为什么需要它（实测发现的真实不一致）</h3>
 * 字典种子里 {@code sys_user_status} 的"锁定"项配的是 {@code css_class = 'danger'}，
 * 而 <b>Naive UI 的 NTag 没有 {@code danger}，对应取值叫 {@code error}</b>
 * （{@code danger} 是 Element Plus / Bootstrap 的用词）。
 * 若只做白名单过滤、不做映射，"锁定"这个状态的标签会退化成默认灰色 ——
 * <b>最需要醒目警示的状态反而最不醒目</b>，而且不会有任何报错。
 *
 * <h3>为什么不直接把库里的值改成 error</h3>
 * 那等于让<b>数据库里存的样式值绑定了某个具体的 UI 库</b>。
 * 字典是业务配置，它应该表达语义（"这是危险级别"），而不是实现（"用 error 这个类"）。
 * 一旦将来换 UI 库，改库里的一张表远比改一个映射表危险。
 * <b>让数据库存语义，让前端负责翻译 —— 这是 UI 库可替换的前提。</b>
 */
const TAG_TYPE_ALIASES: Record<string, TagType> = {
  danger: 'error',
  critical: 'error',
  warn: 'warning',
  informational: 'info',
  ok: 'success'
}

const normalizedValue = computed(() =>
  props.value === null || props.value === undefined ? '' : String(props.value)
)

const option = computed<DictOption | undefined>(() =>
  dictOptions(props.dictType).value.find((item) => item.value === normalizedValue.value)
)

const tagType = computed<TagType>(() => {
  const cssClass = option.value?.cssClass
  if (!cssClass) {
    return 'default'
  }
  // 先查同义词表，再查白名单直通。
  // 顺序不可颠倒：danger 不在白名单里，先查白名单会直接落到 default
  const alias = TAG_TYPE_ALIASES[cssClass]
  if (alias) {
    return alias
  }
  return (TAG_TYPES as readonly string[]).includes(cssClass) ? (cssClass as TagType) : 'default'
})
</script>

<template>
  <n-tag v-if="option" :type="tagType" size="small" :bordered="false">
    {{ option.label }}
  </n-tag>

  <!--
    未匹配：保留原始值并给出提示。
    这个分支的存在本身就是一种"数据质量可见性" —— 字典配置漏项会立刻被发现，
    而不是等业务方反馈"某个状态下显示空白"。
  -->
  <n-tooltip v-else-if="normalizedValue" trigger="hover">
    <template #trigger>
      <span class="dict-tag__unmatched">{{ normalizedValue }}</span>
    </template>
    字典「{{ dictType }}」中未找到值为「{{ normalizedValue }}」的项
  </n-tooltip>

  <span v-else class="dict-tag__empty">-</span>
</template>

<style scoped>
/* 用虚线下划线而不是红色：这是"数据可能有问题"的提示，不是错误状态 */
.dict-tag__unmatched {
  color: var(--wa-text-secondary, #5c6570);
  text-decoration: underline dotted;
  cursor: help;
}

.dict-tag__empty {
  color: var(--wa-text-disabled, #a8b0ba);
}
</style>
