<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  AuthButton,
  PageContainer,
  ProEditor,
  ProForm,
  ProModal,
  ProTable,
  feedback
} from '@admin/ui'
import type {
  ProColumn,
  ProEditorUploadResult,
  ProFormExpose,
  ProFormItem,
  ProRowAction,
  ProTableExpose
} from '@admin/ui'
import type { PaperRequest, PaperResponse, TemplateResponse } from '@admin/api'
import {
  FILE_BIZ_TYPE,
  applySurveyTemplateAction,
  bumpSurveyPaperVersionAction,
  createSurveyPaperAction,
  deleteSurveyPaperAction,
  fetchSurveyPaperPage,
  fetchSurveyTemplatePage,
  fetchSurveyFileDataUrl,
  loadSurveyPaperDetail,
  requireId,
  updateSurveyPaperAction,
  uploadSurveyFileAction
} from '@/api/survey'

/**
 * 问卷 / 提纲设计。
 *
 * <p>两个值得注意的点：
 * <ol>
 *   <li><b>富文本正文只在详情接口返回</b>：列表接口刻意不返回 content
 *       （一页 20 条正文会把列表接口撑爆）。因此"编辑"必须先取详情，
 *       不能直接拿列表行去填表单 —— 那样正文会是空的，保存即丢失。</li>
 *   <li><b>插图用 data URL</b>：附件接口需要认证，而正文里的 {@code <img src>}
 *       不会带 Authorization 头。转 data URL 后正文自包含，
 *       代价是正文体积变大（因此插图单独限 2MB）。详见 api/survey.ts 的说明。</li>
 * </ol>
 */
type PaperRow = PaperResponse
type TemplateRow = TemplateResponse

const tableRef = ref<ProTableExpose<PaperRow> | null>(null)
const pickerRef = ref<ProTableExpose<TemplateRow> | null>(null)
const formRef = ref<ProFormExpose | null>(null)

const formVisible = ref(false)
const editingId = ref<number | null>(null)
const modalModel = ref<Record<string, unknown>>({})
const content = ref('')

// ---------------------------------------------------------------------
// 列表
// ---------------------------------------------------------------------

const columns: ProColumn<PaperRow>[] = [
  { key: 'paperCode', title: '编码', minWidth: 170, search: 'input', searchPlaceholder: '模糊匹配' },
  { key: 'title', title: '标题', minWidth: 240, search: 'input', searchPlaceholder: '模糊匹配' },
  { key: 'paperType', title: '类型', width: 110, dict: 'srvy_paper_type', search: 'select' },
  { key: 'versionNo', title: '版本', width: 80 },
  { key: 'status', title: '状态', width: 110, dict: 'srvy_paper_status', search: 'select' },
  { key: 'createTime', title: '创建时间', width: 170, render: 'datetime' }
]

const rowActions: ProRowAction<PaperRow>[] = [
  { key: 'edit', label: '编辑', permission: 'srvy:paper:update', onClick: (row) => openForm(row) },
  {
    key: 'version',
    label: '升版',
    permission: 'srvy:paper:update',
    confirm: (row) =>
      `确定把「${row.title}」升级到下一版本？新版本回到草稿态；已回收的数据仍对应旧版本。`,
    onClick: async (row) => {
      await bumpSurveyPaperVersionAction(requireId(row))
      feedback.success('已升版，当前为草稿态')
      await tableRef.value?.refresh()
    }
  },
  {
    key: 'delete',
    label: '删除',
    permission: 'srvy:paper:delete',
    danger: true,
    confirm: (row) => `确定删除「${row.title}」？已回收的答卷不会随之删除。`,
    onClick: async (row) => {
      await deleteSurveyPaperAction(requireId(row))
      feedback.success('问卷已删除')
      await tableRef.value?.refresh()
    }
  }
]

// ---------------------------------------------------------------------
// 表单
// ---------------------------------------------------------------------

const formItems = computed<ProFormItem[]>(() => [
  {
    field: 'paperCode',
    title: '编码',
    required: true,
    disabled: editingId.value !== null,
    placeholder: '如 PAPER-NPS-V4',
    tip: '创建后不建议修改：回收的数据按编码+版本归档'
  },
  { field: 'title', title: '标题', required: true, placeholder: '如 NPS 满意度问卷 v4' },
  {
    field: 'paperType',
    title: '类型',
    type: 'select',
    dict: 'srvy_paper_type',
    value: 'QUESTIONNAIRE',
    tip: '问卷用于结构化回收；提纲用于访谈/观察的开放式记录'
  },
  { field: 'versionNo', title: '版本号', type: 'number', value: 1, props: { min: 1 } },
  { field: 'status', title: '状态', type: 'select', dict: 'srvy_paper_status', value: 'DRAFT' },
  { field: 'remark', title: '备注', type: 'textarea' }
])

/**
 * 打开编辑：先取详情。
 *
 * <p>列表行没有 content（列表接口不返回正文），直接用行数据填表单会在保存时
 * <b>把正文清空</b> —— 这是"编辑时先取详情"这类规则最典型的踩坑方式。
 */
async function openForm(row: PaperRow | null): Promise<void> {
  if (row === null) {
    editingId.value = null
    modalModel.value = {}
    content.value = ''
    formVisible.value = true
    return
  }
  const id = requireId(row)
  try {
    const detail = await loadSurveyPaperDetail(id)
    editingId.value = id
    content.value = detail.content ?? ''
    modalModel.value = {
      paperCode: detail.paperCode ?? '',
      title: detail.title ?? '',
      paperType: detail.paperType ?? 'QUESTIONNAIRE',
      versionNo: detail.versionNo ?? 1,
      status: detail.status ?? 'DRAFT',
      remark: detail.remark ?? ''
    }
    formVisible.value = true
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '加载详情失败')
  }
}

function toPayload(values: Record<string, unknown>): PaperRequest {
  return {
    paperCode: String(values.paperCode ?? ''),
    title: String(values.title ?? ''),
    paperType: values.paperType ? String(values.paperType) : 'QUESTIONNAIRE',
    versionNo: typeof values.versionNo === 'number' ? values.versionNo : 1,
    content: content.value,
    status: values.status ? String(values.status) : 'DRAFT',
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
    await updateSurveyPaperAction(editingId.value, payload)
  } else {
    await createSurveyPaperAction(payload)
  }
  feedback.success('保存成功')
  formVisible.value = false
  await (editingId.value === null ? tableRef.value?.reload() : tableRef.value?.refresh())
}

/** 富文本插图：上传 → 转 data URL → 插入正文。 */
async function uploadEditorImage(file: File): Promise<ProEditorUploadResult> {
  const uploaded = await uploadSurveyFileAction(file, FILE_BIZ_TYPE.editor)
  if (!uploaded.id) {
    throw new Error('上传返回缺少附件 ID')
  }
  return { url: await fetchSurveyFileDataUrl(uploaded.id), alt: uploaded.fileName }
}

// ---------------------------------------------------------------------
// 从模板新建（单选表格 + 回选）
// ---------------------------------------------------------------------

const pickerVisible = ref(false)
const pickerRows = ref<TemplateRow[]>([])

const pickerColumns: ProColumn<TemplateRow>[] = [
  { key: 'templateName', title: '模板名称', minWidth: 220 },
  { key: 'category', title: '分类', width: 130, dict: 'srvy_template_category' },
  { key: 'usageCount', title: '引用次数', width: 100 },
  { key: 'status', title: '状态', width: 100, dict: 'sys_status' }
]

const pickerRequest = async (query: Parameters<typeof fetchSurveyTemplatePage>[0]) => {
  const result = await fetchSurveyTemplatePage(query)
  pickerRows.value = result.records
  return result
}

/**
 * 回选：默认选中引用次数最高的模板。
 *
 * <p>单选表格的 {@code checkedKeys} 只取第一个命中的主键 —— 这里传的就是那一个。
 * 语义上等价于"给一个推荐项"，用户可直接确认或改选。
 */
const pickerCheckedKeys = computed<Array<string | number>>(() => {
  const best = [...pickerRows.value]
    .filter((row) => row.id !== undefined)
    .sort((a, b) => (b.usageCount ?? 0) - (a.usageCount ?? 0))[0]
  return best?.id === undefined ? [] : [best.id]
})

async function handleApplyTemplate(): Promise<void> {
  const selected = pickerRef.value?.getSelection() ?? []
  const template = selected[0]
  if (!template?.id) {
    feedback.warning('请先选择一个模板')
    return
  }
  try {
    const detail = await applySurveyTemplateAction(template.id)
    pickerVisible.value = false
    editingId.value = null
    content.value = detail.content ?? ''
    modalModel.value = {
      title: `${detail.templateName ?? '模板'} 副本`,
      paperType: 'QUESTIONNAIRE',
      versionNo: 1,
      status: 'DRAFT',
      remark: `由模板「${detail.templateName ?? ''}」创建`
    }
    formVisible.value = true
    feedback.success('已套用模板，请补充编码与标题后保存')
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '套用模板失败')
  }
}
</script>

<template>
  <PageContainer
    title="问卷/提纲设计"
    description="富文本正文随版本管理；可从模板库套用后二次编辑"
  >
    <template #extra>
      <AuthButton
        permission="srvy:paper:update"
        type="default"
        size="small"
        @click="pickerVisible = true"
      >
        从模板新建
      </AuthButton>
      <AuthButton
        permission="srvy:paper:create"
        mode="disable"
        type="primary"
        size="small"
        denied-text="你没有新增问卷的权限"
        @click="openForm(null)"
      >
        新增问卷
      </AuthButton>
    </template>

    <ProTable
      ref="tableRef"
      :columns="columns"
      :request="fetchSurveyPaperPage"
      :toolbar="['refresh', 'columnSetting', 'density']"
      :row-actions="rowActions"
      empty-action-text="创建第一份问卷"
      @empty-action="openForm(null)"
    />
  </PageContainer>

  <ProModal
    v-model:visible="formVisible"
    :title="editingId !== null ? '编辑问卷' : '新增问卷'"
    :width="860"
    :cols="1"
    :submit="submitForm"
    :on-success="() => (editingId === null ? tableRef?.reload() : tableRef?.refresh())"
    @error="(error: unknown) => feedback.error(error instanceof Error ? error.message : '保存失败')"
  >
    <ProForm ref="formRef" :items="formItems" :model="modalModel" :cols="2" />

    <div class="paper-page__editor">
      <label class="paper-page__label">正文</label>
      <ProEditor
        v-model:value="content"
        :height="360"
        :upload-image="uploadEditorImage"
        placeholder="编辑问卷题目或访谈提纲；支持插入图片与富文本格式"
        @error="() => feedback.error('图片上传失败，请重试')"
      />
    </div>
  </ProModal>

  <!-- 模板选择：单选表格 + 默认回选引用次数最高的模板 -->
  <ProModal
    v-model:visible="pickerVisible"
    title="从模板新建问卷"
    :width="820"
    submit-text="套用所选模板"
    @success="handleApplyTemplate"
  >
    <p class="paper-page__hint">
      选择一份模板作为起点（默认已选中引用次数最高的一份）。套用后正文会复制到新问卷，可继续编辑。
    </p>
    <ProTable
      ref="pickerRef"
      :columns="pickerColumns"
      :request="pickerRequest"
      :toolbar="['refresh']"
      selection-mode="single"
      :checked-keys="pickerCheckedKeys"
      :default-page-size="5"
    />
  </ProModal>
</template>

<style scoped>
.paper-page__editor {
  margin-top: var(--wa-spacing-lg);
}

.paper-page__label {
  display: block;
  margin-bottom: var(--wa-spacing-xs);
  font-size: var(--wa-font-size-md);
  color: var(--wa-text-primary);
}

.paper-page__hint {
  margin: 0 0 var(--wa-spacing-md);
  font-size: var(--wa-font-size-xs);
  line-height: 1.6;
  color: var(--wa-text-secondary);
}
</style>
