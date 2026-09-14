<script setup lang="ts">
import { computed, h, ref } from 'vue'
import { NInput, NInputNumber, NModal, NSelect, NSwitch, ProTable, feedback } from '@admin/ui'
import type { ProColumn, ProRowAction, ProTableQuery } from '@admin/ui'
import { useDict } from '@/composables/useDict'
import DictTag from '@/components/DictTag.vue'
import type { MenuDTO, MenuRequest } from '@admin/api'
import { createMenuAction, deleteMenuAction, loadMenuList, updateMenuAction } from '@/api/iam'

/**
 * 菜单管理页。
 *
 * <h3>⚠️ 修改菜单会影响所有租户</h3>
 * 菜单是<b>平台级共享定义</b>（{@code tenant_id=0}），不参与租户隔离。
 * 改一个菜单的 component 或权限码，所有租户的前端都会跟着变。
 * 因此本页在界面上给出明确提示，避免被当作"我租户自己的配置"来操作。
 *
 * <h3>component 与 perms 的填写规则必须写清楚</h3>
 * 这两个字段是前后端之间的契约，填错不会有编译错误，只会在运行时表现为
 * "菜单点不开"或"按钮永远不显示"。因此表单里对两者的格式给出示例。
 */

const tableRef = ref<{ reload: (resetPage?: boolean) => void } | null>(null)
const dicts = useDict('sys_menu_type', 'sys_yes_no')

const menus = ref<MenuDTO[]>([])

/**
 * 菜单没有物化路径（与部门不同），层级需要沿 parentId 向上回推。
 * 计算结果缓存成 Map，避免每行都重新走一遍链（行数 × 深度 的重复计算）。
 */
const depthById = computed(() => {
  const byId = new Map<number, MenuDTO>()
  for (const menu of menus.value) {
    if (menu.id !== undefined) byId.set(menu.id, menu)
  }
  const depths = new Map<number, number>()
  for (const menu of menus.value) {
    if (menu.id === undefined) continue
    let depth = 0
    let cursor = menu
    const guard = new Set<number>()
    // guard 防环：脏数据（父子互指）会让这个循环永不结束、把页面卡死
    while (cursor.parentId && cursor.parentId !== 0 && !guard.has(cursor.id as number)) {
      guard.add(cursor.id as number)
      const parent = byId.get(cursor.parentId)
      if (!parent) break
      depth += 1
      cursor = parent
    }
    depths.set(menu.id, depth)
  }
  return depths
})

async function fetchMenuPage(_query: ProTableQuery) {
  const list = (await loadMenuList()) ?? []
  menus.value = list
  return { records: list, total: list.length, page: 1, size: Math.max(list.length, 1) }
}

const columns: ProColumn<MenuDTO>[] = [
  {
    key: 'menuName',
    title: '菜单名称',
    minWidth: 200,
    renderFn: (row) =>
      h(
        'span',
        { style: { paddingLeft: `${(depthById.value.get(row.id as number) ?? 0) * 20}px` } },
        row.menuName ?? ''
      )
  },
  {
    key: 'menuType',
    title: '类型',
    width: 90,
    renderFn: (row) => h(DictTag, { dictType: 'sys_menu_type', value: row.menuType })
  },
  { key: 'path', title: '路由地址', width: 150 },
  { key: 'component', title: '组件路径', width: 190 },
  {
    key: 'perms',
    title: '权限码',
    width: 180,
    renderFn: (row) =>
      row.perms
        ? h('code', { class: 'menu-page__code' }, row.perms)
        : h('span', { class: 'menu-page__muted' }, '—')
  },
  {
    key: 'visible',
    title: '显示',
    width: 80,
    renderFn: (row) => h(DictTag, { dictType: 'sys_yes_no', value: row.visible ? '1' : '0' })
  },
  { key: 'sort', title: '排序', width: 80 }
]

const rowActions: ProRowAction<MenuDTO>[] = [
  {
    key: 'addChild',
    label: '新增下级',
    permission: 'iam:menu:create',
    // 按钮下不能再挂子节点 —— 权限点是最末级
    disabled: (row) => row.menuType === 'BUTTON',
    onClick: (row) => openForm(null, row)
  },
  { key: 'edit', label: '编辑', permission: 'iam:menu:update', onClick: (row) => openForm(row, null) },
  {
    key: 'delete',
    label: '删除',
    permission: 'iam:menu:delete',
    danger: true,
    // 有子节点时提前禁用（后端也会拒绝）。不做级联删除：
    // 一次误点会连带删掉整个子树的权限点，而它们可能正被角色引用
    disabled: (row) => menus.value.some((item) => item.parentId === row.id),
    confirm: (row) =>
      `确定删除菜单「${row.menuName}」？若已被角色引用，相关角色的权限会同步减少。`,
    onClick: async (row) => {
      if (row.id === undefined) return
      await deleteMenuAction(row.id)
      feedback.success('菜单已删除')
      tableRef.value?.reload(false)
    }
  }
]

// ---------------------------------------------------------------------
// 表单
// ---------------------------------------------------------------------

/**
 * 表单模型。
 *
 * <p>为什么不用生成类型 {@code MenuRequest} 直接当表单模型：
 * 生成器把 {@code menuType} 收窄成了字面量联合（{@code 'DIR' | 'MENU' | 'BUTTON'}），
 * 而表单里需要承接<b>任意字符串</b> —— 后端返回的脏数据（历史遗留的 'dir'）、
 * 用户在下拉里选择前的中间态，都会是普通 string。
 *
 * <p>用 {@code Omit} 放宽这一个字段，比在十几处赋值时都写 {@code as} 断言更清爽，
 * 也把"这里放宽了"这件事<b>显式记录在一个地方</b>。
 */
type MenuFormModel = Omit<MenuRequest, 'menuType'> & { menuType: string }

const formVisible = ref(false)
const submitting = ref(false)
const editingId = ref<number | null>(null)
const form = ref<MenuFormModel>({
  parentId: 0,
  menuName: '',
  menuType: 'MENU',
  path: '',
  component: '',
  perms: '',
  icon: '',
  sort: 0,
  visible: true,
  keepAlive: true,
  alwaysShow: false
})
const isEdit = computed(() => editingId.value !== null)

/** 只有 MENU 需要 component；只有 BUTTON 需要 perms。表单据此动态提示。 */
const needComponent = computed(() => form.value.menuType === 'MENU')
const needPerms = computed(() => form.value.menuType === 'BUTTON')
const needPath = computed(() => form.value.menuType !== 'BUTTON')

const parentOptions = computed(() => {
  const options = menus.value
    .filter((menu) => menu.id !== undefined && menu.menuType !== 'BUTTON')
    .filter((menu) => menu.id !== editingId.value)
    .map((menu) => ({
      label: `${'　'.repeat(depthById.value.get(menu.id as number) ?? 0)}${menu.menuName ?? ''}`,
      value: menu.id as number
    }))
  options.unshift({ label: '— 根节点 —', value: 0 })
  return options
})

function openForm(row: MenuDTO | null, parent: MenuDTO | null): void {
  if (row) {
    editingId.value = row.id ?? null
    form.value = {
      parentId: row.parentId ?? 0,
      menuName: row.menuName ?? '',
      menuType: row.menuType ?? 'MENU',
      path: row.path ?? '',
      component: row.component ?? '',
      perms: row.perms ?? '',
      icon: row.icon ?? '',
      sort: row.sort ?? 0,
      visible: row.visible ?? true,
      keepAlive: row.keepAlive ?? true,
      alwaysShow: row.alwaysShow ?? false
    }
  } else {
    editingId.value = null
    form.value = {
      parentId: parent?.id ?? 0,
      menuName: '',
      menuType: parent?.menuType === 'DIR' ? 'MENU' : 'BUTTON',
      path: '',
      component: '',
      perms: '',
      icon: '',
      sort: 0,
      visible: true,
      keepAlive: true,
      alwaysShow: false
    }
  }
  formVisible.value = true
}

async function submitForm(): Promise<void> {
  if (!form.value.menuName.trim()) {
    feedback.warning('请输入菜单名称')
    return
  }
  if (needPath.value && !form.value.path?.trim()) {
    feedback.warning('目录与菜单必须填写路由地址')
    return
  }
  if (needComponent.value && !form.value.component?.trim()) {
    feedback.warning('菜单类型必须填写组件路径，否则页面打不开')
    return
  }
  if (needPerms.value && !form.value.perms?.trim()) {
    feedback.warning('按钮类型必须填写权限码，否则 @PreAuthorize 无法匹配')
    return
  }
  submitting.value = true
  try {
    // 这里的一次断言是上面"放宽 menuType 为 string"的必要代价：
    // 上面的三条前置校验已经保证 menuType 是合法取值，
    // 因此断言在运行时是安全的 —— 校验与断言必须成对出现，缺一不可
    const payload = form.value as MenuRequest
    if (editingId.value !== null) {
      await updateMenuAction(editingId.value, payload)
    } else {
      await createMenuAction(payload)
    }
    feedback.success('保存成功')
    formVisible.value = false
    tableRef.value?.reload(false)
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    submitting.value = false
  }
}

const menuTypeOptions = computed(() =>
  dicts.sys_menu_type.value.map((o) => ({ label: o.label, value: o.value }))
)
</script>

<template>
  <div class="menu-page">
    <div class="menu-page__notice">
      菜单是<b>平台级共享定义</b>，修改会影响所有租户。前端页面路径由 <code>component</code> 决定，
      需与 <code>src/views</code> 下的文件路径一致（如 <code>iam/user/index</code>）。
    </div>

    <ProTable
      ref="tableRef"
      :columns="columns"
      :request="fetchMenuPage"
      :toolbar="['create', 'refresh']"
      :row-actions="rowActions"
      :default-page-size="500"
      empty-action-text="创建第一个菜单"
      @create="openForm(null, null)"
      @empty-action="openForm(null, null)"
    />

    <n-modal v-model:show="formVisible" preset="card" :title="isEdit ? '编辑菜单' : '新增菜单'" style="width: 600px">
      <div class="menu-page__form">
        <div class="menu-page__field">
          <label>上级节点</label>
          <n-select v-model:value="form.parentId" :options="parentOptions" />
        </div>
        <div class="menu-page__field">
          <label>类型 <span class="menu-page__required">*</span></label>
          <n-select v-model:value="form.menuType" :options="menuTypeOptions" />
          <p class="menu-page__tip">
            目录=可展开的父节点（必有路由地址）；菜单=可打开的页面（必有组件路径）；
            按钮=权限点（必有权限码，不在菜单中显示）。
          </p>
        </div>
        <div class="menu-page__field">
          <label>菜单名称 <span class="menu-page__required">*</span></label>
          <n-input v-model:value="form.menuName" placeholder="如 用户管理" />
        </div>
        <div v-if="needPath" class="menu-page__field">
          <label>路由地址 <span class="menu-page__required">*</span></label>
          <n-input v-model:value="form.path" placeholder="目录用 /system；菜单用 user 这类相对片段" />
        </div>
        <div v-if="needComponent" class="menu-page__field">
          <label>组件路径 <span class="menu-page__required">*</span></label>
          <n-input v-model:value="form.component" placeholder="如 iam/user/index" />
          <p class="menu-page__tip">
            对应 <code>apps/admin/src/views/iam/user/index.vue</code>。填错不会报错，
            只会让菜单点开后显示"页面尚未实现"的占位页。
          </p>
        </div>
        <div v-if="needPerms" class="menu-page__field">
          <label>权限码 <span class="menu-page__required">*</span></label>
          <n-input v-model:value="form.perms" placeholder="如 iam:user:add" />
          <p class="menu-page__tip">
            格式 <code>上下文:资源:动作</code>。必须与后端 <code>@PreAuthorize</code> 里写的完全一致，
            否则按钮显示出来了、点下去却被拒绝。
          </p>
        </div>
        <div class="menu-page__field">
          <label>显示顺序</label>
          <n-input-number v-model:value="form.sort" :min="0" />
        </div>
        <div class="menu-page__switches">
          <label class="menu-page__switch">
            <n-switch v-model:value="form.visible" />
            <span>在菜单中显示</span>
          </label>
          <label class="menu-page__switch">
            <n-switch v-model:value="form.keepAlive" />
            <span>缓存页面状态</span>
          </label>
          <label class="menu-page__switch">
            <n-switch v-model:value="form.alwaysShow" />
            <span>仅一个子路由时也显示父级</span>
          </label>
        </div>
      </div>
      <template #footer>
        <div class="menu-page__footer">
          <n-button @click="formVisible = false">取消</n-button>
          <n-button type="primary" :loading="submitting" @click="submitForm">确定</n-button>
        </div>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.menu-page__notice {
  margin-bottom: var(--wa-spacing-md, 12px);
  padding: var(--wa-spacing-md, 12px);
  border-left: 3px solid var(--wa-color-warning, #d97706);
  background: var(--wa-bg-elevated, #fff);
  font-size: var(--wa-font-size-xs, 12px);
  line-height: 1.8;
  color: var(--wa-text-secondary, #5c6570);
}

.menu-page__code {
  padding: 1px 5px;
  border-radius: 3px;
  background: var(--wa-bg-hover, #f0f2f5);
  font-size: 0.9em;
}

.menu-page__muted {
  color: var(--wa-text-disabled, #a8b0ba);
}

.menu-page__form {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-lg, 16px);
}

.menu-page__field {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-xs, 4px);
}

.menu-page__field label {
  font-size: var(--wa-font-size-md, 14px);
  color: var(--wa-text-primary, #1f2329);
}

.menu-page__required {
  color: var(--wa-color-error, #dc2626);
}

.menu-page__tip {
  margin: 0;
  font-size: var(--wa-font-size-xs, 12px);
  line-height: 1.6;
  color: var(--wa-text-disabled, #a8b0ba);
}

.menu-page__switches {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-sm, 8px);
}

.menu-page__switch {
  display: flex;
  align-items: center;
  gap: var(--wa-spacing-sm, 8px);
  font-size: var(--wa-font-size-md, 14px);
  color: var(--wa-text-primary, #1f2329);
}

.menu-page__footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--wa-spacing-sm, 8px);
}

code {
  padding: 0 4px;
  border-radius: 3px;
  background: var(--wa-bg-hover, #f0f2f5);
}
</style>
