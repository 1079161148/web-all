import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { login as loginApi } from '@admin/api'
import type { CurrentUserDTO } from '@admin/api'
import { getToken, setTenantId, setToken } from '@/utils/session'

/**
 * 认证状态（**客户端状态**，允许放 Pinia）。
 *
 * <h3>为什么它可以进 Pinia，而用户列表不能</h3>
 * 设计文档 §11.5 的分工判据是"数据的所有者是谁"：
 * <ul>
 *   <li>用户列表、字典、统计 → 数据在服务端，会过期、需要并发去重与失效重取 → TanStack Query</li>
 *   <li>令牌、当前用户身份 → 它是<b>客户端持有的会话状态</b>，
 *       生命周期与页面无关，不存在"过期重取"的语义 → Pinia</li>
 * </ul>
 * 把它们都塞进 Pinia 或都塞进 Query 都是错的 —— 判据是<b>数据归属</b>，不是"哪种状态好"。
 *
 * <h3>为什么令牌写在 sessionStorage 而不是 Pinia 里就完事</h3>
 * 因为请求客户端（{@code @admin/api} 的 request）需要在<b>非组件上下文</b>里
 * 读取令牌来设置请求头。Pinia store 在组件外使用需要先激活实例，
 * 而 sessionStorage 任何时候都能读。因此 Pinia 持有的是"响应式视图"，
 * 真正的存储仍是 sessionStorage —— 两者通过本 store 的 setter 保持同步，
 * **不允许任何地方绕过本 store 直接写 sessionStorage**（否则会出现
 * "状态显示已登录、请求却不带令牌"的诡异现象）。
 */
export const useAuthStore = defineStore('auth', () => {
  // 从统一的会话存储读取初始值（而不是直接 sessionStorage）：
  // 刷新页面后 Pinia 状态会重建，必须与存储保持一致，否则会出现
  // "存储里有令牌、store 却认为未登录"从而被守卫踢回登录页。
  const accessToken = ref<string>(getToken() ?? '')
  const user = ref<CurrentUserDTO | null>(null)
  const initialized = ref(false)

  const isAuthenticated = computed(() => accessToken.value.length > 0)

  /** 登录并写入会话。 */
  async function login(username: string, password: string, tenantId: string): Promise<void> {
    // 租户必须在登录请求<b>之前</b>写入：登录接口本身需要它来确定
    // "在哪个租户里查这个用户名"。此时还没有令牌，只能靠请求头传递。
    setTenantId(tenantId)

    const result = await loginApi({ username, password })

    // 生成类型里 accessToken 是可选的（OpenAPI 未标 required），
    // 但"登录成功却没有令牌"在业务上不可能成立。
    // 在这里显式拒绝，而不是把 undefined 写进存储 ——
    // 后者会表现为"登录似乎成功、后续请求全部 401"，排查方向会被带偏。
    if (!result.accessToken) {
      throw new Error('登录响应缺少访问令牌，请联系管理员')
    }
    setToken(result.accessToken)
    accessToken.value = result.accessToken
    user.value = result.user ?? null

    // 以服务端返回的租户为准，而不是用户手填的值 ——
    // 若两者不一致（比如用户填错了租户 ID 但账号恰好在另一个租户里），
    // 必须以后端裁决结果为准，否则后续所有请求都会带错租户头。
    const resolvedTenantId = result.user?.tenantId
    if (resolvedTenantId !== undefined) {
      setTenantId(String(resolvedTenantId))
    }
    initialized.value = true
  }

  /**
   * 用当前令牌拉取用户信息（刷新页面后调用）。
   *
   * <p>返回是否成功。失败时调用方（路由守卫）应清理并跳登录 ——
   * 令牌可能已过期或被服务端吊销，此时"看起来已登录"是最危险的状态。
   */
  async function fetchCurrentUser(): Promise<boolean> {
    if (!isAuthenticated.value) {
      return false
    }
    try {
      user.value = await getCurrentUserSafe()
      initialized.value = true
      return true
    } catch {
      clear()
      return false
    }
  }

  function clear(): void {
    setToken('')
    accessToken.value = ''
    user.value = null
    initialized.value = false
  }

  /** 退出登录：本地清理即可（无状态令牌，服务端不持有会话）。 */
  function logout(): void {
    clear()
  }

  return { accessToken, user, initialized, isAuthenticated, login, fetchCurrentUser, logout, clear }
})

// ---------------------------------------------------------------------
// 内部
// ---------------------------------------------------------------------

/** 单独抽出以便于将来替换为按需拉取的实现（避免 store 顶层 import 造成的循环依赖）。 */
async function getCurrentUserSafe(): Promise<CurrentUserDTO> {
  const { getCurrentUser } = await import('@admin/api')
  return getCurrentUser()
}
