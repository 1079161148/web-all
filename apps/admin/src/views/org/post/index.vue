<script setup lang="ts">
import { computed, h, ref } from 'vue'
import { NInput, NInputNumber, NModal, NSelect, ProTable, feedback } from '@admin/ui'
import type { ProColumn, ProRowAction } from '@admin/ui'
import { useDict } from '@/composables/useDict'
import DictTag from '@/components/DictTag.vue'
import type { PostRequest } from '@admin/api'
import { createPostAction, deletePostAction, fetchPostPage, updatePostAction } from '@/api/iam'

/**
 * 岗位管理页（L1 支撑域的标准形态）。
 *
 * <h3>本页是"典型 CRUD 页面"的样本</h3>
 * 设计文档 §11.4 给出目标：典型 CRUD 页面 ≤ 60 行 script。
 * 本页的业务代码只描述了"有什么列、有什么操作"，搜索、分页、加载态、
 * 错误处理、空状态全部由 ProTable 承担。
 *
 * <p>对比 RuoYi 的同类页面（400~600 行），差异不在"写得简洁"，
 * 而在于<b>重复的骨架代码被收敛到了组件里</b>：
 * 搜索区、分页、loading、异常提示各自只有一处实现。
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

const tableRef = ref<{ reload: (resetPage?: boolean) => void } | null>(null)
const dicts = useDict('sys_status')

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
    renderFn: (row) => h(DictTag, { dictType: 'sys_status', value: row.status })
  },
  {
    key: 'userCount',
    title: '关联员工',
    width: 110,
    renderFn: (row) =>
      h(
        'span',
        { class: (row.userCount ?? 0) > 0 ? '' : 'post-page__muted' },
        `${row.userCount ?? 0} 人`
      )
  },
  { key: 'remark', title: '备注', minWidth: 160 }
])

const rowActions: ProRowAction<PostRow>[] = [
  { key: 'edit', label: '编辑', permission: 'org:post:update', onClick: (row) => openForm(row) },
  {
    key: 'delete',
    label: '删除',
    permission: 'org:post:delete',
    danger: true,
    // 仍分配给员工时提前禁用 —— 让限制可见，而不是点了被拒绝
    disabled: (row) => (row.userCount ?? 0) > 0,
    confirm: (row) => `确定删除岗位「${row.postName}」？`,
    onClick: async (row) => {
      if (row.id === undefined) return
      await deletePostAction(row.id)
      feedback.success('岗位已删除')
      tableRef.value?.reload(false)
    }
  }
]

// ---------------------------------------------------------------------

const formVisible = ref(false)
const submitting = ref(false)
const editingId = ref<number | null>(null)
const form = ref<PostRequest>({ postCode: '', postName: '', sort: 0, status: 'ACTIVE', remark: '' })
const isEdit = computed(() => editingId.value !== null)

function openForm(row: PostRow | null): void {
  if (row) {
    editingId.value = row.id ?? null
    form.value = {
      postCode: row.postCode ?? '',
      postName: row.postName ?? '',
      sort: row.sort ?? 0,
      status: row.status ?? 'ACTIVE',
      remark: row.remark ?? ''
    }
  } else {
    editingId.value = null
    form.value = { postCode: '', postName: '', sort: 0, status: 'ACTIVE', remark: '' }
  }
  formVisible.value = true
}

async function submitForm(): Promise<void> {
  if (!form.value.postCode.trim() || !form.value.postName.trim()) {
    feedback.warning('请填写岗位编码与名称')
    return
  }
  submitting.value = true
  try {
    if (editingId.value !== null) {
      await updatePostAction(editingId.value, form.value)
    } else {
      await createPostAction(form.value)
    }
    feedback.success('保存成功')
    formVisible.value = false
    tableRef.value?.reload(!isEdit.value)
  } catch (error) {
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
  <div class="post-page">
    <ProTable
      ref="tableRef"
      :columns="columns"
      :request="fetchPostPage"
      :toolbar="['create', 'refresh']"
      :row-actions="rowActions"
      empty-action-text="创建第一个岗位"
      @create="openForm(null)"
      @empty-action="openForm(null)"
    />

    <n-modal v-model:show="formVisible" preset="card" :title="isEdit ? '编辑岗位' : '新增岗位'" style="width: 480px">
      <div class="post-page__form">
        <div class="post-page__field">
          <label>岗位编码 <span class="post-page__required">*</span></label>
          <n-input v-model:value="form.postCode" placeholder="如 DEV。字母开头，仅含字母数字下划线" />
        </div>
        <div class="post-page__field">
          <label>岗位名称 <span class="post-page__required">*</span></label>
          <n-input v-model:value="form.postName" placeholder="如 研发工程师" />
        </div>
        <div class="post-page__field">
          <label>显示顺序</label>
          <n-input-number v-model:value="form.sort" :min="0" />
        </div>
        <div class="post-page__field">
          <label>状态</label>
          <n-select v-model:value="form.status" :options="statusOptions" />
        </div>
        <div class="post-page__field">
          <label>备注</label>
          <n-input v-model:value="form.remark" type="textarea" :rows="2" />
        </div>
      </div>
      <template #footer>
        <div class="post-page__footer">
          <n-button @click="formVisible = false">取消</n-button>
          <n-button type="primary" :loading="submitting" @click="submitForm">确定</n-button>
        </div>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.post-page__form {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-lg, 16px);
}

.post-page__field {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-xs, 4px);
}

.post-page__field label {
  font-size: var(--wa-font-size-md, 14px);
  color: var(--wa-text-primary, #1f2329);
}

.post-page__required {
  color: var(--wa-color-error, #dc2626);
}

.post-page__muted {
  color: var(--wa-text-disabled, #a8b0ba);
}

.post-page__footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--wa-spacing-sm, 8px);
}
</style>
