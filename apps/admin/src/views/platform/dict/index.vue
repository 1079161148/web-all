<script setup lang="ts">
import { computed, h, ref } from 'vue'
import {
  NDrawer,
  NDrawerContent,
  NInput,
  NInputNumber,
  NModal,
  NSelect,
  NTag,
  ProTable,
  feedback
} from '@admin/ui'
import type { ProColumn, ProRowAction } from '@admin/ui'
import { useDict } from '@/composables/useDict'
import DictTag from '@/components/DictTag.vue'
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

const typeTableRef = ref<{ reload: (resetPage?: boolean) => void } | null>(null)
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
      typeTableRef.value?.reload(false)
    }
  }
]

const typeFormVisible = ref(false)
const typeSubmitting = ref(false)
const typeEditingId = ref<number | null>(null)
const typeForm = ref<DictTypeRequest>({ dictName: '', dictType: '', status: 'ACTIVE', remark: '' })

function openTypeForm(row: DictTypeRow | null): void {
  if (row) {
    typeEditingId.value = row.id ?? null
    typeForm.value = {
      dictName: row.dictName ?? '',
      dictType: row.dictType ?? '',
      status: row.status ?? 'ACTIVE',
      remark: row.remark ?? ''
    }
  } else {
    typeEditingId.value = null
    typeForm.value = { dictName: '', dictType: '', status: 'ACTIVE', remark: '' }
  }
  typeFormVisible.value = true
}

async function submitTypeForm(): Promise<void> {
  if (!typeForm.value.dictName.trim() || !typeForm.value.dictType.trim()) {
    feedback.warning('请填写字典名称与类型编码')
    return
  }
  typeSubmitting.value = true
  try {
    if (typeEditingId.value !== null) {
      await updateDictTypeAction(typeEditingId.value, typeForm.value)
    } else {
      await createDictTypeAction(typeForm.value)
    }
    feedback.success('保存成功')
    typeFormVisible.value = false
    typeTableRef.value?.reload(false)
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    typeSubmitting.value = false
  }
}

// ---------------------------------------------------------------------
// 字典项（抽屉）
// ---------------------------------------------------------------------

const itemsVisible = ref(false)
const itemsLoading = ref(false)
const currentType = ref('')
const itemTableRef = ref<{ reload: (resetPage?: boolean) => void } | null>(null)

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
  { key: 'dictLabel', title: '标签', minWidth: 130 },
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
    key: 'delete',
    label: '删除',
    permission: 'plt:dict:delete',
    danger: true,
    confirm: (row) => `确定删除字典项「${row.dictLabel}」？`,
    onClick: async (row) => {
      if (row.id === undefined) return
      await deleteDictDataAction(row.id)
      feedback.success('字典项已删除')
      itemTableRef.value?.reload(false)
    }
  }
]

const itemFormVisible = ref(false)
const itemSubmitting = ref(false)
const itemEditingId = ref<number | null>(null)
const itemForm = ref<DictDataRequest>({
  dictType: '',
  dictLabel: '',
  dictValue: '',
  sort: 0,
  cssClass: 'default',
  status: 'ACTIVE'
})

/** 标签样式的可选项：与 NTag 的 type 白名单保持一致（避免配出无效样式）。 */
const cssClassOptions = [
  { label: '默认（灰）', value: 'default' },
  { label: '主色（蓝）', value: 'primary' },
  { label: '信息（青）', value: 'info' },
  { label: '成功（绿）', value: 'success' },
  { label: '警告（橙）', value: 'warning' },
  { label: '错误（红）', value: 'error' }
]

function openItemForm(row: DictDataRow | null): void {
  if (row) {
    itemEditingId.value = row.id ?? null
    itemForm.value = {
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
    itemForm.value = {
      dictType: currentType.value,
      dictLabel: '',
      dictValue: '',
      sort: 0,
      cssClass: 'default',
      status: 'ACTIVE'
    }
  }
  itemFormVisible.value = true
}

async function submitItemForm(): Promise<void> {
  if (!itemForm.value.dictLabel.trim() || !itemForm.value.dictValue.trim()) {
    feedback.warning('请填写标签与键值')
    return
  }
  itemSubmitting.value = true
  try {
    if (itemEditingId.value !== null) {
      await updateDictDataAction(itemEditingId.value, itemForm.value)
    } else {
      await createDictDataAction(itemForm.value)
    }
    feedback.success('保存成功')
    itemFormVisible.value = false
    itemTableRef.value?.reload(false)
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    itemSubmitting.value = false
  }
}

const statusOptions = computed(() =>
  dicts.sys_status.value.map((o) => ({ label: o.label, value: o.value }))
)
</script>

<template>
  <div class="dict-page">
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

    <!-- 字典项抽屉 -->
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
    <n-modal v-model:show="typeFormVisible" preset="card"
             :title="typeEditingId ? '编辑字典类型' : '新增字典类型'" style="width: 480px">
      <div class="dict-page__form">
        <div class="dict-page__field">
          <label>字典名称 <span class="dict-page__required">*</span></label>
          <n-input v-model:value="typeForm.dictName" placeholder="如 用户性别" />
        </div>
        <div class="dict-page__field">
          <label>类型编码 <span class="dict-page__required">*</span></label>
          <n-input v-model:value="typeForm.dictType" placeholder="如 sys_user_sex" />
          <p class="dict-page__tip">
            小写字母开头，仅含小写字母、数字与下划线。代码里按它取字典，创建后不建议修改。
          </p>
        </div>
        <div class="dict-page__field">
          <label>状态</label>
          <n-select v-model:value="typeForm.status" :options="statusOptions" />
        </div>
        <div class="dict-page__field">
          <label>备注</label>
          <n-input v-model:value="typeForm.remark" type="textarea" :rows="2" />
        </div>
      </div>
      <template #footer>
        <div class="dict-page__footer">
          <n-button @click="typeFormVisible = false">取消</n-button>
          <n-button type="primary" :loading="typeSubmitting" @click="submitTypeForm">确定</n-button>
        </div>
      </template>
    </n-modal>

    <!-- 字典项表单 -->
    <n-modal v-model:show="itemFormVisible" preset="card"
             :title="itemEditingId ? '编辑字典项' : '新增字典项'" style="width: 480px">
      <div class="dict-page__form">
        <div class="dict-page__field">
          <label>字典类型</label>
          <n-input v-model:value="itemForm.dictType" disabled />
        </div>
        <div class="dict-page__field">
          <label>标签 <span class="dict-page__required">*</span></label>
          <n-input v-model:value="itemForm.dictLabel" placeholder="展示给用户看的文案，如 正常" />
        </div>
        <div class="dict-page__field">
          <label>键值 <span class="dict-page__required">*</span></label>
          <n-input v-model:value="itemForm.dictValue" placeholder="数据库存储的值，如 ACTIVE" />
        </div>
        <div class="dict-page__field">
          <label>显示顺序</label>
          <n-input-number v-model:value="itemForm.sort" :min="0" />
        </div>
        <div class="dict-page__field">
          <label>标签样式</label>
          <n-select v-model:value="itemForm.cssClass" :options="cssClassOptions" />
        </div>
        <div class="dict-page__field">
          <label>状态</label>
          <n-select v-model:value="itemForm.status" :options="statusOptions" />
        </div>
      </div>
      <template #footer>
        <div class="dict-page__footer">
          <n-button @click="itemFormVisible = false">取消</n-button>
          <n-button type="primary" :loading="itemSubmitting" @click="submitItemForm">确定</n-button>
        </div>
      </template>
    </n-modal>
  </div>
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

.dict-page__form {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-lg, 16px);
}

.dict-page__field {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-xs, 4px);
}

.dict-page__field label {
  font-size: var(--wa-font-size-md, 14px);
  color: var(--wa-text-primary, #1f2329);
}

.dict-page__required {
  color: var(--wa-color-error, #dc2626);
}

.dict-page__tip {
  margin: 0;
  font-size: var(--wa-font-size-xs, 12px);
  line-height: 1.6;
  color: var(--wa-text-disabled, #a8b0ba);
}

.dict-page__footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--wa-spacing-sm, 8px);
}
</style>
