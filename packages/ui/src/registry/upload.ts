/**
 * 上传鉴权来源注册表。
 *
 * <h3>为什么和字典、权限用同一套模式</h3>
 * 上传请求要带令牌与租户头，而这两样都由应用的会话状态管理 ——
 * 组件库直接去读 {@code sessionStorage} 或 Pinia 就等于与业务实现绑死。
 * 因此同样反过来：<b>应用把"怎么取鉴权头"注入进来</b>。
 *
 * <pre>{@code
 * // apps/admin/src/main.ts
 * setUploadAuthResolver(() => ({
 *   Authorization: `Bearer ${sessionStorage.getItem('accessToken') ?? ''}`,
 *   'X-Tenant-Id': sessionStorage.getItem('tenantId') ?? ''
 * }))
 * }</pre>
 *
 * <h3>为什么返回"一批头"而不是单独的 token</h3>
 * 上传不只是带 token —— 本项目还需要租户头（后端据此路由到正确的租户存储路径）。
 * 若接口只暴露 token，租户头就会漏，而漏了它的表现是
 * "上传成功但文件进了错误的租户目录"，属于跨租户的数据问题。
 * <b>接口的形状应当让正确用法成为最省事的那个。</b>
 */
export type UploadAuthResolver = () => Record<string, string>

let resolver: UploadAuthResolver | null = null
let warned = false

/** 注入上传鉴权头来源。应用启动时调用一次。 */
export function setUploadAuthResolver(next: UploadAuthResolver | null): void {
  resolver = next
  warned = false
}

/**
 * 取上传所需的鉴权头。
 *
 * <p>未注入时返回空对象并打印一次警告 ——
 * 不抛错是刻意的：未注入的场景（组件故事、单元测试）应当仍能渲染上传控件，
 * 只是请求会因缺鉴权头被服务端拒绝。
 */
export function getUploadAuthHeaders(): Record<string, string> {
  if (!resolver) {
    if (!warned) {
      warned = true
      console.warn(
        '[upload] 尚未注入上传鉴权（setUploadAuthResolver）。' +
          '上传请求将不带令牌与租户头，服务端会拒绝。'
      )
    }
    return {}
  }
  return resolver()
}
