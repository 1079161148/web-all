<script setup lang="ts" generic="T extends object">
import { computed, getCurrentInstance, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch, type Component } from 'vue'
import { NButton, NPopconfirm, NSpace, NTooltip } from 'naive-ui'
import { ensureVxe } from '../../adapters/lazy'
import type { VxeGridConstructor, VxeGridProps } from '../../adapters/vxe'
import AppSkeleton from '../state/AppSkeleton.vue'
import { hasPermission } from '../../registry/permission'
import type {
  ProBatchAction,
  ProColumn,
  ProRowAction,
  ProTableExpose,
  ProTablePreference,
  ProTablePersistence,
  ProTableQuery,
  ProTableRequest,
  ProToolbarKey
} from '../../types'
import { ProCell } from './ProCell'
import ProSearch from './ProSearch.vue'

/**
 * 数据表格（vxe-grid 内核）。
 *
 * <h3>目标：一个页面只写「列定义 + 请求函数」</h3>
 * <pre>{@code
 * const columns: ProColumn<User>[] = [
 *   { key: 'username', title: '账号', search: 'input' },
 *   { key: 'status', title: '状态', dict: 'sys_user_status', search: 'select' },
 *   { key: 'createTime', title: '创建时间', render: 'datetime' }
 * ]
 * <ProTable :columns="columns" :request="fetchUserPage" :row-actions="actions" />
 * }</pre>
 *
 * <h3>内核是 vxe-grid，不是 n-data-table</h3>
 * 按 {@code ui-component-policy} 的选型矩阵，复杂表格的内核是 vxe-table。
 * 我们封装的是<b>约定与集成</b>：搜索区、工具栏、分页契约、字典翻译、权限、
 * 状态矩阵。<b>本文件里不应出现"算法"</b> —— 虚拟滚动、列拖拽、树形展开、
 * 单元格编辑全部由 vxe 提供，一行都不重写。
 *
 * <h3>三个容易做错、这里刻意做对的点</h3>
 * <ol>
 *   <li><b>不用 vxe 的 proxyConfig，也不开 vxe 内建的 refresh</b>：
 *       内建 refresh 走 {@code reloadData()}，只对 proxy 模式有意义。
 *       我们的数据来自调用方传入的 {@code request}，因此刷新按钮必须调我们自己的
 *       {@code load()}。<b>混用会出现"点刷新没反应"</b>（它刷的是 vxe 自己的数据源）</li>
 *   <li><b>排序必须设 {@code remote: true}</b>：否则 vxe 会<b>只对当前页</b>排序 ——
 *       用户看到"按创建时间排序"却只在 20 条里排序，是一个会得出错误结论的假象</li>
 *   <li><b>不在组件内弹错误提示</b>：提示方式（message / notification / 静默）
 *       是应用级决策。组件擅自弹窗，会在调用方自己也处理了错误时出现双重提示</li>
 * </ol>
 */

const props = withDefaults(
  defineProps<{
    /** 列定义。搜索区由列上的 `search` 声明自动派生。 */
    columns: ProColumn<T>[]
    /** 数据请求函数。组件不关心它内部用什么请求库。 */
    request: ProTableRequest<T>
    /** 工具栏动作。 */
    toolbar?: ProToolbarKey[]
    /**
     * 行操作。
     *
     * <p>⚠️ 刻意<b>不</b>给含泛型参数的 props 提供 {@code withDefaults} 默认值。
     * 原因：{@code () => []} 的默认值会让该 prop 的类型变成
     * {@code ProRowAction<T>[] | never[]}，而 Vue 的泛型组件推断遇到这种联合
     * 会放弃推断、退回泛型约束 —— 结果是页面侧 {@code T} 被解析成
     * {@code Record<string, unknown>}，报出"request 类型不匹配"这种
     * <b>完全指不到真因</b>的错误。默认值改在下方用 ?? 处理。
     */
    rowActions?: ProRowAction<T>[]
    /** 批量操作。声明后自动出现多选列与批量操作区。默认值同样见上方说明。 */
    batchActions?: ProBatchAction<T>[]
    /**
     * 选择模式。
     *
     * <ul>
     *   <li>{@code 'multiple'}（默认）—— 复选。选择列在<b>声明了 batchActions、
     *       或开启 reserveSelection、或传了 checkedKeys</b> 时出现
     *       （多选列由"要用它"派生，而不是一个永远显示的空白列）</li>
     *   <li>{@code 'single'} —— 单选（radio 列）。用于"选中一条做后续动作"，
     *       如从模板库挑一个模板</li>
     *   <li>{@code 'none'} —— 不显示选择列</li>
     * </ul>
     */
    selectionMode?: 'none' | 'multiple' | 'single'
    /**
     * 跨页保留选择。
     *
     * <p>翻页后已勾选的行不丢失（配合 {@code rowKey} 按主键记忆）。
     * 批量操作跨页时<b>必须</b>开启 —— 否则用户翻页后前面勾的会静默消失，
     * 而"已选 3 项"还显示着 3，点下去却只处理当前页，是典型的数据误伤。
     */
    reserveSelection?: boolean
    /**
     * 初始勾选 / 回选的主键集合（单向，不随用户操作回写）。
     *
     * <p>两种典型用法：<b>回选</b>（编辑时把已关联的记录勾上）与
     * <b>初始化</b>（列表加载后默认勾选满足条件的行）。每次数据加载后按主键应用一次；
     * 用户之后的勾选变化通过 {@code selection-change} 抛给页面，由页面持有状态。
     */
    checkedKeys?: Array<string | number>
    /** 行主键字段名。 */
    rowKey?: string
    /** 默认每页条数。 */
    defaultPageSize?: number
    /** 空状态操作按钮文案；为空则不显示按钮。 */
    emptyActionText?: string
    /** 空状态文案。 */
    emptyText?: string
    /** 是否在挂载时立即加载。 */
    immediate?: boolean
    /** 表格高度；不传则由内容撑开。 */
    height?: number | string
    /**
     * 是否显示搜索区。
     *
     * <p>默认 `'auto'`：<b>有列声明了 search 才显示</b>。
     * 做成自动判断而不是默认 true，是为了避免在无搜索需求的表格上方
     * 留一个空白的搜索条 —— 那看起来像加载失败。
     */
    showSearch?: boolean | 'auto'
    /** 搜索区默认折叠。 */
    searchDefaultCollapsed?: boolean
    /** 透传 vxe-grid 的其它配置（逃生舱，用于罕见需求）。 */
    gridProps?: Omit<VxeGridProps, 'columns' | 'data' | 'loading'>
    /**
     * 列布局持久化的键（配合 {@link persistence} 使用，两者都提供才启用）。
     * 建议形如 {@code table:user-list} —— 它会原样作为偏好键发往服务端。
     */
    persistenceKey?: string
    /**
     * 持久化适配器（依赖倒置：本包定义接口，应用提供实现 —— 通常是
     * 服务端偏好 API 的薄包装）。持久化的只有<b>布局</b>：列顺序、隐藏列、
     * 列宽、每页条数；数据相关的状态（排序、筛选、当前页）刻意不存 ——
     * 它们表达"这次在看什么"，不是"我喜欢怎么摆"。
     */
    persistence?: ProTablePersistence
  }>(),
  {
    toolbar: () => ['refresh', 'columnSetting', 'density'],
    rowKey: 'id',
    defaultPageSize: 20,
    emptyActionText: '',
    emptyText: '暂无数据',
    immediate: true,
    height: undefined,
    showSearch: 'auto',
    searchDefaultCollapsed: true,
    gridProps: undefined,
    persistenceKey: undefined,
    persistence: undefined
  }
)

const emit = defineEmits<{
  (e: 'create'): void
  (e: 'empty-action'): void
  (e: 'refresh'): void
  (e: 'loaded', payload: { total: number }): void
  /** 请求失败。**这是失败钩子** —— 组件不自行提示，由应用决定怎么呈现。 */
  (e: 'error', error: unknown): void
  (e: 'selection-change', rows: T[]): void
  (e: 'batch-action', payload: { key: string; rows: T[] }): void
  (e: 'row-click', row: T): void
}>()

const gridRef = ref<VxeGridConstructor | null>(null)

/**
 * 表格内核组件（异步加载）。
 *
 * <p>不静态 import {@code VxeGrid} 是刻意的：静态 import 会把
 * vxe 整包（约 200 KB gzip）绑进首屏 chunk，让登录页也下载它。
 * 改为运行时加载后，打包器把内核切到按需 chunk。
 * 详见 {@code adapters/lazy.ts}。
 */
const gridComponent = shallowRef<Component | null>(null)

/** 组件所在的应用实例，用于内核注册。 */
const app = getCurrentInstance()?.appContext.app

// ---------------------------------------------------------------------
// 状态
// ---------------------------------------------------------------------

const loading = ref(false)
/**
 * 行数据用 shallowRef：表格一次可能持有数百行，
 * 深度响应式代理这些对象的开销没有收益 —— 行数据整体替换才是唯一的变更方式。
 */
const rows = shallowRef<T[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(props.defaultPageSize)
const sortField = ref<string | undefined>(undefined)
const sortOrder = ref<'asc' | 'desc' | undefined>(undefined)
const searchModel = ref<Record<string, unknown>>({})
const selected = shallowRef<T[]>([])
const density = ref<'small' | 'medium' | 'large'>('small')

// ---------------------------------------------------------------------
// 搜索区：由列的 search 声明派生
// ---------------------------------------------------------------------

const searchItems = computed(() =>
  props.columns
    .filter((column) => Boolean(column.search))
    .map((column) => ({
      // 展示字段与查询参数不同名时以 searchParam 为准（见其注释）
      key: column.searchParam ?? column.key,
      label: column.title,
      type: column.search!,
      placeholder: column.searchPlaceholder,
      dict: column.dict,
      options: column.options
    }))
)

const searchVisible = computed(() =>
  props.showSearch === 'auto' ? searchItems.value.length > 0 : props.showSearch === true
)

// ---------------------------------------------------------------------
// 列映射：ProColumn → vxe 列配置
// ---------------------------------------------------------------------

/**
 * 该列是否需要走 VNode 渲染。
 *
 * <p>纯文本列交给 vxe 直接渲染（零 VNode 开销）。
 * 只有字典 / 自定义格式 / 自定义函数才走 {@link ProCell}。
 * <b>把"重"路径限制在真正需要的列上</b>，是表格性能的第一道闸门。
 */
function needsCellRender(column: ProColumn<T>): boolean {
  return Boolean(column.dict) || Boolean(column.renderFn) || Boolean(column.render)
}

const cellColumns = computed(() =>
  props.columns
    .filter((column) => hasPermission(column.permission))
    .map((column) => ({ key: column.key, column, slotName: `cell-${column.key}` }))
)

const slotColumns = computed(() =>
  cellColumns.value
    .filter((item) => needsCellRender(item.column))
    // 这里没有任何断言：ProColumn<T> 可直接赋给 ProCellColumn，
    // 因为 renderFn 的 T 处于逆变位置（详见 ProCellColumn 的说明）
    .map((item) => ({ slotName: item.slotName, column: item.column }))
)

const visibleRowActions = computed(() =>
  (props.rowActions ?? []).filter((action) => hasPermission(action.permission))
)

const visibleBatchActions = computed(() =>
  (props.batchActions ?? []).filter((action) => hasPermission(action.permission))
)

const selectable = computed(() => visibleBatchActions.value.length > 0)

/** 单选列：显式声明 selectionMode='single' 才出现。 */
const showRadio = computed(() => props.selectionMode === 'single')

/** 复选列：声明了批量操作、或需要跨页保留、或需要回选时才出现。 */
const showCheckbox = computed(
  () =>
    props.selectionMode === 'multiple' &&
    (selectable.value || props.reserveSelection === true || props.checkedKeys !== undefined)
)

const vxeColumns = computed<NonNullable<VxeGridProps['columns']>>(() => {
  const result: Record<string, unknown>[] = []

  if (showRadio.value) {
    result.push({ type: 'radio', width: 44, fixed: 'left', align: 'center' })
  } else if (showCheckbox.value) {
    result.push({ type: 'checkbox', width: 44, fixed: 'left', align: 'center' })
  }

  for (const item of cellColumns.value) {
    const column = item.column
    const config: Record<string, unknown> = {
      field: column.key,
      title: column.title,
      width: column.width,
      minWidth: column.minWidth,
      fixed: column.fixed,
      sortable: column.sortable === true,
      visible: column.hidden !== true,
      // 树表格的展开列（vxe treeNode 透传，见 ProColumn.treeNode 的说明）
      treeNode: column.treeNode === true
    }
    if (needsCellRender(column)) {
      // slots.default 映射到表格的具名插槽，插槽由本组件的模板动态提供
      config.slots = { default: item.slotName }
    }
    result.push(config)
  }

  if (visibleRowActions.value.length > 0) {
    result.push({
      field: '__actions',
      title: '操作',
      width: actionColumnWidth.value,
      fixed: 'right',
      slots: { default: 'row-actions' }
    })
  }

  return applyPreference(result) as NonNullable<VxeGridProps['columns']>
})

// ---------------------------------------------------------------------
// 列布局持久化
// ---------------------------------------------------------------------

/**
 * 已恢复的布局偏好。加载是异步的：null = 尚未加载完成（不应用任何偏好），
 * 加载完成后 vxeColumns 会自动重算 —— 列布局"跳一下"只发生在
 * 恢复的那一瞬间，且只发生一次。
 */
const prefState = ref<ProTablePreference | null>(null)

/** 布局变更 → 800ms 防抖保存。加载期间置 true，避免"恢复默认"被当成用户改动存回去。 */
let suppressPrefSave = false
let prefSaveTimer: number | null = null

const prefEnabled = computed(() =>
  Boolean(props.persistenceKey && props.persistence)
)

/** 把偏好应用回列数组（顺序 / 隐藏 / 宽度；操作列与勾选列 field 不在偏好里，天然免疫）。 */
function applyPreference(
  columns: Record<string, unknown>[]
): Record<string, unknown>[] {
  const pref = prefState.value
  if (!pref) {
    return columns
  }
  let list = columns
  if (pref.hidden && pref.hidden.length > 0) {
    const hidden = new Set(pref.hidden)
    list = list.filter((c) => typeof c.field !== 'string' || !hidden.has(c.field))
  }
  if (pref.order && pref.order.length > 0) {
    const rank = new Map(pref.order.map((field, index) => [field, index]))
    const indexed = list.map((column, original) => ({
      column,
      original,
      r: rank.get(String(column.field)) ?? Number.MAX_SAFE_INTEGER
    }))
    indexed.sort((a, b) => a.r - b.r || a.original - b.original)
    list = indexed.map((entry) => entry.column)
  }
  if (pref.widths) {
    list = list.map((column) => {
      const width = typeof column.field === 'string' ? pref.widths?.[column.field] : undefined
      return width ? { ...column, width } : column
    })
  }
  return list
}

/** 从内核读出当前布局（vxe 内建列设置与列宽拖拽的结果都在这里）。 */
function collectPreference(): ProTablePreference {
  const pref: ProTablePreference = {}
  const grid = gridRef.value as unknown as {
    getTableColumn?: () => {
      collectColumn?: Array<{ field?: string; width?: number | string; visible?: boolean }>
    }
  } | null
  const columns = grid?.getTableColumn?.().collectColumn ?? []
  const order: string[] = []
  const hidden: string[] = []
  const widths: Record<string, number> = {}
  for (const column of columns) {
    if (typeof column.field !== 'string' || column.field === '') {
      continue
    }
    order.push(column.field)
    if (column.visible === false) {
      hidden.push(column.field)
    }
    if (typeof column.width === 'number') {
      widths[column.field] = column.width
    }
  }
  pref.order = order
  if (hidden.length > 0) {
    pref.hidden = hidden
  }
  if (Object.keys(widths).length > 0) {
    pref.widths = widths
  }
  if (size.value !== props.defaultPageSize) {
    pref.pageSize = size.value
  }
  return pref
}

function schedulePrefSave(): void {
  if (!prefEnabled.value || suppressPrefSave) {
    return
  }
  if (prefSaveTimer !== null) {
    window.clearTimeout(prefSaveTimer)
  }
  // 800ms：列宽拖动/列设置确认是连续动作，逐事件保存会把偏好接口打成高频写
  prefSaveTimer = window.setTimeout(() => {
    prefSaveTimer = null
    const persistence = props.persistence
    if (!props.persistenceKey || !persistence) {
      return
    }
    void persistence.save(props.persistenceKey, collectPreference()).catch(() => {
      // 保存失败静默：偏好丢了只损失布局，不该打断用户的操作流
    })
  }, 800)
}

watch(
  () => [props.persistenceKey, props.persistence] as const,
  async ([key, persistence]) => {
    if (!key || !persistence) {
      prefState.value = null
      return
    }
    try {
      const raw: unknown = await persistence.load(key)
      // 校验形状而不是直接信任：存储里的数据可能是旧版本写的
      const pref: ProTablePreference =
        raw && typeof raw === 'object' && !Array.isArray(raw)
          ? (raw as ProTablePreference)
          : {}
      suppressPrefSave = true
      prefState.value = pref
      if (typeof pref.pageSize === 'number' && pref.pageSize > 0) {
        size.value = pref.pageSize
        void load()
      }
      // 等本轮响应更新跑完再解除抑制
      window.setTimeout(() => {
        suppressPrefSave = false
      }, 0)
    } catch {
      // 加载失败 = 用默认布局，功能不受损
      prefState.value = {}
    }
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  if (prefSaveTimer !== null) {
    window.clearTimeout(prefSaveTimer)
    // 卸载前把未落盘的布局写掉（尽力而为）
    const persistence = props.persistence
    if (props.persistenceKey && persistence) {
      void persistence.save(props.persistenceKey, collectPreference()).catch(() => {})
    }
  }
})

/** 操作列宽度按按钮数量估算，避免固定宽度导致按钮换行。 */
const actionColumnWidth = computed(() => Math.max(120, visibleRowActions.value.length * 64))

// ---------------------------------------------------------------------
// 工具栏
// ---------------------------------------------------------------------

const toolbarConfig = computed<NonNullable<VxeGridProps['toolbarConfig']>>(() => ({
  // custom = 列设置。这是 vxe 内建能力，不重写（ui-component-policy 明确不做重写）
  custom: props.toolbar.includes('columnSetting'),
  // vxe 内建的 refresh 只对 proxyConfig 模式有效，我们用自己的按钮（见类注释第 1 点）
  refresh: false,
  zoom: false,
  slots: { buttons: 'toolbar-buttons', tools: 'toolbar-tools' }
}))

const DENSITY_ORDER: Array<'small' | 'medium' | 'large'> = ['small', 'medium', 'large']
const DENSITY_LABEL: Record<string, string> = { small: '紧凑', medium: '默认', large: '宽松' }

function cycleDensity(): void {
  const index = DENSITY_ORDER.indexOf(density.value)
  density.value = DENSITY_ORDER[(index + 1) % DENSITY_ORDER.length] as 'small' | 'medium' | 'large'
}

// ---------------------------------------------------------------------
// 数据加载
// ---------------------------------------------------------------------

function buildQuery(): ProTableQuery {
  const query: ProTableQuery = { page: page.value, size: size.value }
  if (sortField.value && sortOrder.value) {
    query.sortField = sortField.value
    query.sortOrder = sortOrder.value
  }
  for (const [key, value] of Object.entries(searchModel.value)) {
    // 空值不参与查询：空串与 null 会被后端当成"等于空"的条件，
    // 而用户的意图是"不限制这一项"
    if (value === undefined || value === null || value === '') {
      continue
    }
    query[key] = Array.isArray(value) && value.length === 0 ? undefined : value
  }
  return query
}

/**
 * 请求序号 —— 翻页竞态的唯一正确解。
 *
 * <h3>要防的是什么</h3>
 * 用户快速翻页（或连续改搜索条件）时，前一个请求可能<b>后到</b>：
 * 第 2 页的响应比第 3 页的慢，它到达时会把已显示的第 3 页数据覆盖掉 ——
 * 于是"页码显示 3、数据是第 2 页"，且无任何报错。
 *
 * <h3>为什么用序号而不是 AbortController</h3>
 * abort 只能省流量，<b>防不了竞态</b>：请求已经发出去了，取消不保证
 * 响应不回来（Promise 仍可能以结果 settle）。序号守卫是正确性地板 ——
 * 迟到的响应直接丢弃；rows/total/loading 的赋值全部只允许"最新那次请求"做。
 */
let loadSeq = 0

/**
 * 执行一次请求。
 *
 * <p>失败时不向上抛：错误通过 {@code error} 事件暴露。
 * 理由是"刷新表格"几乎总是发生在一次成功操作之后
 * （提交完刷新、删除完刷新），若这里抛错，每个调用点都要写 try/catch，
 * 而它们对失败的处理其实是同一件事 —— 由应用统一提示。
 */
async function load(): Promise<void> {
  const seq = ++loadSeq
  loading.value = true
  try {
    const result = await props.request(buildQuery())
    // 迟到的旧响应：期间用户又翻了一页/改了条件 —— 丢弃，不污染新状态
    if (seq !== loadSeq) {
      return
    }

    const records = result.records ?? []
    const resultTotal = result.total ?? 0

    // ⚠️ 空页回退：用户在最后一页删掉唯一一条后刷新（或数据被他人删除），
    // 此时页码已超出总页数，后端如实返回"空列表 + total 40" ——
    // 直接显示等于告诉用户"数据全没了"。正确动作是回退到最后一页再取一次。
    // 只回退一次（page ≤ lastPage 时不再递归），空结果不会死循环。
    if (records.length === 0 && resultTotal > 0 && page.value > 1) {
      const lastPage = Math.max(1, Math.ceil(resultTotal / size.value))
      if (page.value > lastPage) {
        page.value = lastPage
        // 递归调用会让 loadSeq 前进：外层的 finally 因此跳过 loading 清理，
        // 由递归请求自己负责 —— 不会出现" loading 提前熄灭"
        await load()
        return
      }
    }

    rows.value = records
    total.value = resultTotal
    // 以后端返回的分页信息为准（后端若做了页码纠正，这里同步）
    if (typeof result.page === 'number' && result.page > 0) {
      page.value = result.page
    }
    if (typeof result.size === 'number' && result.size > 0) {
      size.value = result.size
    }
    // 回选/初始化：仅在主键集合<b>发生变化</b>时应用（签名判重，见 keySignature 的说明）
    if (props.checkedKeys !== undefined) {
      const signature = keySignature(props.checkedKeys)
      if (signature !== appliedKeysSignature) {
        await nextTick()
        appliedKeysSignature = signature
        await setCheckedKeys(props.checkedKeys)
      }
    }
    // 跨页保留：把本页里属于"已保留集合"的行重新勾上，并让 selected 反映全部已选
    if (props.reserveSelection) {
      await nextTick()
      restoreReservedSelection()
      selected.value = [...reservedSelection.value.values()]
    }
    emit('loaded', { total: total.value })
  } catch (error) {
    // 旧请求的失败同样要丢弃：否则"翻页后旧请求超时"会把新页面的数据清空
    if (seq !== loadSeq) {
      return
    }
    // 失败时清空数据而不是保留上一次的结果：
    // 留着旧数据会让用户以为"刷新成功、数据就是这么少"
    rows.value = []
    total.value = 0
    emit('error', error)
  } finally {
    // 只有"仍是最新请求"才有资格熄灭 loading：
    // 否则迟到的旧请求会把新请求的 loading 提前关掉，表格呈现"假完成"态
    if (seq === loadSeq) {
      loading.value = false
    }
  }
}

/**
 * 刷新：**保持当前页**重新请求。
 *
 * <p>对应"编辑完保存后刷新"的语义 —— 用户在第 3 页改了一条数据，
 * 刷新完跳回第 1 页是会被骂的。
 */
async function refresh(): Promise<void> {
  await load()
  emit('refresh')
}

/**
 * 重载：**回到第一页**重新请求。
 *
 * <p>对应"新增/搜索/筛选条件变化"的语义 —— 条件变了，当前页很可能
 * 已不存在（total 变小），留在原页只会看到空列表。
 */
async function reload(): Promise<void> {
  page.value = 1
  await load()
  emit('refresh')
}

// ---------------------------------------------------------------------
// 事件处理
// ---------------------------------------------------------------------

function handlePageChange(params: { currentPage: number; pageSize: number }): void {
  page.value = params.currentPage
  const sizeChanged = size.value !== params.pageSize
  size.value = params.pageSize
  if (sizeChanged) {
    // 每页条数属于用户布局偏好，一并持久化
    schedulePrefSave()
  }
  void load()
}

function handleSortChange(params: { field: string; order: string | null }): void {
  if (!params.order) {
    sortField.value = undefined
    sortOrder.value = undefined
  } else {
    sortField.value = params.field
    sortOrder.value = params.order === 'asc' ? 'asc' : 'desc'
  }
  // 排序变化必须回到第一页：停留在第 5 页看"按时间倒序"是没有意义的
  page.value = 1
  void load()
}

/**
 * 跨页保留的选择（按主键记住行对象）。
 *
 * <h3>为什么不直接用 vxe 的 checkbox-config.reserve</h3>
 * 实测 vxe-table 4.21 <b>没有</b>读取"保留选中"的公开 API
 * （{@code getCheckboxReserveRecords} 在该版本不存在）：内核能记住勾选态，
 * 但外部既读不到、也无法据此执行批量操作 —— 表现是"翻页后『已选 N 项』归零，
 * 批量按钮作用于空集合"。
 *
 * <p>因此这里由组件自己维护一份"主键 → 行"的表：当前页的勾选以内核为准，
 * 跨页的由本表补齐。这样"已选 N 项"与批量操作的范围始终一致。
 */
const reservedSelection = shallowRef<Map<string | number, T>>(new Map())

/** 取行主键（跨页识别行的唯一依据）。 */
function rowKeyOf(row: T): string | number {
  return (row as Record<string, unknown>)[props.rowKey] as string | number
}

function syncSelection(params?: { row?: T }): void {
  // 单选：内核只维护"当前选中行"，事件载荷直接给出该行
  if (props.selectionMode === 'single') {
    const row = (params?.row ?? gridRef.value?.getRadioRecord?.()) as T | undefined
    const next = row ? [row] : []
    selected.value = next
    emit('selection-change', next)
    return
  }
  void Promise.resolve(gridRef.value?.getCheckboxRecords?.() ?? []).then((records: T[]) => {
    if (!props.reserveSelection) {
      selected.value = records
      emit('selection-change', records)
      return
    }
    // 跨页保留：当前页的勾选以内核为准（先删本页旧状态，再加回勾上的），
    // 其它页的行留在表里不动
    const next = new Map(reservedSelection.value)
    for (const row of rows.value as T[]) {
      next.delete(rowKeyOf(row))
    }
    for (const row of records) {
      next.set(rowKeyOf(row), row)
    }
    reservedSelection.value = next
    selected.value = [...next.values()]
    emit('selection-change', selected.value)
  })
}

function clearSelection(): void {
  const grid = gridRef.value as { clearCheckboxRow?: () => void; clearRadioRow?: () => void } | null
  grid?.clearCheckboxRow?.()
  grid?.clearRadioRow?.()
  reservedSelection.value = new Map()
  selected.value = []
  emit('selection-change', [])
}

/**
 * 按主键设置勾选（回选 / 初始化）。
 *
 * <p>先清再勾：语义是"选择集就是这些"，而不是"在现有选择上追加"。
 * 开启 {@code reserveSelection} 时，内核会把勾选记在保留集合里，
 * 因此这里设置的行即使当前页看不到，也会在翻回来时仍是勾选态。
 */
async function setCheckedKeys(keys: Array<string | number>): Promise<void> {
  const grid = gridRef.value as {
    setCheckboxRow?: (rows: T[], checked: boolean) => void
    setRadioRow?: (row: T | null) => void
    clearCheckboxRow?: () => void
  } | null
  if (!grid) {
    return
  }
  const wanted = new Set(keys)
  const targets = (rows.value as T[]).filter((row) =>
    wanted.has((row as Record<string, unknown>)[props.rowKey] as string | number)
  )

  if (props.selectionMode === 'single') {
    const first = targets[0] ?? null
    grid.setRadioRow?.(first)
    selected.value = first ? [first] : []
  } else {
    grid.clearCheckboxRow?.()
    if (targets.length > 0) {
      grid.setCheckboxRow?.(targets, true)
    }
    if (props.reserveSelection) {
      // 回选/初始化也要进入"保留集合"，否则翻页后它会丢
      const next = new Map(reservedSelection.value)
      for (const row of rows.value as T[]) {
        next.delete(rowKeyOf(row))
      }
      for (const row of targets) {
        next.set(rowKeyOf(row), row)
      }
      reservedSelection.value = next
      selected.value = [...next.values()]
    } else {
      selected.value = targets
    }
  }
  emit('selection-change', selected.value)
}

/** 把当前页中"已保留"的行重新勾上（数据替换后内核会清掉勾选态）。 */
function restoreReservedSelection(): void {
  if (reservedSelection.value.size === 0) {
    return
  }
  const grid = gridRef.value as { setCheckboxRow?: (rows: T[], checked: boolean) => void } | null
  const targets = (rows.value as T[]).filter((row) => reservedSelection.value.has(rowKeyOf(row)))
  if (targets.length > 0) {
    grid?.setCheckboxRow?.(targets, true)
  }
}

/**
 * 已应用的回选主键签名。
 *
 * <p>用于判断"是否需要重新应用"：页面常用 {@code computed} 从当前页数据推导
 * {@code checkedKeys}（如"默认勾选进行中的记录"），每次翻页都会产生一个新数组 ——
 * 只比较引用会<b>每次加载都重置用户的勾选</b>。比较内容才能区分
 * "主键集合真的变了"与"只是重新算了一遍"。
 */
let appliedKeysSignature: string | null = null

function keySignature(keys: Array<string | number>): string {
  return [...keys].map(String).sort().join(',')
}

/**
 * 回选主键变化时重新应用。
 *
 * <p>页面通常是"先渲染表格、后异步拿到已关联的主键"（详情接口比列表慢），
 * 只靠加载时应用一次会漏掉这种时序。
 */
watch(
  () => props.checkedKeys,
  (keys) => {
    if (keys === undefined) {
      appliedKeysSignature = null
      return
    }
    // 数据还没到位就先不应用、也不记签名：
    // 页面的 checkedKeys 常由"当前页数据"派生，它会在请求返回的瞬间变化，
    // 而此时 ProTable 的 rows 还没赋值 —— 那时应用等于对空表格设置勾选，
    // 却把签名记成"已应用"，后续真正的加载就会跳过。交给加载路径处理。
    if (rows.value.length === 0) {
      return
    }
    const signature = keySignature(keys)
    if (signature === appliedKeysSignature) {
      return
    }
    appliedKeysSignature = signature
    void setCheckedKeys(keys)
  }
)

async function handleBatchAction(action: ProBatchAction<T>): Promise<void> {
  const current = selected.value
  if (current.length === 0) {
    return
  }
  const confirmText =
    typeof action.confirm === 'function' ? action.confirm(current) : action.confirm
  if (confirmText) {
    // 二次确认里补上数量：批量删除 1 条和 200 条的风险完全不同，
    // 而文案通常只写了"确定删除吗"
    const withCount = current.length > 1 ? `${confirmText}（共 ${current.length} 项）` : confirmText
    const { feedback } = await import('../../adapters/feedback')
    const confirmed = await feedback.confirm({
      title: '确认操作',
      content: withCount,
      positiveText: '确定'
    })
    if (!confirmed) {
      return
    }
  }
  emit('batch-action', { key: action.key, rows: current })
  await action.onClick(current)
}

function isRowActionDisabled(action: ProRowAction<T>, row: T): boolean {
  if (typeof action.disabled === 'function') {
    return action.disabled(row)
  }
  return action.disabled === true
}

/** 行操作文案：静态字符串或按行求值的函数（状态开关类操作用得上）。 */
function rowActionLabel(action: ProRowAction<T>, row: T): string {
  return typeof action.label === 'function' ? action.label(row) : action.label
}

async function handleRowAction(action: ProRowAction<T>, row: T): Promise<void> {
  if (isRowActionDisabled(action, row)) {
    return
  }
  const confirmText = typeof action.confirm === 'function' ? action.confirm(row) : action.confirm
  if (confirmText) {
    const { feedback } = await import('../../adapters/feedback')
    const confirmed = action.danger
      ? await feedback.confirmDanger('确认操作', confirmText)
      : await feedback.confirm({ title: '确认操作', content: confirmText })
    if (!confirmed) {
      return
    }
  }
  await action.onClick(row)
}

function handleSearch(): void {
  page.value = 1
  void load()
}

function handleCreate(): void {
  emit('create')
}

onMounted(async () => {
  // 数据加载与内核加载<b>并行</b>：两者互不依赖，
  // 串行会让首屏多等一个 RTT（内核是本地 chunk，数据是网络请求）
  if (props.immediate) {
    void load()
  }
  const mod = await ensureVxe(app)
  gridComponent.value = mod.VxeGrid as Component
})

defineExpose<ProTableExpose<T>>({
  reload,
  refresh,
  load,
  getSelection: () => selected.value,
  clearSelection,
  setCheckedKeys,
  getQuery: buildQuery
})
</script>

<template>
  <div class="pro-table">
    <ProSearch
      v-if="searchVisible"
      :items="searchItems"
      :model-value="searchModel"
      :loading="loading"
      :default-collapsed="searchDefaultCollapsed"
      @update:model-value="(value: Record<string, unknown>) => (searchModel = value)"
      @search="handleSearch"
      @reset="handleSearch"
    />

    <!--
      内核就绪前显示表格形状的骨架屏，而不是空白或转圈：
      骨架屏让"将要出现一个表格"这件事在视觉上已经成立，内容到位时不发生跳变。
    -->
    <AppSkeleton v-if="!gridComponent" variant="table" :rows="6" :cols="5" />

    <component
      :is="gridComponent"
      v-else
      ref="gridRef"
      v-bind="gridProps"
      :columns="vxeColumns"
      :data="rows"
      :loading="loading"
      :size="density"
      :height="height"
      :empty-text="emptyText"
      :row-config="{ keyField: rowKey }"
      :pager-config="{
        enabled: true,
        currentPage: page,
        pageSize: size,
        total,
        pageSizes: [10, 20, 50, 100],
        layouts: ['Total', 'PrevPage', 'JumpNumber', 'NextPage', 'Sizes']
      }"
      :toolbar-config="toolbarConfig"
      :checkbox-config="{ range: true, checkAll: true, highlight: true, reserve: reserveSelection }"
      :sort-config="{ remote: true, trigger: 'cell' }"
      @page-change="handlePageChange"
      @sort-change="handleSortChange"
      @checkbox-change="syncSelection"
      @checkbox-all="syncSelection"
      @radio-change="syncSelection"
      @custom="schedulePrefSave"
      @resizable-change="schedulePrefSave"
      @cell-click="(params: { row: T }) => emit('row-click', params.row)"
    >
      <!-- 工具栏左侧：新增 + 批量操作 -->
      <template #toolbar-buttons>
        <n-space :size="8" :wrap="false" align="center">
          <n-button
            v-if="toolbar.includes('create')"
            type="primary"
            size="small"
            @click="handleCreate"
          >
            新增
          </n-button>

          <!--
            批量操作区：仅在勾选后出现。
            未勾选时整块不渲染，避免一排永远点不动的禁用按钮占地方。
          -->
          <template v-if="selected.length > 0">
            <span class="pro-table__selection-hint">已选 {{ selected.length }} 项</span>
            <n-popconfirm
              v-for="action in visibleBatchActions"
              :key="action.key"
              :negative-text="'取消'"
              :positive-text="'确定'"
              @positive-click="handleBatchAction(action)"
            >
              <template #trigger>
                <n-button size="small" :type="action.danger ? 'error' : 'default'" quaternary>
                  {{ action.label }}
                </n-button>
              </template>
              {{ typeof action.confirm === 'function' ? '确认执行该批量操作？' : (action.confirm ?? '确认执行该批量操作？') }}
            </n-popconfirm>
            <n-button size="small" text @click="clearSelection">取消选择</n-button>
          </template>

          <slot name="toolbar-extra" />
        </n-space>
      </template>

      <!-- 工具栏右侧：刷新 / 密度（列设置由 vxe 内建的 custom 工具提供） -->
      <template #toolbar-tools>
        <n-space :size="4" :wrap="false">
          <n-tooltip v-if="toolbar.includes('refresh')" trigger="hover">
            <template #trigger>
              <n-button size="small" quaternary :loading="loading" @click="refresh()">
                刷新
              </n-button>
            </template>
            重新加载当前页（不跳页）
          </n-tooltip>

          <n-tooltip v-if="toolbar.includes('density')" trigger="hover">
            <template #trigger>
              <n-button size="small" quaternary @click="cycleDensity">
                密度·{{ DENSITY_LABEL[density] }}
              </n-button>
            </template>
            切换行高密度
          </n-tooltip>
        </n-space>
      </template>

      <!--
        动态单元格插槽。
        只为"需要 VNode"的列生成（字典/自定义格式/自定义函数）；
        纯文本列由 vxe 直接渲染，不经过这里。
      -->
      <template
        v-for="item in slotColumns"
        :key="item.slotName"
        #[item.slotName]="{ row }"
      >
        <ProCell :column="item.column" :row="row" />
      </template>

      <!-- 行操作：用 span 而不是按钮组件（表格单元格内禁止放 Naive 组件） -->
      <template #row-actions="{ row }">
        <span class="pro-table__actions">
          <span
            v-for="action in visibleRowActions"
            :key="action.key"
            class="pro-table__action"
            :class="{
              'pro-table__action--danger': action.danger,
              'pro-table__action--disabled': isRowActionDisabled(action, row)
            }"
            @click="handleRowAction(action, row)"
          >
            {{ rowActionLabel(action, row) }}
          </span>
        </span>
      </template>

      <!-- 空状态：有操作时给一个明确的入口，而不是只有一行"暂无数据" -->
      <template #empty>
        <div class="pro-table__empty">
          <slot name="empty">
            <p class="pro-table__empty-text">{{ emptyText }}</p>
            <n-button
              v-if="emptyActionText"
              type="primary"
              size="small"
              @click="emit('empty-action')"
            >
              {{ emptyActionText }}
            </n-button>
          </slot>
        </div>
      </template>
    </component>
  </div>
</template>

<style scoped>
.pro-table {
  width: 100%;
}

.pro-table__selection-hint {
  font-size: var(--wa-font-size-sm);
  color: var(--wa-text-secondary);
}

.pro-table__actions {
  display: inline-flex;
  flex-wrap: wrap;
  gap: var(--wa-spacing-md);
}

/*
  行操作做成可点击的文本而不是按钮：
  单元格里的 Naive 按钮会产生实例开销（架构规范明确禁止），
  而视觉上"一排文字操作"在后管理端同样是清晰、且更常见的形式。
*/
.pro-table__action {
  color: var(--wa-color-primary);
  font-size: var(--wa-font-size-sm);
  cursor: pointer;
  user-select: none;
  transition: opacity var(--wa-motion-duration-fast, 0.1s) var(--wa-motion-ease-out, ease-out);
}

.pro-table__action:hover {
  opacity: 0.75;
}

.pro-table__action--danger {
  color: var(--wa-color-error);
}

.pro-table__action--disabled {
  color: var(--wa-text-disabled);
  cursor: not-allowed;
  pointer-events: none;
}

.pro-table__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--wa-spacing-md);
  padding: var(--wa-spacing-xxl) 0;
}

.pro-table__empty-text {
  margin: 0;
  color: var(--wa-text-disabled);
  font-size: var(--wa-font-size-md);
}

/* 进度条与轻量单元格样式（ProCell 内部产出的 class） */
:deep(.pro-cell-progress-wrap) {
  display: flex;
  align-items: center;
  gap: var(--wa-spacing-sm);
}

:deep(.pro-cell-progress__text) {
  flex: none;
  font-size: var(--wa-font-size-sm);
  color: var(--wa-text-secondary);
  font-variant-numeric: tabular-nums;
}

:deep(.pro-cell-progress) {
  display: block;
  width: 100%;
  max-width: 120px;
  height: 6px;
  border-radius: var(--wa-radius-full);
  background: var(--wa-bg-hover);
  overflow: hidden;
}

:deep(.pro-cell-progress__inner) {
  display: block;
  height: 100%;
  background: var(--wa-color-primary);
  border-radius: var(--wa-radius-full);
}

:deep(.pro-cell-link) {
  color: var(--wa-color-primary);
}

:deep(.pro-cell-tag) {
  padding: 0 var(--wa-spacing-sm);
  border-radius: var(--wa-radius-sm);
  background: var(--wa-bg-hover);
  font-size: var(--wa-font-size-xs);
}

:deep(.pro-cell-config-error) {
  color: var(--wa-color-error);
  font-size: var(--wa-font-size-xs);
}
</style>
