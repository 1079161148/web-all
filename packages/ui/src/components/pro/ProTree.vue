<script setup lang="ts" generic="T extends FlatTreeRow">
import { computed, onMounted, ref } from 'vue'
import { NEmpty, NInput, NSpin, NTree } from 'naive-ui'
import type { TreeOption } from 'naive-ui'
import type { ProTreeDropInfo, ProTreeNode, ProTreePreset } from '../../types'
import { TREE_PRESETS, flatToTree, type FlatTreeRow } from './tree'

/**
 * 树控件（独立选择 / 编辑用）。
 *
 * <h3>与 ProTreeSelect 的分工 —— 两者不是重复</h3>
 * <table>
 *   <tr><th></th><th>形态</th><th>场景</th></tr>
 *   <tr><td>{@code ProTreeSelect}</td><td>下拉里的树</td>
 *       <td>表单里"选一个部门"—— 占位小、选完即收</td></tr>
 *   <tr><td>{@code ProTree}</td><td><b>直接铺在页面上</b>的树</td>
 *       <td>权限分配、部门/菜单维护 —— 需要常驻、可拖拽、可反复勾选</td></tr>
 * </table>
 * 前者是"选择控件"，后者是"工作区控件"：拖拽与持续勾选在下拉里根本没法用。
 *
 * <h3>它封装的四件事（规则 §3 对 ProTree 的要求）</h3>
 * <ol>
 *   <li><b>懒加载</b> — {@code :load-children}，大树上只展开需要的分支</li>
 *   <li><b>搜索</b> — 组件自带搜索框（n-tree 本身没有输入框，只有 pattern）</li>
 *   <li><b>拖拽</b> — 暴露 {@code drop} 事件与 {@code allow-drop} 校验</li>
 *   <li><b>部门 / 菜单预设</b> — {@code rows} + {@code preset} 直接建树，
 *       见 {@code tree.ts} 里关于"同一个建树逻辑被重复 5 遍"的说明</li>
 * </ol>
 *
 * <h3>⚠️ 懒加载与搜索放在一起会互相打架（必须知道）</h3>
 * 搜索是在<b>已有数据</b>上做的。若树是懒加载的，
 * 未展开的分支压根没有数据 —— 于是"明明有『技术中心』这个部门却搜不出来"。
 * 两条出路：
 * <ol>
 *   <li>树不大 → 用 {@code request} / {@code rows} 一次性加载全量</li>
 *   <li>树很大 → 把搜索交给后端：监听 {@code search} 事件，
 *       自己按关键词请求，再把结果作为 {@code options} 传回来。
 *       <b>组件不替你做这个决定</b> —— 因为它取决于数据规模，
 *       而数据规模只有调用方知道</li>
 * </ol>
 *
 * <h3>⚠️ 拖拽只抛事件，不实现移动</h3>
 * 与 ProTable 一致：组件不发起请求。但拖拽有个额外陷阱 ——
 * <b>n-tree 在 {@code allowDrop} 通过时会自己把节点挪过去</b>，
 * 于是界面动了、服务端没动，两边开始漂移。
 * 因此若希望"服务端确认后才移动"，让 {@code allow-drop} 返回 {@code false}
 * 拦住内核的移动，再在 {@code drop} 处理里调接口、成功后 {@code reload()}。
 */

const props = withDefaults(
  defineProps<{
    /** 已嵌套的树数据。与 `rows` / `request` 三选一。 */
    options?: ProTreeNode[]
    /**
     * 扁平数据（后端列表接口的形态）。需配合 `preset` 或 `labelOf`。
     *
     * <p>泛型 {@code T} 从它推断 —— 于是 {@code labelOf} 能拿到<b>真实的行类型</b>，
     * 自定义结构（如分类树）可以写 {@code (row) => row.name} 而无需断言。
     *
     * <p>⚠️ 与 ProTable 的说明同理：泛型 props <b>不给 {@code withDefaults} 默认值</b>，
     * 否则 Vue 的泛型推断会放弃、退回泛型约束。
     */
    rows?: T[]
    /** 预设：自动取好标题字段与禁用规则。 */
    preset?: ProTreePreset
    /** 自定义标题字段（非部门/菜单的树用）。与 `preset` 二选一，本项优先。 */
    labelOf?: (row: T) => string
    /**
     * 根节点的 `parentId` 取值。默认 0（本项目后端约定）。
     *
     * <p>必须可配置：真实表里 `null` / `0` / 空串都出现过，
     * 而"根值猜错"的表现是**整棵树为空** —— 看起来像没查到数据，
     * 排查方向会先跑到接口上。
     */
    rootValue?: number | string
    /** 异步取数（返回已嵌套的树）。 */
    request?: () => Promise<ProTreeNode[]>
    /** 懒加载子级。提供后展开节点才请求其子级。 */
    loadChildren?: (node: ProTreeNode) => Promise<ProTreeNode[]>

    /**
     * 选中值 / 勾选值（v-model）。
     *
     * <p>刻意只用一个 prop 而不是分开的 {@code selectedKeys} 与 {@code checkedKeys}：
     * 内核确实有两套 key，但调用方只关心"我选了什么"。
     * 由 {@code checkable} 决定这个值落到哪一套 ——
     * <b>让调用方看不见内核的两套模型</b>，是这层封装该做的事。
     */
    value?: string | number | Array<string | number> | null
    /** 勾选模式（带勾选框）。 */
    checkable?: boolean
    /** 勾选联动策略。 */
    checkStrategy?: 'all' | 'parent' | 'child'
    /** 非勾选模式下是否允许多选。 */
    multiple?: boolean

    /** 是否显示搜索框。 */
    searchable?: boolean
    searchPlaceholder?: string

    /** 是否可拖拽。 */
    draggable?: boolean
    /** 拖拽落点校验：返回 false 则禁止落下。 */
    allowDrop?: (info: ProTreeDropInfo) => boolean

    /** 初始是否展开全部。 */
    defaultExpandAll?: boolean
    /** 树的最大高度（超出滚动）。 */
    maxHeight?: number
    /** 外部控制的加载态（与组件内部的取数加载态取或）。 */
    loading?: boolean
    emptyText?: string
  }>(),
  {
    checkable: false,
    checkStrategy: 'all',
    multiple: false,
    searchable: true,
    searchPlaceholder: '搜索',
    draggable: false,
    defaultExpandAll: false,
    maxHeight: 420,
    loading: false,
    emptyText: '暂无数据'
  }
)

/**
 * ⚠️ 拖拽载荷类型（{@link ProTreeDropInfo}）定义在 {@code types.ts}，不在这里。
 *
 * <p>原因是实测踩出来的：本文件加了 {@code generic} 之后，
 * 写在 {@code <script setup>} 里的 {@code export interface} 会编译失败
 * （{@code TS1184: Modifiers cannot appear here}），
 * 且消费方（{@code index.ts}）会报"模块没有导出该成员"。
 *
 * <p>即便没有这个限制，把公共形状放在组件文件里导出也是脆的 ——
 * 与 {@code ProTreeNode} 的处理同理：<b>跨文件共用的形状属于契约，
 * 不属于某一个组件。</b>
 */

const emit = defineEmits<{
  (e: 'update:value', value: string | number | Array<string | number> | null): void
  /** 拖拽落下。**组件不实现移动**，由调用方决定是否/如何调接口。 */
  (e: 'drop', info: ProTreeDropInfo): void
  /** 搜索词变化。用于把搜索交给后端（见类注释的懒加载说明）。 */
  (e: 'search', pattern: string): void
  (e: 'loaded', nodes: ProTreeNode[]): void
  (e: 'error', error: unknown): void
}>()

const innerLoading = ref(false)
const remote = ref<ProTreeNode[]>([])
const pattern = ref('')

/** 预设或自定义 labelOf 提供的转换规则。 */
const presetConfig = computed(() => (props.preset ? TREE_PRESETS[props.preset] : undefined))

/**
 * 最终采用的标题字段取法。
 *
 * <p>这里刻意把预设的 {@code labelOf} 包一层，而不是直接
 * {@code props.labelOf ?? presetConfig.value?.labelOf}：
 * 后者会产生一个"联合的调用签名"类型，调用处需要同时满足两种形参。
 * 包装成 {@code (row: T) => string} 后类型收敛到一处，
 * 且适配的方向是安全的（{@code T} 本来就是 {@code FlatTreeRow} 的子类型）。
 */
const resolvedLabelOf = computed<((row: T) => string) | undefined>(() => {
  if (props.labelOf) {
    return props.labelOf
  }
  const presetLabelOf = presetConfig.value?.labelOf
  if (!presetLabelOf) {
    return undefined
  }
  return (row: T) => presetLabelOf(row)
})

/**
 * 由扁平行建出的树。
 *
 * <p>{@code rows} 给了但拿不到 {@code labelOf} 时不去猜字段名，
 * 而是返回空树并在控制台报一次 —— "猜"会得到一个全部叫「未命名节点」的树，
 * 看起来像数据有问题，而真实原因只是漏传了 preset。
 */
const rowsTree = computed<ProTreeNode[] | undefined>(() => {
  if (!props.rows) {
    return undefined
  }
  const labelOf = resolvedLabelOf.value
  if (!labelOf) {
    warnMissingLabelOf()
    return []
  }
  return flatToTree(props.rows, {
    labelOf,
    disabledOf: presetConfig.value?.disabledOf,
    rootValue: props.rootValue
  })
})

let warnedMissingLabelOf = false

function warnMissingLabelOf(): void {
  if (warnedMissingLabelOf) {
    return
  }
  warnedMissingLabelOf = true
  console.error(
    '[ProTree] 传入了 rows 但无法确定标题字段：请提供 preset（dept / menu）' +
      '或自定义 labelOf。当前树为空。'
  )
}

const treeData = computed(() => props.options ?? rowsTree.value ?? remote.value)

const loading = computed(() => props.loading || innerLoading.value)

/** 无数据且不在加载中 —— 才显示空态（加载中显示空态会闪一下「暂无数据」）。 */
const showEmpty = computed(() => !loading.value && treeData.value.length === 0)

// ---------------------------------------------------------------------
// 取数
// ---------------------------------------------------------------------

async function reload(): Promise<void> {
  if (!props.request) {
    return
  }
  innerLoading.value = true
  try {
    remote.value = await props.request()
    emit('loaded', remote.value)
  } catch (error) {
    // 与 ProTreeSelect 一致：不吞错、不假装成功。
    // 静默失败会让用户看到"树是空的"，而真实原因是接口挂了
    emit('error', error)
  } finally {
    innerLoading.value = false
  }
}

/**
 * 懒加载。
 *
 * <p>直接改写节点的 {@code children}（内核在展开时读它），
 * 而不是维护一份外部映射 —— 后者还要处理"重新加载后映射过期"。
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

// ---------------------------------------------------------------------
// 搜索
// ---------------------------------------------------------------------

/**
 * 搜索框输入 → 内核的 pattern。
 *
 * <p>组件只负责"把词交给内核"与"把词抛出去"，
 * 匹配规则由内核决定（它在匹配节点的同时会保留其祖先链，
 * 否则深层节点即使命中也会因为父级不可见而无法呈现）。
 */
function handlePatternInput(next: string): void {
  pattern.value = next
  emit('search', next)
}

// ---------------------------------------------------------------------
// 拖拽
// ---------------------------------------------------------------------

/**
 * 本次拖拽的源节点。
 *
 * <p>必须自己记一份：内核的 {@code allowDrop} 载荷里没有 {@code dragNode}
 * （见 {@link ProTreeDropInfo} 的说明）。
 */
const draggingNode = ref<ProTreeNode | null>(null)

function handleDragStart(info: { node: TreeOption }): void {
  draggingNode.value = info.node as unknown as ProTreeNode
}

function handleDragEnd(): void {
  draggingNode.value = null
}

/**
 * 内核的落点信息 → 对外的载荷。
 *
 * <p>一个函数同时服务 {@code onDrop} 与 {@code allowDrop}：
 * 前者的载荷自带 {@code dragNode}，后者没有 ——
 * 因此优先用内核给的、缺失时回退到 dragstart 记录的那份。
 * 两种入口的载荷形状因此完全一致，调用方不需要记"哪个回调有哪个字段"。
 */
function toDropInfo(info: {
  node: TreeOption
  dropPosition: 'before' | 'inside' | 'after'
  dragNode?: TreeOption
}): ProTreeDropInfo {
  const dragNode = info.dragNode
    ? (info.dragNode as unknown as ProTreeNode)
    : draggingNode.value

  return {
    // 断言说明：内核的 TreeOption 与本组件的 ProTreeNode 运行期是同一个对象，
    // 差别只在索引签名的声明方式上。这里做一次性还原，
    // 让对外的每个入口都只出现我们自己的类型
    dragNode,
    dropNode: info.node as unknown as ProTreeNode,
    position: info.dropPosition
  }
}

function handleDrop(info: {
  dragNode: TreeOption
  node: TreeOption
  dropPosition: 'before' | 'inside' | 'after'
}): void {
  emit('drop', toDropInfo(info))
}

/**
 * 落点校验。
 *
 * <p>形参刻意只声明内核 {@code AllowDrop} 里确实存在的那两项
 * （{@code node} / {@code dropPosition}）—— 多声明一个 {@code dragNode}
 * 会编译失败，因为内核压根不传它（这是实测踩出来的，不是推测）。
 * 被拖节点通过 {@code draggingNode} 补齐。
 */
function handleAllowDrop(info: {
  node: TreeOption
  dropPosition: 'before' | 'inside' | 'after'
}): boolean {
  if (!props.allowDrop) {
    return true
  }
  return props.allowDrop(toDropInfo(info))
}

// ---------------------------------------------------------------------
// 值的读写：把内核的两套 key 收敛成一个 value
// ---------------------------------------------------------------------

function toKeyArray(value: string | number | Array<string | number> | null | undefined) {
  if (value === null || value === undefined) {
    return []
  }
  return Array.isArray(value) ? value : [value]
}

/** 单选模式下取第一个；多选/勾选模式下取数组。 */
function handleUpdateKeys(keys: Array<string | number>): void {
  if (props.checkable || props.multiple) {
    emit('update:value', keys)
    return
  }
  // 单选：内核传回的是数组，但业务语义是"一个值"。
  // 若原样把数组抛出去，调用方绑定到 string 字段上就会类型不符
  emit('update:value', keys.length > 0 ? keys[0]! : null)
}

const selectedKeys = computed(() => toKeyArray(props.value))
const checkedKeys = computed(() =>
  props.checkable ? toKeyArray(props.value) : ([] as Array<string | number>)
)

onMounted(async () => {
  if (props.request && !props.options && !props.rows) {
    await reload()
  }
})

defineExpose({ reload })
</script>

<template>
  <div class="pro-tree">
    <n-input
      v-if="searchable"
      :value="pattern"
      :placeholder="searchPlaceholder"
      clearable
      size="small"
      class="pro-tree__search"
      @update:value="handlePatternInput"
    />

    <n-spin :show="loading">
      <n-empty v-if="showEmpty" :description="emptyText" class="pro-tree__empty" />

      <n-tree
        v-else
        block-line
        :data="treeData"
        :pattern="pattern"
        :selectable="!checkable"
        :checkable="checkable"
        :checked-keys="checkedKeys"
        :selected-keys="selectedKeys"
        :check-strategy="checkStrategy"
        :multiple="checkable || multiple"
        :draggable="draggable"
        :default-expand-all="defaultExpandAll"
        :max-height="maxHeight"
        key-field="key"
        label-field="label"
        children-field="children"
        :on-load="handleLoad"
        :on-drop="handleDrop"
        :on-dragstart="handleDragStart"
        :on-dragend="handleDragEnd"
        :allow-drop="draggable ? handleAllowDrop : undefined"
        @update:selected-keys="handleUpdateKeys"
        @update:checked-keys="handleUpdateKeys"
      />
    </n-spin>
  </div>
</template>

<style scoped>
/* 尺寸全部引用 Token，无硬编码（ui-component-policy 强行约束第 5 条） */
.pro-tree__search {
  margin-bottom: var(--wa-spacing-sm);
}

.pro-tree__empty {
  padding: var(--wa-spacing-xl) 0;
}
</style>
