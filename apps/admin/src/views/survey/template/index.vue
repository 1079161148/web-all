<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  AuthButton,
  PageContainer,
  ProEditor,
  ProExcel,
  ProForm,
  ProModal,
  ProTable,
  feedback,
  hasPermission
} from '@admin/ui'
import type {
  ProColumn,
  ProEditorUploadResult,
  ProExcelColumn,
  ProExcelExpose,
  ProExcelRowError,
  ProFormExpose,
  ProFormItem,
  ProRowAction,
  ProTableExpose
} from '@admin/ui'
import type { TemplateRequest, TemplateResponse } from '@admin/api'
import { useDict } from '@/composables/useDict'
import {
  FILE_BIZ_TYPE,
  applySurveyTemplateAction,
  batchCreateSurveyTemplatesAction,
  createSurveyTemplateAction,
  deleteSurveyTemplateAction,
  fetchSurveyFileDataUrl,
  fetchSurveyTemplatePage,
  loadSurveyTemplateDetail,
  requireId,
  updateSurveyTemplateAction,
  uploadSurveyFileAction
} from '@/api/survey'

/**
 * 调研模板库。
 *
 * <p>本页是<b>单选表格</b>的参考实现（与数据采集页的多选恰好互补）：
 * <ul>
 *   <li>单选：一个模板就是一份正文，一次只能套用一个 —— 多选列在这里是误导</li>
 *   <li>回选：默认选中引用次数最高的模板（用户最可能想要的那个）</li>
 *   <li>取选中项：{@code tableRef.getSelection()} 在单选模式下返回 0 或 1 项</li>
 * </ul>
 */
type TemplateRow = TemplateResponse

const tableRef = ref<ProTableExpose<TemplateRow> | null>(null)
const excelRef = ref<ProExcelExpose<TemplateRow> | null>(null)
const formRef = ref<ProFormExpose | null>(null)

const currentRows = ref<TemplateRow[]>([])

const requestPage = async (query: Parameters<typeof fetchSurveyTemplatePage>[0]) => {
  const result = await fetchSurveyTemplatePage(query)
  currentRows.value = result.records
  captureInitialSelection(result.records)
  return result
}

/**
 * 回选：默认选中引用次数最高的模板（只算一次）。
 *
 * <p>单选模式下 checkedKeys 只取第一个命中的主键。与采集页同理，
 * 刻意不按"当前页数据"每次派生：那会在翻页时不断改选，
 * 用户刚点的那一行会被覆盖掉。
 */
const initialCheckedKeys = ref<Array<string | number> | undefined>(undefined)

const checkedKeys = computed(() => initialCheckedKeys.value)

function captureInitialSelection(records: TemplateRow[]): void {
  if (initialCheckedKeys.value !== undefined) {
    return
  }
  const best = [...records]
    .filter((row) => row.id !== undefined)
    .sort((a, b) => (b.usageCount ?? 0) - (a.usageCount ?? 0))[0]
  initialCheckedKeys.value = best?.id === undefined ? [] : [best.id]
}

const columns: ProColumn<TemplateRow>[] = [
  { key: 'templateCode', title: '模板编码', minWidth: 160, search: 'input', searchPlaceholder: '模糊匹配' },
  { key: 'templateName', title: '模板名称', minWidth: 240, search: 'input', searchPlaceholder: '模糊匹配' },
  { key: 'category', title: '分类', width: 130, dict: 'srvy_template_category', search: 'select' },
  { key: 'usageCount', title: '引用次数', width: 100 },
  { key: 'status', title: '状态', width: 100, dict: 'sys_status', search: 'select' },
  { key: 'createTime', title: '创建时间', width: 170, render: 'datetime' }
]

const rowActions: ProRowAction<TemplateRow>[] = [
  {
    key: 'apply',
    label: '套用',
    permission: 'srvy:template:query',
    onClick: async (row) => {
      const detail = await applySurveyTemplateAction(requireId(row))
      previewModel.value = {
        templateName: detail.templateName ?? '',
        category: detail.category ?? '',
        usageCount: detail.usageCount ?? 0,
        status: detail.status ?? ''
      }
      previewContent.value = detail.content ?? ''
      previewVisible.value = true
      feedback.success('已取用模板正文，引用次数 +1')
      await tableRef.value?.refresh()
    }
  },
  { key: 'edit', label: '编辑', permission: 'srvy:template:update', onClick: (row) => openForm(row) },
  {
    key: 'delete',
    label: '删除',
    permission: 'srvy:template:delete',
    danger: true,
    confirm: (row) => `确定删除模板「${row.templateName}」？已用该模板创建的问卷不受影响。`,
    onClick: async (row) => {
      await deleteSurveyTemplateAction(requireId(row))
      feedback.success('模板已删除')
      await tableRef.value?.refresh()
    }
  }
]

/** 读取单选结果（演示"选一条做后续动作"的用法）。 */
async function openSelected(): Promise<void> {
  const selected = tableRef.value?.getSelection() ?? []
  if (selected.length === 0) {
    feedback.warning('请先在左侧勾选一个模板')
    return
  }
  await rowActions[0].onClick(selected[0])
}

// ---------------------------------------------------------------------
// 表单
// ---------------------------------------------------------------------

const formVisible = ref(false)
const editingId = ref<number | null>(null)
const modalModel = ref<Record<string, unknown>>({})
const content = ref('')

const formItems: ProFormItem[] = [
  {
    field: 'templateCode',
    title: '模板编码',
    required: true,
    placeholder: '如 TPL-NPS-02',
    tip: '字母开头，仅含字母、数字、下划线与连字符'
  },
  { field: 'templateName', title: '模板名称', required: true, placeholder: '如 标准 NPS 满意度模板' },
  { field: 'category', title: '分类', type: 'select', dict: 'srvy_template_category', value: 'NPS' },
  {
    field: 'usageCount',
    title: '引用次数',
    type: 'number',
    value: 0,
    props: { min: 0 },
    disabled: true,
    tip: '由"套用"操作自动累加，无需手工维护'
  },
  { field: 'status', title: '状态', type: 'select', dict: 'sys_status', value: 'ACTIVE' },
  { field: 'remark', title: '备注', type: 'textarea' }
]

/** 编辑前取详情：列表不返回正文，直接用行数据保存会清空正文。 */
async function openForm(row: TemplateRow | null): Promise<void> {
  if (row === null) {
    editingId.value = null
    modalModel.value = {}
    content.value = ''
    formVisible.value = true
    return
  }
  const id = requireId(row)
  try {
    const detail = await loadSurveyTemplateDetail(id)
    editingId.value = id
    content.value = detail.content ?? ''
    modalModel.value = {
      templateCode: detail.templateCode ?? '',
      templateName: detail.templateName ?? '',
      category: detail.category ?? 'NPS',
      usageCount: detail.usageCount ?? 0,
      status: detail.status ?? 'ACTIVE',
      remark: detail.remark ?? ''
    }
    formVisible.value = true
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '加载详情失败')
  }
}

function toPayload(values: Record<string, unknown>): TemplateRequest {
  return {
    templateCode: String(values.templateCode ?? ''),
    templateName: String(values.templateName ?? ''),
    category: values.category ? String(values.category) : 'NPS',
    content: content.value,
    usageCount: typeof values.usageCount === 'number' ? values.usageCount : 0,
    status: values.status ? String(values.status) : 'ACTIVE',
    remark: values.remark ? String(values.remark) : undefined
  }
}

async function submitForm(): Promise<void> {
  const valid = await formRef.value?.validate()
  if (valid === false) {
    return
  }
  const payload = toPayload(formRef.value?.getValues() ?? {})
  if (editingId.value !== null) {
    await updateSurveyTemplateAction(editingId.value, payload)
  } else {
    await createSurveyTemplateAction(payload)
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
// 预览
// ---------------------------------------------------------------------

const previewVisible = ref(false)
const previewModel = ref<Record<string, unknown>>({})
const previewContent = ref('')

const previewItems: ProFormItem[] = [
  { field: 'templateName', title: '模板名称' },
  { field: 'category', title: '分类', type: 'select', dict: 'srvy_template_category' },
  { field: 'usageCount', title: '引用次数' },
  { field: 'status', title: '状态', type: 'select', dict: 'sys_status' }
]

// ---------------------------------------------------------------------
// 导入 / 导出
// ---------------------------------------------------------------------

const dicts = useDict('srvy_template_category')

function labelOf(value?: string): string {
  if (!value) return ''
  return dicts.srvy_template_category.value.find((item) => item.value === value)?.label ?? value
}

function codeOf(value: unknown, fallback: string): string {
  if (value === undefined || value === null || value === '') return fallback
  const raw = String(value)
  const items = dicts.srvy_template_category.value
  if (items.some((item) => item.value === raw)) return raw
  return items.find((item) => item.label === raw)?.value ?? raw
}

const excelColumns: ProExcelColumn<TemplateRow>[] = [
  { key: 'templateCode', title: '模板编码', required: true, width: 20 },
  { key: 'templateName', title: '模板名称', required: true, width: 28 },
  {
    key: 'category',
    title: '分类',
    width: 14,
    format: (row) => labelOf(row.category),
    // 分类接受"编码"或"中文标签"两种填法；字典未加载时不误报
    validate: (value) => {
      if (value === undefined || value === null || value === '') return null
      const items = dicts.srvy_template_category.value
      if (items.length === 0) return null
      const raw = String(value)
      return items.some((item) => item.value === raw || item.label === raw)
        ? null
        : '分类取值不合法（NPS 满意度/可用性测试/访谈提纲/其他）'
    }
  },
  { key: 'usageCount', title: '引用次数', width: 12 },
  { key: 'status', title: '状态', width: 12 },
  { key: 'remark', title: '备注', width: 30 }
]

async function handleImported(rows: TemplateRow[]): Promise<void> {
  if (rows.length === 0) return
  const payload: TemplateRequest[] = rows.map((row) => ({
    templateCode: String(row.templateCode ?? ''),
    templateName: String(row.templateName ?? ''),
    category: codeOf(row.category, 'NPS'),
    usageCount: Number(row.usageCount) || 0,
    status: row.status ? String(row.status) : 'ACTIVE',
    remark: row.remark ? String(row.remark) : undefined
  }))
  try {
    const count = await batchCreateSurveyTemplatesAction(payload)
    feedback.success(`成功导入 ${count} 个模板`)
    await tableRef.value?.reload()
  } catch (error) {
    const message = error instanceof Error ? error.message : '导入失败'
    feedback.error(message)
    const errors: ProExcelRowError<TemplateRow>[] = [{ rowIndex: 1, message }]
    await excelRef.value?.downloadErrorRows(errors)
  }
}
</script>

<template>
  <PageContainer
    title="调研模板库"
    description="单选表格：一个模板就是一份正文，一次只能套用一个；默认选中引用次数最高的模板"
  >
    <template #extra>
      <ProExcel
        v-if="hasPermission('srvy:template:import')"
        ref="excelRef"
        :columns="excelColumns"
        file-name="调研模板"
        @imported="handleImported"
      />
      <AuthButton permission="srvy:template:query" type="default" size="small" @click="openSelected">
        查看所选
      </AuthButton>
      <AuthButton
        permission="srvy:template:create"
        mode="disable"
        type="primary"
        size="small"
        denied-text="你没有新增模板的权限"
        @click="openForm(null)"
      >
        新增模板
      </AuthButton>
    </template>

    <ProTable
      ref="tableRef"
      :columns="columns"
      :request="requestPage"
      :toolbar="['refresh', 'columnSetting', 'density']"
      :row-actions="rowActions"
      selection-mode="single"
      :checked-keys="checkedKeys"
      empty-action-text="创建第一个模板"
      @empty-action="openForm(null)"
    />
  </PageContainer>

  <ProModal
    v-model:visible="formVisible"
    :title="editingId !== null ? '编辑模板' : '新增模板'"
    :width="860"
    :submit="submitForm"
    :on-success="() => (editingId === null ? tableRef?.reload() : tableRef?.refresh())"
    @error="(error: unknown) => feedback.error(error instanceof Error ? error.message : '保存失败')"
  >
    <ProForm ref="formRef" :items="formItems" :model="modalModel" :cols="2" />
    <div class="template-page__section">
      <label class="template-page__label">模板正文</label>
      <ProEditor
        v-model:value="content"
        :height="340"
        :upload-image="uploadEditorImage"
        placeholder="模板正文；套用时会整段复制到新问卷"
        @error="() => feedback.error('图片上传失败，请重试')"
      />
    </div>
  </ProModal>

  <ProModal
    v-model:visible="previewVisible"
    :title="String(previewModel.templateName ?? '模板预览')"
    :width="820"
    readonly
  >
    <ProForm :items="previewItems" :model="previewModel" :cols="2" disabled />
    <div class="template-page__section">
      <label class="template-page__label">模板正文</label>
      <ProEditor :value="previewContent" :height="340" read-only />
    </div>
  </ProModal>
</template>

<style scoped>
.template-page__section {
  margin-top: var(--wa-spacing-lg);
  padding-top: var(--wa-spacing-lg);
  border-top: 1px solid var(--wa-divider);
}

.template-page__label {
  display: block;
  margin-bottom: var(--wa-spacing-xs);
  font-size: var(--wa-font-size-md);
  color: var(--wa-text-primary);
}
</style>
