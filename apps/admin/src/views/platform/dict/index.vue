<script setup lang="ts">
import { computed, h, ref } from 'vue'
import { NDrawer, NDrawerContent, NTag, PageContainer, ProModal, ProTable, feedback } from '@admin/ui'
import type { ProColumn, ProFormItem, ProRowAction } from '@admin/ui'
import { useDict } from '@/composables/useDict'
import { DictTag } from '@admin/ui'
import type { DictDataRequest, DictTypeRequest } from '@admin/api'
import {
  createDictDataAction,
  createDictTypeAction,
  deleteDictDataAction,
  deleteDictTypeAction,
  fetchDictDataPage,
  fetchDictTypePage,
  updateDictDataAction,
  updateDictTypeAction
} from '@/api/iam'

/**
 * 字典管理页（类型 + 字典项两级）。
 *
 * <h3>为什么用「主表 + 抽屉」而不是左右分栏</h3>
 * 左右分栏在窄屏下会把两个表都挤得没法用，而字典维护是一个
 * "先选类型、再维护它的项"的<b>串行</b>流程 —— 抽屉正好表达这种从属关系，
 * 并且关掉抽屉就回到类型列表，不会留下"当前到底在看哪个类型"的困惑。
 *
 * <h3>{@code sourceTenantId} 列的意义</h3>
 * 字典支持「平台默认(0) + 租户覆盖」。这一列让管理员能一眼分辨
 * "这条是平台给的还是本租户改的" —— 没有这个可见性，
 * "为什么这个租户的文案和别的不一样"会变成一个纯靠猜的问题。
 */

const typeTableRef = ref<{ reload: () => void; refresh: () => void } | null>(null)
const dicts = useDict('sys_status')

interface DictTypeRow {
  id?: number
  dictName?: string
  dictType?: string
  status?: string
  remark?: string
  sourceTenantId?: number
}

/** 数据来源标记：平台默认 vs 租户覆盖。 */
function sourceTag(sourceTenantId?: number) {
  return sourceTenantId === 0 || sourceTenantId === undefined
    ? h(NTag, { size: 'small', type: 'default', bordered: false }, { default: () => '平台默认' })
    : h(
        NTag,
        { size: 'small', type: 'info', bordered: false },
        { default: () => `租户覆盖 #${sourceTenantId}` }
      )
}

// ---------------------------------------------------------------------
// 字典类型
// ---------------------------------------------------------------------

const typeColumns = computed<ProColumn<DictTypeRow>[]>(() => [
  { key: 'dictName', title: '字典名称', minWidth: 150, search: 'input' },
  {
    key: 'dictType',
    title: '类型编码',
    width: 190,
    search: 'input',
    renderFn: (row) => h('code', { class: 'dict-page__code' }, row.dictType ?? '')
  },
  {
    key: 'status',
    title: '状态',
    width: 100,
    search: 'select',
    options: dicts.sys_status.value.map((o) => ({ label: o.label, value: o.value })),
    renderFn: (row) => h(DictTag, { dictType: 'sys_status', value: row.status })
  },
  {
    key: 'sourceTenantId',
    title: '数据来源',
    width: 140,
    renderFn: (row) => sourceTag(row.sourceTenantId)
  },
  { key: 'remark', title: '备注', minWidth: 140 }
])

const typeRowActions: ProRowAction<DictTypeRow>[] = [
  {
    key: 'items',
    label: '管理字典项',
    permission: 'plt:dict:query',
    onClick: (row) => openItems(row)
  },
  {
    key: 'edit',
    label: '编辑',
    permission: 'plt:dict:update',
    onClick: (row) => openTypeForm(row)
  },
  {
    key: 'toggleStatus',
    // 文案跟当前状态走：启用中显示「禁用」（点是禁用），已禁用显示「正常」
    label: (row) => (row.status === 'ACTIVE' ? '禁用' : '正常'),
    permission: 'plt:dict:update',
    confirm: (row) =>
      row.status === 'ACTIVE'
        ? `确定禁用字典「${row.dictName}」？禁用后其下所有字典项不再下发到前端，使用该字典的页面会拿不到选项。`
        : `确定启用字典「${row.dictName}」？`,
    onClick: async (row) => {
      if (row.id === undefined) return
      const next = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
      // 后端没有独立的改状态接口，按全量更新提交（其余字段原样带回，避免被覆盖成空）
      await updateDictTypeAction(row.id, {
        dictName: row.dictName ?? '',
        dictType: row.dictType ?? '',
        status: next,
        remark: row.remark ?? ''
      })
      feedback.success(next === 'ACTIVE' ? `字典「${row.dictName}」已启用` : `字典「${row.dictName}」已禁用`)
      typeTableRef.value?.refresh()
    }
  },
  {
    key: 'delete',
    label: '删除',
    permission: 'plt:dict:delete',
    danger: true,
    confirm: (row) =>
      `确定删除字典类型「${row.dictName}」？若其下仍有字典项，删除会被拒绝。`,
    onClick: async (row) => {
      if (row.id === undefined) return
      await deleteDictTypeAction(row.id)
      feedback.success('字典类型已删除')
      typeTableRef.value?.refresh()
    }
  }
]

const typeFormVisible = ref(false)
const typeEditingId = ref<number | null>(null)
const typeModalModel = ref<Record<string, unknown>>({})

const typeFormItems: ProFormItem[] = [
  { field: 'dictName', title: '字典名称', required: true, placeholder: '如 用户性别' },
  {
    field: 'dictType',
    title: '类型编码',
    required: true,
    placeholder: '如 sys_user_sex',
    tip: '小写字母开头，仅含小写字母、数字与下划线。代码里按它取字典，创建后不建议修改。'
  },
  { field: 'status', title: '状态', type: 'select', dict: 'sys_status', value: 'ACTIVE' },
  { field: 'remark', title: '备注', type: 'textarea' }
]

function openTypeForm(row: DictTypeRow | null): void {
  if (row) {
    typeEditingId.value = row.id ?? null
    typeModalModel.value = {
      dictName: row.dictName ?? '',
      dictType: row.dictType ?? '',
      status: row.status ?? 'ACTIVE',
      remark: row.remark ?? ''
    }
  } else {
    typeEditingId.value = null
    typeModalModel.value = { status: 'ACTIVE' }
  }
  typeFormVisible.value = true
}

async function submitTypeForm(values: Record<string, unknown>): Promise<void> {
  const payload: DictTypeRequest = {
    dictName: String(values.dictName ?? ''),
    dictType: String(values.dictType ?? ''),
    status: values.status ? String(values.status) : 'ACTIVE',
    remark: values.remark ? String(values.remark) : ''
  }
  if (typeEditingId.value !== null) {
    await updateDictTypeAction(typeEditingId.value, payload)
  } else {
    await createDictTypeAction(payload)
  }
  feedback.success('保存成功')
}

// ---------------------------------------------------------------------
// 字典项（抽屉）
// ---------------------------------------------------------------------

const itemsVisible = ref(false)
const itemsLoading = ref(false)
const currentType = ref('')
const itemTableRef = ref<{ reload: () => void; refresh: () => void } | null>(null)

interface DictDataRow {
  id?: number
  dictType?: string
  dictLabel?: string
  dictValue?: string
  sort?: number
  cssClass?: string
  isDefault?: boolean
  status?: string
  remark?: string
  sourceTenantId?: number
}

function openItems(row: DictTypeRow): void {
  currentType.value = row.dictType ?? ''
  itemsVisible.value = true
  // 抽屉打开后再触发列表加载：ProTable 的 immediate 在挂载时执行，
  // 而此时 currentType 已经是新值
  void itemTableRef.value
}

/** 字典项的查询被限定在当前类型上 —— 直接复用生成的接口并按类型过滤。 */
const fetchItemsForCurrentType = async (query: import('@admin/ui').ProTableQuery) => {
  itemsLoading.value = true
  try {
    return await fetchDictDataPage({ ...query, dictType: currentType.value })
  } finally {
    itemsLoading.value = false
  }
}

const itemColumns: ProColumn<DictDataRow>[] = [
  { key: 'dictLabel', title: '标签', minWidth: 130, search: 'input', searchPlaceholder: '模糊匹配' },
  {
    key: 'dictValue',
    title: '键值',
    width: 120,
    renderFn: (row) => h('code', { class: 'dict-page__code' }, row.dictValue ?? '')
  },
  { key: 'sort', title: '排序', width: 80 },
  {
    key: 'cssClass',
    title: '标签样式',
    width: 120,
    // 直接渲染成实际效果而不是显示样式的名字 —— 配置样式时所见即所得
    renderFn: (row) =>
      row.dictLabel
        ? h(DictTag, { dictType: currentType.value, value: row.dictValue })
        : h('span', { class: 'dict-page__muted' }, '—')
  },
  {
    key: 'status',
    title: '状态',
    width: 100,
    search: 'select',
    dict: 'sys_status',
    renderFn: (row) => h(DictTag, { dictType: 'sys_status', value: row.status })
  },
  {
    key: 'sourceTenantId',
    title: '来源',
    width: 130,
    renderFn: (row) => sourceTag(row.sourceTenantId)
  }
]

const itemRowActions: ProRowAction<DictDataRow>[] = [
  { key: 'edit', label: '编辑', permission: 'plt:dict:update', onClick: (row) => openItemForm(row) },
  {
    key: 'toggleStatus',
    label: (row) => (row.status === 'ACTIVE' ? '禁用' : '正常'),
    permission: 'plt:dict:update',
    confirm: (row) =>
      row.status === 'ACTIVE'
        ? `确定禁用字典项「${row.dictLabel}」？禁用后该项不再出现在下拉与标签渲染中。`
        : `确定启用字典项「${row.dictLabel}」？`,
    onClick: async (row) => {
      if (row.id === undefined) return
      const next = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
      await updateDictDataAction(row.id, {
        dictType: row.dictType ?? currentType.value,
        dictLabel: row.dictLabel ?? '',
        dictValue: row.dictValue ?? '',
        sort: row.sort ?? 0,
        cssClass: row.cssClass ?? 'default',
        status: next,
        remark: row.remark ?? undefined
      })
      feedback.success(next === 'ACTIVE' ? `字典项「${row.dictLabel}」已启用` : `字典项「${row.dictLabel}」已禁用`)
      itemTableRef.value?.refresh()
    }
  },
  {
    key: 'delete',
    label: '删除',
    permission: 'plt:dict:delete',
    danger: true,
    confirm: (row) => `确定删除字典项「${row.dictLabel}」？`,
    onClick: async (row) => {
      if (row.id === undefined) return
      await deleteDictDataAction(row.id)
      feedback.success('字典项已删除')
      itemTableRef.value?.refresh()
    }
  }
]

const itemFormVisible = ref(false)
const itemEditingId = ref<number | null>(null)
const itemModalModel = ref<Record<string, unknown>>({})

/** 标签样式的可选项：与 NTag 的 type 白名单保持一致（避免配出无效样式）。 */
const cssClassOptions = [
  { label: '默认（灰）', value: 'default' },
  { label: '主色（蓝）', value: 'primary' },
  { label: '信息（青）', value: 'info' },
  { label: '成功（绿）', value: 'success' },
  { label: '警告（橙）', value: 'warning' },
  { label: '错误（红）', value: 'error' }
]

const itemFormItems: ProFormItem[] = [
  { field: 'dictType', title: '字典类型', disabled: true },
  { field: 'dictLabel', title: '标签', required: true, placeholder: '展示给用户看的文案，如 正常' },
  { field: 'dictValue', title: '键值', required: true, placeholder: '数据库存储的值，如 ACTIVE' },
  { field: 'sort', title: '显示顺序', type: 'number', value: 0, props: { min: 0 } },
  { field: 'cssClass', title: '标签样式', type: 'select', options: cssClassOptions, value: 'default' },
  { field: 'status', title: '状态', type: 'select', dict: 'sys_status', value: 'ACTIVE' }
]

function openItemForm(row: DictDataRow | null): void {
  if (row) {
    itemEditingId.value = row.id ?? null
    itemModalModel.value = {
      dictType: row.dictType ?? currentType.value,
      dictLabel: row.dictLabel ?? '',
      dictValue: row.dictValue ?? '',
      sort: row.sort ?? 0,
      cssClass: row.cssClass ?? 'default',
      status: row.status ?? 'ACTIVE',
      remark: row.remark ?? ''
    }
  } else {
    itemEditingId.value = null
    itemModalModel.value = {
      dictType: currentType.value,
      sort: 0,
      cssClass: 'default',
      status: 'ACTIVE'
    }
  }
  itemFormVisible.value = true
}

async function submitItemForm(values: Record<string, unknown>): Promise<void> {
  const payload: DictDataRequest = {
    dictType: String(values.dictType ?? currentType.value),
    dictLabel: String(values.dictLabel ?? ''),
    dictValue: String(values.dictValue ?? ''),
    sort: values.sort === undefined || values.sort === null ? 0 : Number(values.sort),
    cssClass: values.cssClass ? String(values.cssClass) : 'default',
    status: values.status ? String(values.status) : 'ACTIVE',
    remark: values.remark ? String(values.remark) : undefined
  }
  if (itemEditingId.value !== null) {
    await updateDictDataAction(itemEditingId.value, payload)
  } else {
    await createDictDataAction(payload)
  }
  feedback.success('保存成功')
}
</script>

<template>
  <PageContainer title="字典管理" description="字典类型 + 字典项两级维护；支持平台默认与租户覆盖">
    <ProTable
      ref="typeTableRef"
      :columns="typeColumns"
      :request="fetchDictTypePage"
      :toolbar="['create', 'refresh']"
      :row-actions="typeRowActions"
      empty-action-text="创建第一个字典类型"
      @create="openTypeForm(null)"
      @empty-action="openTypeForm(null)"
    />
  </PageContainer>

  <!-- 字典项抽屉：容器是"承载一张表"，不是表单，保留 NDrawer -->
  <n-drawer v-model:show="itemsVisible" :width="860" placement="right">
    <n-drawer-content :title="`字典项 — ${currentType}`" closable>
      <ProTable
        ref="itemTableRef"
        :columns="itemColumns"
        :request="fetchItemsForCurrentType"
        :toolbar="['create', 'refresh']"
        :row-actions="itemRowActions"
        empty-action-text="为该字典添加第一项"
        @create="openItemForm(null)"
        @empty-action="openItemForm(null)"
      />
    </n-drawer-content>
  </n-drawer>

  <!-- 字典类型表单 -->
  <ProModal
    v-model:visible="typeFormVisible"
    :title="typeEditingId !== null ? '编辑字典类型' : '新增字典类型'"
    :items="typeFormItems"
    :model="typeModalModel"
    :cols="1"
    :submit="submitTypeForm"
    :on-success="() => typeTableRef?.refresh()"
    @error="(error: unknown) => feedback.error(error instanceof Error ? error.message : '保存失败')"
  />

  <!-- 字典项表单 -->
  <ProModal
    v-model:visible="itemFormVisible"
    :title="itemEditingId !== null ? '编辑字典项' : '新增字典项'"
    :items="itemFormItems"
    :model="itemModalModel"
    :cols="1"
    :submit="submitItemForm"
    :on-success="() => itemTableRef?.refresh()"
    @error="(error: unknown) => feedback.error(error instanceof Error ? error.message : '保存失败')"
  />
</template>

<style scoped>
.dict-page__code {
  padding: 1px 5px;
  border-radius: 3px;
  background: var(--wa-bg-hover, #f0f2f5);
  font-size: 0.9em;
}

.dict-page__muted {
  color: var(--wa-text-disabled, #a8b0ba);
}
</style>
