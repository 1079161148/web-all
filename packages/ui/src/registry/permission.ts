/**
 * 权限判定注册表。
 *
 * <h3>为什么不由组件库自己判断</h3>
 * 与字典同理（见 {@code registry/dict.ts}）：当前用户的权限码来自会话/接口，
 * 属于应用层状态。组件库若直接去读 Pinia 或调接口，就与业务绑死了。
 *
 * <p>方向反过来：<b>应用把「这个权限码有没有」这件事注入进来</b>。
 *
 * <pre>{@code
 * // apps/admin/src/main.ts
 * import { setPermissionResolver } from '@admin/ui'
 * setPermissionResolver(hasPermission)   // 来自 permission store
 * }</pre>
 *
 * <h3>⚠️ 未注入时「放行」—— 这是一个有意的取舍</h3>
 * 两种失败模式：
 * <table>
 *   <tr><th>策略</th><th>漏注入时的表现</th></tr>
 *   <tr><td>fail-open（放行）</td><td>按钮都显示，点下去被服务端 403 拒绝</td></tr>
 *   <tr><td>fail-closed（全禁）</td><td><b>整站按钮消失</b>，看起来像权限系统坏了</td></tr>
 * </table>
 *
 * <p>选择 fail-open 的理由：<b>服务端才是权限的唯一裁决者</b>
 * （前端权限只是"要不要渲染这个按钮"的依据，绕过它什么也拿不到），
 * 而「整站按钮消失」这个故障极难归因 —— 排查方向会先跑偏到权限配置上。
 *
 * <p>为了不让"漏注入"变成静默故障，未注入时会打印一次警告 ——
 * 让配置错误在控制台自己暴露。
 */
export type PermissionResolver = (code: string) => boolean

let resolver: PermissionResolver | null = null
let warned = false

/** 注入权限判定函数。应用启动时调用一次。 */
export function setPermissionResolver(next: PermissionResolver | null): void {
  resolver = next
  warned = false
}

/**
 * 是否拥有权限码。
 *
 * @param code 权限码。<b>未声明（undefined）表示"不做权限限制"</b>，直接放行 ——
 *             这条语义让调用方可以只在需要限制的地方写权限码，
 *             而不必为每个按钮都补一个。
 */
export function hasPermission(code?: string | null): boolean {
  if (!code) {
    return true
  }
  if (!resolver) {
    if (!warned) {
      warned = true
      console.warn(
        '[permission] 尚未注入权限判定函数（setPermissionResolver）。' +
          '当前所有带权限码的元素都会显示，请确认这不是配置遗漏。'
      )
    }
    return true
  }
  return resolver(code)
}

/** 是否至少拥有其中一个权限码。 */
export function hasAnyPermission(codes: Array<string | undefined>): boolean {
  const valid = codes.filter((code): code is string => Boolean(code))
  if (valid.length === 0) {
    return true
  }
  return valid.some((code) => hasPermission(code))
}
