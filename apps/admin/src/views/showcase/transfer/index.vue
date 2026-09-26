<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import {
  NButton,
  NCard,
  NCheckboxGroup,
  NCheckbox,
  NProgress,
  NRadioButton,
  NRadioGroup,
  NSelect,
  NSpace,
  NTag,
  ProTable,
  feedback,
  type ProTableRequest
} from '@admin/ui'
import type { ProTableExpose } from '@admin/ui'
import type { TaskResponse } from '@admin/api'
import { fetchSurveyTaskPage } from '@/api/survey'
import {
  cancelTransferTask,
  confirmImportTask,
  createExportTask,
  createImportTask,
  downloadBlob,
  downloadTransferTemplate,
  getErrorMessage,
  getTransferTask,
  previewImportHeaders,
  retryImportFailures,
  type ImportHeaderPreview,
  type TransferTaskView
} from '@/api/transfer'

/**
 * 数据导入导出。
 *
 * <h3>设计对照（每个"看似简单"背后对应的机制）</h3>
 * <ul>
 *   <li><b>模板 → 上传 → 校验 → 预览 → 确认</b>：校验与落库是两个独立阶段
 *       （后端两阶段任务），预览确认前<b>不写任何数据</b></li>
 *   <li><b>错误行定位</b>：后端逐行逐格校验，错误精确到"第 N 行 / 某列 / 原值 / 原因"，
 *       并可下载错误报告（xlsx）回源修正</li>
 *   <li><b>异步任务 + 轮询</b>：解析/校验/落库都在后端虚拟线程上跑，
 *       前端 800ms 轮询进度 —— 大文件不再受 HTTP 请求超时约束</li>
 *   <li><b>部分成功 + 选择性重试</b>：单行失败按行记录，其余照常入库；
 *       失败行保留原值，可一键重试</li>
 *   <li><b>导出三范围 + 字段可选 + 进度/取消</b>：范围（当前页/全部/选中）
 *       由前端把 id 集合交给后端 —— 与列表查询同一条数据权限链路</li>
 * </ul>
 */

// ---------------------------------------------------------------------
// 数据导出的底座：调研任务列表（选中行 → 导出范围"选中"）
// ---------------------------------------------------------------------

const EXPORT_FIELDS = [
  '任务编码', '任务名称', '任务类型', '优先级', '负责人',
  '开始日期', '结束日期', '进度', '状态', '备注'
]

const taskColumns = [
  { key: 'taskCode', title: '任务编码' },
  { key: 'taskName', title: '任务名称' },
  { key: 'taskType', title: '类型', dict: 'srvy_task_type' },
  { key: 'priority', title: '优先级', dict: 'srvy_priority' },
  { key: 'ownerName', title: '负责人' },
  { key: 'progress', title: '进度' },
  { key: 'status', title: '状态', dict: 'srvy_task_status' }
]

const tableRef = ref<ProTableExpose | null>(null)
/**
 * "当前页"的 id 快照：在请求包装器里记录每次返回的行 id ——
 * 翻页/筛选后它始终等于"此刻表格可见页"（导出范围"当前页"的数据来源）。
 */
let latestPageIds: number[] = []

const wrappedTaskRequest: ProTableRequest<TaskResponse> = async (query) => {
  const result = await fetchSurveyTaskPage(query)
  latestPageIds = (result.records ?? [])
    .map((row) => Number(row.id))
    .filter((id) => Number.isFinite(id))
  return result
}

// ---------------------------------------------------------------------
// 导入流程
// ---------------------------------------------------------------------

type ImportPhase = 'IDLE' | 'MAPPING' | 'RUNNING' | 'REVIEW' | 'DONE' | 'FAILED'

const importPhase = ref<ImportPhase>('IDLE')
const importTaskId = ref('')
const importTask = ref<TransferTaskView | null>(null)
const importProgress = ref(0)
const importError = ref('')
const uploading = ref(false)

// ---------------------------------------------------------------------
// 列映射（表头与模板不一致时的手动映射步骤）
// ---------------------------------------------------------------------

/** 与后端 IMPORT_COLUMNS 严格一致（模板列序）。 */
const TEMPLATE_COLUMNS = [
  '任务编码', '任务名称', '任务类型', '优先级', '负责人',
  '开始日期', '结束日期', '进度', '状态', '备注'
] as const

/** 待映射的文件（预览表头后暂存，映射确认时才上传建任务）。 */
const pendingFile = ref<File | null>(null)
const headerPreview = ref<ImportHeaderPreview | null>(null)
/**
 * mapping[平台字段序] = Excel 列下标；-1 = 未映射。
 * 未映射字段交由后端必填校验报错（复用校验器，而不是前端另立规则）。
 */
const columnMapping = ref<number[]>(TEMPLATE_COLUMNS.map(() => -1))

/** 自动预匹配：表头名与模板字段同名即命中。 */
function autoMatchMapping(headers: string[]): number[] {
  return TEMPLATE_COLUMNS.map((column) => {
    const index = headers.findIndex((h) => h.trim() === column)
    return index
  })
}

const mappingComplete = computed(() => columnMapping.value.every((m) => m >= 0))

/** 每个文件列是否已被其它字段占用（下拉禁用重复选择 —— 重复在后端是硬错误）。 */
function columnTaken(columnIndex: number, exceptFieldIndex: number): boolean {
  return columnMapping.value.some((m, i) => m === columnIndex && i !== exceptFieldIndex)
}

function resetMapping(): void {
  pendingFile.value = null
  headerPreview.value = null
  columnMapping.value = TEMPLATE_COLUMNS.map(() => -1)
}

let pollTimer: number | null = null

function stopPolling(): void {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

async function onImportFile(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) {
    return
  }
  uploading.value = true
  importError.value = ''
  try {
    // 两段式：先只读表头（不建任务）——模板命中则自动映射直传，
    // 有歧义才停在映射步骤让用户手动对齐
    const preview = await previewImportHeaders(file)
    const mapping = autoMatchMapping(preview.headers)
    if (mapping.every((m) => m >= 0)) {
      // 模板文件：全字段命中，无需打扰用户
      importTaskId.value = await createImportTask(file, mapping)
      importPhase.value = 'RUNNING'
      importProgress.value = 5
      startPolling()
      return
    }
    pendingFile.value = file
    headerPreview.value = preview
    columnMapping.value = mapping
    importPhase.value = 'MAPPING'
  } catch (error) {
    feedback.error(await getErrorMessage(error))
  } finally {
    uploading.value = false
  }
}

/** 映射确认：带 mapping 建任务（未映射字段交给后端必填校验报错）。 */
async function confirmMapping(): Promise<void> {
  const file = pendingFile.value
  if (!file) {
    return
  }
  uploading.value = true
  try {
    importTaskId.value = await createImportTask(file, [...columnMapping.value])
    resetMapping()
    importPhase.value = 'RUNNING'
    importProgress.value = 5
    startPolling()
  } catch (error) {
    feedback.error(await getErrorMessage(error))
  } finally {
    uploading.value = false
  }
}

function cancelMapping(): void {
  resetMapping()
  importPhase.value = 'IDLE'
}

/** 轮询任务进度：终态停止；VALIDATED 进入人工确认（审查阶段）。 */
function startPolling(): void {
  stopPolling()
  pollTimer = window.setInterval(async () => {
    try {
      const task = await getTransferTask(importTaskId.value)
      importTask.value = task
      importProgress.value = task.progress
      const terminal =
        task.status === 'DONE' ||
        task.status === 'PARTIAL' ||
        task.status === 'FAILED' ||
        task.status === 'CANCELLED'
      if (task.status === 'VALIDATED') {
        importPhase.value = 'REVIEW'
        stopPolling()
        feedback.success(`校验完成：${task.totalRows - task.failedRows} 行可导入，${task.errors.length} 处错误`)
      } else if (terminal) {
        importPhase.value = task.status === 'FAILED' || task.status === 'CANCELLED' ? 'FAILED' : 'DONE'
        stopPolling()
      }
    } catch (error) {
      stopPolling()
      importPhase.value = 'FAILED'
      importError.value = await getErrorMessage(error)
    }
  }, 800)
}

async function confirmImport(): Promise<void> {
  try {
    await confirmImportTask(importTaskId.value)
    importPhase.value = 'RUNNING'
    startPolling()
  } catch (error) {
    feedback.error(await getErrorMessage(error))
  }
}

async function retryFailed(): Promise<void> {
  try {
    await retryImportFailures(importTaskId.value)
    importPhase.value = 'RUNNING'
    startPolling()
  } catch (error) {
    feedback.error(await getErrorMessage(error))
  }
}

async function cancelImport(): Promise<void> {
  try {
    await cancelTransferTask(importTaskId.value)
  } catch (error) {
    feedback.error(await getErrorMessage(error))
  }
}

async function downloadErrorReport(): Promise<void> {
  try {
    await downloadBlob(
      `/api/v1/tools/transfers/${importTaskId.value}/error-report`,
      '导入错误报告.xlsx'
    )
  } catch (error) {
    feedback.error(await getErrorMessage(error))
  }
}

const importStep = computed(() => {
  if (importPhase.value === 'IDLE') {
    return 0
  }
  if (importPhase.value === 'MAPPING') {
    return 1
  }
  if (importPhase.value === 'RUNNING') {
    return importTask.value?.status === 'IMPORTING' ? 3 : 2
  }
  if (importPhase.value === 'REVIEW') {
    return 2
  }
  return 4
})

/** 更新单个字段的映射（模板内联多语句会触发 lint，收敛成方法）。 */
function setMapping(fieldIndex: number, columnIndex: number): void {
  columnMapping.value[fieldIndex] = columnIndex
}

const PREVIEW_COLUMNS = ['任务编码', '任务名称', '任务类型', '优先级', '负责人', '开始日期', '结束日期', '状态']

// ---------------------------------------------------------------------
// 导出流程
// ---------------------------------------------------------------------

const exportScope = ref<'ALL' | 'PAGE' | 'SELECTED'>('ALL')
const exportFields = ref<string[]>([...EXPORT_FIELDS])
const exportPhase = ref<'IDLE' | 'RUNNING' | 'DONE'>('IDLE')
const exportTaskId = ref('')
const exportTask = ref<TransferTaskView | null>(null)
const exportProgress = ref(0)
let exportPollTimer: number | null = null

function stopExportPolling(): void {
  if (exportPollTimer !== null) {
    window.clearInterval(exportPollTimer)
    exportPollTimer = null
  }
}

const exportDisabledReason = computed(() => {
  if (exportScope.value === 'SELECTED') {
    return selectedIds.value.length === 0 ? '请先在列表中勾选要导出的行' : ''
  }
  return ''
})

const selectedIds = ref<number[]>([])

function handleSelection(): void {
  const selection: Array<Record<string, unknown>> = tableRef.value?.getSelection() ?? []
  selectedIds.value = selection
    .map((row) => Number(row.id))
    .filter((id) => Number.isFinite(id))
}

async function startExport(): Promise<void> {
  if (exportDisabledReason.value) {
    feedback.warning(exportDisabledReason.value)
    return
  }
  const ids =
    exportScope.value === 'ALL'
      ? []
      : exportScope.value === 'SELECTED'
        ? selectedIds.value
        : currentPageIds()
  try {
    exportTaskId.value = await createExportTask(exportScope.value, ids, exportFields.value)
    exportPhase.value = 'RUNNING'
    exportProgress.value = 5
    startExportPolling()
  } catch (error) {
    feedback.error(await getErrorMessage(error))
  }
}

/** "当前页"的 id：见 wrappedTaskRequest 的说明。 */
function currentPageIds(): number[] {
  return latestPageIds
}

function startExportPolling(): void {
  stopExportPolling()
  exportPollTimer = window.setInterval(async () => {
    try {
      const task = await getTransferTask(exportTaskId.value)
      exportTask.value = task
      exportProgress.value = task.progress
      if (task.status === 'DONE') {
        exportPhase.value = 'DONE'
        stopExportPolling()
        feedback.success('导出完成，可下载文件')
      } else if (task.status === 'FAILED' || task.status === 'CANCELLED') {
        exportPhase.value = 'IDLE'
        stopExportPolling()
        feedback.error(task.phase)
      }
    } catch (error) {
      stopExportPolling()
      exportPhase.value = 'IDLE'
      feedback.error(await getErrorMessage(error))
    }
  }, 800)
}

async function cancelExport(): Promise<void> {
  try {
    await cancelTransferTask(exportTaskId.value)
  } catch (error) {
    feedback.error(await getErrorMessage(error))
  }
}

async function downloadResult(): Promise<void> {
  try {
    await downloadBlob(
      `/api/v1/tools/transfers/${exportTaskId.value}/download`,
      exportTask.value?.resultFileName ?? '导出结果.xlsx'
    )
  } catch (error) {
    feedback.error(await getErrorMessage(error))
  }
}

function refreshTable(): void {
  void tableRef.value?.refresh()
}

onBeforeUnmount(() => {
  stopPolling()
  stopExportPolling()
})
</script>

<template>
  <div class="transfer">
    <!-- ==================== 导入 ==================== -->
    <NCard title="① 数据导入（模板 → 上传 → 校验 → 预览 → 确认）" :bordered="false" class="transfer__card">
      <div class="transfer__steps">
        <div
          v-for="(label, index) in ['下载模板', '上传并映射', '校验预览', '确认导入']"
          :key="label"
          class="transfer__step"
          :class="{ 'transfer__step--active': importStep === index, 'transfer__step--done': importStep > index }"
        >
          <span class="transfer__step-index">{{ index + 1 }}</span>
          <span>{{ label }}</span>
        </div>
      </div>

      <NSpace align="center" class="transfer__actions">
        <NButton size="small" @click="downloadTransferTemplate">下载模板</NButton>
        <label class="transfer__picker">
          <input type="file" accept=".xlsx" :disabled="uploading" @change="onImportFile" />
          选择 xlsx 文件并上传
        </label>
        <NTag size="small" :bordered="false">
          表头与模板一致时自动匹配；不一致会进入列映射
        </NTag>
      </NSpace>

      <!-- 列映射：表头与模板不一致时（表头只读预览，用户对齐到平台字段） -->
      <template v-if="importPhase === 'MAPPING' && headerPreview">
        <h4 class="transfer__sub">列映射 — 把文件的每一列对齐到平台字段</h4>
        <div class="transfer__mapping">
          <div v-for="(field, fieldIndex) in TEMPLATE_COLUMNS" :key="field" class="transfer__mapping-row">
            <span class="transfer__mapping-field">{{ field }}</span>
            <NSelect
              size="small"
              class="transfer__mapping-select"
              :value="columnMapping[fieldIndex]"
              :options="[
                { label: '（未映射）', value: -1 },
                ...headerPreview.headers.map((header, columnIndex) => ({
                  label: `第 ${columnIndex + 1} 列：${header}`,
                  value: columnIndex,
                  disabled: columnTaken(columnIndex, fieldIndex)
                }))
              ]"
              @update:value="(value: number) => setMapping(fieldIndex, value)"
            />
          </div>
          <div v-if="!mappingComplete" class="transfer__mapping-hint">
            有字段未映射 —— 缺失字段按既有校验规则报错（必填缺失）。
          </div>
        </div>
        <NSpace align="center" class="transfer__actions">
          <NButton size="small" type="primary" :loading="uploading" @click="confirmMapping">
            按此映射开始校验
          </NButton>
          <NButton size="small" @click="cancelMapping">取消</NButton>
        </NSpace>
      </template>

      <!-- 运行中：进度 + 取消 -->
      <template v-if="importPhase === 'RUNNING'">
        <div class="transfer__progress">
          <NProgress
            type="line"
            :percentage="importProgress"
            :status="importTask?.status === 'IMPORTING' ? 'success' : 'default'"
            :height="10"
          />
          <span class="transfer__phase">{{ importTask?.phase ?? '处理中…' }}</span>
        </div>
        <NButton size="small" type="error" ghost @click="cancelImport">取消任务</NButton>
      </template>

      <!-- 校验完成：预览 + 错误清单 + 确认 -->
      <template v-if="importPhase === 'REVIEW' && importTask">
        <div class="transfer__summary">
          <NTag type="success" size="small">可导入 {{ importTask.totalRows - importTask.failedRows }} 行</NTag>
          <NTag :type="importTask.errors.length > 0 ? 'error' : 'default'" size="small">
            {{ importTask.errors.length }} 处错误
          </NTag>
        </div>

        <template v-if="importTask.errors.length > 0">
          <h4 class="transfer__sub">错误定位（可下载报告回源修正）</h4>
          <div class="transfer__errors">
            <div v-for="(error, index) in importTask.errors.slice(0, 20)" :key="index" class="transfer__error">
              <NTag size="small" type="error">第 {{ error.row }} 行</NTag>
              <NTag size="small" :bordered="false">{{ error.col }}</NTag>
              <span class="transfer__error-value">"{{ error.value }}"</span>
              <span class="transfer__error-msg">{{ error.message }}</span>
            </div>
            <p v-if="importTask.errors.length > 20" class="transfer__hint">
              仅展示前 20 条，完整清单共 {{ importTask.errors.length }} 条 —— 请下载错误报告。
            </p>
          </div>
          <NButton size="small" class="transfer__report" @click="downloadErrorReport">下载错误报告</NButton>
        </template>

        <template v-if="importTask.preview.length > 0">
          <h4 class="transfer__sub">合规行预览（前 {{ importTask.preview.length }} 行 / 共
            {{ importTask.totalRows - importTask.failedRows }} 行）</h4>
          <div class="transfer__preview">
            <div class="transfer__preview-row transfer__preview-row--head">
              <span v-for="col in PREVIEW_COLUMNS" :key="col">{{ col }}</span>
            </div>
            <div v-for="(row, index) in importTask.preview" :key="index" class="transfer__preview-row">
              <span v-for="col in PREVIEW_COLUMNS" :key="col">{{ row[col] }}</span>
            </div>
          </div>
        </template>

        <NSpace class="transfer__actions">
          <NButton type="primary" @click="confirmImport">确认导入 {{ importTask.totalRows - importTask.failedRows }} 行</NButton>
          <NButton @click="importPhase = 'IDLE'">放弃</NButton>
        </NSpace>
      </template>

      <!-- 终态 -->
      <template v-if="importPhase === 'DONE' && importTask">
        <div class="transfer__summary">
          <NTag type="success" size="small">{{ importTask.phase }}</NTag>
          <NTag v-if="importTask.status === 'PARTIAL'" type="warning" size="small">
            失败 {{ importTask.failedRows }} 行
          </NTag>
        </div>
        <NSpace class="transfer__actions">
          <NButton
            v-if="importTask.status === 'PARTIAL'"
            type="primary"
            @click="retryFailed"
          >
            重试失败行（{{ importTask.failedRows }}）
          </NButton>
          <NButton v-if="importTask.errors.length > 0" size="small" @click="downloadErrorReport">
            下载错误报告
          </NButton>
          <NButton @click="importPhase = 'IDLE'">再导一批</NButton>
        </NSpace>
      </template>

      <template v-if="importPhase === 'FAILED'">
        <div class="transfer__summary">
          <NTag type="error" size="small">{{ importTask?.phase ?? importError ?? '处理失败' }}</NTag>
        </div>
        <NSpace class="transfer__actions">
          <NButton v-if="importTask && importTask.errors.length > 0" size="small" @click="downloadErrorReport">
            下载错误报告
          </NButton>
          <NButton @click="importPhase = 'IDLE'">重新开始</NButton>
        </NSpace>
      </template>
    </NCard>

    <!-- ==================== 导出 ==================== -->
    <NCard title="② 数据导出（范围 / 字段可选 · 异步进度 · 可取消）" :bordered="false" class="transfer__card">
      <div class="transfer__export-controls">
        <div class="transfer__field">
          <label>导出范围</label>
          <NRadioGroup v-model:value="exportScope" size="small">
            <NRadioButton value="ALL">全部</NRadioButton>
            <NRadioButton value="PAGE">当前页</NRadioButton>
            <NRadioButton value="SELECTED">选中行</NRadioButton>
          </NRadioGroup>
          <span v-if="exportScope === 'SELECTED'" class="transfer__hint">
            已选 {{ selectedIds.length }} 行
          </span>
        </div>
        <div class="transfer__field">
          <label>导出字段</label>
          <NCheckboxGroup v-model:value="exportFields">
            <NCheckbox v-for="field in EXPORT_FIELDS" :key="field" :value="field" :label="field" />
          </NCheckboxGroup>
        </div>
        <NSpace align="center">
          <NButton
            type="primary"
            size="small"
            :disabled="exportPhase === 'RUNNING' || !!exportDisabledReason"
            @click="startExport"
          >
            开始导出
          </NButton>
          <NButton v-if="exportPhase === 'RUNNING'" size="small" type="error" ghost @click="cancelExport">
            取消
          </NButton>
        </NSpace>
      </div>

      <div v-if="exportPhase === 'RUNNING'" class="transfer__progress">
        <NProgress type="line" :percentage="exportProgress" :height="10" />
        <span class="transfer__phase">{{ exportTask?.phase ?? '处理中…' }}</span>
      </div>
      <div v-if="exportPhase === 'DONE'" class="transfer__summary">
        <NTag type="success" size="small">
          导出完成：{{ exportTask?.totalRows ?? 0 }} 行 × {{ exportFields.length }} 列
        </NTag>
        <NButton size="small" type="primary" @click="downloadResult">下载文件</NButton>
      </div>

      <!-- 任务列表底座：导出范围"当前页/选中"的数据来源 -->
      <ProTable
        ref="tableRef"
        :columns="taskColumns"
        :request="wrappedTaskRequest"
        selection-mode="multiple"
        :reserve-selection="true"
        :row-key="'id'"
        :toolbar="[]"
        @loaded="handleSelection"
        @selection-change="handleSelection"
      >
        <template #toolbar-extra>
          <NButton size="small" quaternary @click="refreshTable">刷新列表</NButton>
        </template>
      </ProTable>
      <p class="transfer__hint">
        "当前页"导出的是此刻表格可见页的行（经请求包装器维护的 id 快照）；
        "选中"来自勾选，翻页不丢（跨页保留）。
      </p>
    </NCard>
  </div>
</template>

<style scoped>
.transfer__card {
  margin-bottom: 16px;
}

.transfer__steps {
  display: flex;
  gap: 28px;
  margin-bottom: 16px;
}

.transfer__step {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--wa-text-disabled, #a8b0ba);
  font-size: var(--wa-font-size-sm, 13px);
}

.transfer__step-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border: 1px solid currentcolor;
  border-radius: 50%;
  font-size: 11px;
}

.transfer__step--active {
  color: var(--wa-color-primary, #2563eb);
  font-weight: 600;
}

.transfer__step--done {
  color: var(--wa-color-success, #18a058);
}

.transfer__actions {
  margin-top: 14px;
}

.transfer__mapping {
  margin-top: 6px;
  max-width: 520px;
}

.transfer__mapping-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.transfer__mapping-field {
  width: 90px;
  font-size: 13px;
  text-align: right;
}

.transfer__mapping-select {
  flex: 1;
}

.transfer__mapping-hint {
  font-size: 12px;
  color: #f0a020;
  margin: 4px 0 0;
}

.transfer__picker {
  display: inline-flex;
  align-items: center;
  padding: 6px 14px;
  cursor: pointer;
  border: 1px dashed var(--wa-border, #e4e7ed);
  border-radius: var(--wa-radius-md, 4px);
  color: var(--wa-color-primary, #2563eb);
  font-size: var(--wa-font-size-sm, 13px);
}

.transfer__picker input {
  display: none;
}

.transfer__progress {
  display: flex;
  gap: 12px;
  align-items: center;
  margin: 14px 0;
}

.transfer__progress > :first-child {
  flex: 1;
}

.transfer__phase {
  flex: none;
  min-width: 220px;
  font-size: var(--wa-font-size-sm, 13px);
  color: var(--wa-text-secondary, #5c6570);
}

.transfer__summary {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 12px 0;
}

.transfer__sub {
  margin: 14px 0 8px;
  font-size: var(--wa-font-size-sm, 13px);
  color: var(--wa-text-primary, #1f2329);
}

.transfer__errors {
  max-height: 220px;
  overflow: auto;
  border: 1px solid var(--wa-border-light, #f0f2f5);
  border-radius: var(--wa-radius-md, 4px);
}

.transfer__error {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 6px 10px;
  font-size: var(--wa-font-size-sm, 13px);
  border-bottom: 1px dashed var(--wa-border-light, #f0f2f5);
}

.transfer__error-value {
  color: var(--wa-color-error, #d03050);
}

.transfer__error-msg {
  flex: 1;
  color: var(--wa-text-secondary, #5c6570);
}

.transfer__report {
  margin-top: 10px;
}

.transfer__preview {
  max-height: 240px;
  overflow: auto;
  border: 1px solid var(--wa-border-light, #f0f2f5);
  border-radius: var(--wa-radius-md, 4px);
}

.transfer__preview-row {
  display: grid;
  grid-template-columns: 1.2fr 1.6fr 0.8fr 0.7fr 0.8fr 0.9fr 0.9fr 0.8fr;
  gap: 8px;
  padding: 5px 10px;
  font-size: 12.5px;
  border-bottom: 1px dashed var(--wa-border-light, #f0f2f5);
}

.transfer__preview-row--head {
  position: sticky;
  top: 0;
  z-index: 1;
  background: var(--wa-fill-light, #f5f7fa);
  font-weight: 600;
}

.transfer__export-controls {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 14px;
}

.transfer__field {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}

.transfer__field > label {
  flex: none;
  width: 70px;
  font-size: var(--wa-font-size-sm, 13px);
  color: var(--wa-text-secondary, #5c6570);
}

.transfer__hint {
  margin: 8px 0 0;
  color: var(--wa-text-secondary, #5c6570);
  font-size: var(--wa-font-size-sm, 13px);
}
</style>
