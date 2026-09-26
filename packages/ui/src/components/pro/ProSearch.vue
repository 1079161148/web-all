<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NDatePicker, NInput, NInputNumber, NSelect, NSpace } from 'naive-ui'
import type { ProSearchExpose, ProSearchItem, ProSearchOption } from '../../types'
import DictSelect from './DictSelect.vue'

/**
 * 配置化搜索栏。
 *
 * <h3>三件"看起来可有可无、实际天天用"的事</h3>
 * <ol>
 *   <li><b>回车即搜索</b>：输入完按回车是最自然的动作。
 *       少了它会变成"填完条件后要找一下搜索按钮在哪"，每天重复几十次</li>
 *   <li><b>展开/收起</b>：条件多时默认只露出 3 个，其余折叠。
 *       全部铺开会把表格挤到首屏之外，而多数时候只用前几个条件</li>
 *   <li><b>重置</b>：必须把条件清空<b>并立即重新查询</b>。
 *       只清空不查询，用户会以为"重置没生效"（因为列表没变）</li>
 * </ol>
 *
 * <h3>与 ProTable 的关系</h3>
 * 既可被 ProTable 内部使用（由列的 `search` 声明派生 items），
 * 也可单独使用（搜索字段与表格列不一致时）。
 * 单独使用时把 {@code items} 传进来即可，事件契约完全一样。
 *
 * <p>本组件不发起请求 —— 它只抛出"用户想搜索了"以及当前条件。
 * <b>取数是表格/页面的职责</b>，这样搜索栏能脱离任何请求库使用。
 */
const props = withDefaults(
  defineProps<{
    /** 搜索项。 */
    items: ProSearchItem[]
    /** 条件值（受控）。 */
    modelValue?: Record<string, unknown>
    /**
     * 折叠阈值：条件数超过它才显示「展开/收起」。
     *
     * <p>做成阈值而不是布尔开关，是因为它的真实语义是
     * "条件多到放不下了"。固定显示展开按钮会让只有 2 个条件的页面
     * 出现一个永远不需要点的按钮。
     */
    collapseThreshold?: number
    /** 默认是否折叠。 */
    defaultCollapsed?: boolean
    loading?: boolean
    /** 搜索按钮文案。 */
    searchText?: string
  }>(),
  {
    collapseThreshold: 3,
    defaultCollapsed: true,
    loading: false,
    searchText: '查询'
  }
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: Record<string, unknown>): void
  /** 用户请求搜索（点查询、按回车、重置）。 */
  (e: 'search', value: Record<string, unknown>): void
  (e: 'reset'): void
}>()

const collapsed = ref(props.defaultCollapsed)

/** 内部条件值。无 v-model 时自行维护，有 v-model 时以外部为准。 */
const innerModel = ref<Record<string, unknown>>(buildDefaultModel())

function buildDefaultModel(): Record<string, unknown> {
  const model: Record<string, unknown> = {}
  for (const item of props.items) {
    if (item.defaultValue !== undefined) {
      model[item.key] = item.defaultValue
    }
  }
  return model
}

const model = computed<Record<string, unknown>>(() =>
  props.modelValue === undefined ? innerModel.value : props.modelValue
)

watch(
  () => props.items,
  () => {
    if (props.modelValue === undefined) {
      innerModel.value = buildDefaultModel()
    }
  }
)

function setValue(key: string, value: unknown): void {
  const next = { ...model.value, [key]: value }
  if (props.modelValue === undefined) {
    innerModel.value = next
  }
  emit('update:modelValue', next)
}

/** 需要折叠的项数（advanced 的项默认隐藏）。 */
const advancedItems = computed(() => props.items.filter((item) => item.advanced === true))
const basicItems = computed(() => props.items.filter((item) => item.advanced !== true))
const canCollapse = computed(
  () => advancedItems.value.length > 0 && props.items.length > props.collapseThreshold
)
const visibleItems = computed(() =>
  canCollapse.value && collapsed.value ? basicItems.value : props.items
)

/** 已生效条件数（用于收起状态下的角标提示）。 */
const activeCount = computed(
  () =>
    Object.values(model.value).filter(
      (value) => value !== undefined && value !== null && value !== ''
    ).length
)

function handleSearch(): void {
  emit('update:modelValue', model.value)
  emit('search', model.value)
}

function handleReset(): void {
  const cleared: Record<string, unknown> = {}
  for (const item of props.items) {
    if (item.defaultValue !== undefined) {
      cleared[item.key] = item.defaultValue
    }
  }
  if (props.modelValue === undefined) {
    innerModel.value = cleared
  }
  emit('update:modelValue', cleared)
  emit('reset')
  // 重置后必须立即查询：只清空不查询会让用户以为重置没生效
  emit('search', cleared)
}

/**
 * 选项转换。
 *
 * <p>不直接把 {@link ProSearchOption} 交给 NSelect：Naive 的
 * {@code SelectMixedOption} 是个联合类型（含分组、分隔线等变体），
 * 我们的选项结构与之不严格兼容。显式转换比一个 {@code as} 断言更稳 ——
 * 将来 Naive 调整类型时，这里会编译报错而不是在运行时渲染出空白下拉。
 */
function toSelectOptions(options?: ProSearchOption[]): Array<{ label: string; value: string | number }> {
  return (options ?? []).map((option) => ({ label: option.label, value: option.value }))
}

defineExpose<ProSearchExpose>({
  search: handleSearch,
  reset: handleReset,
  getModel: () => model.value
})
</script>

<template>
  <div v-if="items.length > 0" class="pro-search">
    <div class="pro-search__fields">
      <div v-for="item in visibleItems" :key="item.key" class="pro-search__field">
        <label class="pro-search__label">{{ item.label }}</label>

        <!--
          select：优先字典，其次静态选项。

          ⚠️ 这个分支曾经不存在（实测发现的缺陷）：`ProSearchItem.dict` 的
          注释写着"声明后下拉选项自动来自字典"，但模板只用了 `item.options` ——
          于是**只声明 dict 的搜索项会得到一个空下拉**，而且不报任何错。
          它比"报错"难查得多：页面能打开、控件在、点开是空的，
          排查方向会先跑到"字典没配"或"接口没返回"上。

          修法不是在模板里就地展开字典，而是抽出 DictSelect ——
          这样"字典 → 选项"的转换（含值类型处理）只有一份实现，
          搜索区、表单、详情三处不会各写一遍。
        -->
        <DictSelect
          v-if="item.type === 'select' && item.dict"
          :value="model[item.key] as string | number | null"
          :dict-type="item.dict"
          :placeholder="item.placeholder ?? `请选择${item.label}`"
          :multiple="item.multiple"
          clearable
          size="small"
          @update:value="(value) => setValue(item.key, value)"
        />

        <n-select
          v-else-if="item.type === 'select'"
          :value="model[item.key] as string | number | null"
          :options="toSelectOptions(item.options)"
          :placeholder="item.placeholder ?? `请选择${item.label}`"
          :multiple="item.multiple"
          clearable
          size="small"
          @update:value="(value: unknown) => setValue(item.key, value)"
        />

        <n-input-number
          v-else-if="item.type === 'number'"
          :value="model[item.key] as number | null"
          :placeholder="item.placeholder ?? `请输入${item.label}`"
          clearable
          size="small"
          class="pro-search__number"
          @update:value="(value: unknown) => setValue(item.key, value)"
        />

        <n-date-picker
          v-else-if="item.type === 'date'"
          :value="model[item.key] as number | null"
          type="date"
          clearable
          size="small"
          @update:value="(value: unknown) => setValue(item.key, value)"
        />

        <n-date-picker
          v-else-if="item.type === 'date-range' || item.type === 'datetime-range'"
          :value="model[item.key] as [number, number] | null"
          :type="item.type === 'date-range' ? 'daterange' : 'datetimerange'"
          clearable
          size="small"
          @update:value="(value: unknown) => setValue(item.key, value)"
        />

        <n-input
          v-else
          :value="model[item.key] as string | null"
          :type="item.type === 'textarea' ? 'textarea' : 'text'"
          :placeholder="item.placeholder ?? `请输入${item.label}`"
          clearable
          size="small"
          @update:value="(value: unknown) => setValue(item.key, value)"
          @keyup.enter="handleSearch"
        />
      </div>
    </div>

    <div class="pro-search__actions">
      <n-space :size="8" :wrap="false">
        <n-button type="primary" size="small" :loading="loading" @click="handleSearch">
          {{ searchText }}
        </n-button>
        <n-button size="small" @click="handleReset">重置</n-button>

        <n-button v-if="canCollapse" size="small" text @click="collapsed = !collapsed">
          {{ collapsed ? '展开' : '收起' }}
          <span v-if="collapsed && activeCount > 0" class="pro-search__badge">{{ activeCount }}</span>
        </n-button>
      </n-space>
    </div>
  </div>
</template>

<style scoped>
.pro-search {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--wa-spacing-lg);
  padding: var(--wa-spacing-lg);
  margin-bottom: var(--wa-spacing-lg);
  background: var(--wa-bg-elevated);
  border: 1px solid var(--wa-border);
  border-radius: var(--wa-radius-md);
}

/*
  栅格而非 flex-wrap：flex 下每行控件数由内容宽度决定，
  换行后第二行的单项会与第一行错位（同一列的两个控件起点不同），
  条件越多越明显。等宽栅格保证「上下两行的字段严格按列对齐」。
*/
.pro-search__fields {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: var(--wa-spacing-md) var(--wa-spacing-lg);
  flex: 1;
  min-width: 0;
}

.pro-search__field {
  display: flex;
  align-items: center;
  gap: var(--wa-spacing-sm);
  /* 不加 min-width:0 时，长占位文案会把输入框顶宽导致列宽不均 */
  min-width: 0;
}

/*
  标签定宽 + 右对齐：标签文案长度天然不同（「状态」2 字 vs 「剩余用户数」5 字），
  不固定宽度时同一列里控件的起点会随标签长短左右浮动。
*/
.pro-search__label {
  flex: none;
  width: 5em;
  text-align: right;
  font-size: var(--wa-font-size-md);
  color: var(--wa-text-secondary);
  white-space: nowrap;
}

.pro-search__number {
  flex: 1;
}

.pro-search__actions {
  flex: none;
}

.pro-search__badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 16px;
  height: 16px;
  margin-left: var(--wa-spacing-xs);
  padding: 0 var(--wa-spacing-xs);
  border-radius: var(--wa-radius-full);
  background: var(--wa-color-primary);
  color: var(--wa-bg-elevated);
  font-size: var(--wa-font-size-xs);
  line-height: 1;
}
</style>
