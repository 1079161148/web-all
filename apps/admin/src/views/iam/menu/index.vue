<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NInput,
  NInputNumber,
  NSelect,
  NSwitch,
  IconPicker,
  PageContainer,
  ProModal,
  ProTable,
  TREE_PRESETS,
  feedback,
  filterFlatTreeByLabel,
  flatOptionsWithDepth,
  flatToTableTree
} from '@admin/ui'
import type { ProColumn, ProIconOption, ProRowAction, ProTableQuery } from '@admin/ui'
import { loadMenuIcons } from '@/icons'
import { useDict } from '@/composables/useDict'
import { DictTag } from '@admin/ui'
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

const tableRef = ref<{ reload: () => void; refresh: () => void } | null>(null)
const dicts = useDict('sys_menu_type', 'sys_yes_no')

/**
 * 图标候选（整包）。
 *
 * <p>刻意<b>异步</b>载入：整包图标约几百 KB，而它只有在这个页面（尤其是
 * 打开表单选图标时）才需要。首屏只注册常用集，整包走懒加载 chunk ——
 * 这是首屏体积门禁（设计 §12.1）能守住的直接原因，见 `@/icons` 的说明。
 */
const iconOptions = ref<ProIconOption[]>([])

const menus = ref<MenuDTO[]>([])

/**
 * 菜单是<b>全量树</b>（不分页、后端无筛选参数），因此名称筛选在前端做 ——
 * 数据已在内存，再发一次请求没有意义。用共享的 {@code filterFlatTreeByLabel}：
 * 命中节点连同祖先一起保留，否则子菜单会失去父级、树形展开失效。
 */
// 进入本页即预热图标候选：等表单打开再拉会让选择器"空一拍"。
// 这份代价只发生在本页（懒加载 chunk），不影响首屏与其它页面。
onMounted(() => {
  void loadMenuIcons().then((icons) => {
    iconOptions.value = icons
  })
})

async function fetchMenuPage(query: ProTableQuery) {
  const list = (await loadMenuList()) ?? []
  // menus 始终保存全量：新增/编辑的"上级节点"下拉与"是否有子节点"的判断都依赖完整列表
  menus.value = list
  const keyword = typeof query.menuName === 'string' ? query.menuName : ''
  const filtered =
    keyword.trim() === ''
      ? list
      : filterFlatTreeByLabel(list, keyword, { labelOf: TREE_PRESETS.menu.labelOf })
  // 层级由 vxe 的树形展开表达（gridProps.treeConfig），不再手动算缩进
  return {
    records: flatToTableTree(filtered, { labelOf: (menu) => menu.menuName ?? '未命名菜单' }),
    total: filtered.length,
    page: 1,
    size: Math.max(filtered.length, 1)
  }
}

/**
 * vxe 树形配置（内核能力，原样透传不重写）。
 * 默认全部展开：菜单总量在几十条量级，收起状态反而让人找不到刚加的子节点。
 */
const menuGridProps = {
  treeConfig: { rowField: 'id', childrenField: 'children', expandAll: true }
}

const columns: ProColumn<MenuDTO>[] = [
  {
    key: 'menuName',
    title: '菜单名称',
    minWidth: 200,
    // 首列承载树形展开按钮与缩进
    treeNode: true,
    search: 'input',
    searchPlaceholder: '模糊匹配'
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
      tableRef.value?.refresh()
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

const parentOptions = computed(() => [
  { label: '— 根节点 —', value: 0 },
  ...flatOptionsWithDepth(menus.value, {
    labelOf: TREE_PRESETS.menu.labelOf,
    valueOf: (menu) => menu.id as number,
    filter: (menu) =>
      // 按钮不能作为父节点：它是权限点，不是导航层级
      menu.menuType !== 'BUTTON' && menu.id !== editingId.value
  })
])

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
    tableRef.value?.refresh()
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
  <PageContainer title="菜单管理" description="菜单是平台级共享定义，修改会影响所有租户">
    <template #extra>
      <span class="menu-page__notice">
        页面路径由 <code>component</code> 决定，需与 <code>src/views</code> 下的文件路径一致
        （如 <code>iam/user/index</code>）。
      </span>
    </template>

    <ProTable
      ref="tableRef"
      :columns="columns"
      :request="fetchMenuPage"
      :toolbar="['create', 'refresh']"
      :row-actions="rowActions"
      :default-page-size="500"
      :grid-props="menuGridProps"
      empty-action-text="创建第一个菜单"
      @create="openForm(null, null)"
      @empty-action="openForm(null, null)"
    />
  </PageContainer>

  <!--
    表单含 IconPicker（自定义控件）与按类型联动的字段组，不是纯 items 可表达的表单，
    因此用 ProModal 的默认插槽承接；开关/重置/底部按钮由 ProModal 统一提供
  -->
  <ProModal
    v-model:visible="formVisible"
    :title="isEdit ? '编辑菜单' : '新增菜单'"
    :loading="submitting"
    @success="submitForm"
  >
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
        <div class="menu-page__field">
          <label>图标</label>
          <IconPicker v-model="form.icon" :icons="iconOptions" placeholder="选择菜单图标" />
          <p class="menu-page__tip">
            图标名与后端一致（如 {@code Settings} / {@code User}），才会在侧栏正确显示；
            留空则父级菜单以首字母占位。
          </p>
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
  </ProModal>
</template>

<style scoped>
.menu-page__notice {
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

code {
  padding: 0 4px;
  border-radius: 3px;
  background: var(--wa-bg-hover, #f0f2f5);
}
</style>
