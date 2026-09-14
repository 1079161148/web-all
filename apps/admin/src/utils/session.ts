/**
 * 会话存储的唯一读写入口。
 *
 * <h3>为什么需要这一层（而不是各处直接 sessionStorage）</h3>
 * 令牌与租户 ID 是请求客户端（`@admin/api`）在组件外读取的，
 * 也是 Pinia store 在组件内持有的。若允许任何地方直接读写 sessionStorage，
 * 迟早会出现"某处写了、store 不知道"或"store 清了、存储没清"的不一致，
 * 表现为<b>状态显示已登录但请求不带令牌</b>这类极难排查的现象。
 *
 * <p>集中到一处后，"同步"成为实现细节而非各调用方的责任。
 *
 * <h3>为什么用 sessionStorage 而不是 localStorage</h3>
 * 会话令牌在关闭标签页后即失效更符合"会话"语义，也缩小了令牌被驻留读取的窗口。
 * 代价是"新开标签页需要重新登录"—— 这对管理后台是可接受的，
 * 而对 C 端产品则需要重新权衡（那时应改用 refresh token + 静默续期）。
 *
 * <h3>关于 XSS</h3>
 * sessionStorage 可被同源脚本读取，因此 <b>XSS 防护是它的安全前提</b>
 * （见设计文档 §14.2 的 CSP 与富文本白名单）。
 * 彻底消除该风险需要 BFF + httpOnly Cookie，属 P2 的评估项。
 */

const TOKEN_KEY = 'accessToken'
const TENANT_KEY = 'tenantId'

/** sessionStorage 在隐私模式或被禁用时可能抛异常，统一兜底避免整个应用崩溃。 */
function safeGet(key: string): string | null {
  try {
    return sessionStorage.getItem(key)
  } catch {
    return null
  }
}

function safeSet(key: string, value: string): void {
  try {
    if (value) {
      sessionStorage.setItem(key, value)
    } else {
      sessionStorage.removeItem(key)
    }
  } catch {
    // 存储不可用时静默降级：当前会话仍可继续（内存中的 Pinia 状态有效），
    // 只是刷新页面会丢失登录态。这比让应用直接报错更合理。
  }
}

export function getToken(): string | null {
  return safeGet(TOKEN_KEY)
}

export function setToken(token: string): void {
  safeSet(TOKEN_KEY, token)
}

export function getTenantId(): string | null {
  return safeGet(TENANT_KEY)
}

export function setTenantId(tenantId: string): void {
  safeSet(TENANT_KEY, tenantId)
}

/** 清空会话（退出登录 / 令牌失效）。 */
export function clearSession(): void {
  safeSet(TOKEN_KEY, '')
  safeSet(TENANT_KEY, '')
}
