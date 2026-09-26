<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  AuthButton,
  PageContainer,
  ProExcel,
  ProModal,
  ProTable,
  TREE_PRESETS,
  feedback,
  flatToTree,
  hasPermission
} from '@admin/ui'
import type {
  ProBatchAction,
  ProColumn,
  ProExcelColumn,
  ProExcelExpose,
  ProExcelRowError,
  ProFormItem,
  ProRowAction,
  ProTableExpose
} from '@admin/ui'
import type { TaskRequest, TaskResponse } from '@admin/api'
import { useDict } from '@/composables/useDict'
import { loadDeptList } from '@/api/iam'
import {
  batchCreateSurveyTasksAction,
  createSurveyTaskAction,
  deleteSurveyTaskAction,
  fetchSurveyTaskPage,
  requireId,
  toDateString,
  updateSurveyTaskAction
} from '@/api/survey'

/**
 * 调研任务管理。
 *
 * <p>枚举字段（类型/优先级/状态）全部走<b>字典声明</b>渲染与筛选：
 * 页面里不出现"编码 → 中文"的映射表，新增枚举值时只改字典数据。
 * 唯一需要映射的是 Excel 导入导出（Excel 里给人看的是中文标签），
 * 那部分用 {@link useDict} 取一次字典自己翻译。
 */
type TaskRow = TaskResponse

const tableRef = ref<ProTableExpose<TaskRow> | null>(null)
const excelRef = ref<ProExcelExpose<TaskRow> | null>(null)
const currentRows = ref<TaskRow[]>([])

/**
 * 请求适配器：顺手留一份当前页数据。
 *
 * <p>ProTable 刻意不暴露行数据（它是数据管道的使用者而非持有者），
 * 而"导出当前页"需要行数据 —— 在适配器里存一份是最短的路径，
 * 也不破坏组件契约。
 */
const requestPage = async (query: Parameters<typeof fetchSurveyTaskPage>[0]) => {
  const result = await fetchSurveyTaskPage(query)
  currentRows.value = result.records
  return result
}

const dicts = useDict('srvy_task_type', 'srvy_priority', 'srvy_task_status')

/** 编码 → 中文（Excel 导出用）。 */
function toLabelMap(items: Array<{ label: string; value: string }>): Record<string, string> {
  return Object.fromEntries(items.map((item) => [item.value, item.label]))
}

const labelMaps = computed<Record<string, Record<string, string>>>(() => ({
  srvy_task_type: toLabelMap(dicts.srvy_task_type.value),
  srvy_priority: toLabelMap(dicts.srvy_priority.value),
  srvy_task_status: toLabelMap(dicts.srvy_task_status.value)
}))

// ---------------------------------------------------------------------
// 部门（负责部门列的回显 + 表单树选择）
// ---------------------------------------------------------------------

/**
 * 部门映射与树选项。
 *
 * <p>列表接口只返回 {@code deptId}（不为一个展示字段引入 JOIN），所以这里拉一次
 * 部门列表做 id → 名称回显。同一份数据也用于表单的 treeSelect ——
 * 与角色页"数据范围"是同一个数据源，避免两处口径不一致。
 */
const deptNameMap = ref<Record<number, string>>({})
const deptTreeOptions = ref<NonNullable<ProFormItem['options']>>([])

function deptNameOf(deptId?: number): string {
  if (!deptId) {
    return '-'
  }
  return deptNameMap.value[deptId] ?? String(deptId)
}

async function loadDepts(): Promise<void> {
  try {
    const depts = (await loadDeptList()) ?? []
    deptNameMap.value = Object.fromEntries(
      depts.filter((dept) => dept.id !== undefined).map((dept) => [Number(dept.id), dept.deptName ?? ''])
    )
    deptTreeOptions.value = flatToTree(depts, TREE_PRESETS.dept) as NonNullable<
      ProFormItem['options']
    >
  } catch {
    // 部门加载失败不该让任务列表不可用：此时该列退化为显示部门 ID
  }
}

onMounted(() => {
  void loadDepts()
})

const columns: ProColumn<TaskRow>[] = [
  { key: 'taskCode', title: '任务编码', minWidth: 170, search: 'input', searchPlaceholder: '模糊匹配' },
  { key: 'taskName', title: '任务名称', minWidth: 220, search: 'input', searchPlaceholder: '模糊匹配' },
  { key: 'taskType', title: '任务类型', width: 120, dict: 'srvy_task_type', search: 'select' },
  { key: 'priority', title: '优先级', width: 100, dict: 'srvy_priority', search: 'select' },
  { key: 'ownerName', title: '负责人', width: 110 },
  // 负责部门是行级数据权限在任务表上的过滤列，必须可见 ——
  // 否则用户看到"列表里少了几条"时无从判断是过滤还是真的没有
  { key: 'deptId', title: '负责部门', width: 130, renderFn: (row) => deptNameOf(row.deptId) },
  { key: 'startDate', title: '开始日期', width: 120 },
  { key: 'endDate', title: '结束日期', width: 120 },
  { key: 'progress', title: '进度', width: 130, render: 'progress' },
  { key: 'status', title: '状态', width: 110, dict: 'srvy_task_status', search: 'select' },
  { key: 'createTime', title: '创建时间', width: 170, render: 'datetime' }
]

const rowActions: ProRowAction<TaskRow>[] = [
  { key: 'edit', label: '编辑', permission: 'srvy:task:update', onClick: (row) => openForm(row) },
  {
    key: 'delete',
    label: '删除',
    permission: 'srvy:task:delete',
    danger: true,
    confirm: (row) => `确定删除任务「${row.taskName}」？其下的采集记录与报告不会级联删除。`,
    onClick: async (row) => {
      await deleteSurveyTaskAction(requireId(row))
      feedback.success('任务已删除')
      await tableRef.value?.refresh()
    }
  }
]

const batchActions: ProBatchAction<TaskRow>[] = [
  {
    key: 'batchDelete',
    label: '批量删除',
    permission: 'srvy:task:delete',
    danger: true,
    confirm: '确定删除所选任务？采集记录与报告不会级联删除。',
    onClick: async (rows) => {
      for (const row of rows) {
        await deleteSurveyTaskAction(requireId(row))
      }
      feedback.success(`已删除 ${rows.length} 个任务`)
      tableRef.value?.clearSelection()
      await tableRef.value?.refresh()
    }
  }
]

// ---------------------------------------------------------------------
// 表单
// ---------------------------------------------------------------------

const formVisible = ref(false)
const editingId = ref<number | null>(null)
const modalModel = ref<Record<string, unknown>>({})

/**
 * 表单项。
 *
 * <p>用 {@code computed} 而不是普通数组：负责部门的 treeSelect 选项是
 * <b>异步加载</b>的，普通数组会在脚本求值时就固化下来（拿到空选项），
 * 表现为"部门树点开是空的"且刷新才好不了。
 */
const formItems = computed<ProFormItem[]>(() => [
  {
    field: 'taskCode',
    title: '任务编码',
    required: true,
    placeholder: '如 TASK-NPS-2026Q4',
    tip: '字母开头，仅含字母、数字、下划线与连字符。采集记录与报告按它归档，创建后不建议修改'
  },
  { field: 'taskName', title: '任务名称', required: true, placeholder: '如 2026Q4 客户满意度调研' },
  { field: 'taskType', title: '任务类型', type: 'select', dict: 'srvy_task_type', value: 'SURVEY' },
  { field: 'priority', title: '优先级', type: 'select', dict: 'srvy_priority', value: 'MEDIUM' },
  { field: 'ownerName', title: '负责人', placeholder: '如 张调研' },
  {
    field: 'deptId',
    title: '负责部门',
    type: 'treeSelect',
    options: deptTreeOptions.value,
    tip: '决定该任务归属哪个部门（数据范围按它过滤）。留空则默认取创建人所在部门'
  },
  {
    field: 'startDate',
    title: '计划开始',
    type: 'date',
    // valueFormat 让控件直接输出 yyyy-MM-dd 字符串（与后端 LocalDate 对齐）
    props: { valueFormat: 'yyyy-MM-dd' }
  },
  { field: 'endDate', title: '计划结束', type: 'date', props: { valueFormat: 'yyyy-MM-dd' } },
  { field: 'progress', title: '进度(%)', type: 'number', value: 0, props: { min: 0, max: 100 } },
  { field: 'status', title: '任务状态', type: 'select', dict: 'srvy_task_status', value: 'PENDING' },
  { field: 'remark', title: '备注', type: 'textarea' }
])

function openForm(row: TaskRow | null): void {
  editingId.value = row ? requireId(row) : null
  modalModel.value = row
    ? {
        taskCode: row.taskCode ?? '',
        taskName: row.taskName ?? '',
        taskType: row.taskType ?? 'SURVEY',
        priority: row.priority ?? 'MEDIUM',
        ownerName: row.ownerName ?? '',
        deptId: row.deptId ?? null,
        startDate: row.startDate ?? null,
        endDate: row.endDate ?? null,
        progress: row.progress ?? 0,
        status: row.status ?? 'PENDING',
        remark: row.remark ?? ''
      }
    : {}
  formVisible.value = true
}

/** 显式构造 payload：后端需要哪些字段、日期怎么转换都在这一处可见。 */
function toPayload(values: Record<string, unknown>): TaskRequest {
  return {
    taskCode: String(values.taskCode ?? ''),
    taskName: String(values.taskName ?? ''),
    taskType: values.taskType ? String(values.taskType) : 'SURVEY',
    priority: values.priority ? String(values.priority) : 'MEDIUM',
    ownerName: values.ownerName ? String(values.ownerName) : undefined,
    // undefined = 不指定；新增时后端补默认值（创建人部门），修改时后端保留原值
    deptId: typeof values.deptId === 'number' ? values.deptId : undefined,
    startDate: toDateString(values.startDate),
    endDate: toDateString(values.endDate),
    progress: typeof values.progress === 'number' ? values.progress : 0,
    status: values.status ? String(values.status) : 'PENDING',
    remark: values.remark ? String(values.remark) : undefined
  }
}

async function submitForm(values: Record<string, unknown>): Promise<void> {
  const payload = toPayload(values)
  if (editingId.value !== null) {
    await updateSurveyTaskAction(editingId.value, payload)
  } else {
    await createSurveyTaskAction(payload)
  }
  feedback.success('保存成功')
}

// ---------------------------------------------------------------------
// 导入 / 导出
//
// 导出：枚举列用 format 转成中文（导出编码对用 Excel 的人没意义）；
// 导入：按<b>列标题</b>匹配（ProExcel 的能力），所以用户调整列顺序不会错位。
// ---------------------------------------------------------------------

const excelColumns: ProExcelColumn<TaskRow>[] = [
  { key: 'taskCode', title: '任务编码', required: true, width: 22 },
  { key: 'taskName', title: '任务名称', required: true, width: 28 },
  { key: 'taskType', title: '任务类型', width: 14, format: (row) => label('srvy_task_type', row.taskType) },
  { key: 'priority', title: '优先级', width: 10, format: (row) => label('srvy_priority', row.priority) },
  { key: 'ownerName', title: '负责人', width: 12 },
  { key: 'startDate', title: '开始日期', width: 14, validate: validateDate },
  { key: 'endDate', title: '结束日期', width: 14, validate: validateDate },
  { key: 'progress', title: '进度(%)', width: 10, validate: validateProgress },
  { key: 'status', title: '状态', width: 12, format: (row) => label('srvy_task_status', row.status) },
  { key: 'remark', title: '备注', width: 32 }
]

function label(dictType: string, value?: string): string {
  if (!value) return ''
  return labelMaps.value[dictType]?.[value] ?? value
}

function validateDate(value: unknown): string | null {
  if (value === undefined || value === null || value === '') return null
  return /^\d{4}-\d{2}-\d{2}$/.test(String(value)) ? null : '日期格式应为 yyyy-MM-dd'
}

function validateProgress(value: unknown): string | null {
  if (value === undefined || value === null || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) && n >= 0 && n <= 100 ? null : '进度应为 0~100 的数字'
}

/** 中文标签 → 编码（Excel 里用户更可能填"进行中"而不是 RUNNING）。 */
function normalizeEnum(dictType: string, value: unknown, fallback: string): string {
  if (value === undefined || value === null || value === '') return fallback
  const raw = String(value)
  const map = labelMaps.value[dictType]
  if (!map || map[raw]) return raw
  const hit = Object.entries(map).find(([, text]) => text === raw)
  return hit ? hit[0] : raw
}

async function handleImported(rows: TaskRow[]): Promise<void> {
  if (rows.length === 0) return
  const payload: TaskRequest[] = rows.map((row) => ({
    taskCode: String(row.taskCode ?? ''),
    taskName: String(row.taskName ?? ''),
    taskType: normalizeEnum('srvy_task_type', row.taskType, 'SURVEY'),
    priority: normalizeEnum('srvy_priority', row.priority, 'MEDIUM'),
    ownerName: row.ownerName ? String(row.ownerName) : undefined,
    startDate: toDateString(row.startDate),
    endDate: toDateString(row.endDate),
    progress: Number.isFinite(Number(row.progress)) ? Number(row.progress) : 0,
    status: normalizeEnum('srvy_task_status', row.status, 'PENDING'),
    remark: row.remark ? String(row.remark) : undefined
  }))
  try {
    const count = await batchCreateSurveyTasksAction(payload)
    feedback.success(`成功导入 ${count} 条任务`)
    await tableRef.value?.reload()
  } catch (error) {
    // 后端整批回滚并给出带行号的错误：落成"错误行文件"让用户改完重传
    const message = error instanceof Error ? error.message : '导入失败'
    feedback.error(message)
    const errors: ProExcelRowError<TaskRow>[] = [{ rowIndex: 1, message }]
    await excelRef.value?.downloadErrorRows(errors)
  }
}
</script>

<template>
  <PageContainer
    title="调研任务管理"
    description="一次调研的全生命周期；采集记录与报告都挂在任务下"
  >
    <template #extra>
      <ProExcel
        v-if="hasPermission('srvy:task:import')"
        ref="excelRef"
        :columns="excelColumns"
        file-name="调研任务"
        @imported="handleImported"
      />
      <AuthButton
        permission="srvy:task:create"
        mode="disable"
        type="primary"
        size="small"
        denied-text="你没有新增调研任务的权限"
        @click="openForm(null)"
      >
        新增任务
      </AuthButton>
    </template>

    <ProTable
      ref="tableRef"
      :columns="columns"
      :request="requestPage"
      :toolbar="['refresh', 'columnSetting', 'density']"
      :row-actions="rowActions"
      :batch-actions="batchActions"
      empty-action-text="创建第一个调研任务"
      @empty-action="openForm(null)"
    />
  </PageContainer>

  <ProModal
    v-model:visible="formVisible"
    :title="editingId !== null ? '编辑调研任务' : '新增调研任务'"
    :items="formItems"
    :model="modalModel"
    :cols="1"
    :submit="submitForm"
    :on-success="() => (editingId === null ? tableRef?.reload() : tableRef?.refresh())"
    @error="(error: unknown) => feedback.error(error instanceof Error ? error.message : '保存失败')"
  />
</template>
