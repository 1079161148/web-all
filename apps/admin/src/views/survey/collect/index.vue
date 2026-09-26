<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  AuthButton,
  PageContainer,
  ProExcel,
  ProForm,
  ProModal,
  ProTable,
  ProUpload,
  feedback,
  hasPermission
} from '@admin/ui'
import type {
  ProBatchAction,
  ProColumn,
  ProExcelColumn,
  ProExcelExpose,
  ProExcelRowError,
  ProFormExpose,
  ProFormItem,
  ProRowAction,
  ProTableExpose
} from '@admin/ui'
import type { CollectRequest, CollectResponse, TaskOption } from '@admin/api'
import { useDict } from '@/composables/useDict'
import {
  FILE_BIZ_TYPE,
  batchCreateSurveyCollectsAction,
  createSurveyCollectAction,
  deleteSurveyCollectAction,
  downloadSurveyFile,
  fetchSurveyCollectPage,
  loadSurveyTaskOptions,
  requireId,
  toDateString,
  updateSurveyCollectAction,
  uploadSurveyFileAction
} from '@/api/survey'

/**
 * 数据采集。
 *
 * <p>这个页面同时覆盖四类表格能力，是"列表页能力清单"的参考实现：
 * <ol>
 *   <li><b>分页勾选</b>：声明 batchActions 即出现多选列</li>
 *   <li><b>跨页保留</b>：reserveSelection，翻页后已勾选的行不丢</li>
 *   <li><b>初始化勾选</b>：checkedKeys 默认勾选"采集中"的记录，
 *       因为它们才是最需要跟进的；用户改过之后不会被重置（按内容判重）</li>
 *   <li><b>枚举列</b>：渠道 / 状态走字典渲染与字典筛选，页面无映射表</li>
 * </ol>
 */
type CollectRow = CollectResponse

const tableRef = ref<ProTableExpose<CollectRow> | null>(null)
const excelRef = ref<ProExcelExpose<CollectRow> | null>(null)
const formRef = ref<ProFormExpose | null>(null)

const currentRows = ref<CollectRow[]>([])
const taskOptions = ref<TaskOption[]>([])

const requestPage = async (query: Parameters<typeof fetchSurveyCollectPage>[0]) => {
  const result = await fetchSurveyCollectPage(query)
  currentRows.value = result.records
  captureInitialSelection(result.records)
  return result
}

/**
 * 初始化勾选：首次加载后默认选中"采集中"的记录。
 *
 * <p>⚠️ 刻意<b>只算一次</b>（首次数据到位时），而不是每次都由当前页数据派生：
 * 后者在翻页时会得到不同的集合，触发 ProTable 重新应用回选，
 * <b>把用户在上一页勾的、以及跨页保留的选择一起清掉</b> ——
 * "初始化"的语义本来就是开个头，之后由用户与 reserveSelection 接管。
 */
const initialCheckedKeys = ref<Array<string | number> | undefined>(undefined)

const checkedKeys = computed(() => initialCheckedKeys.value)

function captureInitialSelection(records: CollectRow[]): void {
  if (initialCheckedKeys.value !== undefined) {
    // 页码切换 / 手动刷新都不再重置（用户的选择要留住）
    return
  }
  initialCheckedKeys.value = records
    .filter((row) => row.status === 'COLLECTING' && row.id !== undefined)
    .map((row) => row.id as number)
}

const taskSelectOptions = computed(() =>
  taskOptions.value.map((task) => ({ label: task.taskName ?? '', value: task.id as number }))
)

const columns = computed<ProColumn<CollectRow>[]>(() => [
  {
    key: 'taskName',
    title: '所属任务',
    minWidth: 200,
    // 展示的是任务名（写入时快照），筛选要传的是 taskId —— 两者不同名，
    // 用 searchParam 映射（不映射的话参数会以 taskName 提交，筛选静默失效）
    search: 'select',
    searchParam: 'taskId',
    options: taskSelectOptions.value
  },
  { key: 'channel', title: '采集渠道', width: 110, dict: 'srvy_channel', search: 'select' },
  { key: 'collector', title: '采集人', width: 110, search: 'input', searchPlaceholder: '模糊匹配' },
  { key: 'collectDate', title: '采集日期', width: 120 },
  { key: 'sampleCount', title: '计划样本', width: 100 },
  { key: 'validCount', title: '有效样本', width: 100 },
  { key: 'qualityScore', title: '质量评分', width: 130, render: 'progress' },
  { key: 'fileName', title: '数据文件', minWidth: 180, renderFn: renderFileLink },
  { key: 'status', title: '状态', width: 110, dict: 'srvy_collect_status', search: 'select' },
  { key: 'createTime', title: '创建时间', width: 170, render: 'datetime' }
])

/**
 * 附件列：有文件时给一个可点的下载链接。
 *
 * <p>不能写成 {@code <a href>} —— 附件接口需要认证，而浏览器直接打开链接
 * <b>不会带 Authorization 头</b>。因此点击后走"带鉴权头 fetch → Blob → 触发下载"。
 */
function renderFileLink(row: CollectRow) {
  if (!row.fileId || !row.fileName) {
    return row.fileName ?? '-'
  }
  const fileId = row.fileId
  const fileName = row.fileName
  return h(
    'span',
    {
      class: 'collect-page__file',
      title: '点击下载',
      onClick: () => {
        void downloadSurveyFile(fileId, fileName)
      }
    },
    fileName
  )
}

const rowActions: ProRowAction<CollectRow>[] = [
  {
    key: 'download',
    label: '下载数据',
    permission: 'srvy:collect:query',
    disabled: (row) => !row.fileId,
    onClick: async (row) => {
      await downloadSurveyFile(row.fileId as number, row.fileName ?? 'download')
    }
  },
  { key: 'edit', label: '编辑', permission: 'srvy:collect:update', onClick: (row) => openForm(row) },
  {
    key: 'delete',
    label: '删除',
    permission: 'srvy:collect:delete',
    danger: true,
    confirm: (row) => `确定删除「${row.collector ?? ''}」的采集记录？已上传的数据文件不会一并清理。`,
    onClick: async (row) => {
      await deleteSurveyCollectAction(requireId(row))
      feedback.success('采集记录已删除')
      await tableRef.value?.refresh()
    }
  }
]

const batchActions: ProBatchAction<CollectRow>[] = [
  {
    key: 'finish',
    label: '批量标记结束',
    permission: 'srvy:collect:update',
    confirm: (rows) => `确定把所选 ${rows.length} 条记录的采集状态标记为「已结束」？`,
    onClick: async (rows) => {
      for (const row of rows) {
        await updateSurveyCollectAction(requireId(row), toPayloadOf(row, 'FINISHED'))
      }
      feedback.success(`已结束 ${rows.length} 条采集记录`)
      await tableRef.value?.refresh()
    }
  },
  {
    key: 'batchDelete',
    label: '批量删除',
    permission: 'srvy:collect:delete',
    danger: true,
    confirm: '确定删除所选采集记录？已上传的数据文件不会一并清理。',
    onClick: async (rows) => {
      for (const row of rows) {
        await deleteSurveyCollectAction(requireId(row))
      }
      feedback.success(`已删除 ${rows.length} 条记录`)
      tableRef.value?.clearSelection()
      await tableRef.value?.refresh()
    }
  }
]

// ---------------------------------------------------------------------
// 表单（槽位形态：字段用 items 声明，附件用 ProUpload 自定义控件）
// ---------------------------------------------------------------------

const formVisible = ref(false)
const editingId = ref<number | null>(null)
const modalModel = ref<Record<string, unknown>>({})
const attachment = ref<{ fileId?: number; fileName?: string }>({})

const formItems = computed<ProFormItem[]>(() => [
  {
    field: 'taskId',
    title: '所属任务',
    type: 'select',
    required: true,
    message: '请选择所属调研任务',
    options: taskSelectOptions.value
  },
  { field: 'channel', title: '采集渠道', type: 'select', dict: 'srvy_channel', value: 'ONLINE' },
  { field: 'collector', title: '采集人', placeholder: '如 张调研' },
  { field: 'collectDate', title: '采集日期', type: 'date', props: { valueFormat: 'yyyy-MM-dd' } },
  { field: 'sampleCount', title: '计划样本量', type: 'number', value: 0, props: { min: 0 } },
  { field: 'validCount', title: '有效样本量', type: 'number', value: 0, props: { min: 0 } },
  {
    field: 'qualityScore',
    title: '质量评分',
    type: 'number',
    value: 0,
    props: { min: 0, max: 100 },
    tip: '0~100，用于横向比较不同批次的数据可信度'
  },
  { field: 'status', title: '采集状态', type: 'select', dict: 'srvy_collect_status', value: 'COLLECTING' },
  { field: 'remark', title: '备注', type: 'textarea' }
])

/**
 * 附件上传（自定义传输）。
 *
 * <p>不走 {@code action} 模式的原因：n-upload 的完成事件拿不到服务端响应体，
 * 而我们需要的正是响应里的附件 ID。走契约客户端（自带鉴权头与错误分流）
 * 并在闭包里直接写页面状态，链路最短也最可靠。
 */
async function uploadAttachment(file: File): Promise<void> {
  const uploaded = await uploadSurveyFileAction(file, FILE_BIZ_TYPE.collect)
  attachment.value = { fileId: uploaded.id, fileName: uploaded.fileName }
  feedback.success('附件已上传')
}

function openForm(row: CollectRow | null): void {
  editingId.value = row ? requireId(row) : null
  modalModel.value = row
    ? {
        taskId: row.taskId ?? null,
        channel: row.channel ?? 'ONLINE',
        collector: row.collector ?? '',
        collectDate: row.collectDate ?? null,
        sampleCount: row.sampleCount ?? 0,
        validCount: row.validCount ?? 0,
        qualityScore: row.qualityScore ?? 0,
        status: row.status ?? 'COLLECTING',
        remark: row.remark ?? ''
      }
    : {}
  attachment.value = row ? { fileId: row.fileId, fileName: row.fileName } : {}
  formVisible.value = true
}

function handleUploadRemove(): void {
  attachment.value = {}
}

function toPayloadOf(row: CollectRow, status?: string): CollectRequest {
  return {
    taskId: row.taskId as number,
    channel: row.channel ?? 'ONLINE',
    collector: row.collector,
    collectDate: toDateString(row.collectDate),
    sampleCount: row.sampleCount ?? 0,
    validCount: row.validCount ?? 0,
    qualityScore: row.qualityScore ?? 0,
    fileId: row.fileId,
    fileName: row.fileName,
    status: status ?? row.status ?? 'COLLECTING',
    remark: row.remark
  }
}

function toPayload(values: Record<string, unknown>): CollectRequest {
  return {
    taskId: Number(values.taskId),
    channel: values.channel ? String(values.channel) : 'ONLINE',
    collector: values.collector ? String(values.collector) : undefined,
    collectDate: toDateString(values.collectDate),
    sampleCount: typeof values.sampleCount === 'number' ? values.sampleCount : 0,
    validCount: typeof values.validCount === 'number' ? values.validCount : 0,
    qualityScore: typeof values.qualityScore === 'number' ? values.qualityScore : 0,
    fileId: attachment.value.fileId,
    fileName: attachment.value.fileName,
    status: values.status ? String(values.status) : 'COLLECTING',
    remark: values.remark ? String(values.remark) : undefined
  }
}

/** 槽位形态下由页面驱动校验与提交（ProModal 的 items 模式用不到表单实例）。 */
async function submitForm(): Promise<void> {
  const valid = await formRef.value?.validate()
  if (valid === false) {
    return
  }
  const values = formRef.value?.getValues() ?? {}
  if (!values.taskId) {
    feedback.warning('请选择所属调研任务')
    return
  }
  const payload = toPayload(values)
  if (editingId.value !== null) {
    await updateSurveyCollectAction(editingId.value, payload)
  } else {
    await createSurveyCollectAction(payload)
  }
  feedback.success('保存成功')
  formVisible.value = false
  await (editingId.value === null ? tableRef.value?.reload() : tableRef.value?.refresh())
}

// ---------------------------------------------------------------------
// 导入 / 导出
// ---------------------------------------------------------------------

const dicts = useDict('srvy_channel', 'srvy_collect_status')

function labelOf(items: Array<{ label: string; value: string }>, value?: string): string {
  if (!value) return ''
  return items.find((item) => item.value === value)?.label ?? value
}

function codeOf(items: Array<{ label: string; value: string }>, value: unknown, fallback: string): string {
  if (value === undefined || value === null || value === '') return fallback
  const raw = String(value)
  const byCode = items.find((item) => item.value === raw)
  if (byCode) return raw
  return items.find((item) => item.label === raw)?.value ?? raw
}

const excelColumns: ProExcelColumn<CollectRow>[] = [
  { key: 'taskName', title: '所属任务', required: true, width: 26 },
  { key: 'channel', title: '采集渠道', width: 12, format: (row) => labelOf(dicts.srvy_channel.value, row.channel) },
  { key: 'collector', title: '采集人', width: 12 },
  { key: 'collectDate', title: '采集日期', width: 14 },
  { key: 'sampleCount', title: '计划样本', width: 12 },
  { key: 'validCount', title: '有效样本', width: 12 },
  { key: 'qualityScore', title: '质量评分', width: 12 },
  {
    key: 'status',
    title: '状态',
    width: 12,
    format: (row) => labelOf(dicts.srvy_collect_status.value, row.status)
  },
  { key: 'remark', title: '备注', width: 30 }
]

/**
 * 导入：Excel 里写的是<b>任务名称</b>（人可读），落库要 taskId。
 * 名称对不上时按行给出错误，让用户改文件而不是猜。
 */
async function handleImported(rows: CollectRow[]): Promise<void> {
  if (rows.length === 0) return
  const errors: Array<ProExcelRowError<CollectRow>> = []
  const payload: CollectRequest[] = []

  rows.forEach((row, index) => {
    const task = taskOptions.value.find((item) => item.taskName === row.taskName)
    if (!task?.id) {
      errors.push({
        rowIndex: index + 1,
        row,
        message: `找不到名为「${row.taskName ?? ''}」的调研任务`
      })
      return
    }
    payload.push({
      taskId: task.id,
      channel: codeOf(dicts.srvy_channel.value, row.channel, 'ONLINE'),
      collector: row.collector,
      collectDate: toDateString(row.collectDate),
      sampleCount: Number(row.sampleCount) || 0,
      validCount: Number(row.validCount) || 0,
      qualityScore: Number(row.qualityScore) || 0,
      status: codeOf(dicts.srvy_collect_status.value, row.status, 'COLLECTING'),
      remark: row.remark
    })
  })

  if (errors.length > 0) {
    feedback.error(`${errors.length} 行未通过校验，已生成错误行文件`)
    await excelRef.value?.downloadErrorRows(errors)
  }
  if (payload.length === 0) {
    return
  }
  try {
    const count = await batchCreateSurveyCollectsAction(payload)
    feedback.success(`成功导入 ${count} 条采集记录`)
    await tableRef.value?.reload()
  } catch (error) {
    const message = error instanceof Error ? error.message : '导入失败'
    feedback.error(message)
    await excelRef.value?.downloadErrorRows([{ rowIndex: 1, message }])
  }
}

onMounted(async () => {
  taskOptions.value = await loadSurveyTaskOptions()
})
</script>

<template>
  <PageContainer
    title="数据采集"
    description="采集批次与数据文件；勾选支持跨页保留，默认勾选「采集中」的记录"
  >
    <template #extra>
      <ProExcel
        v-if="hasPermission('srvy:collect:import')"
        ref="excelRef"
        :columns="excelColumns"
        file-name="数据采集"
        @imported="handleImported"
      />
      <AuthButton
        permission="srvy:collect:create"
        mode="disable"
        type="primary"
        size="small"
        denied-text="你没有新增采集记录的权限"
        @click="openForm(null)"
      >
        新增采集
      </AuthButton>
    </template>

    <ProTable
      ref="tableRef"
      :columns="columns"
      :request="requestPage"
      :toolbar="['refresh', 'columnSetting', 'density']"
      :row-actions="rowActions"
      :batch-actions="batchActions"
      selection-mode="multiple"
      reserve-selection
      :checked-keys="checkedKeys"
      empty-action-text="登记第一条采集记录"
      @empty-action="openForm(null)"
    />
  </PageContainer>

  <!-- 槽位形态：字段交给 ProForm 的 items，附件用 ProUpload 作为自定义控件 -->
  <ProModal
    v-model:visible="formVisible"
    :title="editingId !== null ? '编辑采集记录' : '新增采集记录'"
    :cols="1"
    :submit="submitForm"
    :on-success="() => (editingId === null ? tableRef?.reload() : tableRef?.refresh())"
    @error="(error: unknown) => feedback.error(error instanceof Error ? error.message : '保存失败')"
  >
    <ProForm ref="formRef" :items="formItems" :model="modalModel" :cols="1" />

    <div class="collect-page__upload">
      <label class="collect-page__label">采集数据文件</label>
      <ProUpload
        :request="uploadAttachment"
        :max-count="1"
        :max-size-mb="20"
        :accept="['csv', 'xlsx', 'xls', 'txt', 'zip']"
        tip="单文件 ≤20MB；服务端按「租户/业务类型/年月」分目录存放"
        @remove="handleUploadRemove"
        @error="() => feedback.error('附件上传失败，请重试')"
      />
      <p class="collect-page__hint">
        大文件说明：本接口是一次性上传（不含分片与断点续传）。超过 20MB 的文件
        （如原始录音、视频）请改走对象存储直传，再把地址填进备注 ——
        自研分片协议不在本模块范围内。
      </p>
      <p v-if="attachment.fileId" class="collect-page__hint">
        已绑定附件：{{ attachment.fileName }}（ID: {{ attachment.fileId }}）
      </p>
    </div>
  </ProModal>
</template>

<style scoped>
.collect-page__file {
  color: var(--wa-color-primary);
  cursor: pointer;
  text-decoration: underline;
}

.collect-page__upload {
  margin-top: var(--wa-spacing-lg);
  padding-top: var(--wa-spacing-lg);
  border-top: 1px solid var(--wa-divider);
}

.collect-page__label {
  display: block;
  margin-bottom: var(--wa-spacing-xs);
  font-size: var(--wa-font-size-md);
  color: var(--wa-text-primary);
}

.collect-page__hint {
  margin: var(--wa-spacing-xs) 0 0;
  font-size: var(--wa-font-size-xs);
  line-height: 1.6;
  color: var(--wa-text-secondary);
}
</style>
