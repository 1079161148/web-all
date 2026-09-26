<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NAlert,
  NRadio,
  NRadioGroup,
  NSpace,
  NTag,
  NTree,
  PageContainer,
  ProModal,
  ProTable,
  TREE_PRESETS,
  feedback,
  flatToTree
} from '@admin/ui'
import type { ProColumn, ProFormItem, ProRowAction, ProTreeNode } from '@admin/ui'
import { useDict } from '@/composables/useDict'
import { DictTag } from '@admin/ui'
import type { MenuDTO, RoleResponse, RoleSimulationResponse } from '@admin/api'
import {
  assignRolePermissionsAction,
  changeRoleStatusAction,
  createRoleAction,
  deleteRoleAction,
  fetchRolePage,
  loadDeptList,
  loadMenuList,
  simulateRoleDataScopeAction,
  updateRoleAction,
  type RoleFormModel
} from '@/api/iam'
import { pageUsers } from '@admin/api'

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

const tableRef = ref<{ reload: () => void; refresh: () => void } | null>(null)
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
    key: 'simulate',
    label: '数据权限预览',
    permission: 'iam:role:simulate',
    // 超管不受数据范围限制，模拟它必然得到"全部数据"，没有信息量且会误导，
    // 因此这里提前禁用；后端也会拒绝（两处一致，避免"点了才被拒"）
    disabled: (row) => row.roleKey === 'SUPER_ADMIN',
    onClick: (row) => openSimulation(row)
  },
  {
    key: 'toggleStatus',
    // 文案跟当前状态走：启用中显示「禁用」，已禁用显示「正常」
    label: (row) => (row.status === 'ACTIVE' ? '禁用' : '正常'),
    permission: 'iam:role:update',
    disabled: (row) => row.builtin === true,
    confirm: (row) =>
      row.status === 'ACTIVE'
        ? `确定禁用角色「${row.roleName}」？禁用后该角色下的用户立即降权，需要重新分配角色才能恢复。`
        : `确定启用角色「${row.roleName}」？其下用户的权限会立即生效。`,
    onClick: async (row) => {
      const next = row.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE'
      await changeRoleStatusAction(requireId(row), next)
      feedback.success(next === 'ACTIVE' ? '已启用' : '已停用，该角色下的用户已立即降权')
      tableRef.value?.refresh()
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

const formItems = computed<ProFormItem[]>(() => [
  {
    field: 'roleKey',
    title: '角色标识',
    required: true,
    message: '请输入角色标识',
    disabled: isEdit.value,
    placeholder: '如 AUDITOR。大写字母/数字/下划线，不要带 ROLE_ 前缀',
    tip: isEdit.value
      ? '标识是代码里引用角色的依据，创建后不可修改'
      : '标识是代码里引用角色的依据，创建后不可修改。Spring Security 的 hasRole 会自动加 ROLE_ 前缀，所以这里不要写。'
  },
  { field: 'roleName', title: '角色名称', required: true, message: '请输入角色名称', placeholder: '如 审计员' },
  { field: 'sort', title: '显示顺序', type: 'number', value: 0, props: { min: 0 } },
  { field: 'remark', title: '备注', type: 'textarea' }
])

function openCreate(): void {
  editingId.value = null
  modalModel.value = { sort: 0 }
  formVisible.value = true
}

function openEdit(row: RoleResponse): void {
  editingId.value = requireId(row)
  modalModel.value = {
    roleKey: row.roleKey ?? '',
    roleName: row.roleName ?? '',
    sort: row.sort ?? 0,
    remark: row.remark ?? ''
  }
  formVisible.value = true
}

/** 编辑时只更新名称与排序（与后端 update 接口语义一致）。 */
async function submitForm(values: Record<string, unknown>): Promise<void> {
  const roleName = String(values.roleName ?? '')
  const sort = values.sort === undefined || values.sort === null ? 0 : Number(values.sort)
  if (isEdit.value) {
    await updateRoleAction(editingId.value as number, { roleName, sort })
  } else {
    const payload: RoleFormModel = {
      roleKey: String(values.roleKey ?? ''),
      roleName,
      sort,
      dataScope: 'SELF',
      remark: values.remark ? String(values.remark) : ''
    }
    await createRoleAction(payload)
  }
  feedback.success('保存成功')
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

/**
 * 共享建树结果 → 本页的 CheckableNode。
 *
 * <p>这里<b>只做类型收敛，不做任何建树逻辑</b> ——
 * 分组、按 sort 排序、标题文案（含菜单预设给按钮加的「（按钮）」后缀）
 * 全部来自 {@code flatToTree} 与预设。
 *
 * <p>为什么要这层收敛：{@code ProTreeNode.key} 是 {@code string | number}
 * （树是通用的），而本页的节点键一定是数字 ——
 * 收敛一次，提交给接口的 {@code menuIds} 就不需要写 {@code as number[]} 断言。
 * <b>断言一旦写下去，它就是对编译器的谎话</b>，而这里能诚实地把类型收窄。
 */
function toCheckable(nodes: ProTreeNode[]): CheckableNode[] {
  return nodes.map((node) => ({
    label: node.label,
    key: node.key as number,
    ...(node.children?.length ? { children: toCheckable(node.children) } : {})
  }))
}

async function ensureTreeData(): Promise<void> {
  if (menuTree.value.length > 0) {
    return
  }
  permLoading.value = true
  try {
    const [menus, depts] = await Promise.all([loadMenuList(), loadDeptList()])
    flatMenus.value = menus ?? []
    // 预设自带「按钮加后缀」的规则 —— 这里此前手写过同一段逻辑，
    // 而现在"菜单在树上长什么样"只有 tree.ts 一处定义
    menuTree.value = toCheckable(flatToTree(flatMenus.value, { labelOf: TREE_PRESETS.menu.labelOf }))
    deptTree.value = toCheckable(flatToTree(depts ?? [], TREE_PRESETS.dept))
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
    tableRef.value?.refresh()
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    permSubmitting.value = false
  }
}

// ---------------------------------------------------------------------
// 数据权限预览（模拟）
// ---------------------------------------------------------------------

const simVisible = ref(false)
const simLoading = ref(false)
const simTargetId = ref<number | null>(null)
const simTargetName = ref('')
// 选中值用**账号**（字符串）而不是用户 ID：雪花 ID 超过 2^53，
// 经浏览器数字会被取整，传回后端就成了"另一个用户"。见 api/iam.ts 的说明。
const simUsername = ref<string | null>(null)
const simUserOptions = ref<Array<{ label: string; value: string }>>([])
const simSearching = ref(false)
const simResult = ref<RoleSimulationResponse | null>(null)

/** 数据范围中文名：与表格列共用同一份字典，避免出现第二份文案。 */
const simScopeLabel = computed(() => {
  const scope = simResult.value?.dataScope
  if (!scope) {
    return ''
  }
  return dicts.sys_data_scope.value.find((item) => item.value === scope)?.label ?? scope
})

function openSimulation(row: RoleResponse): void {
  simTargetId.value = requireId(row)
  simTargetName.value = row.roleName ?? ''
  simUsername.value = null
  simResult.value = null
  simUserOptions.value = []
  simVisible.value = true
  void searchSimUsers('')
}

/**
 * 模拟用户的远程检索。
 *
 * <p>用远程搜索而不是一次性拉全量：用户数没有上限，全量加载会让"选一个人"
 * 变成等待一个可能很慢的请求。
 *
 * <p>⚠️ 这里调用的是<b>受数据权限约束</b>的用户列表接口，因此候选里只有调用者
 * 自己可见范围内的用户。这是刻意的 —— 预览功能不该顺带成为"查看全部用户名单"的口子。
 * 若目标用户不在你的可见范围内，应由可见该用户的人来执行预览。
 */
async function searchSimUsers(keyword: string): Promise<void> {
  simSearching.value = true
  try {
    const page = await pageUsers({ page: 1, size: 20, username: keyword || undefined })
    simUserOptions.value = (page.records ?? []).map((user) => ({
      label: user.nickname ? `${user.nickname}（${user.username}）` : String(user.username ?? ''),
      value: String(user.username ?? '')
    }))
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '用户检索失败')
  } finally {
    simSearching.value = false
  }
}

async function runSimulation(): Promise<void> {
  if (simTargetId.value === null || !simUsername.value) {
    feedback.warning('请先选择要模拟的用户')
    return
  }
  simLoading.value = true
  try {
    simResult.value = await simulateRoleDataScopeAction(simTargetId.value, simUsername.value)
  } catch (error) {
    // 失败时清空上一次结果：留着旧数字会让人以为"这次预览就是这个结果"
    simResult.value = null
    feedback.error(error instanceof Error ? error.message : '预览失败')
  } finally {
    simLoading.value = false
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
  <PageContainer title="角色管理" description="角色决定用户能看到哪些菜单与数据；内置角色受保护不可改名或删除">
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
  </PageContainer>

  <!-- 新增 / 编辑 -->
  <ProModal
    v-model:visible="formVisible"
    :title="isEdit ? '编辑角色' : '新增角色'"
    :items="formItems"
    :model="modalModel"
    :cols="1"
    :submit="submitForm"
    :on-success="() => (editingId === null ? tableRef?.reload() : tableRef?.refresh())"
    @error="(error: unknown) => feedback.error(error instanceof Error ? error.message : '保存失败')"
  />

  <!-- 权限分配：内容是两组勾选树 + 数据范围联动，不是标准字段表单，用默认插槽 -->
  <ProModal
    v-model:visible="permVisible"
    :title="`分配权限 — ${permTargetName}`"
    :width="720"
    :loading="permSubmitting"
    submit-text="保存"
    @success="submitPermission"
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
  </ProModal>

  <!--
    数据权限预览：输入是「角色 + 用户」，输出是可见条数与生效部门。
    只读接口 —— 它回答"范围配得对不对"，而不是"把那些数据给我看看"。
  -->
  <ProModal
    v-model:visible="simVisible"
    :title="`数据权限预览 — ${simTargetName}`"
    :width="620"
    :loading="simLoading"
    submit-text="开始预览"
    @success="runSimulation"
  >
    <n-space vertical :size="16">
      <div class="role-page__field">
        <label>模拟用户 <span class="role-page__required">*</span></label>
        <n-select
          v-model:value="simUsername"
          filterable
          remote
          clearable
          :options="simUserOptions"
          :loading="simSearching"
          placeholder="输入账号或昵称搜索"
          @search="searchSimUsers"
        />
        <p class="role-page__tip">
          「仅本人 / 本部门 / 本部门及以下」都依赖"人在哪个部门"，
          因此预览必须指定被模拟的用户 —— 只给角色无法回答"这个人能看到什么"。
        </p>
      </div>

      <template v-if="simResult">
        <div class="role-page__result">
          <span>数据范围：{{ simScopeLabel }}</span>
          <span>所属部门：{{ simResult.deptName ?? '未分配' }}</span>
        </div>

        <div class="role-page__field">
          <label>可见数据条数</label>
          <div class="role-page__counts">
            <div v-for="item in simResult.resources" :key="item.resource" class="role-page__count">
              <span class="role-page__count-value">{{ item.visible }}</span>
              <span class="role-page__count-label">{{ item.label }}</span>
            </div>
          </div>
          <p class="role-page__tip">
            条数由与真实列表<b>同一个</b>数据权限拦截器产生，
            因此与实际打开列表看到的条数一致（这也是"预览可信"的唯一依据）。
          </p>
        </div>

        <div class="role-page__field">
          <label>生效部门（{{ simResult.depts?.length ?? 0 }}）</label>
          <n-space v-if="simResult.depts?.length">
            <n-tag
              v-for="dept in simResult.depts ?? []"
              :key="dept.deptId"
              size="small"
              :bordered="false"
            >
              {{ dept.deptName }}
            </n-tag>
          </n-space>
          <p v-else class="role-page__tip">
            {{
              simResult.dataScope === 'ALL'
                ? '不限部门 —— 可见全部数据。'
                : '不按部门集合限定范围（「仅本人」按创建人过滤）。'
            }}
          </p>
        </div>
      </template>
    </n-space>
  </ProModal>
</template>

<style scoped>
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

/* 预览结果：两个键值对一行；再往下是"大数字 + 说明"的计数块 */
.role-page__result {
  display: flex;
  flex-wrap: wrap;
  gap: var(--wa-spacing-lg, 16px);
  padding: var(--wa-spacing-sm, 8px) var(--wa-spacing-md, 12px);
  font-size: var(--wa-font-size-md, 14px);
  background: var(--wa-fill-light, #f5f7fa);
  border-radius: var(--wa-radius-md, 4px);
}

.role-page__counts {
  display: flex;
  flex-wrap: wrap;
  gap: var(--wa-spacing-lg, 16px);
}

.role-page__count {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 96px;
  padding: var(--wa-spacing-sm, 8px) var(--wa-spacing-md, 12px);
  border: 1px solid var(--wa-border, #e4e7ed);
  border-radius: var(--wa-radius-md, 4px);
}

.role-page__count-value {
  font-size: 22px;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
  color: var(--wa-text-primary, #1f2329);
}

.role-page__count-label {
  font-size: var(--wa-font-size-xs, 12px);
  color: var(--wa-text-disabled, #a8b0ba);
}
</style>
