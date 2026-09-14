import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { usePermissionStore } from '@/stores/permission'

/**
 * 路由表。
 *
 * <h3>静态路由 vs 动态路由（设计文档 §11.6）</h3>
 * 这里只放<b>静态白名单</b>（登录页、错误页）与布局壳。
 * 业务菜单全部由后端菜单树驱动、通过 {@code router.addRoute} 动态注册，
 * <b>前端不得硬编码任何业务菜单</b> —— 否则后台改了权限配置、前端不生效。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/',
    name: 'Root',
    component: () => import('@/layouts/BasicLayout.vue'),
    // 刻意<b>不</b>重定向到某个业务页：重定向目标必须由菜单决定。
    // 硬编码一个默认页会在该用户没有这个权限时产生"登录后立刻 403"的糟糕首体验，
    // 因此交给守卫按"第一个可用菜单"决定（见下方 guard）。
    children: []
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在', public: true }
  }
]

export const router = createRouter({
  history: createWebHistory(),
  routes
})

/**
 * 全局前置守卫。
 *
 * <h3>⚠️ 它不是权限控制手段</h3>
 * 守卫只影响体验（看不到页面）。真正的鉴权在后端（设计文档 §7.2）：
 * 绕过前端守卫最多看到一个空页面，拿不到任何数据。
 *
 * <h3>返回 `{ ...to, replace: true }` 的原因</h3>
 * `addRoute` 之后当前这次导航仍按旧路由表匹配，结果是命中通配的 404。
 * 必须返回同一个目标并 `replace`，让 vue-router <b>用新路由表重新匹配一次</b>。
 * 这是动态路由最经典的一个坑：忘了它就会表现为"刷新后首次进入业务页永远 404，
 * 再点一次就正常"。
 */
router.beforeEach(async (to) => {
  const title = (to.meta.title as string | undefined) ?? ''
  document.title = title ? `${title} · 中台管理系统` : '中台管理系统'

  const authStore = useAuthStore()
  const permissionStore = usePermissionStore()

  // ① 公开页直接放行
  if (to.meta.public) {
    // 已登录用户访问登录页 → 送回首页
    if (to.path === '/login' && authStore.isAuthenticated) {
      return { path: '/' }
    }
    return true
  }

  // ② 未登录 → 跳登录并记录来源
  if (!authStore.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // ③ 已登录但身份未就绪（刷新页面后的首次导航）：
  //    先拉当前用户，失败说明令牌已失效 → 清理并跳登录。
  //    ⚠️ 这一步不能省：令牌可能已过期或被服务端吊销，
  //    此时"看起来已登录"是最危险的状态（用户会不断看到请求失败）。
  if (!authStore.initialized) {
    const ok = await authStore.fetchCurrentUser()
    if (!ok) {
      permissionStore.reset()
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  // ④ 菜单与权限尚未加载 → 加载、注册动态路由、重新匹配
  if (!permissionStore.loaded) {
    try {
      await permissionStore.load()
    } catch {
      // 菜单/权限拉取失败（网络或服务端异常）：清理会话并回登录页，
      // 让用户重新登录是此时唯一可预期的恢复路径。
      // 不在这里展示"系统错误"页面 —— 那会让用户卡在一个无法自救的状态。
      authStore.clear()
      permissionStore.reset()
      return { path: '/login', query: { redirect: to.fullPath } }
    }

    for (const route of permissionStore.dynamicRoutes) {
      router.addRoute('Root', route)
    }

    // 访问根路径时，送到第一个可用菜单，而不是硬编码的默认页
    if (to.path === '/') {
      const first = permissionStore.dynamicRoutes[0]
      if (first) {
        return { path: first.path, replace: true }
      }
    }

    // ★ 关键：用新路由表重新匹配一次，否则首次进入会命中 404
    return { ...to, replace: true }
  }

  return true
})

export default router
