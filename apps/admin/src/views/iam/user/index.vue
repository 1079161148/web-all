<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NCheckbox,
  NInput,
  NCheckboxGroup,
  PageContainer,
  ProModal,
  ProTable,
  TREE_PRESETS,
  feedback,
  flatToTree
} from '@admin/ui'
import type { ProColumn, ProFormItem, ProRowAction, ProTreeNode } from '@admin/ui'
import { tablePreferenceAdapter } from '@/api/preference'
import { useDict } from '@/composables/useDict'
import { DictTag } from '@admin/ui'
import type { RoleResponse, UserResponse } from '@admin/api'
import {
  assignUserRolesAction,
  changeUserStatusAction,
  createUserAction,
  deleteUserAction,
  fetchUserPage,
  loadDeptList,
  loadUsableRoles,
  resetUserPasswordAction,
  unlockUserAction,
  updateUserAction,
  type UserFormModel
} from '@/api/iam'

/**
 * 用户管理页。
 *
 * <h3>本页要演示的四件事</h3>
 * <ol>
 *   <li><b>字典驱动</b>：状态与性别列由 {@code useDict} 渲染成带颜色的标签，
 *       而不是在页面里写死"ACTIVE→正常"的映射</li>
 *   <li><b>权限码声明</b>：每个行操作都带 {@code permission}，
 *       由 ProTable 与 {@code v-permission} 自动控制显隐</li>
 *   <li><b>危险操作二次确认</b>：删除、重置密码有确认文案，且把后果说清楚</li>
 *   <li><b>状态与资料分离</b>：编辑只改资料，停用/启用是独立操作 ——
 *       这与后端的接口划分一致（<b>能改资料不代表能停用别人</b>）</li>
 * </ol>
 */

const tableRef = ref<{ reload: () => void; refresh: () => void } | null>(null)

// 页面用到的字典：显式声明，让"这个页面依赖哪些字典"在代码里可见。
//
// 这里只剩 sys_user_sex：状态列改为由 dict 声明驱动后，
// 页面不再需要 sys_user_status 的选项数据（标签渲染走 DictTag、
// 搜索下拉走 DictSelect，两者都只认字典编码，自己会去取）。
// 去掉它不是"顺手清理"，而是**避免一次没人用的字典请求**。
const dicts = useDict('sys_user_sex')

/** 生成类型里 id 是可选的，但业务上必然存在。缺失即报错而不是用 0 兜底。 */
function requireId(row: UserResponse): number {
  if (row.id === undefined) {
    throw new Error('用户数据缺少主键 ID，无法执行该操作')
  }
  return row.id
}

// ---------------------------------------------------------------------
// 下拉数据：部门树与角色列表（进页面各取一次）
// ---------------------------------------------------------------------

const deptTreeOptions = ref<ProTreeNode[]>([])
const roleOptions = ref<Array<{ label: string; value: number }>>([])

/*
 * 建树改用 @admin/ui 的 flatToTree + dept 预设。
 *
 * 此前这里有一份 23 行的 buildDeptTree 手写实现。它与角色页、部门页、
 * 权限 store 里的实现逻辑完全相同（按 parentId 分组 → 按 sort 排序 → 递归）——
 * 同一份规则在项目里存在了 5 处，任何一处漏了按 sort 排序，
 * 那个页面的树序就会与其它页面不一致，而这类问题通常被当成后端问题。
 *
 * 预设的额外收益：'未命名部门' 的兜底文案、停用部门置灰这些规则
 * 现在也统一了（此前只有部分页面做了）。
 */

onMounted(async () => {
  try {
    const [depts, roles] = await Promise.all([loadDeptList(), loadUsableRoles()])
    // 预设本身就是一个合法的 options 对象，因此可以直接传 ——
    // "部门该怎么建树"这件事的答案只有一处，不在这里
    deptTreeOptions.value = flatToTree(depts ?? [], TREE_PRESETS.dept)
    roleOptions.value = (roles ?? []).map((role: RoleResponse) => ({
      label: role.roleName ?? '未命名角色',
      value: role.id as number
    }))
  } catch {
    // 下拉数据失败不阻塞列表：用户列表本身仍可查看，
    // 只是新建/编辑时选不了部门与角色。这比整页打不开好得多。
    feedback.warning('部门或角色数据加载失败，新建/编辑时可能无法选择')
  }
})

// ---------------------------------------------------------------------
// 表格
// ---------------------------------------------------------------------

const columns = computed<ProColumn<UserResponse>[]>(() => [
  { key: 'username', title: '账号', width: 140, search: 'input', searchPlaceholder: '模糊匹配' },
  { key: 'nickname', title: '姓名', width: 120, search: 'input' },
  { key: 'deptName', title: '所属部门', width: 150 },
  { key: 'phone', title: '手机号', width: 140, search: 'input' },
  {
    key: 'status',
    title: '状态',
    width: 100,
    // 搜索项的候选值由字典驱动：只声明 dict，选项自动来自字典。
    //
    // 这里此前是手写的 `options: dicts.sys_user_status.value.map(...)` ——
    // 不是风格问题，而是**当时的字典路径不通**：ProSearchItem 的 dict 字段
    // 被声明了却从未被解析（详见 DictSelect 与 ProSearch 的说明），
    // 只声明 dict 会得到一个空下拉。所以页面只能绕开它自己 map 一遍。
    //
    // 现在 ProSearch 会走 DictSelect，于是回归 ProTable JSDoc 里的写法：
    // 字典改了，搜索下拉自动跟着变，前端一行都不用动。
    search: 'select',
    dict: 'sys_user_status',
    renderFn: (row) => h(DictTag, { dictType: 'sys_user_status', value: row.status })
  },
  { key: 'roleNames', title: '角色', minWidth: 160 },
  {
    key: 'sex',
    title: '性别',
    width: 90,
    renderFn: (row) => h(DictTag, { dictType: 'sys_user_sex', value: row.sex })
  },
  { key: 'createTime', title: '创建时间', width: 170, sortable: true, render: 'datetime' }
])

const rowActions: ProRowAction<UserResponse>[] = [
  {
    key: 'edit',
    label: '编辑',
    permission: 'iam:user:update',
    onClick: (row) => openEdit(row)
  },
  {
    key: 'roles',
    label: '分配角色',
    permission: 'iam:user:update',
    onClick: (row) => openRoleAssign(row)
  },
  {
    key: 'resetPassword',
    label: '重置密码',
    permission: 'iam:user:reset-password',
    // 确认文案必须说清后果：重置后对方会立刻无法用旧密码登录
    confirm: (row) =>
      `确定重置「${row.nickname ?? row.username}」的密码？重置后其原密码立即失效，需告知新密码。`,
    onClick: async (row) => {
      await resetUserPasswordAction(requireId(row))
      feedback.success('已重置为平台初始密码，请告知用户尽快修改')
      tableRef.value?.refresh()
    }
  },
  {
    key: 'unlock',
    label: '解锁',
    permission: 'iam:user:update',
    // 只有被锁定的账号才需要解锁 —— 用 disabled 而不是"点了才发现不行"
    disabled: (row) => row.status !== 'LOCKED',
    onClick: async (row) => {
      await unlockUserAction(requireId(row))
      feedback.success('已解锁并清零失败计数')
      tableRef.value?.refresh()
    }
  },
  {
    key: 'toggleStatus',
    // 文案跟当前状态走：启用中显示「禁用」，已禁用显示「正常」
    label: (row) => (row.status === 'ACTIVE' ? '禁用' : '正常'),
    permission: 'iam:user:update',
    // danger 只能声明为布尔（ProRowAction 的设计如此），不能按行动态变色。
    // 因此这里统一用中性色，把"这条操作会停用账号"的警示放到确认弹窗里 ——
    // 那里能说清后果，比一个红字更有用。
    disabled: (row) => row.status === 'LOCKED',
    onClick: (row) => openStatusChange(row)
  },
  {
    key: 'delete',
    label: '删除',
    permission: 'iam:user:delete',
    danger: true,
    confirm: (row) =>
      `确定删除「${row.nickname ?? row.username}」？该账号将无法登录，历史操作记录会保留。`,
    onClick: async (row) => {
      await deleteUserAction(requireId(row))
      feedback.success('用户已删除')
      tableRef.value?.refresh()
    }
  }
]

// ---------------------------------------------------------------------
// 新增 / 编辑
// ---------------------------------------------------------------------

const formVisible = ref(false)
const editingId = ref<number | null>(null)
const modalModel = ref<Record<string, unknown>>({})

const isEdit = computed(() => editingId.value !== null)

/**
 * 表单字段（items 声明，控件/校验/布局由 ProForm 生成）。
 *
 * <p>初始密码只在新增时出现 —— 编辑用户改密码走「重置密码」行操作，
 * 两条链路语义不同，不该共用一个输入框。
 */
const formItems = computed<ProFormItem[]>(() => {
  const items: ProFormItem[] = [
    {
      field: 'username',
      title: '登录账号',
      required: true,
      message: '请输入登录账号',
      disabled: isEdit.value,
      placeholder: '4~64 位字母、数字或下划线',
      tip: isEdit.value ? '登录账号是登录凭据与审计主体标识，不可修改' : undefined
    },
    { field: 'nickname', title: '姓名', required: true, message: '请输入姓名' },
    { field: 'deptId', title: '所属部门', type: 'treeSelect', options: deptTreeOptions.value },
    { field: 'roleIds', title: '角色', type: 'multi-select', options: roleOptions.value },
    { field: 'sex', title: '性别', type: 'radio', options: sexOptions.value, value: 2 },
    { field: 'phone', title: '手机号', placeholder: '11 位手机号' },
    { field: 'email', title: '邮箱' }
  ]
  // 初始密码只在新增时出现；编辑用户改密码走「重置密码」行操作
  if (!isEdit.value) {
    items.splice(2, 0, {
      field: 'password',
      title: '初始密码',
      type: 'password',
      placeholder: '留空则使用平台初始密码'
    })
  }
  return items
})

function openCreate(): void {
  editingId.value = null
  modalModel.value = { sex: 2, roleIds: [] }
  formVisible.value = true
}

function openEdit(row: UserResponse): void {
  editingId.value = requireId(row)
  modalModel.value = {
    username: row.username ?? '',
    nickname: row.nickname ?? '',
    deptId: row.deptId ?? null,
    phone: row.phone ?? '',
    email: row.email ?? '',
    sex: row.sex ?? 2,
    roleIds: row.roleIds ?? []
  }
  formVisible.value = true
}

/** 显式构造 payload：后端需要哪些字段、各自怎么转换在这一处可见。 */
async function submitForm(values: Record<string, unknown>): Promise<void> {
  const payload: UserFormModel = {
    username: String(values.username ?? ''),
    nickname: String(values.nickname ?? ''),
    password: values.password ? String(values.password) : '',
    deptId: values.deptId === undefined || values.deptId === null ? null : Number(values.deptId),
    phone: values.phone ? String(values.phone) : '',
    email: values.email ? String(values.email) : '',
    sex: values.sex === undefined || values.sex === null ? 2 : Number(values.sex),
    roleIds: Array.isArray(values.roleIds) ? values.roleIds.map(Number) : []
  }
  if (editingId.value !== null) {
    await updateUserAction(editingId.value, payload)
    feedback.success('保存成功')
  } else {
    await createUserAction(payload)
    feedback.success(
      payload.password ? '创建成功' : '创建成功，已使用平台初始密码，请告知用户首次登录后修改'
    )
  }
}

// ---------------------------------------------------------------------
// 分配角色（弹窗内容是"提示文案 + 多选列表"，不是标准字段表单，
// 因此用 ProModal 的默认插槽而不是 items）
// ---------------------------------------------------------------------

const roleVisible = ref(false)
const roleSubmitting = ref(false)
const roleTargetId = ref<number | null>(null)
const selectedRoleIds = ref<number[]>([])

function openRoleAssign(row: UserResponse): void {
  roleTargetId.value = requireId(row)
  selectedRoleIds.value = [...(row.roleIds ?? [])]
  roleVisible.value = true
}

async function submitRoles(): Promise<void> {
  if (roleTargetId.value === null) return
  roleSubmitting.value = true
  try {
    await assignUserRolesAction(roleTargetId.value, selectedRoleIds.value)
    feedback.success('角色已更新，该用户的权限已立即生效')
    roleVisible.value = false
    tableRef.value?.refresh()
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '分配失败')
  } finally {
    roleSubmitting.value = false
  }
}

// ---------------------------------------------------------------------
// 启用 / 停用
// ---------------------------------------------------------------------

const statusVisible = ref(false)
const statusSubmitting = ref(false)
const statusTarget = ref<UserResponse | null>(null)
const statusReason = ref('')

function openStatusChange(row: UserResponse): void {
  statusTarget.value = row
  statusReason.value = ''
  statusVisible.value = true
}

async function submitStatus(): Promise<void> {
  const target = statusTarget.value
  if (!target || !statusReason.value.trim()) {
    feedback.warning('请填写变更原因（会记入日志，便于事后追溯）')
    return
  }
  statusSubmitting.value = true
  const nextStatus = target.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE'
  try {
    await changeUserStatusAction(requireId(target), nextStatus, statusReason.value.trim())
    feedback.success(nextStatus === 'ACTIVE' ? '已启用' : '已停用，该用户将无法登录')
    statusVisible.value = false
    tableRef.value?.refresh()
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    statusSubmitting.value = false
  }
}

/**
 * 性别选项。
 *
 * <p>注意 {@code Number(option.value)} —— 字典的存储值是字符串（"0"/"1"/"2"），
 * 而 {@code sex} 字段在后端是 {@code Integer}。若不转换，
 * 表单里的值和后端类型对不上：{{@code form.sex}} 会是字符串，
 * 提交时序列化成 {@code "2"}，后端反序列化 {@code Integer} 时会失败。
 * <b>字典一律是字符串，使用处必须显式转换</b>，这是字典方案唯一的"税"。
 */
const sexOptions = computed(() =>
  dicts.sys_user_sex.value.map((option) => ({
    label: option.label,
    value: Number(option.value)
  }))
)
</script>

<template>
  <PageContainer title="用户管理" description="账号与角色、部门的关系在这里维护；停用/解锁等敏感操作有独立审计">
    <ProTable
      ref="tableRef"
      :columns="columns"
      :request="fetchUserPage"
      :toolbar="['create', 'refresh']"
      :row-actions="rowActions"
      persistence-key="table:iam-user"
      :persistence="tablePreferenceAdapter"
      empty-action-text="创建第一个用户"
      @create="openCreate"
      @empty-action="openCreate"
    />
  </PageContainer>

  <!-- 新增 / 编辑：items 声明字段，控件/校验/布局由 ProForm 生成 -->
  <ProModal
    v-model:visible="formVisible"
    :title="isEdit ? '编辑用户' : '新增用户'"
    :items="formItems"
    :model="modalModel"
    :cols="1"
    :submit="submitForm"
    :on-success="() => (editingId === null ? tableRef?.reload() : tableRef?.refresh())"
    @error="(error: unknown) => feedback.error(error instanceof Error ? error.message : '保存失败')"
  />

  <!-- 分配角色 -->
  <ProModal
    v-model:visible="roleVisible"
    title="分配角色"
    :loading="roleSubmitting"
    submit-text="保存"
    @success="submitRoles"
  >
    <p class="user-page__hint">角色决定该用户能看到哪些菜单与按钮。保存后权限立即生效，无需重新登录。</p>
    <n-checkbox-group v-model:value="selectedRoleIds">
      <div class="user-page__role-list">
        <n-checkbox v-for="role in roleOptions" :key="role.value" :value="role.value">
          {{ role.label }}
        </n-checkbox>
      </div>
    </n-checkbox-group>
  </ProModal>

  <!-- 启用 / 停用 -->
  <ProModal
    v-model:visible="statusVisible"
    title="变更用户状态"
    :loading="statusSubmitting"
    @success="submitStatus"
  >
    <p class="user-page__hint">
      将把「{{ statusTarget?.nickname ?? statusTarget?.username }}」变更为
      <b>{{ statusTarget?.status === 'ACTIVE' ? '停用' : '启用' }}</b>。
      <template v-if="statusTarget?.status === 'ACTIVE'">
        停用后该用户立即无法登录，权限缓存会被清除。
      </template>
    </p>
    <div class="user-page__field">
      <label>变更原因 <span class="user-page__required">*</span></label>
      <n-input v-model:value="statusReason" type="textarea" :rows="3" placeholder="会记入日志，便于事后追溯" />
    </div>
  </ProModal>
</template>

<style scoped>
.user-page__field {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-xs, 4px);
}

.user-page__field label {
  font-size: var(--wa-font-size-md, 14px);
  color: var(--wa-text-primary, #1f2329);
}

.user-page__required {
  color: var(--wa-color-error, #dc2626);
}

.user-page__hint {
  margin: 0 0 var(--wa-spacing-lg, 16px);
  font-size: var(--wa-font-size-md, 14px);
  line-height: 1.6;
  color: var(--wa-text-secondary, #5c6570);
}

.user-page__role-list {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-sm, 8px);
}
</style>
