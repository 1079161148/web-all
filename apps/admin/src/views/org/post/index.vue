<script setup lang="ts">
import { computed, ref } from 'vue'
import { AuthButton, PageContainer, ProModal, ProTable, feedback } from '@admin/ui'
import type {
  ProBatchAction,
  ProColumn,
  ProFormItem,
  ProRowAction,
  ProTableExpose
} from '@admin/ui'
import type { PostRequest } from '@admin/api'
import { createPostAction, deletePostAction, fetchPostPage, updatePostAction } from '@/api/iam'
import { useDict } from '@/composables/useDict'

/**
 * 岗位管理页 —— **新组件体系的参考实现**。
 *
 * <h3>这一页现在只描述"业务是什么"</h3>
 * 对比迁移前的版本，页面上消失的都是与业务无关的样板：
 * <ul>
 *   <li><b>弹窗开关、表单实例、提交后刷新、关闭重置</b> → 全部收进 {@code ProModal}</li>
 *   <li><b>表单字段的控件、校验、布局</b> → 全部由 {@code ProForm} 的 items 声明生成</li>
 *   <li><b>页面标题与留白</b> → {@code PageContainer}</li>
 *   <li><b>按钮权限</b> → {@code AuthButton}（可区分"隐藏"与"禁用+提示"）</li>
 * </ul>
 *
 * <p>剩下的是三件只有业务才知道的事：<b>有哪些列、有哪些字段、提交到哪个接口</b>。
 * 这正是"一个页面只写列定义和请求函数"的目标形态。
 */

interface PostRow {
  id?: number
  postCode?: string
  postName?: string
  sort?: number
  status?: string
  remark?: string
  userCount?: number
}

const tableRef = ref<ProTableExpose<PostRow> | null>(null)
const dicts = useDict('sys_status')

// ---------------------------------------------------------------------
// 弹窗状态
//
// 只保留"当前在编辑哪一条"这一件事 —— 开关、重置、提交态都由 ProModal 持有。
// editingId 为 null 表示新增，这比额外维护一个 mode 字符串更难写错。
// ---------------------------------------------------------------------
const modalVisible = ref(false)
const editingId = ref<number | null>(null)
const modalModel = ref<Record<string, unknown>>({})

// ---------------------------------------------------------------------
// 列定义
// ---------------------------------------------------------------------

const columns = computed<ProColumn<PostRow>[]>(() => [
  { key: 'postCode', title: '岗位编码', width: 150, search: 'input' },
  { key: 'postName', title: '岗位名称', minWidth: 160, search: 'input' },
  { key: 'sort', title: '排序', width: 80 },
  {
    key: 'status',
    title: '状态',
    width: 100,
    search: 'select',
    options: dicts.sys_status.value.map((o) => ({ label: o.label, value: o.value })),
    dict: 'sys_status'
  },
  {
    key: 'userCount',
    title: '关联员工',
    width: 110,
    renderFn: (row) => (row.userCount ? `${row.userCount} 人` : '-')
  },
  { key: 'remark', title: '备注', minWidth: 160 },
  { key: 'createTime', title: '创建时间', width: 170, sortable: true, render: 'datetime' }
])

// ---------------------------------------------------------------------
// 表单字段
// ---------------------------------------------------------------------

const formItems = computed<ProFormItem[]>(() => [
  {
    field: 'postCode',
    title: '岗位编码',
    required: true,
    // 编辑时编码不可改：它是被别处引用的标识。
    // 让它在表单里可见但不可编辑，比"隐藏起来"更好 —— 使用者需要看到当前值
    disabled: editingId.value !== null,
    tip: '字母开头，仅含字母、数字与下划线。创建后不可修改',
    placeholder: '如 DEV'
  },
  { field: 'postName', title: '岗位名称', required: true, placeholder: '如 研发工程师' },
  { field: 'sort', title: '显示顺序', type: 'number', value: 0 },
  { field: 'status', title: '状态', type: 'select', dict: 'sys_status', value: 'ACTIVE' },
  { field: 'remark', title: '备注', type: 'textarea' }
])

// ---------------------------------------------------------------------
// 行为
// ---------------------------------------------------------------------

function openCreate(): void {
  editingId.value = null
  modalModel.value = {}
  modalVisible.value = true
}

function openEdit(row: PostRow): void {
  editingId.value = row.id ?? null
  modalModel.value = { ...row }
  modalVisible.value = true
}

/**
 * 提交。
 *
 * <p>显式构造 payload 而不是把表单值直接断言成 {@code PostRequest}：
 * 表单值是 {@code Record<string, unknown>}，直接断言等于放弃类型检查；
 * 而显式构造让"后端需要哪些字段、各自怎么转换"在这一处可见，
 * 后端改字段时这里会立刻编译报错。
 */
async function handleSubmit(values: Record<string, unknown>): Promise<void> {
  const payload: PostRequest = {
    postCode: String(values.postCode ?? ''),
    postName: String(values.postName ?? ''),
    sort: values.sort === undefined || values.sort === null ? 0 : Number(values.sort),
    status: values.status ? String(values.status) : 'ACTIVE',
    remark: values.remark ? String(values.remark) : undefined
  }

  if (editingId.value !== null) {
    await updatePostAction(editingId.value, payload)
  } else {
    await createPostAction(payload)
  }
}

const rowActions: ProRowAction<PostRow>[] = [
  {
    key: 'edit',
    label: '编辑',
    permission: 'org:post:update',
    onClick: (row) => openEdit(row)
  },
  {
    key: 'delete',
    label: '删除',
    permission: 'org:post:delete',
    danger: true,
    // 仍分配给员工时提前禁用 —— 让限制可见，而不是点了才被拒绝
    disabled: (row) => (row.userCount ?? 0) > 0,
    confirm: (row) => `确定删除岗位「${row.postName}」？`,
    onClick: async (row) => {
      if (row.id === undefined) return
      await deletePostAction(row.id)
      feedback.success('岗位已删除')
      await tableRef.value?.refresh()
    }
  }
]

/**
 * 批量操作。
 *
 * <p>声明了它，ProTable 才出现多选列 —— 多选由批量操作派生，
 * 而不是一个独立的开关（见 ProTable 的说明）。
 */
const batchActions: ProBatchAction<PostRow>[] = [
  {
    key: 'delete',
    label: '批量删除',
    permission: 'org:post:delete',
    danger: true,
    confirm: '确定删除所选岗位？仍在被员工使用的岗位会被跳过。',
    onClick: async (rows) => {
      // 逐条删除而不是并发：其中一条失败（被员工占用）时，
      // 并发会让"哪些成功、哪些失败"变得难以说清；
      // 串行至少能保证错误信息与顺序对应
      let succeeded = 0
      const failed: string[] = []
      for (const row of rows) {
        if (row.id === undefined) continue
        try {
          await deletePostAction(row.id)
          succeeded += 1
        } catch {
          failed.push(row.postName ?? String(row.id))
        }
      }
      if (failed.length === 0) {
        feedback.success(`已删除 ${succeeded} 个岗位`)
      } else {
        feedback.warning(`成功 ${succeeded} 个，失败 ${failed.length} 个：${failed.join('、')}`)
      }
      await tableRef.value?.refresh()
      tableRef.value?.clearSelection()
    }
  }
]
</script>

<template>
  <PageContainer title="岗位管理" description="岗位用于标记员工的职务属性，可参与数据权限的部门范围判断">
    <template #extra>
      <AuthButton
        permission="org:post:create"
        mode="disable"
        type="primary"
        size="small"
        denied-text="你没有新增岗位的权限"
        @click="openCreate"
      >
        新增岗位
      </AuthButton>
    </template>

    <ProTable
      ref="tableRef"
      :columns="columns"
      :request="fetchPostPage"
      :toolbar="['refresh', 'columnSetting', 'density']"
      :row-actions="rowActions"
      :batch-actions="batchActions"
      empty-action-text="创建第一个岗位"
      @empty-action="openCreate"
    />
  </PageContainer>

  <!--
    弹窗表单：开关、重置、提交态、提交后刷新表格全部由它承担。
    页面只需给"字段"、"提交函数"与"成功后做什么"。
  -->
  <ProModal
    v-model:visible="modalVisible"
    :title="editingId !== null ? '编辑岗位' : '新增岗位'"
    :items="formItems"
    :model="modalModel"
    :cols="1"
    :submit="handleSubmit"
    :on-success="() => (editingId === null ? tableRef?.reload() : tableRef?.refresh())"
    @error="(error: unknown) => feedback.error(error instanceof Error ? error.message : '保存失败')"
  />
</template>
