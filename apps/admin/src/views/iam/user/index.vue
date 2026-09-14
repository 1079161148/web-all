<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NCheckbox,
  NInput,
  NModal,
  NRadio,
  NRadioGroup,
  NSelect,
  NTreeSelect,
  ProTable,
  feedback
} from '@admin/ui'
import type { ProColumn, ProRowAction } from '@admin/ui'
import { useDict } from '@/composables/useDict'
import DictTag from '@/components/DictTag.vue'
import type { DeptDTO, RoleResponse, UserResponse } from '@admin/api'
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

const tableRef = ref<{ reload: (resetPage?: boolean) => void } | null>(null)

// 页面用到的字典：显式声明，让"这个页面依赖哪些字典"在代码里可见
const dicts = useDict('sys_user_status', 'sys_user_sex')

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

interface TreeSelectOption {
  label: string
  key: number
  children?: TreeSelectOption[]
}

const deptTreeOptions = ref<TreeSelectOption[]>([])
const roleOptions = ref<Array<{ label: string; value: number }>>([])

/** 由扁平部门列表建树（后端刻意返回扁平结构，建树规则归前端）。 */
function buildDeptTree(depts: DeptDTO[]): TreeSelectOption[] {
  const byParent = new Map<number, DeptDTO[]>()
  for (const dept of depts) {
    if (dept.id === undefined) continue
    const parentId = dept.parentId ?? 0
    const siblings = byParent.get(parentId) ?? []
    siblings.push(dept)
    byParent.set(parentId, siblings)
  }
  const build = (parentId: number): TreeSelectOption[] =>
    (byParent.get(parentId) ?? [])
      .slice()
      .sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0))
      .map((dept) => {
        const children = build(dept.id as number)
        return {
          label: dept.deptName ?? '未命名部门',
          key: dept.id as number,
          children: children.length > 0 ? children : undefined
        }
      })
  return build(0)
}

onMounted(async () => {
  try {
    const [depts, roles] = await Promise.all([loadDeptList(), loadUsableRoles()])
    deptTreeOptions.value = buildDeptTree(depts ?? [])
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
    // 搜索项的候选值由字典驱动：字典改了，搜索下拉自动跟着变，前端不用改代码。
    // 用 computed 是因为字典是异步加载的 —— 写死成静态数组会导致
    // "首次渲染时下拉是空的、字典到了之后也不会自动补上"。
    search: 'select',
    options: dicts.sys_user_status.value.map((option) => ({
      label: option.label,
      value: option.value
    })),
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
      tableRef.value?.reload(false)
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
      tableRef.value?.reload(false)
    }
  },
  {
    key: 'toggleStatus',
    label: '停用/启用',
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
      tableRef.value?.reload(false)
    }
  }
]

// ---------------------------------------------------------------------
// 新增 / 编辑
// ---------------------------------------------------------------------

const formVisible = ref(false)
const submitting = ref(false)
const editingId = ref<number | null>(null)
const form = ref<UserFormModel>(emptyForm())

function emptyForm(): UserFormModel {
  return { username: '', nickname: '', password: '', deptId: null, phone: '', email: '', sex: 2, roleIds: [] }
}

const isEdit = computed(() => editingId.value !== null)

function openCreate(): void {
  editingId.value = null
  form.value = emptyForm()
  formVisible.value = true
}

function openEdit(row: UserResponse): void {
  editingId.value = requireId(row)
  form.value = {
    username: row.username ?? '',
    nickname: row.nickname ?? '',
    password: '',
    deptId: row.deptId ?? null,
    phone: row.phone ?? '',
    email: row.email ?? '',
    sex: row.sex ?? 2,
    roleIds: row.roleIds ?? []
  }
  formVisible.value = true
}

async function submitForm(): Promise<void> {
  if (!form.value.nickname.trim()) {
    feedback.warning('请输入姓名')
    return
  }
  if (!isEdit.value && !form.value.username.trim()) {
    feedback.warning('请输入登录账号')
    return
  }
  submitting.value = true
  try {
    if (isEdit.value) {
      await updateUserAction(editingId.value as number, form.value)
      feedback.success('保存成功')
    } else {
      await createUserAction(form.value)
      feedback.success(
        form.value.password
          ? '创建成功'
          : '创建成功，已使用平台初始密码，请告知用户首次登录后修改'
      )
    }
    formVisible.value = false
    tableRef.value?.reload(!isEdit.value)
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    submitting.value = false
  }
}

// ---------------------------------------------------------------------
// 分配角色
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
    tableRef.value?.reload(false)
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
    tableRef.value?.reload(false)
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
  <div class="user-page">
    <ProTable
      ref="tableRef"
      :columns="columns"
      :request="fetchUserPage"
      :toolbar="['create', 'refresh']"
      :row-actions="rowActions"
      empty-action-text="创建第一个用户"
      @create="openCreate"
      @empty-action="openCreate"
    />

    <!-- 新增 / 编辑 -->
    <n-modal v-model:show="formVisible" preset="card" :title="isEdit ? '编辑用户' : '新增用户'" style="width: 620px">
      <div class="user-page__form">
        <div class="user-page__field">
          <label>登录账号 <span class="user-page__required">*</span></label>
          <!-- 编辑时账号不可改：它是登录凭据与审计主体标识，改名会让历史日志失去指向 -->
          <n-input v-model:value="form.username" :disabled="isEdit" placeholder="4~64 位字母、数字或下划线" />
        </div>

        <div class="user-page__field">
          <label>姓名 <span class="user-page__required">*</span></label>
          <n-input v-model:value="form.nickname" placeholder="请输入姓名" />
        </div>

        <div v-if="!isEdit" class="user-page__field">
          <label>初始密码</label>
          <n-input v-model:value="form.password" type="password" show-password-on="click" placeholder="留空则使用平台初始密码" />
        </div>

        <div class="user-page__field">
          <label>所属部门</label>
          <n-tree-select
            v-model:value="form.deptId"
            :options="deptTreeOptions"
            placeholder="请选择部门"
            clearable
            key-field="key"
          />
        </div>

        <div class="user-page__field">
          <label>角色</label>
          <n-select v-model:value="form.roleIds" :options="roleOptions" multiple placeholder="可多选" clearable />
        </div>

        <div class="user-page__row">
          <div class="user-page__field">
            <label>性别</label>
            <n-radio-group v-model:value="form.sex">
              <n-radio v-for="item in sexOptions" :key="item.value" :value="item.value">
                {{ item.label }}
              </n-radio>
            </n-radio-group>
          </div>
        </div>

        <div class="user-page__field">
          <label>手机号</label>
          <n-input v-model:value="form.phone" placeholder="11 位手机号" />
        </div>

        <div class="user-page__field">
          <label>邮箱</label>
          <n-input v-model:value="form.email" placeholder="请输入邮箱" />
        </div>
      </div>

      <template #footer>
        <div class="user-page__footer">
          <n-button @click="formVisible = false">取消</n-button>
          <n-button type="primary" :loading="submitting" @click="submitForm">确定</n-button>
        </div>
      </template>
    </n-modal>

    <!-- 分配角色 -->
    <n-modal v-model:show="roleVisible" preset="card" title="分配角色" style="width: 460px">
      <p class="user-page__hint">角色决定该用户能看到哪些菜单与按钮。保存后权限立即生效，无需重新登录。</p>
      <n-checkbox-group v-model:value="selectedRoleIds">
        <div class="user-page__role-list">
          <n-checkbox v-for="role in roleOptions" :key="role.value" :value="role.value">
            {{ role.label }}
          </n-checkbox>
        </div>
      </n-checkbox-group>
      <template #footer>
        <div class="user-page__footer">
          <n-button @click="roleVisible = false">取消</n-button>
          <n-button type="primary" :loading="roleSubmitting" @click="submitRoles">保存</n-button>
        </div>
      </template>
    </n-modal>

    <!-- 启用 / 停用 -->
    <n-modal v-model:show="statusVisible" preset="card" title="变更用户状态" style="width: 460px">
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
      <template #footer>
        <div class="user-page__footer">
          <n-button @click="statusVisible = false">取消</n-button>
          <n-button type="primary" :loading="statusSubmitting" @click="submitStatus">确定</n-button>
        </div>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.user-page__form {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-lg, 16px);
}

.user-page__row {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--wa-spacing-lg, 16px);
}

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

.user-page__footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--wa-spacing-sm, 8px);
}
</style>
