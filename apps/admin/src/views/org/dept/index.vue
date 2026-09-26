<script setup lang="ts">
import { computed, h, ref } from 'vue'
import {
  PageContainer,
  ProModal,
  ProTable,
  TREE_PRESETS,
  feedback,
  filterFlatTreeByLabel,
  flatOptionsWithDepth
} from '@admin/ui'
import type { ProColumn, ProFormItem, ProRowAction, ProTableQuery } from '@admin/ui'
import { DictTag } from '@admin/ui'
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

const tableRef = ref<{ reload: () => void; refresh: () => void } | null>(null)

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
 *
 * <p>名称筛选在<b>前端</b>做（数据已全量在内存，再发一次请求没有意义），
 * 用共享的 {@code filterFlatTreeByLabel}：命中节点连同祖先一起保留，
 * 否则子部门会变成孤立的根节点、缩进全错。
 */
async function fetchDeptPage(query: ProTableQuery) {
  const list = (await loadDeptList()) ?? []
  // depts 始终保存全量：上级部门下拉与"是否有子部门"的判断都依赖完整列表
  depts.value = list
  const keyword = typeof query.deptName === 'string' ? query.deptName : ''
  const records =
    keyword.trim() === ''
      ? list
      : filterFlatTreeByLabel(list, keyword, { labelOf: TREE_PRESETS.dept.labelOf })
  return {
    records,
    total: records.length,
    page: 1,
    // size 必须等于 total，否则 ProTable 会按 size 切掉后面的行
    size: Math.max(records.length, 1)
  }
}

const columns: ProColumn<DeptDTO>[] = [
  {
    key: 'deptName',
    title: '部门名称',
    minWidth: 220,
    search: 'input',
    searchPlaceholder: '模糊匹配',
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
      tableRef.value?.refresh()
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
 * 「上级部门」下拉选项。
 *
 * <p>⚠️ 这里修过一个真实 bug：「— 根部门 —」曾经用
 * {@code parentOptions.value.unshift(...)} 加在 <b>computed 外面</b>。
 * 那行只在 setup 时执行一次，而 computed 的缓存数组会在依赖变化后被整个替换 ——
 * 于是部门数据一加载完，这个选项就<b>消失</b>了，
 * 表现是"新增部门时选不了根部门"，且没有任何报错。
 * 现在把它放进 computed 内部，随每次求值一起产生。
 *
 * <p>层级缩进交给共享的 {@code flatOptionsWithDepth}：
 * 它先建树（层级天然可得）再遍历，因此不再需要手写"算深度"。
 */
const parentOptions = computed(() => [
  { label: '— 根部门 —', value: 0 },
  ...flatOptionsWithDepth(depts.value, {
    labelOf: TREE_PRESETS.dept.labelOf,
    valueOf: (dept) => dept.id as number,
    // 不能把部门挂到自己下面（真正意义上的"到子部门下"由后端拒绝）
    filter: (dept) => dept.id !== editingId.value
  })
])

const formItems = computed<ProFormItem[]>(() => [
  {
    field: 'parentId',
    title: '上级部门',
    type: 'select',
    options: parentOptions.value,
    value: 0,
    tip: '变更上级部门会级联更新整棵子树的路径。不能移动到自己的子部门下（会形成环）。'
  },
  { field: 'deptName', title: '部门名称', required: true, placeholder: '请输入部门名称' },
  { field: 'sort', title: '显示顺序', type: 'number', value: 0, props: { min: 0 } },
  { field: 'phone', title: '联系电话' },
  { field: 'email', title: '邮箱' },
  { field: 'status', title: '状态', type: 'select', dict: 'sys_status', value: 'ACTIVE' },
  { field: 'remark', title: '备注', type: 'textarea' }
])

function openForm(row: DeptDTO | null, parent: DeptDTO | null): void {
  if (row) {
    editingId.value = row.id ?? null
    modalModel.value = {
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
    modalModel.value = {
      parentId: parent?.id ?? 0,
      sort: 0,
      status: 'ACTIVE'
    }
  }
  formVisible.value = true
}

/** 显式构造 payload：后端需要哪些字段在这一处可见。 */
async function submitForm(values: Record<string, unknown>): Promise<void> {
  const payload: DeptRequest = {
    parentId: values.parentId === undefined || values.parentId === null ? 0 : Number(values.parentId),
    deptName: String(values.deptName ?? ''),
    sort: values.sort === undefined || values.sort === null ? 0 : Number(values.sort),
    phone: values.phone ? String(values.phone) : '',
    email: values.email ? String(values.email) : '',
    status: values.status ? String(values.status) : 'ACTIVE',
    remark: values.remark ? String(values.remark) : ''
  }
  if (editingId.value !== null) {
    await updateDeptAction(editingId.value, payload)
  } else {
    await createDeptAction(payload)
  }
  feedback.success('保存成功')
}
</script>

<template>
  <PageContainer title="部门管理" description="部门树用于数据权限的范围判定；上级关系决定数据可见层级">
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
  </PageContainer>

  <ProModal
    v-model:visible="formVisible"
    :title="editingId !== null ? '编辑部门' : '新增部门'"
    :items="formItems"
    :model="modalModel"
    :cols="1"
    :submit="submitForm"
    :on-success="() => tableRef?.refresh()"
    @error="(error: unknown) => feedback.error(error instanceof Error ? error.message : '保存失败')"
  />
</template>
