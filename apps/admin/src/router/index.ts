import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
// 路由入口在首屏执行，因此 feedback 也从窄入口取（见 packages/ui/src/core.ts）
import { feedback } from '@admin/ui/core'
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
    // 自助注册：与登录页同为公开页（未登录必须可访问，否则没人能注册）
    path: '/register',
    name: 'Register',
    component: () => import('@/views/RegisterView.vue'),
    meta: { title: '注册账号', public: true }
  },
  {
    path: '/',
    name: 'Root',
    component: () => import('@/layouts/BasicLayout.vue'),
    // 首页是静态路由（所有登录用户可见，与权限无关），因此这里可以安全地
    // 静态重定向 —— 之前"不重定向、交给守卫选第一个菜单"的约束针对的是
    // 业务页（可能无权限），首页不存在这个问题。
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '首页' }
      },
      {
        // 个人中心：静态子路由（所有登录用户都有），不进业务菜单 ——
        // "我自己的账号"不该依赖任何权限配置，入口在右上角头像下拉里
        path: 'profile',
        name: 'Profile',
        component: () => import('@/views/ProfileView.vue'),
        meta: { title: '个人中心' }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在' }
    /*
     * ⚠️ 这里绝对不能加 `public: true`（实测踩过：加上它 = 刷新任何业务页必 404）。
     *
     * <h3>为什么</h3>
     * 动态路由是在守卫第 ④ 步里注册的，而守卫的第 ① 步就是「公开页直接放行」。
     * 刷新页面时路由表还是空的，<b>任何</b>深链路（如 /system/tenant）
     * 都会先匹配到这条通配路由 —— 如果它是 public，
     * 守卫在第 ① 步就 return true 了，<b>根本走不到注册动态路由的那一步</b>，
     * 于是用户看到的就是 404 页。而站内点菜单正常（路由表那时已注册），
     * 症状恰好是「点菜单正常、一刷新就 404」。
     *
     * <p>不加 public 的代价：未登录用户访问一个不存在的地址会被送去登录页
     * 而不是 404 页 —— 这是可接受的（也是多数后台的通用行为）。
     * 登录后再次访问该地址，动态路由已注册、仍未命中，才会真正看到 404。
     */
  }
]

export const router = createRouter({
  history: createWebHistory(),
  routes
})

/*
 * 路由切换顶部进度条。
 *
 * <h3>为什么用 Naive 的 loadingBar 而不是 NProgress</h3>
 * 两者能力完全重叠（顶部细进度条）。loadingBar 已经由 @admin/ui 的
 * feedback 单例提供（与主题联动、免新增依赖），按「同一能力不引入
 * 第二套方案」的依赖规范复用它；守卫里的异步耗时（拉用户、拉菜单）
 * 会被真实反映出来。若要换成 NProgress 只需替换这三处调用。
 *
 * <p><b>必须注册在主守卫之前</b>：vue-router 按注册顺序执行 beforeEach，
 * 放在后面会让 start() 等到主守卫拉完用户/菜单后才调用，进度条失去意义。
 * afterEach 在导航最终确认后触发（守卫重定向算新导航，会再次 start），
 * onError 兜底异常导航，正常路径不会残留半截进度条。
 */
router.beforeEach(() => {
  feedback.loading.start()
})

router.afterEach(() => {
  feedback.loading.finish()
})

router.onError(() => {
  feedback.loading.error()
})

/**
 * 全局前置守卫。
 *
 * <h3>⚠️ 它不是权限控制手段</h3>
 * 守卫只影响体验（看不到页面）。真正的鉴权在后端（设计文档 §7.2）：
 * 绕过前端守卫最多看到一个空页面，拿不到任何数据。
 *
 * <h3>「刷新业务页 404」的两个叠加原因（都实测踩过）</h3>
 * <ol>
 *   <li>NotFound 路由不能声明 {@code public}（见上方路由表的说明）——
 *       否则守卫在第 ① 步就放行，动态路由永远没机会注册；</li>
 *   <li>第 ④ 步重新匹配时不能返回 `{ ...to }` ——
 *       刷新时的首次匹配已命中通配 NotFound，展开会把
 *       `name: 'NotFound'` 带回去，而 vue-router 的 name 优先于 path，
 *       重匹配仍落回 404。必须只给位置信息（见下方代码）。</li>
 * </ol>
 * 单修任何一个都不够：只修 ① 时，重匹配仍会因 name 落回 404；
 * 只修 ② 时，守卫在第 ① 步就放行，这段代码根本不会执行。
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

    /*
     * ★ 用新路由表重新匹配一次，否则首次进入会命中 404。
     *
     * ⚠️ 这里**不能**写成 `return { ...to, replace: true }`（实测踩过）。
     * 刷新页面时路由表尚未注册，本次导航已经匹配到<b>通配的 NotFound</b>，
     * 于是 `to.name === 'NotFound'`。而展开 `to` 会把这个 name 一起带上 ——
     * vue-router 解析位置对象时 <b>name 优先于 path</b>，
     * 所以重新匹配又会落回 NotFound，表现是<b>"点菜单正常、一刷新就 404"</b>。
     *
     * <p>两个后果叠在一起时尤其难查：路径拼接错了会"全都 404"，
     * 而这个错误只在刷新（路由表为空的首次导航）时出现 ——
     * 于是修复前一个问题之后，看起来像"没修好"。
     *
     * <p>正确做法是<b>只给位置信息，不给 name</b>：
     * path 用 `to.path`（不是 `fullPath`，否则 query 会被再转义一次），
     * query / hash 原样带上。
     */
    return { path: to.path, query: to.query, hash: to.hash, replace: true }
  }

  return true
})

export default router
