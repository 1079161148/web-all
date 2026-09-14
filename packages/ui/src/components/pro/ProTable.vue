<script setup lang="ts" generic="T extends object = Record<string, unknown>">
import { computed, onMounted, reactive, ref, shallowRef } from 'vue'
import { NButton, NInput, NPagination, NSelect, NSpace } from 'naive-ui'
import type { AppStateStatus, ProColumn, ProRowAction, ProTableRequest, ProToolbarKey } from '../../types'
import AppState from '../state/AppState.vue'
import { feedback } from '../../adapters/feedback'

/**
 * ProTable —— 中台的核心组件。
 *
 * <h3>封装职责（设计文档 §11.4）</h3>
 * 内核能力（虚拟滚动 / 行列编辑 / 树形 / 导出）100% 来自 vxe-table；
 * 本组件只做「加约定」的部分：
 *  ① 列配置 → 表格列的映射
 *  ② 搜索区自动生成（由列的 `search` 声明驱动）
 *  ③ 分页 / 排序 → 与后端 PageResult 契约对齐
 *  ④ 工具栏标准动作
 *  ⑤ 行操作与权限
 *  ⑥ 状态矩阵（加载 / 空 / 错误）
 *  ⑦ 列显隐控制
 *
 * <h3>为什么用泛型组件</h3>
 * `ProColumn<T>` 的 `renderFn?: (row: T) => VNode` 处于**逆变位置**，
 * 因此 `ProColumn<TenantResponse>` 无法赋给 `ProColumn<Record<string, unknown>>`。
 * 用 `generic="T"` 让 Vue 从业务侧传入的 columns 推断行类型，
 * 业务代码才能既拿到完整类型提示、又不用写类型断言。
 *
 * <h3>明确不做</h3>
 * 不重写虚拟滚动、不重写编辑能力、不重写导出实现。
 * 判定标准：**封装层里不应该有算法**。核心逻辑目标 &lt; 800 行。
 *
 * <h3>⚠️ 当前表格内核状态（P0 骨架阶段的诚实说明）</h3>
 * 按设计文档 §11.4，表格内核应为 **vxe-table 4.x**。当前骨架阶段先用原生
 * `<table>` 实现渲染层，原因是：设计文档 §二十一 P0 验证第 2 项要求先实测
 * 「vxe-table × Naive UI 主题桥接成本」，第 3 项要求实测 CSS-in-JS 大规模渲染开销 ——
 * 在拿到数据之前接入内核，可能白做一遍。
 *
 * <p>这不影响业务代码：业务侧只依赖 ProTable 的 props 契约，
 * 内核替换被完全封装在本文件内（这正是「二次封装约定而非实现」的价值，设计文档 §1.2）。
 */

const props = withDefaults(
  defineProps<{
    /** 列定义。搜索区由列上的 `search` 声明自动生成。 */
    columns: ProColumn<T>[]
    /** 数据请求函数，签名与后端 PageResult 对齐。 */
    request: ProTableRequest<T>
    /** 工具栏动作。 */
    toolbar?: ProToolbarKey[]
    /** 行操作。 */
    rowActions?: ProRowAction<T>[]
    /** 行主键字段名。 */
    rowKey?: string
    /** 默认每页条数。 */
    defaultPageSize?: number
    /** 空状态操作按钮文案；为空则不显示按钮。 */
    emptyActionText?: string
    /** 是否在挂载时立即加载。 */
    immediate?: boolean
  }>(),
  {
    toolbar: () => ['refresh'],
    rowActions: () => [],
    rowKey: 'id',
    defaultPageSize: 20,
    emptyActionText: '',
    immediate: true
  }
)

const emit = defineEmits<{
  (e: 'create'): void
  (e: 'export'): void
  (e: 'loaded', payload: { total: number }): void
}>()

// ---------------------------------------------------------------------
// 状态
// ---------------------------------------------------------------------

/**
 * 行数据。
 *
 * 用 `shallowRef` 而非 `ref`，两个原因：
 * ① 类型：泛型 `T` 经 `ref` 会被深度解包成 `UnwrapRefSimple<T>`，导致与
 *    接收 `T` 的函数签名不匹配；
 * ② 性能：表格一次可能持有数百行数据，深度响应式代理这些对象的开销没有收益 ——
 *    行数据整体替换（重新请求）才是唯一的变更方式，不需要逐字段追踪。
 */
const records = shallowRef<T[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(props.defaultPageSize)
const sortField = ref<string | undefined>(undefined)
const sortOrder = ref<'asc' | 'desc' | undefined>(undefined)

const loading = ref(false)
const loadError = ref<string | undefined>(undefined)
const traceId = ref<string | undefined>(undefined)

/**
 * 搜索条件。
 *
 * 字段名与类型完全由运行时的列配置决定，编译期无法收敛，
 * 因此这里使用索引签名容器；对外的类型安全由 `ProColumn.search` 保证。
 */
const searchModel = reactive<Record<string, string | number | null | undefined>>({})

/** 用户通过「列设置」隐藏的列 key。 */
const hiddenKeys = ref<Set<string>>(new Set())

// ---------------------------------------------------------------------
// 派生
// ---------------------------------------------------------------------

/** 参与搜索的列。 */
const searchColumns = computed(() => props.columns.filter((col) => col.search))

/** 可在列设置中切换的列。 */
const configurableColumns = computed(() => props.columns.filter((col) => !col.disableColumnSetting))

/** 当前可见的列。 */
const visibleColumns = computed(() =>
  props.columns.filter((col) => !col.hidden && !hiddenKeys.value.has(col.key))
)

/** 列设置下拉的当前选中值（即未隐藏的列）。 */
const visibleColumnKeys = computed(() =>
  configurableColumns.value
    .filter((col) => !hiddenKeys.value.has(col.key))
    .map((col) => col.key)
)

const state = computed<AppStateStatus>(() => {
  if (loading.value) {
    return 'loading'
  }
  if (loadError.value) {
    return 'error'
  }
  if (records.value.length === 0) {
    return 'empty'
  }
  return 'ready'
})

/** 空状态描述：区分「真的没数据」与「筛选没结果」，后者应引导清空筛选。 */
const emptyDescription = computed(() => {
  const hasFilter = Object.values(searchModel).some(
    (value) => value !== undefined && value !== null && value !== ''
  )
  return hasFilter ? '没有符合条件的记录，可尝试清空筛选条件' : ''
})

// ---------------------------------------------------------------------
// 数据加载
// ---------------------------------------------------------------------

async function load(): Promise<void> {
  loading.value = true
  loadError.value = undefined
  traceId.value = undefined

  try {
    const result = await props.request({
      page: page.value,
      size: size.value,
      sortField: sortField.value,
      sortOrder: sortOrder.value,
      ...searchModel
    })

    records.value = result.records
    total.value = result.total
    emit('loaded', { total: result.total })
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '数据加载失败'
    // 后端返回的追踪码，便于用户报障时提供有效信息
    if (error instanceof Error && 'code' in error) {
      traceId.value = String((error as { code: unknown }).code)
    }
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 重新加载；resetPage 为 true 时回到第一页（搜索条件变更时使用）。 */
function reload(resetPage = true): void {
  if (resetPage) {
    page.value = 1
  }
  void load()
}

function handleSearch(): void {
  reload(true)
}

function handleResetSearch(): void {
  Object.keys(searchModel).forEach((key) => {
    searchModel[key] = undefined
  })
  reload(true)
}

function handlePageChange(nextPage: number): void {
  page.value = nextPage
  void load()
}

function handlePageSizeChange(nextSize: number): void {
  size.value = nextSize
  page.value = 1
  void load()
}

// ---------------------------------------------------------------------
// 列设置
// ---------------------------------------------------------------------

function handleColumnSettingChange(keys: string[]): void {
  hiddenKeys.value = new Set(
    configurableColumns.value.filter((col) => !keys.includes(col.key)).map((col) => col.key)
  )
}

// ---------------------------------------------------------------------
// 工具栏
// ---------------------------------------------------------------------

function hasToolbar(key: ProToolbarKey): boolean {
  return props.toolbar.includes(key)
}

// ---------------------------------------------------------------------
// 行操作
// ---------------------------------------------------------------------

/** 把行对象当作索引容器读取字段（列 key 由运行时配置决定）。 */
function cellOf(row: T, key: string): unknown {
  return (row as Record<string, unknown>)[key]
}

async function handleRowAction(action: ProRowAction<T>, row: T): Promise<void> {
  if (action.confirm) {
    const message = typeof action.confirm === 'function' ? action.confirm(row) : action.confirm
    const confirmed = action.danger
      ? await feedback.confirmDanger(action.label, message)
      : await feedback.confirm({ title: action.label, content: message })
    if (!confirmed) {
      return
    }
  }

  try {
    await action.onClick(row)
  } catch (error) {
    // 后端业务错误已由 ApiError 携带可读消息，这里只做兜底
    feedback.error(error instanceof Error ? error.message : '操作失败')
  }
}

function isRowActionDisabled(action: ProRowAction<T>, row: T): boolean {
  return typeof action.disabled === 'function' ? action.disabled(row) : Boolean(action.disabled)
}

// ---------------------------------------------------------------------
// 单元格渲染
// ---------------------------------------------------------------------

/** 渲染规则由列配置统一驱动，业务页面无需在模板里写 if-else 链。 */
function renderCellValue(column: ProColumn<T>, row: T): string {
  const value = cellOf(row, column.key)

  if (value === undefined || value === null || value === '') {
    return '-'
  }

  switch (column.render) {
    case 'datetime':
      return formatDateTime(String(value))
    case 'bytes':
      return formatBytes(Number(value))
    default:
      return String(value)
  }
}

function formatDateTime(input: string): string {
  const date = new Date(input)
  if (Number.isNaN(date.getTime())) {
    return input
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) {
    return '-'
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let unitIndex = 0
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024
    unitIndex += 1
  }
  return `${value.toFixed(unitIndex === 0 ? 0 : 1)} ${units[unitIndex]}`
}

function columnWidth(column: ProColumn<T>): string | undefined {
  if (column.width === undefined) {
    return undefined
  }
  return typeof column.width === 'number' ? `${column.width}px` : column.width
}

onMounted(() => {
  if (props.immediate) {
    void load()
  }
})

// 暴露给父组件，便于操作成功后刷新
defineExpose({ reload, load })
</script>

<template>
  <div class="pro-table">
    <!-- 搜索区：由列的 search 声明自动生成 -->
    <div v-if="searchColumns.length > 0" class="pro-table__search">
      <div class="pro-table__search-fields">
        <div v-for="col in searchColumns" :key="col.key" class="pro-table__search-field">
          <label class="pro-table__search-label">{{ col.title }}</label>
          <n-input
            v-if="!col.search || col.search === 'input' || col.search === 'textarea'"
            v-model:value="searchModel[col.key] as string"
            :placeholder="col.searchPlaceholder ?? `请输入${col.title}`"
            clearable
            size="small"
            @keyup.enter="handleSearch"
          />
          <n-select
            v-else
            v-model:value="searchModel[col.key] as string"
            :options="col.options ?? []"
            :placeholder="col.searchPlaceholder ?? `请选择${col.title}`"
            clearable
            size="small"
          />
        </div>
      </div>
      <n-space :size="8">
        <n-button size="small" type="primary" @click="handleSearch">查询</n-button>
        <n-button size="small" @click="handleResetSearch">重置</n-button>
      </n-space>
    </div>

    <!-- 工具栏 -->
    <div v-if="toolbar.length > 0" class="pro-table__toolbar">
      <n-space :size="8">
        <n-button v-if="hasToolbar('create')" size="small" type="primary" @click="emit('create')">
          新增
        </n-button>
        <n-button v-if="hasToolbar('export')" size="small" @click="emit('export')">导出</n-button>
        <n-button v-if="hasToolbar('refresh')" size="small" :loading="loading" @click="reload(false)">
          刷新
        </n-button>
      </n-space>

      <n-space v-if="hasToolbar('columnSetting') && configurableColumns.length > 0" :size="8">
        <n-select
          size="small"
          multiple
          :value="visibleColumnKeys"
          :options="configurableColumns.map((c) => ({ label: c.title, value: c.key }))"
          placeholder="显示列"
          style="min-width: 180px"
          @update:value="handleColumnSettingChange"
        />
      </n-space>
    </div>

    <!-- 状态矩阵 + 表格 -->
    <AppState
      :status="state"
      :error-message="loadError"
      :trace-id="traceId"
      :empty-description="emptyDescription"
      :empty-action-text="emptyActionText"
      @retry="reload(false)"
      @empty-action="emit('create')"
    >
      <div class="pro-table__wrapper">
        <table class="pro-table__table">
          <thead>
            <tr>
              <th v-for="col in visibleColumns" :key="col.key" :style="{ width: columnWidth(col) }">
                {{ col.title }}
              </th>
              <th v-if="rowActions.length > 0" class="pro-table__actions-header">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in records" :key="String(cellOf(row, rowKey))">
              <td v-for="col in visibleColumns" :key="col.key">
                <slot :name="col.key" :row="row" :value="cellOf(row, col.key)">
                  {{ renderCellValue(col, row) }}
                </slot>
              </td>
              <td v-if="rowActions.length > 0" class="pro-table__actions">
                <button
                  v-for="action in rowActions"
                  :key="action.key"
                  type="button"
                  class="pro-table__action"
                  :class="{ 'pro-table__action--danger': action.danger }"
                  :disabled="isRowActionDisabled(action, row)"
                  @click="handleRowAction(action, row)"
                >
                  {{ action.label }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>

        <div class="pro-table__footer">
          <n-pagination
            :page="page"
            :page-size="size"
            :item-count="total"
            :page-sizes="[10, 20, 50, 100]"
            show-size-picker
            show-quick-jumper
            @update:page="handlePageChange"
            @update:page-size="handlePageSizeChange"
          />
        </div>
      </div>
    </AppState>
  </div>
</template>

<style scoped>
.pro-table {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-md, 12px);
}

.pro-table__search,
.pro-table__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--wa-spacing-md, 12px);
  padding: var(--wa-spacing-md, 12px);
  border-radius: var(--wa-radius-lg, 8px);
  background: var(--wa-bg-elevated, #fff);
}

.pro-table__search-fields {
  display: flex;
  flex-wrap: wrap;
  gap: var(--wa-spacing-md, 12px);
}

.pro-table__search-field {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-xs, 4px);
  min-width: 180px;
}

.pro-table__search-label {
  font-size: var(--wa-font-size-xs, 12px);
  color: var(--wa-text-secondary, #5c6570);
}

.pro-table__wrapper {
  border-radius: var(--wa-radius-lg, 8px);
  background: var(--wa-bg-elevated, #fff);
  overflow-x: auto;
}

.pro-table__table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--wa-font-size-md, 14px);
}

.pro-table__table th,
.pro-table__table td {
  padding: var(--wa-spacing-sm, 8px) var(--wa-spacing-md, 12px);
  text-align: left;
  border-bottom: 1px solid var(--wa-divider, #ebeef5);
}

.pro-table__table th {
  font-weight: 500;
  color: var(--wa-text-secondary, #5c6570);
  background: var(--wa-bg-hover, #f0f2f5);
  white-space: nowrap;
}

.pro-table__actions-header {
  text-align: right;
}

.pro-table__actions {
  text-align: right;
  white-space: nowrap;
}

.pro-table__action {
  padding: 0;
  margin-left: var(--wa-spacing-md, 12px);
  border: none;
  background: transparent;
  color: var(--wa-color-primary, #2563eb);
  font-size: var(--wa-font-size-md, 14px);
  cursor: pointer;
}

.pro-table__action:disabled {
  color: var(--wa-text-disabled, #a8b0ba);
  cursor: not-allowed;
}

.pro-table__action--danger {
  color: var(--wa-color-error, #dc2626);
}

.pro-table__footer {
  display: flex;
  justify-content: flex-end;
  padding: var(--wa-spacing-md, 12px);
}
</style>
