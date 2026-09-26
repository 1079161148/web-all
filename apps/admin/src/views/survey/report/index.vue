<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  AuthButton,
  PageContainer,
  ProEditor,
  ProExcel,
  ProForm,
  ProModal,
  ProTable,
  ProUpload,
  feedback,
  hasPermission
} from '@admin/ui'
import type {
  ProColumn,
  ProEditorUploadResult,
  ProExcelColumn,
  ProExcelExpose,
  ProFormExpose,
  ProFormItem,
  ProRowAction,
  ProTableExpose
} from '@admin/ui'
import type { ReportRequest, ReportResponse, TaskOption } from '@admin/api'
import {
  FILE_BIZ_TYPE,
  createSurveyReportAction,
  deleteSurveyReportAction,
  downloadSurveyFile,
  fetchSurveyFileDataUrl,
  fetchSurveyReportPage,
  loadSurveyReportDetail,
  loadSurveyTaskOptions,
  requireId,
  toDateString,
  updateSurveyReportAction,
  uploadSurveyFileAction
} from '@/api/survey'

/**
 * 调研分析报告。
 *
 * <p>与问卷页共用两条规则：正文只在详情接口返回（编辑前必须取详情）、
 * 插图用 data URL（附件接口需要认证，{@code <img src>} 带不上令牌）。
 */
type ReportRow = ReportResponse

const tableRef = ref<ProTableExpose<ReportRow> | null>(null)
const excelRef = ref<ProExcelExpose<ReportRow> | null>(null)
const formRef = ref<ProFormExpose | null>(null)

const currentRows = ref<ReportRow[]>([])
const taskOptions = ref<TaskOption[]>([])

const requestPage = async (query: Parameters<typeof fetchSurveyReportPage>[0]) => {
  const result = await fetchSurveyReportPage(query)
  currentRows.value = result.records
  return result
}

const taskSelectOptions = computed(() =>
  taskOptions.value.map((task) => ({ label: task.taskName ?? '', value: task.id as number }))
)

const columns = computed<ProColumn<ReportRow>[]>(() => [
  {
    key: 'taskName',
    title: '所属任务',
    minWidth: 190,
    search: 'select',
    // 展示任务名（快照），筛选传 taskId
    searchParam: 'taskId',
    options: taskSelectOptions.value
  },
  { key: 'reportTitle', title: '报告标题', minWidth: 220, search: 'input', searchPlaceholder: '模糊匹配' },
  { key: 'reportType', title: '类型', width: 110, dict: 'srvy_report_type', search: 'select' },
  { key: 'author', title: '撰写人', width: 110 },
  { key: 'publishDate', title: '发布日期', width: 120 },
  {
    key: 'status',
    title: '状态',
    width: 110,
    dict: 'srvy_report_status',
    search: 'select'
  },
  { key: 'fileName', title: '附件', minWidth: 160, renderFn: renderFileLink },
  { key: 'createTime', title: '创建时间', width: 170, render: 'datetime' }
])

function renderFileLink(row: ReportRow) {
  if (!row.fileId || !row.fileName) {
    return row.fileName ?? '-'
  }
  const fileId = row.fileId
  const fileName = row.fileName
  return h(
    'span',
    {
      class: 'report-page__file',
      title: '点击下载',
      onClick: () => {
        void downloadSurveyFile(fileId, fileName)
      }
    },
    fileName
  )
}

const rowActions: ProRowAction<ReportRow>[] = [
  { key: 'edit', label: '编辑', permission: 'srvy:report:update', onClick: (row) => openForm(row) },
  {
    key: 'preview',
    label: '预览',
    permission: 'srvy:report:query',
    onClick: (row) => void openPreview(row)
  },
  {
    key: 'delete',
    label: '删除',
    permission: 'srvy:report:delete',
    danger: true,
    confirm: (row) => `确定删除报告「${row.reportTitle}」？`,
    onClick: async (row) => {
      await deleteSurveyReportAction(requireId(row))
      feedback.success('报告已删除')
      await tableRef.value?.refresh()
    }
  }
]

// ---------------------------------------------------------------------
// 编辑
// ---------------------------------------------------------------------

const formVisible = ref(false)
const editingId = ref<number | null>(null)
const modalModel = ref<Record<string, unknown>>({})
const content = ref('')
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
  { field: 'reportTitle', title: '报告标题', required: true, placeholder: '如 新用户上手体验问题汇总报告' },
  { field: 'reportType', title: '报告类型', type: 'select', dict: 'srvy_report_type', value: 'SUMMARY' },
  { field: 'author', title: '撰写人' },
  { field: 'publishDate', title: '发布日期', type: 'date', props: { valueFormat: 'yyyy-MM-dd' } },
  { field: 'status', title: '状态', type: 'select', dict: 'srvy_report_status', value: 'DRAFT' },
  { field: 'summary', title: '摘要', type: 'textarea', tip: '一句话结论，列表与订阅推送里都会用到' },
  { field: 'remark', title: '备注', type: 'textarea' }
])

async function openForm(row: ReportRow | null): Promise<void> {
  if (row === null) {
    editingId.value = null
    modalModel.value = {}
    content.value = ''
    attachment.value = {}
    formVisible.value = true
    return
  }
  const id = requireId(row)
  try {
    const detail = await loadSurveyReportDetail(id)
    editingId.value = id
    content.value = detail.content ?? ''
    attachment.value = { fileId: detail.fileId, fileName: detail.fileName }
    modalModel.value = {
      taskId: detail.taskId ?? null,
      reportTitle: detail.reportTitle ?? '',
      reportType: detail.reportType ?? 'SUMMARY',
      author: detail.author ?? '',
      publishDate: detail.publishDate ?? null,
      status: detail.status ?? 'DRAFT',
      summary: detail.summary ?? '',
      remark: detail.remark ?? ''
    }
    formVisible.value = true
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '加载详情失败')
  }
}

/**
 * 附件上传（自定义传输）。
 *
 * <p>不用 {@code action} 模式：n-upload 的完成事件拿不到服务端响应体，
 * 而附件 ID 正是响应体里的内容。走契约客户端并在闭包里写状态，最直接。
 */
async function uploadAttachment(file: File): Promise<void> {
  const uploaded = await uploadSurveyFileAction(file, FILE_BIZ_TYPE.report)
  attachment.value = { fileId: uploaded.id, fileName: uploaded.fileName }
  feedback.success('附件已上传')
}

function toPayload(values: Record<string, unknown>): ReportRequest {
  return {
    taskId: Number(values.taskId),
    reportTitle: String(values.reportTitle ?? ''),
    reportType: values.reportType ? String(values.reportType) : 'SUMMARY',
    author: values.author ? String(values.author) : undefined,
    publishDate: toDateString(values.publishDate),
    summary: values.summary ? String(values.summary) : undefined,
    content: content.value,
    fileId: attachment.value.fileId,
    fileName: attachment.value.fileName,
    status: values.status ? String(values.status) : 'DRAFT',
    remark: values.remark ? String(values.remark) : undefined
  }
}

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
    await updateSurveyReportAction(editingId.value, payload)
  } else {
    await createSurveyReportAction(payload)
  }
  feedback.success('保存成功')
  formVisible.value = false
  await (editingId.value === null ? tableRef.value?.reload() : tableRef.value?.refresh())
}

async function uploadEditorImage(file: File): Promise<ProEditorUploadResult> {
  const uploaded = await uploadSurveyFileAction(file, FILE_BIZ_TYPE.editor)
  if (!uploaded.id) {
    throw new Error('上传返回缺少附件 ID')
  }
  return { url: await fetchSurveyFileDataUrl(uploaded.id), alt: uploaded.fileName }
}

// ---------------------------------------------------------------------
// 预览（只读）
// ---------------------------------------------------------------------

const previewVisible = ref(false)
const previewModel = ref<Record<string, unknown>>({})
const previewContent = ref('')

const previewItems = computed<ProFormItem[]>(() => [
  { field: 'taskName', title: '所属任务' },
  { field: 'reportTitle', title: '报告标题' },
  { field: 'reportType', title: '报告类型', type: 'select', dict: 'srvy_report_type' },
  { field: 'author', title: '撰写人' },
  { field: 'publishDate', title: '发布日期' },
  { field: 'status', title: '状态', type: 'select', dict: 'srvy_report_status' },
  { field: 'summary', title: '摘要', type: 'textarea' }
])

async function openPreview(row: ReportRow): Promise<void> {
  try {
    const detail = await loadSurveyReportDetail(requireId(row))
    previewModel.value = {
      taskName: detail.taskName ?? '',
      reportTitle: detail.reportTitle ?? '',
      reportType: detail.reportType ?? '',
      author: detail.author ?? '',
      publishDate: detail.publishDate ?? '',
      status: detail.status ?? '',
      summary: detail.summary ?? ''
    }
    previewContent.value = detail.content ?? ''
    previewVisible.value = true
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '加载详情失败')
  }
}

// ---------------------------------------------------------------------
// 导出
// ---------------------------------------------------------------------

const excelColumns: ProExcelColumn<ReportRow>[] = [
  { key: 'taskName', title: '所属任务', width: 26 },
  { key: 'reportTitle', title: '报告标题', width: 30 },
  { key: 'reportType', title: '报告类型', width: 12 },
  { key: 'author', title: '撰写人', width: 12 },
  { key: 'publishDate', title: '发布日期', width: 14 },
  { key: 'status', title: '状态', width: 12 },
  { key: 'summary', title: '摘要', width: 40 },
  { key: 'remark', title: '备注', width: 30 }
]

onMounted(async () => {
  taskOptions.value = await loadSurveyTaskOptions()
})
</script>

<template>
  <PageContainer
    title="调研分析报告"
    description="正文富文本 + 附件；报告是任务的产出物，任务名按写入时快照保留"
  >
    <template #extra>
      <ProExcel
        v-if="hasPermission('srvy:report:query')"
        ref="excelRef"
        :columns="excelColumns"
        file-name="调研报告"
        :show-actions="false"
      />
      <AuthButton
        permission="srvy:report:query"
        type="default"
        size="small"
        @click="excelRef?.exportRows(currentRows)"
      >
        导出当前页
      </AuthButton>
      <AuthButton
        permission="srvy:report:create"
        mode="disable"
        type="primary"
        size="small"
        denied-text="你没有新增报告的权限"
        @click="openForm(null)"
      >
        新增报告
      </AuthButton>
    </template>

    <ProTable
      ref="tableRef"
      :columns="columns"
      :request="requestPage"
      :toolbar="['refresh', 'columnSetting', 'density']"
      :row-actions="rowActions"
      empty-action-text="撰写第一份报告"
      @empty-action="openForm(null)"
    />
  </PageContainer>

  <ProModal
    v-model:visible="formVisible"
    :title="editingId !== null ? '编辑报告' : '新增报告'"
    :width="900"
    :cols="2"
    :submit="submitForm"
    :on-success="() => (editingId === null ? tableRef?.reload() : tableRef?.refresh())"
    @error="(error: unknown) => feedback.error(error instanceof Error ? error.message : '保存失败')"
  >
    <ProForm ref="formRef" :items="formItems" :model="modalModel" :cols="2" />

    <div class="report-page__section">
      <label class="report-page__label">报告附件</label>
      <ProUpload
        :request="uploadAttachment"
        :max-count="1"
        :max-size-mb="20"
        :accept="['pdf', 'doc', 'docx', 'xlsx', 'zip', 'png', 'jpg']"
        tip="单文件 ≤20MB；服务端按「租户/业务类型/年月」分目录存放"
        @remove="() => (attachment = {})"
        @error="() => feedback.error('附件上传失败，请重试')"
      />
      <p v-if="attachment.fileId" class="report-page__hint">
        已绑定附件：{{ attachment.fileName }}（ID: {{ attachment.fileId }}）
      </p>
    </div>

    <div class="report-page__section">
      <label class="report-page__label">正文</label>
      <ProEditor
        v-model:value="content"
        :height="380"
        :upload-image="uploadEditorImage"
        placeholder="撰写报告正文；支持标题、列表、引用与插图"
        @error="() => feedback.error('图片上传失败，请重试')"
      />
    </div>
  </ProModal>

  <!-- 只读预览：只读态下 ProModal 只保留关闭按钮 -->
  <ProModal
    v-model:visible="previewVisible"
    :title="String(previewModel.reportTitle ?? '报告预览')"
    :width="900"
    readonly
  >
    <ProForm :items="previewItems" :model="previewModel" :cols="2" disabled />
    <div class="report-page__section">
      <label class="report-page__label">正文</label>
      <ProEditor :value="previewContent" :height="360" read-only />
    </div>
  </ProModal>
</template>

<style scoped>
.report-page__file {
  color: var(--wa-color-primary);
  cursor: pointer;
  text-decoration: underline;
}

.report-page__section {
  margin-top: var(--wa-spacing-lg);
  padding-top: var(--wa-spacing-lg);
  border-top: 1px solid var(--wa-divider);
}

.report-page__label {
  display: block;
  margin-bottom: var(--wa-spacing-xs);
  font-size: var(--wa-font-size-md);
  color: var(--wa-text-primary);
}

.report-page__hint {
  margin: var(--wa-spacing-xs) 0 0;
  font-size: var(--wa-font-size-xs);
  color: var(--wa-text-secondary);
}
</style>
