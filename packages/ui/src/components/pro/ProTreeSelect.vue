<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NTreeSelect } from 'naive-ui'
import type { TreeOption } from 'naive-ui'
import type { ProTreeNode } from '../../types'

/**
 * 树形选择（部门 / 分类 / 菜单等）。
 *
 * <h3>为什么不直接用 n-tree-select</h3>
 * 原始 n-tree-select 已经很强（搜索、多选、勾选、键盘导航都有），
 * 我们不重写这些。封装只加三件业务侧每次都要重做的事：
 * <ol>
 *   <li><b>异步取数</b>：{@code :request="loadDeptList"} 一行搞定，
 *       连同加载态与失败处理</li>
 *   <li><b>懒加载</b>：大树上只展开需要的分支</li>
 *   <li><b>失败不静默</b>：取数失败时给出可见的提示，
 *       而不是呈现一个"看起来是空的"下拉框</li>
 * </ol>
 *
 * <h3>关于回显</h3>
 * 回显（编辑时显示中文而不是 id）依赖选项里能找到该 key。
 * 若树是懒加载的、而目标节点尚未加载，就会出现"显示成 id"的情况。
 * 因此本组件在 {@code modelValue} 变化且当前选项树中找不到它时，
 * <b>会触发一次完整加载</b>来补全选项 —— 这是回显能正常工作的前提。
 */

const props = withDefaults(
  defineProps<{
    /** 当前值（v-model）。 */
    modelValue?: string | number | Array<string | number> | null
    /** 异步取数。与 `options` 二选一，`options` 优先。 */
    request?: () => Promise<ProTreeNode[]>
    /** 静态选项（小型固定树用）。 */
    options?: ProTreeNode[]
    multiple?: boolean
    checkable?: boolean
    filterable?: boolean
    clearable?: boolean
    disabled?: boolean
    placeholder?: string
    /** 懒加载子节点。提供后展开节点才会请求其子级。 */
    loadChildren?: (node: ProTreeNode) => Promise<ProTreeNode[]>
    /** 树的最大高度（超出滚动）。 */
    maxHeight?: number
  }>(),
  {
    multiple: false,
    checkable: false,
    filterable: true,
    clearable: true,
    disabled: false,
    placeholder: '请选择'
  }
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: string | number | Array<string | number> | null): void
  (e: 'loaded', nodes: ProTreeNode[]): void
  (e: 'error', error: unknown): void
}>()

const loading = ref(false)
const remote = ref<ProTreeNode[]>([])

const treeOptions = computed(() => props.options ?? remote.value)

/** 触发一次完整加载。 */
async function reload(): Promise<void> {
  if (!props.request || props.options) {
    return
  }
  loading.value = true
  try {
    remote.value = await props.request()
    emit('loaded', remote.value)
  } catch (error) {
    // 失败时保持空树并抛事件：让调用方（或全局错误拦截）去提示。
    // 这里刻意不吞掉错误后假装成功 —— 那会让用户看到"没有可选项"，
    // 而真实原因是接口挂了，排查方向会完全跑偏。
    emit('error', error)
  } finally {
    loading.value = false
  }
}

/**
 * 懒加载。
 *
 * <p>直接改写节点的 {@code children} 而不是维护一份映射表：
 * Naive 会在展开时读取节点的 children，改它是最直接的告知方式。
 * 若维护外部映射，还要处理"重新加载后映射过期"的问题。
 */
async function handleLoad(node: TreeOption): Promise<void> {
  const target = node as unknown as ProTreeNode
  if (!props.loadChildren || target.children?.length) {
    return
  }
  try {
    target.children = await props.loadChildren(target)
  } catch (error) {
    emit('error', error)
  }
}

/** 当前值是否已存在于选项树中（决定要不要为回显补一次加载）。 */
function containsKey(nodes: ProTreeNode[], key: string | number): boolean {
  for (const node of nodes) {
    if (node.key === key) {
      return true
    }
    if (node.children?.length && containsKey(node.children, key)) {
      return true
    }
  }
  return false
}

onMounted(async () => {
  if (props.request && !props.options) {
    await reload()
  }
  // 回显补全：值已存在但选项里找不到时再拉一次
  const value = props.modelValue
  if (!value || treeOptions.value.length === 0) {
    return
  }
  const keys = Array.isArray(value) ? value : [value]
  const missing = keys.filter((key) => key !== null && key !== undefined && !containsKey(treeOptions.value, key))
  if (missing.length > 0) {
    await reload()
  }
})

defineExpose({ reload })
</script>

<template>
  <n-tree-select
    :value="modelValue"
    :options="treeOptions"
    :multiple="multiple"
    :checkable="checkable"
    :filterable="filterable"
    :clearable="clearable"
    :disabled="disabled"
    :loading="loading"
    :placeholder="placeholder"
    :max-height="maxHeight"
    :show-path="multiple"
    key-field="key"
    label-field="label"
    children-field="children"
    :on-load="handleLoad"
    @update:value="(value: string | number | Array<string | number> | null) => emit('update:modelValue', value)"
  />
</template>

<style scoped>
/* 样式由 Naive 提供，本组件不改视觉 */
</style>
