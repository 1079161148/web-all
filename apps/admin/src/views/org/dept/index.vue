<script setup lang="ts">
import { computed, h, ref } from 'vue'
import { NInput, NInputNumber, NModal, NSelect, ProTable, feedback } from '@admin/ui'
import type { ProColumn, ProRowAction, ProTableQuery } from '@admin/ui'
import { useDict } from '@/composables/useDict'
import DictTag from '@/components/DictTag.vue'
import type { DeptDTO, DeptRequest } from '@admin/api'
import {
  createDeptAction,
  deleteDeptAction,
  loadDeptList,
  updateDeptAction
} from '@/api/iam'

/**
 * 部门管理页。
 *
 * <h3>⚠️ 当前用「扁平列表 + 缩进」模拟树，而非真正的树表格</h3>
 * ProTable 目前的表格内核不支持树形展开（vxe-table 的 tree-config 才有）。
 * 折中方案是：沿用后端返回的扁平结构，<b>用 ancestors 计算层级做缩进</b>，
 * 并让每行显示"上级部门"，从而在不支持树表格的前提下仍然可读。
 *
 * <p>这比"先做一个半成品的树控件"更好：树控件的交互（展开/收起/拖拽排序）
 * 一旦做半套，用户会以为它能用，而实际行为不一致。
 * <b>明确的能力边界好过模糊的半成品。</b>
 *
 * <h3>为什么部门列表不分页</h3>
 * 部门是树形数据，分页会把同一棵子树切到两页 —— 那样缩进与上级关系就失去意义了。
 * 租户的部门总量在几十到几百量级，一次返回是可接受的。因此这里
 * 把 {@code defaultPageSize} 设得很大，让 ProTable 的分页器实际上不生效。
 */

const tableRef = ref<{ reload: (resetPage?: boolean) => void } | null>(null)
const dicts = useDict('sys_status')

/** 缓存最近一次加载的部门，用于"选择上级部门"的下拉与层级名展示。 */
const depts = ref<DeptDTO[]>([])

const deptNameById = computed(() => {
  const map = new Map<number, string>()
  for (const dept of depts.value) {
    if (dept.id !== undefined) {
      map.set(dept.id, dept.deptName ?? '未命名部门')
    }
  }
  return map
})

/** 由 ancestors 计算层级（用于缩进）—— 物化路径在前端的直接收益。 */
function depthOf(dept: DeptDTO): number {
  const path = dept.ancestors ?? '0'
  return Math.max(0, path.split(',').filter((part) => part !== '0').length)
}

/**
 * ProTable 要求分页契约，而部门是全量树。
 * 这里把一次全量查询包装成"单页结果" —— 而不是改造 ProTable 的契约
 * （那会为了一个页面而放宽所有列表的约束）。
 */
async function fetchDeptPage(_query: ProTableQuery) {
  const list = (await loadDeptList()) ?? []
  depts.value = list
  return {
    records: list,
    total: list.length,
    page: 1,
    // size 必须等于 total，否则 ProTable 会按 size 切掉后面的行
    size: Math.max(list.length, 1)
  }
}

const columns: ProColumn<DeptDTO>[] = [
  {
    key: 'deptName',
    title: '部门名称',
    minWidth: 220,
    renderFn: (row) =>
      h(
        'span',
        // 每层缩进 20px。用 ancestors 直接算层级，不需要递归查询父级
        { style: { paddingLeft: `${depthOf(row) * 20}px` } },
        row.deptName ?? ''
      )
  },
  {
    key: 'parentId',
    title: '上级部门',
    width: 160,
    renderFn: (row) => {
      const parentId = row.parentId ?? 0
      return parentId === 0 ? '—（根）' : (deptNameById.value.get(parentId) ?? `#${parentId}`)
    }
  },
  { key: 'sort', title: '排序', width: 80 },
  {
    key: 'status',
    title: '状态',
    width: 100,
    renderFn: (row) => h(DictTag, { dictType: 'sys_status', value: row.status })
  },
  { key: 'phone', title: '联系电话', width: 140 },
  { key: 'createTime', title: '创建时间', width: 170, render: 'datetime' }
]

const rowActions: ProRowAction<DeptDTO>[] = [
  {
    key: 'addChild',
    label: '新增下级',
    permission: 'org:dept:create',
    onClick: (row) => openForm(null, row)
  },
  {
    key: 'edit',
    label: '编辑',
    permission: 'org:dept:update',
    onClick: (row) => openForm(row, null)
  },
  {
    key: 'delete',
    label: '删除',
    permission: 'org:dept:delete',
    danger: true,
    // 有子部门时提前禁用。后端也会拒绝，但让限制提前可见比事后报错友好
    disabled: (row) => depts.value.some((item) => item.parentId === row.id),
    confirm: (row) =>
      `确定删除部门「${row.deptName}」？若该部门下仍有员工，删除会被拒绝并提示人数。`,
    onClick: async (row) => {
      if (row.id === undefined) return
      await deleteDeptAction(row.id)
      feedback.success('部门已删除')
      tableRef.value?.reload(false)
    }
  }
]

// ---------------------------------------------------------------------
// 表单
// ---------------------------------------------------------------------

const formVisible = ref(false)
const submitting = ref(false)
const editingId = ref<number | null>(null)
const form = ref<DeptRequest>({ parentId: 0, deptName: '', sort: 0, status: 'ACTIVE', remark: '' })
const isEdit = computed(() => editingId.value !== null)

const parentOptions = computed(() =>
  depts.value
    .filter((dept) => dept.id !== undefined && dept.id !== editingId.value)
    .map((dept) => ({
      label: `${'　'.repeat(depthOf(dept))}${dept.deptName ?? ''}`,
      value: dept.id as number
    }))
)
parentOptions.value.unshift({ label: '— 根部门 —', value: 0 })

function openForm(row: DeptDTO | null, parent: DeptDTO | null): void {
  if (row) {
    editingId.value = row.id ?? null
    form.value = {
      parentId: row.parentId ?? 0,
      deptName: row.deptName ?? '',
      sort: row.sort ?? 0,
      phone: row.phone ?? '',
      email: row.email ?? '',
      status: row.status ?? 'ACTIVE',
      remark: row.remark ?? ''
    }
  } else {
    editingId.value = null
    form.value = {
      parentId: parent?.id ?? 0,
      deptName: '',
      sort: 0,
      status: 'ACTIVE',
      remark: ''
    }
  }
  formVisible.value = true
}

async function submitForm(): Promise<void> {
  if (!form.value.deptName.trim()) {
    feedback.warning('请输入部门名称')
    return
  }
  submitting.value = true
  try {
    if (editingId.value !== null) {
      await updateDeptAction(editingId.value, form.value)
    } else {
      await createDeptAction(form.value)
    }
    feedback.success('保存成功')
    formVisible.value = false
    tableRef.value?.reload(false)
  } catch (error) {
    // 后端的"不能移动到自己的子部门下"会在这里显示 —— 提示文案已说明后果
    feedback.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    submitting.value = false
  }
}

const statusOptions = computed(() =>
  dicts.sys_status.value.map((o) => ({ label: o.label, value: o.value }))
)
</script>

<template>
  <div class="dept-page">
    <ProTable
      ref="tableRef"
      :columns="columns"
      :request="fetchDeptPage"
      :toolbar="['create', 'refresh']"
      :row-actions="rowActions"
      :default-page-size="500"
      empty-action-text="创建第一个部门"
      @create="openForm(null, null)"
      @empty-action="openForm(null, null)"
    />

    <n-modal v-model:show="formVisible" preset="card" :title="isEdit ? '编辑部门' : '新增部门'" style="width: 520px">
      <div class="dept-page__form">
        <div class="dept-page__field">
          <label>上级部门</label>
          <n-select v-model:value="form.parentId" :options="parentOptions" placeholder="选择上级部门" />
          <p class="dept-page__tip">
            变更上级部门会<b>级联更新整棵子树</b>的路径。不能移动到自己的子部门下（会形成环）。
          </p>
        </div>
        <div class="dept-page__field">
          <label>部门名称 <span class="dept-page__required">*</span></label>
          <n-input v-model:value="form.deptName" placeholder="请输入部门名称" />
        </div>
        <div class="dept-page__field">
          <label>显示顺序</label>
          <n-input-number v-model:value="form.sort" :min="0" />
        </div>
        <div class="dept-page__field">
          <label>联系电话</label>
          <n-input v-model:value="form.phone" />
        </div>
        <div class="dept-page__field">
          <label>邮箱</label>
          <n-input v-model:value="form.email" />
        </div>
        <div class="dept-page__field">
          <label>状态</label>
          <n-select v-model:value="form.status" :options="statusOptions" />
        </div>
        <div class="dept-page__field">
          <label>备注</label>
          <n-input v-model:value="form.remark" type="textarea" :rows="2" />
        </div>
      </div>
      <template #footer>
        <div class="dept-page__footer">
          <n-button @click="formVisible = false">取消</n-button>
          <n-button type="primary" :loading="submitting" @click="submitForm">确定</n-button>
        </div>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.dept-page__form {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-lg, 16px);
}

.dept-page__field {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-xs, 4px);
}

.dept-page__field label {
  font-size: var(--wa-font-size-md, 14px);
  color: var(--wa-text-primary, #1f2329);
}

.dept-page__required {
  color: var(--wa-color-error, #dc2626);
}

.dept-page__tip {
  margin: 0;
  font-size: var(--wa-font-size-xs, 12px);
  line-height: 1.6;
  color: var(--wa-text-disabled, #a8b0ba);
}

.dept-page__footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--wa-spacing-sm, 8px);
}
</style>
