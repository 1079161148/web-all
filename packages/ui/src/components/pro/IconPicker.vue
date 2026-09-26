<script setup lang="ts">
import { computed, ref, type Component } from 'vue'
import { NEmpty, NInput, NPopover } from 'naive-ui'

/**
 * 图标选择器（菜单管理用）。
 *
 * <h3>为什么图标集是"注入"的，而不是内置一套</h3>
 * {@code ui-component-policy} 的选型是 {@code @vicons}（xicons），
 * 它是一个<b>图标集合的集合</b>（ionicons / material / tabler … 各自独立发布）。
 * 内置哪一套是产品决策，不该由组件库替使用者决定 ——
 * 而且把整套图标打进组件库会让所有不选图标的页面也承担这份体积。
 *
 * <p>因此本组件接受 {@code icons} 列表（通常来自
 * {@code import.meta.glob} 动态解析，见 {@code frontend-architecture} 对
 * "图标禁止写死 import 列表"的要求），由应用决定提供哪些。
 *
 * <pre>{@code
 * <IconPicker v-model="form.icon" :icons="menuIcons" />
 * }</pre>
 *
 * <h3>为什么需要搜索</h3>
 * 图标集动辄几百个。没有搜索的选择器等于让用户逐个翻 ——
 * 而图标的名字通常是有语义的（{@code person}、{@code settings}），
 * 搜索比浏览快一个数量级。
 */
export interface ProIconOption {
  /** 图标名（提交给后端的就是它）。 */
  name: string
  /** 图标组件。 */
  component: Component
}

const props = withDefaults(
  defineProps<{
    /** 当前选中的图标名（v-model）。 */
    modelValue?: string
    /** 可选的图标集合。 */
    icons?: ProIconOption[]
    placeholder?: string
    disabled?: boolean
  }>(),
  {
    icons: () => [],
    placeholder: '点击选择图标',
    disabled: false
  }
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const show = ref(false)
const keyword = ref('')

const filtered = computed(() => {
  const word = keyword.value.trim().toLowerCase()
  if (!word) {
    return props.icons
  }
  return props.icons.filter((icon) => icon.name.toLowerCase().includes(word))
})

const current = computed(() => props.icons.find((icon) => icon.name === props.modelValue))

function pick(icon: ProIconOption): void {
  emit('update:modelValue', icon.name)
  show.value = false
  keyword.value = ''
}

function clear(): void {
  emit('update:modelValue', '')
  show.value = false
}

/** 没有注入任何图标时的提示 —— 这是配置遗漏，不是"没有数据"。 */
const emptyText = computed(() =>
  props.icons.length === 0
    ? '未提供图标集合（请通过 icons 传入，例如由 import.meta.glob 解析 @vicons）'
    : '没有匹配的图标'
)
</script>

<template>
  <n-popover v-model:show="show" trigger="click" :disabled="disabled" style="width: 360px">
    <template #trigger>
      <div class="icon-picker__trigger" :class="{ 'icon-picker__trigger--disabled': disabled }">
        <component v-if="current" :is="current.component" class="icon-picker__current" />
        <span class="icon-picker__label">{{ current?.name ?? modelValue ?? placeholder }}</span>
        <span v-if="modelValue" class="icon-picker__clear" title="清除" @click.stop="clear">×</span>
      </div>
    </template>

    <div class="icon-picker__panel">
      <n-input
        v-model:value="keyword"
        size="small"
        clearable
        placeholder="搜索图标名"
        class="icon-picker__search"
      />

      <div v-if="filtered.length > 0" class="icon-picker__grid">
        <button
          v-for="icon in filtered"
          :key="icon.name"
          type="button"
          class="icon-picker__item"
          :class="{ 'icon-picker__item--active': icon.name === modelValue }"
          :title="icon.name"
          @click="pick(icon)"
        >
          <component :is="icon.component" />
        </button>
      </div>

      <n-empty v-else size="small" :description="emptyText" class="icon-picker__empty" />
    </div>
  </n-popover>
</template>

<style scoped>
.icon-picker__trigger {
  display: inline-flex;
  align-items: center;
  gap: var(--wa-spacing-sm);
  min-width: 180px;
  height: 34px;
  padding: 0 var(--wa-spacing-md);
  border: 1px solid var(--wa-border);
  border-radius: var(--wa-radius-md);
  background: var(--wa-bg-elevated);
  color: var(--wa-text-primary);
  font-size: var(--wa-font-size-md);
  cursor: pointer;
  transition: border-color var(--wa-motion-duration-fast, 0.1s);
}

.icon-picker__trigger:hover {
  border-color: var(--wa-color-primary);
}

.icon-picker__trigger--disabled {
  color: var(--wa-text-disabled);
  cursor: not-allowed;
  background: var(--wa-bg-hover);
}

.icon-picker__label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.icon-picker__clear {
  color: var(--wa-text-disabled);
  font-size: var(--wa-font-size-lg);
  line-height: 1;
}

.icon-picker__search {
  margin-bottom: var(--wa-spacing-md);
}

.icon-picker__grid {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: var(--wa-spacing-sm);
  max-height: 240px;
  overflow: auto;
}

.icon-picker__item {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 32px;
  border: 1px solid transparent;
  border-radius: var(--wa-radius-sm);
  background: transparent;
  color: var(--wa-text-primary);
  font-size: var(--wa-font-size-lg);
  cursor: pointer;
}

.icon-picker__item:hover {
  background: var(--wa-bg-hover);
}

.icon-picker__item--active {
  border-color: var(--wa-color-primary);
  color: var(--wa-color-primary);
}

.icon-picker__empty {
  padding: var(--wa-spacing-lg) 0;
}
</style>
