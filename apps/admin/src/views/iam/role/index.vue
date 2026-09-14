<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NAlert,
  NInput,
  NInputNumber,
  NModal,
  NRadio,
  NRadioGroup,
  NSpace,
  NTag,
  NTree,
  ProTable,
  feedback
} from '@admin/ui'
import type { ProColumn, ProRowAction } from '@admin/ui'
import { useDict } from '@/composables/useDict'
import DictTag from '@/components/DictTag.vue'
import type { DeptDTO, MenuDTO, RoleResponse } from '@admin/api'
import {
  assignRolePermissionsAction,
  changeRoleStatusAction,
  createRoleAction,
  deleteRoleAction,
  fetchRolePage,
  loadDeptList,
  loadMenuList,
  updateRoleAction,
  type RoleFormModel
} from '@/api/iam'

/**
 * 角色管理页。
 *
 * <h3>权限分配是本页的核心，也是整个权限体系的可视化入口</h3>
 * 它把三个概念绑在一起提交：
 * <ol>
 *   <li><b>菜单/按钮权限</b>（NTree 勾选）—— 决定"能看到哪些页面与按钮"</li>
 *   <li><b>数据范围</b>（dataScope）—— 决定"能看到哪些数据行"</li>
 *   <li><b>自定义部门</b>（CUSTOM 时生效）—— 数据范围的参数</li>
 * </ol>
 * 三者是<b>一次提交</b>（后端 {@code assignPermissions} 是全量覆盖语义）。
 * 分开提交会让中间状态可用：比如"范围已改成 CUSTOM 但还没选部门"，
 * 那个瞬间该角色下的用户<b>看不到任何数据</b> —— 一个真实存在的越权/失能窗口。
 */

const tableRef = ref<{ reload: (resetPage?: boolean) => void } | null>(null)
const dicts = useDict('sys_data_scope', 'sys_status')

function requireId(row: RoleResponse): number {
  if (row.id === undefined) {
    throw new Error('角色数据缺少主键 ID')
  }
  return row.id
}

// ---------------------------------------------------------------------
// 表格
// ---------------------------------------------------------------------

const columns = computed<ProColumn<RoleResponse>[]>(() => [
  { key: 'roleName', title: '角色名称', minWidth: 140, search: 'input' },
  { key: 'roleKey', title: '角色标识', width: 160, search: 'input' },
  {
    key: 'dataScope',
    title: '数据范围',
    width: 130,
    renderFn: (row) => h(DictTag, { dictType: 'sys_data_scope', value: row.dataScope })
  },
  { key: 'userCount', title: '用户数', width: 90 },
  {
    key: 'menuCount',
    title: '已授权菜单',
    width: 110,
    renderFn: (row) =>
      h(
        NTag,
        { size: 'small', type: (row.menuCount ?? 0) > 0 ? 'info' : 'warning', bordered: false },
        { default: () => `${row.menuCount ?? 0} 项` }
      )
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
    key: 'builtin',
    title: '类型',
    width: 90,
    renderFn: (row) =>
      row.builtin
        ? h(NTag, { size: 'small', type: 'warning', bordered: false }, { default: () => '内置' })
        : h('span', { class: 'role-page__muted' }, '自定义')
  },
  { key: 'createTime', title: '创建时间', width: 170, sortable: true, render: 'datetime' }
])

const rowActions: ProRowAction<RoleResponse>[] = [
  {
    key: 'edit',
    label: '编辑',
    permission: 'iam:role:update',
    // 内置角色不允许改名与改标识（由后端聚合裁决），这里提前禁用，
    // 避免用户点了才被拒绝
    disabled: (row) => row.builtin === true,
    onClick: (row) => openEdit(row)
  },
  {
    key: 'permissions',
    label: '分配权限',
    permission: 'iam:role:assign',
    onClick: (row) => void openPermission(row)
  },
  {
    key: 'toggleStatus',
    label: '停用/启用',
    permission: 'iam:role:update',
    disabled: (row) => row.builtin === true,
    onClick: async (row) => {
      const next = row.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE'
      await changeRoleStatusAction(requireId(row), next)
      feedback.success(next === 'ACTIVE' ? '已启用' : '已停用，该角色下的用户已立即降权')
      tableRef.value?.reload(false)
    }
  },
  {
    key: 'delete',
    label: '删除',
    permission: 'iam:role:delete',
    danger: true,
    disabled: (row) => row.builtin === true || (row.userCount ?? 0) > 0,
    confirm: (row) => `确定删除角色「${row.roleName}」？该操作不可恢复。`,
    onClick: async (row) => {
      await deleteRoleAction(requireId(row))
      feedback.success('角色已删除')
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
const form = ref<RoleFormModel>({ roleKey: '', roleName: '', sort: 0, dataScope: 'SELF', remark: '' })
const isEdit = computed(() => editingId.value !== null)

function openCreate(): void {
  editingId.value = null
  form.value = { roleKey: '', roleName: '', sort: 0, dataScope: 'SELF', remark: '' }
  formVisible.value = true
}

function openEdit(row: RoleResponse): void {
  editingId.value = requireId(row)
  form.value = {
    roleKey: row.roleKey ?? '',
    roleName: row.roleName ?? '',
    sort: row.sort ?? 0,
    dataScope: row.dataScope ?? 'SELF',
    remark: row.remark ?? ''
  }
  formVisible.value = true
}

async function submitForm(): Promise<void> {
  if (!form.value.roleName.trim()) {
    feedback.warning('请输入角色名称')
    return
  }
  if (!isEdit.value && !form.value.roleKey.trim()) {
    feedback.warning('请输入角色标识')
    return
  }
  submitting.value = true
  try {
    if (isEdit.value) {
      await updateRoleAction(editingId.value as number, {
        roleName: form.value.roleName,
        sort: form.value.sort
      })
    } else {
      await createRoleAction(form.value)
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

// ---------------------------------------------------------------------
// 权限分配
// ---------------------------------------------------------------------

interface CheckableNode {
  label: string
  key: number
  children?: CheckableNode[]
}

const permVisible = ref(false)
const permSubmitting = ref(false)
const permLoading = ref(false)
const permTargetId = ref<number | null>(null)
const permTargetName = ref('')
const menuTree = ref<CheckableNode[]>([])
const flatMenus = ref<MenuDTO[]>([])
const deptTree = ref<CheckableNode[]>([])
const checkedMenuIds = ref<number[]>([])
const checkedDeptIds = ref<number[]>([])
const dataScope = ref('SELF')

/** 只有 CUSTOM 才需要选部门 —— 其余范围的部门集合对结果没有影响。 */
const needDeptScope = computed(() => dataScope.value === 'CUSTOM')

function buildTree<T extends { id?: number; parentId?: number; sort?: number }>(
  rows: T[],
  labelOf: (row: T) => string
): CheckableNode[] {
  const byParent = new Map<number, T[]>()
  for (const row of rows) {
    if (row.id === undefined) continue
    const parentId = row.parentId ?? 0
    const siblings = byParent.get(parentId) ?? []
    siblings.push(row)
    byParent.set(parentId, siblings)
  }
  const build = (parentId: number): CheckableNode[] =>
    (byParent.get(parentId) ?? [])
      .slice()
      .sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0))
      .map((row) => {
        const children = build(row.id as number)
        return {
          label: labelOf(row),
          key: row.id as number,
          children: children.length > 0 ? children : undefined
        }
      })
  return build(0)
}

async function ensureTreeData(): Promise<void> {
  if (menuTree.value.length > 0) {
    return
  }
  permLoading.value = true
  try {
    const [menus, depts] = await Promise.all([loadMenuList(), loadDeptList()])
    flatMenus.value = menus ?? []
    menuTree.value = buildTree(flatMenus.value, (menu) => {
      // 按钮在树上加个后缀，否则"用户管理"与"用户新增"在视觉上难以区分层级关系
      const suffix = menu.menuType === 'BUTTON' ? '（按钮）' : ''
      return `${menu.menuName ?? ''}${suffix}`
    })
    deptTree.value = buildTree(depts as DeptDTO[], (dept) => dept.deptName ?? '未命名部门')
  } finally {
    permLoading.value = false
  }
}

async function openPermission(row: RoleResponse): Promise<void> {
  const id = requireId(row)
  permTargetId.value = id
  permTargetName.value = row.roleName ?? ''
  permVisible.value = true

  await ensureTreeData()

  // 详情接口才返回 menuIds / deptIds（列表为了体积不带它们）
  permLoading.value = true
  try {
    const { getRole } = await import('@admin/api')
    const detail = await getRole(id)
    checkedMenuIds.value = [...(detail.menuIds ?? [])]
    checkedDeptIds.value = [...(detail.deptIds ?? [])]
    dataScope.value = detail.dataScope ?? 'SELF'
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '读取角色权限失败')
  } finally {
    permLoading.value = false
  }
}

async function submitPermission(): Promise<void> {
  if (permTargetId.value === null) return
  if (dataScope.value === 'CUSTOM' && checkedDeptIds.value.length === 0) {
    // 这不是"建议"，而是必须拦住的组合：CUSTOM + 空部门集合 = 该角色下所有用户看不到任何数据
    feedback.warning('数据范围选择「自定义」时，必须至少选择一个部门，否则该角色的用户将看不到任何数据')
    return
  }
  permSubmitting.value = true
  try {
    await assignRolePermissionsAction(
      permTargetId.value,
      checkedMenuIds.value,
      dataScope.value,
      checkedDeptIds.value
    )
    feedback.success('权限已保存，该角色下的用户权限已立即刷新')
    permVisible.value = false
    tableRef.value?.reload(false)
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    permSubmitting.value = false
  }
}

const dataScopeOptions = computed(() =>
  dicts.sys_data_scope.value.map((option) => ({ label: option.label, value: option.value }))
)

onMounted(() => {
  void ensureTreeData()
})
</script>

<template>
  <div class="role-page">
    <ProTable
      ref="tableRef"
      :columns="columns"
      :request="fetchRolePage"
      :toolbar="['create', 'refresh']"
      :row-actions="rowActions"
      empty-action-text="创建第一个角色"
      @create="openCreate"
      @empty-action="openCreate"
    />

    <!-- 新增 / 编辑 -->
    <n-modal v-model:show="formVisible" preset="card" :title="isEdit ? '编辑角色' : '新增角色'" style="width: 520px">
      <div class="role-page__form">
        <div class="role-page__field">
          <label>角色标识 <span class="role-page__required">*</span></label>
          <n-input
            v-model:value="form.roleKey"
            :disabled="isEdit"
            placeholder="如 AUDITOR。大写字母/数字/下划线，不要带 ROLE_ 前缀"
          />
          <p class="role-page__tip">
            标识是代码里引用角色的依据，创建后不可修改。Spring Security 的 hasRole 会自动加 ROLE_ 前缀，所以这里不要写。
          </p>
        </div>
        <div class="role-page__field">
          <label>角色名称 <span class="role-page__required">*</span></label>
          <n-input v-model:value="form.roleName" placeholder="如 审计员" />
        </div>
        <div class="role-page__field">
          <label>显示顺序</label>
          <n-input-number v-model:value="form.sort" :min="0" />
        </div>
        <div class="role-page__field">
          <label>备注</label>
          <n-input v-model:value="form.remark" type="textarea" :rows="2" />
        </div>
        <n-alert v-if="!isEdit" type="info" :bordered="false">
          数据范围与菜单权限在创建后通过「分配权限」设置。
        </n-alert>
      </div>
      <template #footer>
        <div class="role-page__footer">
          <n-button @click="formVisible = false">取消</n-button>
          <n-button type="primary" :loading="submitting" @click="submitForm">确定</n-button>
        </div>
      </template>
    </n-modal>

    <!-- 权限分配 -->
    <n-modal
      v-model:show="permVisible"
      preset="card"
      :title="`分配权限 — ${permTargetName}`"
      style="width: 720px"
    >
      <n-space vertical :size="16">
        <div class="role-page__field">
          <label>数据范围</label>
          <n-radio-group v-model:value="dataScope">
            <n-space>
              <n-radio v-for="item in dataScopeOptions" :key="item.value" :value="item.value">
                {{ item.label }}
              </n-radio>
            </n-space>
          </n-radio-group>
          <p class="role-page__tip">
            决定该角色能看到<b>哪些数据行</b>。与下面的菜单权限相互独立 ——
            菜单权限管"能看到哪些页面"，数据范围管"页面里的数据能看到多少"。
          </p>
        </div>

        <div v-if="needDeptScope" class="role-page__field">
          <label>自定义部门 <span class="role-page__required">*</span></label>
          <n-alert type="warning" :bordered="false" class="role-page__alert">
            选择「自定义」时必须至少勾选一个部门，否则该角色下的用户将看不到任何数据。
          </n-alert>
          <div class="role-page__tree">
            <n-tree
              :data="deptTree"
              checkable
              cascade
              default-expand-all
              :checked-keys="checkedDeptIds"
              @update:checked-keys="(keys: Array<string | number>) => (checkedDeptIds = keys.map(Number))"
            />
          </div>
        </div>

        <div class="role-page__field">
          <label>菜单与按钮权限</label>
          <p class="role-page__tip">
            勾选后该角色可见对应菜单与按钮。{{ flatMenus.length }} 个权限点已加载。
          </p>
          <div class="role-page__tree role-page__tree--tall">
            <n-tree
              :data="menuTree"
              checkable
              cascade
              default-expand-all
              :checked-keys="checkedMenuIds"
              @update:checked-keys="(keys: Array<string | number>) => (checkedMenuIds = keys.map(Number))"
            />
          </div>
        </div>
      </n-space>

      <template #footer>
        <div class="role-page__footer">
          <n-button @click="permVisible = false">取消</n-button>
          <n-button type="primary" :loading="permSubmitting" @click="submitPermission">保存</n-button>
        </div>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.role-page__form {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-lg, 16px);
}

.role-page__field {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-xs, 4px);
}

.role-page__field label {
  font-size: var(--wa-font-size-md, 14px);
  color: var(--wa-text-primary, #1f2329);
}

.role-page__required {
  color: var(--wa-color-error, #dc2626);
}

.role-page__tip {
  margin: 0;
  font-size: var(--wa-font-size-xs, 12px);
  line-height: 1.6;
  color: var(--wa-text-disabled, #a8b0ba);
}

.role-page__alert {
  margin-bottom: var(--wa-spacing-sm, 8px);
}

.role-page__tree {
  max-height: 220px;
  overflow: auto;
  padding: var(--wa-spacing-sm, 8px);
  border: 1px solid var(--wa-border, #e4e7ed);
  border-radius: var(--wa-radius-md, 4px);
}

.role-page__tree--tall {
  max-height: 320px;
}

.role-page__muted {
  color: var(--wa-text-disabled, #a8b0ba);
}

.role-page__footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--wa-spacing-sm, 8px);
}
</style>
