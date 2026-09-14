import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getCurrentUserMenus, getCurrentUserPermissions } from '@admin/api'
import type { MenuDTO } from '@admin/api'
import { buildRoutes, type DynamicRoute } from '@/router/dynamic'

/**
 * 权限与动态路由状态。
 *
 * <h3>它管什么、不管什么</h3>
 * <ul>
 *   <li><b>管</b>：菜单树（服务端下发）、权限码集合、由菜单派生出的动态路由。
 *       这三者必须在<b>首次进入受保护路由之前</b>一次性就绪，否则会出现
 *       "先渲染空菜单、再闪烁出内容"或"首次进入 404"</li>
 *   <li><b>不管</b>：业务列表数据。那些走 TanStack Query（设计文档 §11.5）</li>
 * </ul>
 * 菜单与权限码在会话期内不变（改了权限要重新登录），因此放进 Pinia 是合适的 ——
 * 它们在整个会话中保持稳定，没有"过期重取"的需求。
 *
 * <h3>⚠️ 前端权限只影响体验，不构成安全边界</h3>
 * 这里算出的权限码只用于隐藏按钮与菜单。任何人都可以改前端代码让按钮显示出来，
 * 但点击后会被后端 {@code @PreAuthorize} 拒绝（设计文档 §7.2）。
 * <b>把它当作安全机制是严重误判；当作体验优化才是正确用法。</b>
 */
export const usePermissionStore = defineStore('permission', () => {
  /** 后端下发的菜单（扁平）。 */
  const menus = ref<MenuDTO[]>([])
  /** 权限码集合。超管拿到的是通配符 `['*']`。 */
  const permissions = ref<string[]>([])
  /** 由菜单派生、已准备注册到 router 的路由。 */
  const dynamicRoutes = ref<DynamicRoute[]>([])
  /** 是否已加载完成（路由守卫据此判断是否需要拉取）。 */
  const loaded = ref(false)

  /** 判定单条权限码。`*` 为超管通配符。 */
  function hasPermission(code?: string): boolean {
    if (!code) {
      // 未声明权限码 = 不需要权限
      return true
    }
    return permissions.value.includes('*') || permissions.value.includes(code)
  }

  /** 判定任意一条权限码。空数组视为不需要权限。 */
  function hasAnyPermission(codes?: string[]): boolean {
    if (!codes || codes.length === 0) {
      return true
    }
    return codes.some((code) => hasPermission(code))
  }

  /**
   * 拉取菜单与权限并构建动态路由。
   *
   * <p>两个请求<b>并行</b>发出（`Promise.all`）：它们之间没有依赖，
   * 串行会让首屏多等一个 RTT。这是设计文档 §12.7「接口瀑布治理」的 L1 手段。
   *
   * <p>菜单与权限的失败是<b>致命</b>的（没有它们整个应用无法工作），
   * 所以这里不吞异常，由调用方（路由守卫）决定跳登录还是展示错误。
   * 与"字典加载失败"不同 —— 后者可以降级为显示码值，不该阻塞页面。
   */
  async function load(): Promise<void> {
    const [menuList, permissionList] = await Promise.all([
      getCurrentUserMenus(),
      getCurrentUserPermissions()
    ])
    menus.value = menuList ?? []
    permissions.value = permissionList ?? []
    dynamicRoutes.value = buildRoutes(menus.value)
    loaded.value = true
  }

  /** 重置（退出登录时调用），避免下一个用户看到上一个用户的菜单。 */
  function reset(): void {
    menus.value = []
    permissions.value = []
    dynamicRoutes.value = []
    loaded.value = false
  }

  /** 侧边菜单树（由扁平菜单构建，供布局组件直接消费）。 */
  const menuTree = computed(() => buildMenuTree(menus.value))

  return {
    menus,
    permissions,
    dynamicRoutes,
    loaded,
    menuTree,
    hasPermission,
    hasAnyPermission,
    load,
    reset
  }
})

// ---------------------------------------------------------------------
// 菜单树构建
// ---------------------------------------------------------------------

/** 布局用的菜单节点。 */
export interface MenuNode {
  id: number
  label: string
  path: string
  icon?: string
  children: MenuNode[]
}

/**
 * 由扁平菜单构建可渲染的菜单树。
 *
 * <h3>三条过滤规则</h3>
 * <ol>
 *   <li><b>去掉 BUTTON</b>：按钮是权限点，不是导航项</li>
 *   <li><b>去掉 visible=false</b>：隐藏菜单仍会生成路由（可直接访问），但不出现在侧边栏</li>
 *   <li><b>去掉空目录</b>：一个目录下若没有任何可见的 MENU，它自身也不该显示 ——
 *       否则会出现点了没反应的死菜单。父级因权限收窄而变空是很常见的情形</li>
 * </ol>
 * 第 3 条容易被忽略，但它是"权限配置改了之后菜单变得很奇怪"的主要来源。
 */
function buildMenuTree(menus: MenuDTO[]): MenuNode[] {
  const navigable = menus.filter((menu) => menu.menuType !== 'BUTTON')

  const byParent = new Map<number, MenuDTO[]>()
  for (const menu of navigable) {
    // id 必须存在（后端主键），缺失时跳过而不是用 0 兜底 ——
    // 0 是"根"的保留值，用它兜底会把这些异常菜单挂到根上并<b>覆盖</b>真正的根级菜单
    if (menu.id === undefined) {
      continue
    }
    const parentId = menu.parentId ?? 0
    const siblings = byParent.get(parentId) ?? []
    siblings.push(menu)
    byParent.set(parentId, siblings)
  }

  const build = (parentId: number, parentPath: string): MenuNode[] => {
    const children = (byParent.get(parentId) ?? [])
      .slice()
      .sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0))

    const nodes: MenuNode[] = []
    for (const menu of children) {
      if (menu.id === undefined) {
        continue
      }
      const fullPath = joinPath(parentPath, menu.path)
      const sub = build(menu.id, fullPath)
      // 目录若最终没有任何可见子项，则不显示
      if (menu.menuType === 'DIR' && sub.length === 0) {
        continue
      }
      if (menu.visible === false) {
        continue
      }
      nodes.push({
        id: menu.id,
        label: menu.menuName ?? '未命名菜单',
        path: fullPath,
        icon: menu.icon ?? undefined,
        children: sub
      })
    }
    return nodes
  }

  return build(0, '')
}

/**
 * 拼接父子路径。
 *
 * <p>处理三种容易出错的组合：父级为空（根）、子级以 `/` 开头（绝对路径，应忽略父级）、
 * 以及重复斜杠。这些边界不处理的话，会出现 `//system//user` 这类路由匹配失败。
 */
function joinPath(parent: string, child?: string): string {
  if (!child) {
    return parent
  }
  if (child.startsWith('/')) {
    return child
  }
  const base = parent.endsWith('/') ? parent.slice(0, -1) : parent
  const tail = child.startsWith('/') ? child.slice(1) : child
  return `${base}/${tail}`
}
