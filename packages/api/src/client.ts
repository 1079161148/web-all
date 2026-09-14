/**
 * 统一请求客户端（设计文档 §10.1）。
 *
 * 职责：把后端的统一响应体 `R<T>{ code, msg, data }` 剥壳成 `data`，
 * 并把业务错误码转成 {@link ApiError} 抛出，让调用方（TanStack Query）
 * 用统一的错误处理路径。
 *
 * ⚠️ 这是**唯一**允许发起 HTTP 请求的地方。业务代码不得直接使用 fetch/axios。
 */

/** 后端统一响应体（与 server/admin-common 的 R<T> 一一对应）。 */
export interface ApiResponse<T> {
  code: number
  msg: string
  data: T
}

/** 分页结果（与 server/admin-common 的 PageResult<T> 一一对应）。 */
export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
}

/** 成功业务码。 */
export const SUCCESS_CODE = 0

/** API 错误。 */
export class ApiError extends Error {
  readonly code: number
  readonly httpStatus: number

  constructor(code: number, message: string, httpStatus: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.httpStatus = httpStatus
  }

  /** 是否为认证失效（需要跳登录）。 */
  isUnauthenticated(): boolean {
    return this.httpStatus === 401
  }

  /** 是否为无权限。 */
  isForbidden(): boolean {
    return this.httpStatus === 403
  }
}

/** 请求选项。 */
export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH'
  params?: Record<string, unknown>
  /**
   * 请求体。
   *
   * <p>⚠️ 允许传「已序列化的字符串」，也允许传「待序列化的对象」，两者的处理不同 ——
   * 详见 {@link prepareBody}。这个宽松类型是刻意保留的：
   * 生成代码（orval）传的是字符串，而手写调用方习惯传对象，
   * 强制统一成一种会让另一侧的调用都变得别扭。
   */
  body?: unknown
  headers?: Record<string, string>
  signal?: AbortSignal
}

/**
 * 请求基地址。
 *
 * 开发环境走 Vite 代理（见 apps/admin/vite.config.ts）以避免 CORS，
 * 生产环境由 Nginx / Ingress 同源反代，因此默认空字符串。
 */
const BASE_URL = import.meta.env?.VITE_API_BASE_URL ?? ''

/**
 * 租户标识请求头。
 *
 * ⚠️ 临时机制：P1 接入 OAuth2.1 后，租户 ID 应由已验证的 JWT 声明提供，
 * 届时删除该头（见 server 的 TenantContextFilter 注释）。
 */
const TENANT_HEADER = 'X-Tenant-Id'

/** 读取当前租户 ID（由登录流程写入 sessionStorage）。 */
function currentTenantId(): string | null {
  try {
    return sessionStorage.getItem('tenantId')
  } catch {
    return null
  }
}

/** 读取访问令牌（由登录流程写入 sessionStorage）。 */
function accessToken(): string | null {
  try {
    return sessionStorage.getItem('accessToken')
  } catch {
    return null
  }
}

/** 构建查询字符串，自动跳过 undefined / null / 空字符串。 */
function buildQuery(params?: Record<string, unknown>): string {
  if (!params) {
    return ''
  }
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') {
      return
    }
    if (Array.isArray(value)) {
      value.forEach((item) => search.append(key, String(item)))
      return
    }
    search.append(key, String(value))
  })
  const query = search.toString()
  return query ? `?${query}` : ''
}

/**
 * 准备请求体，区分「已序列化」与「待序列化」。
 *
 * <h3>⚠️ 这里存在一个必须显式处理的坑（实测踩过，且只在浏览器里才暴露）</h3>
 * orval 生成的代码长这样：
 * <pre>{@code
 *   request('/api/v1/auth/login', {
 *     method: 'POST',
 *     headers: { 'Content-Type': 'application/json' },
 *     body: JSON.stringify(loginRequest)     // ← 生成代码已经序列化过了
 *   })
 * }</pre>
 * 而本客户端如果对 `body` 再调用一次 {@code JSON.stringify}，就会得到
 * <b>被序列化两次的字符串</b>。后端收到的是
 * {@code "{\"username\":\"admin\"}"}（一个字符串值），
 * 于是抛 {@code HttpMessageNotReadableException}：
 * <pre>
 *   Cannot construct instance of LoginRequest: no String-argument constructor
 *   /factory method to deserialize from String value ('{"username":"admin"}')
 * </pre>
 *
 * <p>这个问题的危险之处在于<b>它不会被任何静态检查发现</b>：
 * 类型是 `unknown`，编译通过；用 curl 或 Postman 直接打接口也完全正常
 * （那些方式不经过这段 JS），<b>只在真实浏览器里点按钮时才炸</b>。
 *
 * <p>因此这里按"内容形态"分流，而不是按"调用方是谁"分流 ——
 * 后者需要在类型上区分两种 body，会让生成代码与手写代码都变得别扭。
 */
function prepareBody(body: unknown): { payload: BodyInit | undefined; contentType?: string } {
  if (body === undefined || body === null) {
    return { payload: undefined }
  }

  // 字符串：视为调用方（通常是生成代码）已经序列化完成。
  // 不再强制 Content-Type —— 生成代码自己会带上；
  // 而如果是别的字符串内容（如纯文本），强加 application/json 反而是错的。
  if (typeof body === 'string') {
    return { payload: body }
  }

  // 这些类型的 body 必须原样交给 fetch：
  // 尤其是 FormData，浏览器需要在其中自动补上带 boundary 的 Content-Type，
  // 我们一旦手动设置就会破坏 multipart 请求。
  if (isRawBody(body)) {
    return { payload: body as BodyInit }
  }

  // 其余情况（普通对象）才由我们序列化，并补上 Content-Type。
  // 判断顺序很关键：先排除字符串与原生 body，最后才是"当成对象序列化"，
  // 这样"漏判"的后果是原样透传（可能报错但可发现），
  // 而不是把二分制数据错误地 JSON 化（报错信息完全指不到根因）。
  return { payload: JSON.stringify(body), contentType: 'application/json' }
}

/** 判断是否为 fetch 能直接接受的原始 body 类型。 */
function isRawBody(body: unknown): boolean {
  return (
    typeof FormData !== 'undefined' && body instanceof FormData
  ) || (
    typeof Blob !== 'undefined' && body instanceof Blob
  ) || (
    typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams
  ) || (
    typeof ArrayBuffer !== 'undefined' && body instanceof ArrayBuffer
  ) || (
    typeof ReadableStream !== 'undefined' && body instanceof ReadableStream
  )
}

/**
 * 发起请求并剥壳。
 *
 * @throws {ApiError} 业务错误码非 0，或 HTTP 层失败
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', params, body, headers, signal } = options

  const finalHeaders: Record<string, string> = {
    Accept: 'application/json',
    ...headers
  }

  // 变量名刻意用 requestBody 而不是 payload ——
  // 本函数下方已有一个 `payload` 表示**响应**载荷，
  // 两者同名会直接编译失败（而且这两个概念确实不该共用一个名字）
  const { payload: requestBody, contentType } = prepareBody(body)
  if (contentType && !finalHeaders['Content-Type']) {
    finalHeaders['Content-Type'] = contentType
  }

  const token = accessToken()
  if (token) {
    finalHeaders.Authorization = `Bearer ${token}`
  }

  const tenantId = currentTenantId()
  if (tenantId) {
    finalHeaders[TENANT_HEADER] = tenantId
  }

  let response: Response
  try {
    response = await fetch(`${BASE_URL}${path}${buildQuery(params)}`, {
      method,
      headers: finalHeaders,
      // 用已经准备好的 requestBody，绝不在此处再序列化（见 prepareBody 的说明）
      body: requestBody,
      signal,
      credentials: 'include'
    })
  } catch (error) {
    // 网络层失败（断网、DNS、CORS、被 abort）—— 与业务错误区分开
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error
    }
    throw new ApiError(-1, '网络连接失败，请检查网络后重试', 0)
  }

  const httpStatus = response.status

  // 204 / 空响应体
  if (httpStatus === 204) {
    return undefined as T
  }

  let payload: ApiResponse<T> | undefined
  try {
    payload = (await response.json()) as ApiResponse<T>
  } catch {
    throw new ApiError(-1, `服务端返回了无法解析的响应（HTTP ${httpStatus}）`, httpStatus)
  }

  if (!payload) {
    throw new ApiError(-1, '响应体为空', httpStatus)
  }

  if (payload.code !== SUCCESS_CODE) {
    throw new ApiError(payload.code, payload.msg || '请求失败', httpStatus)
  }

  return payload.data
}
