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
export const BASE_URL = import.meta.env?.VITE_API_BASE_URL ?? ''

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
  return readStorage('accessToken')
}

/** 读取刷新令牌（由登录/刷新流程写入 sessionStorage）。 */
function refreshToken(): string | null {
  return readStorage('refreshToken')
}

function readStorage(key: string): string | null {
  try {
    return sessionStorage.getItem(key)
  } catch {
    return null
  }
}

function writeStorage(key: string, value: string): void {
  try {
    if (value) {
      sessionStorage.setItem(key, value)
    } else {
      sessionStorage.removeItem(key)
    }
  } catch {
    // 存储不可用时静默降级（与上方读取的兜底一致）
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
 * 全局 API 错误处理器。
 *
 * <h3>为什么需要这个钩子</h3>
 * 「令牌过期 → 跳登录」「无权限 → 提示一下」这类处理<b>与具体接口无关</b>，
 * 每个调用点各写一遍的结果必然是：有的页面跳登录、有的页面只弹个错、
 * 有的页面什么都不做（表现为"点了没反应"）。
 *
 * <p>把这个能力放在请求层而不是组件层，是因为请求层是<b>所有错误的唯一汇聚点</b> ——
 * 任何绕开它的做法（在拦截器之外再判断一遍）都会漏。
 *
 * <h3>为什么由应用注册，而不是本层直接跳转</h3>
 * 请求层不知道路由、也不知道会话状态（那是应用的领域）。
 * 因此这里只提供"通知"能力，怎么做由应用决定 ——
 * 见 {@code apps/admin/src/api/error-handler.ts}。
 */
export type ApiErrorHandler = (error: ApiError) => void

let apiErrorHandler: ApiErrorHandler | null = null

/** 注册全局错误处理器。应用启动时调用一次。 */
export function setApiErrorHandler(handler: ApiErrorHandler | null): void {
  apiErrorHandler = handler
}

/** 抛出前先通知全局处理器。所有 API 错误都从这里出去，避免遗漏。 */
function raise(error: ApiError): never {
  apiErrorHandler?.(error)
  throw error
}

// ---------------------------------------------------------------------
// 访问令牌的静默刷新
// ---------------------------------------------------------------------

/**
 * 进行中的刷新（单飞闸门）。
 *
 * <h3>为什么必须单飞</h3>
 * 一个列表页会并发发出多个请求（菜单 + 权限 + 列表 + 字典）。
 * 访问令牌过期时它们<b>一起</b>返回 401 —— 若各自刷新，会出现：
 * <ul>
 *   <li>后到的刷新读不到最新的刷新令牌（先到的已经轮换掉它）→ 被判为重放 →
 *       <b>整族吊销、所有设备强制下线</b>。也就是说，不做单飞，
 *       静默刷新本身就会触发我们部署的重放保护，把用户踢下线</li>
 *   <li>刷新接口被打出数次无意义调用</li>
 * </ul>
 * 因此并发 401 共享同一次刷新：先到者发起，其余等待结果后各自重试。
 */
let refreshInflight: Promise<boolean> | null = null

/** 登录/刷新接口自身不做静默刷新：它们 401 时唯一正确的出路就是重新登录。 */
function isAuthPath(path: string): boolean {
  return path === '/api/v1/auth/login' || path === '/api/v1/auth/refresh'
}

/** 刷新成功返回 true 并已把新令牌写入存储；失败返回 false（调用方走原 401 路径）。 */
function ensureRefreshed(): Promise<boolean> {
  if (!refreshInflight) {
    refreshInflight = doRefresh().finally(() => {
      refreshInflight = null
    })
  }
  return refreshInflight
}

async function doRefresh(): Promise<boolean> {
  try {
    // ⚠️ 刷新令牌优先走 HttpOnly Cookie：credentials:'include' 让浏览器自动携带，
    // JS 读不到它 —— XSS 偷不走这张"7 天的长期钥匙"。
    // 请求体形式仅作兼容：滚动升级期间已登录的旧标签页里可能还有
    // sessionStorage 残留的刷新令牌（新版本登录不再写入）。
    // ⚠️ 刻意用裸 fetch 而不是本模块的 request()：
    // request() 的 401 处理会再尝试刷新 —— 刷新接口自己 401 时就死循环了
    const legacy = refreshToken()
    const response = await fetch(`${BASE_URL}/api/v1/auth/refresh`, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        ...(legacy ? { 'Content-Type': 'application/json' } : {})
      },
      body: legacy ? JSON.stringify({ refreshToken: legacy }) : undefined,
      credentials: 'include'
    })
    if (!response.ok) {
      // 兼容路径：服务端尚未升级（没有 Cookie 逻辑）且本地无残留令牌时会失败 ——
      // 此时只能走"重新登录"的老路
      return false
    }
    const payload = (await response.json()) as ApiResponse<{ accessToken?: string }>
    if (!payload || payload.code !== SUCCESS_CODE || !payload.data?.accessToken) {
      return false
    }
    writeStorage('accessToken', payload.data.accessToken)
    // 新的刷新令牌经由 HttpOnly Cookie 轮换（Set-Cookie），不再写入 JS 可读的存储
    return true
  } catch {
    // 网络/解析失败都按"刷新失败"处理：由原 401 路径收场（跳登录）
    return false
  }
}

/**
 * 发起请求并剥壳。
 *
 * <p>401 时先尝试<b>静默刷新</b>（见 {@link ensureRefreshed} 的单飞说明），
 * 成功则用新令牌<b>重试一次</b>原请求；重试仍失败才按认证失败上抛 ——
 * 这让"访问令牌 30 分钟过期"对使用者完全透明。
 *
 * @throws {ApiError} 业务错误码非 0，或 HTTP 层失败
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  return doRequest<T>(path, options, true)
}

// ---------------------------------------------------------------------
// 流式（SSE）请求的认证支持
// ---------------------------------------------------------------------

/**
 * 流式请求的认证头快照（访问令牌 + 租户头）。
 *
 * <h3>为什么单独导出，而不是让调用方自己读 sessionStorage</h3>
 * 令牌的存储位置、头名、租户头约定都是本模块的私有知识。
 * 流式接口（AI 对话、Agent 指挥）因为"等完整 JSON"的前提不成立
 * 而不能走 {@link request}，但<b>认证语义必须同源</b> ——
 * 它们此前各自读 sessionStorage，于是绕过了静默刷新：
 * 访问令牌一过期，普通接口静默续期、流式接口直接 401
 * （实测症状：其他页面全都正常，只有 AI 对话报"AI 服务响应异常（401）"）。
 */
export function streamAuthHeaders(): Record<string, string> {
  const headers: Record<string, string> = {}
  const token = accessToken()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  const tenantId = currentTenantId()
  if (tenantId) {
    headers[TENANT_HEADER] = tenantId
  }
  return headers
}

/**
 * 发送流式请求，并在 401 时静默刷新后**重试一次**。
 *
 * <p>与 {@link request} 的 401 处理完全同源（同一个 {@link ensureRefreshed}
 * 单飞闸门）：并发刷新会触发令牌重放保护（整族吊销）—— 流式接口不能
 * 自己再造一套刷新，否则这个坑会在 AI 对话上重演。
 *
 * <p>重试是安全的：401 意味着上游还没开始产生流，不存在"半截回答被重放"。
 *
 * @param path 相对路径（如 {@code /api/v1/ai/chat}）
 * @param init fetch 初始化（method/body/signal/headers）
 * @param base 基址，默认 {@link BASE_URL}；SSE 在 dev 下走 /sse-proxy 手工中间件
 */
export async function streamFetch(
  path: string,
  init: RequestInit = {},
  base: string = BASE_URL
): Promise<Response> {
  const send = (): Promise<Response> =>
    fetch(`${base}${path}`, {
      ...init,
      headers: {
        ...(init.headers as Record<string, string> | undefined),
        ...streamAuthHeaders()
      },
      // 与统一请求层一致：刷新令牌走 HttpOnly Cookie，流式请求同样要带上，
      // 否则静默刷新（依赖 credentials:'include'）在同源代理下也可能失效
      credentials: 'include'
    })

  const first = await send()
  if (first.status === 401 && (await ensureRefreshed())) {
    return send()
  }
  return first
}

async function doRequest<T>(path: string, options: RequestOptions, allowRefresh: boolean): Promise<T> {
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
      // ⚠️ 主动取消**不**走全局处理器：它是正常流程
      // （组件卸载、搜索框连续输入时的取消），
      // 弹一个"网络连接失败"会给用户完全错误的信号
      throw error
    }
    raise(new ApiError(-1, '网络连接失败，请检查网络后重试', 0))
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
    raise(new ApiError(-1, `服务端返回了无法解析的响应（HTTP ${httpStatus}）`, httpStatus))
  }

  if (!payload) {
    raise(new ApiError(-1, '响应体为空', httpStatus))
  }

  // 401 → 尝试静默刷新后重试一次。
  // allowRefresh=false 的重试不再刷新：若新令牌仍被拒，说明是真正的吊销
  // （被踢下线/改密/重放保护），必须走"跳登录"而不是再刷一次造成死循环
  if (httpStatus === 401 && allowRefresh && !isAuthPath(path) && (await ensureRefreshed())) {
    return doRequest<T>(path, options, false)
  }

  if (payload.code !== SUCCESS_CODE) {
    raise(new ApiError(payload.code, payload.msg || '请求失败', httpStatus))
  }

  return payload.data
}
