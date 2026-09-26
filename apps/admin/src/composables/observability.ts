import type { Router } from 'vue-router'
import { BASE_URL } from '@admin/api'

/**
 * 前端可观测性采集器（零第三方依赖）。
 *
 * <h3>采集什么、用什么 API（每一项都有"为什么"）</h3>
 * <ul>
 *   <li><b>接口耗时</b>：Resource Timing API —— SPA 里所有 fetch/XHR 都会
 *       出现在资源条目里，<b>不需要侵入请求层</b>就能拿到完整耗时；
 *       少一处对公共客户端的改动，就少一处维护成本；</li>
 *   <li><b>路由耗时</b>：beforeEach/afterEach 打点（SPA 导航没有原生条目）；</li>
 *   <li><b>错误</b>：Vue errorHandler + window error + unhandledrejection
 *       三层兜底 —— 只挂其中一层会漏掉"异步回调里的错"；</li>
 *   <li><b>性能体征</b>：LCP / CLS 经 PerformanceObserver（Web Vitals 的
 *       标准采集方式）。LCP 在首次输入后不再变化，CLS 持续累计 ——
 *       各自只上报一次/一次会话级快照，够诊断用。</li>
 * </ul>
 *
 * <h3>上报管道的边界</h3>
 * <ul>
 *   <li><b>批量</b>：20 条或 10 秒先到先发 —— 逐条上报会让观测流量
 *       比业务流量还大；</li>
 *   <li><b>fetch keepalive</b> 而不是 sendBeacon：sendBeacon 无法携带
 *       Authorization 头，而本项目认证就是 Bearer；keepalive 正是为
 *       "页面卸载期也要送达"设计的标准能力；</li>
 *   <li><b>自过滤</b>：上报接口自身不采集 —— 否则每 10 秒的上报又生成
 *       一条上报耗时事件，死循环；</li>
 *   <li><b>队列上限 200</b>：错误风暴时丢最老的，保证采集器本身
 *       永远不会成为页面的负载或内存泄漏源。</li>
 * </ul>
 */

interface FeEvent {
  type: 'api' | 'route' | 'error' | 'vital'
  name: string
  page?: string
  durationMs?: number
  detail?: string
}

const FLUSH_INTERVAL_MS = 10_000
const FLUSH_THRESHOLD = 20
const QUEUE_CAP = 200
/** 本端点不采集（自过滤，见文件头）。 */
const INGEST_PATH = '/api/v1/observability/events'

const queue: FeEvent[] = []
let apiObserver: PerformanceObserver | null = null
let lcpObserver: PerformanceObserver | null = null
let clsObserver: PerformanceObserver | null = null
let clsValue = 0
let lcpReported = false
let clsReported = false
let navStart = 0
const stopped = false

/** 令牌在发送时现取：采集器比登录早初始化，启动时可能还没有令牌。 */
function currentAuthHeaders(): Record<string, string> {
  const headers: Record<string, string> = {}
  try {
    const token = sessionStorage.getItem('accessToken')
    const tenantId = sessionStorage.getItem('tenantId')
    if (token) {
      headers.Authorization = `Bearer ${token}`
    }
    if (tenantId) {
      headers['X-Tenant-Id'] = tenantId
    }
  } catch {
    // 隐私模式等存储不可用：无头发送，由服务端按未认证拒绝（丢弃）
  }
  return headers
}

function track(event: FeEvent): void {
  if (stopped) {
    return
  }
  queue.push(event)
  if (queue.length > QUEUE_CAP) {
    // 丢最老的：新事件比旧事件更接近"现在的问题"
    queue.splice(0, queue.length - QUEUE_CAP)
  }
  if (queue.length >= FLUSH_THRESHOLD) {
    void flush()
  }
}

async function flush(): Promise<void> {
  if (stopped || queue.length === 0) {
    return
  }
  const batch = queue.splice(0, queue.length)
  try {
    await fetch(`${BASE_URL}${INGEST_PATH}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...currentAuthHeaders() },
      body: JSON.stringify({ events: batch }),
      // keepalive：页面即将卸载时请求也能继续（上限 64KB，批量远小于此）
      keepalive: true
    })
  } catch {
    // 观测数据送达失败直接放弃：为诊断数据重试，会让诊断流量自我放大
  }
}

function onVisibleHidden(): void {
  if (document.visibilityState === 'hidden') {
    // 用户可能马上关页：把攒着的都发出去（keepalive 兜底）
    void flush()
  }
}

export function initObservability(router: Router): void {
  // ---- 接口耗时：Resource Timing（零侵入，见文件头） ----
  if (typeof PerformanceObserver !== 'undefined') {
    try {
      apiObserver = new PerformanceObserver((list) => {
        for (const entry of list.getEntries()) {
          const url = entry.name
          if (!url.includes('/api/') || url.includes(INGEST_PATH)) {
            continue
          }
          const path = url.replace(/^https?:\/\/[^/]+/, '').split('?')[0]
          track({
            type: 'api',
            name: path.slice(0, 120),
            page: location.pathname,
            durationMs: Math.round(entry.duration)
          })
        }
      })
      apiObserver.observe({ type: 'resource', buffered: false })
    } catch {
      // 浏览器不支持：接口耗时维度缺席，其余维度照常
    }

    // ---- 性能体征：LCP（一次性）与 CLS（会话累计） ----
    try {
      lcpObserver = new PerformanceObserver((list) => {
        const entries = list.getEntries()
        const last = entries[entries.length - 1]
        if (last && !lcpReported) {
          lcpReported = true
          track({
            type: 'vital',
            name: 'LCP',
            page: location.pathname,
            durationMs: Math.round(last.startTime)
          })
        }
      })
      lcpObserver.observe({ type: 'largest-contentful-paint', buffered: false })
    } catch {
      // 不支持则跳过
    }
    try {
      clsObserver = new PerformanceObserver((list) => {
        for (const entry of list.getEntries()) {
          const shift = entry as PerformanceEntry & { value?: number }
          if (typeof shift.value === 'number') {
            clsValue += shift.value
          }
        }
        if (!clsReported && clsValue > 0) {
          clsReported = true
          track({
            type: 'vital',
            name: 'CLS',
            page: location.pathname,
            durationMs: Math.round(clsValue * 1000)
          })
        }
      })
      clsObserver.observe({ type: 'layout-shift', buffered: false })
    } catch {
      // 不支持则跳过
    }
  }

  // ---- 路由耗时 ----
  router.beforeEach(() => {
    navStart = performance.now()
  })
  router.afterEach(() => {
    if (navStart > 0) {
      track({
        type: 'route',
        name: router.currentRoute.value.path,
        page: router.currentRoute.value.path,
        durationMs: Math.round(performance.now() - navStart)
      })
    }
  })

  // ---- 错误三层兜底 ----
  window.addEventListener('error', (event) => {
    track({
      type: 'error',
      name: (event.message || 'script error').slice(0, 120),
      page: location.pathname,
      detail: event.filename ? `${event.filename}:${event.lineno}`.slice(0, 500) : undefined
    })
  })
  window.addEventListener('unhandledrejection', (event) => {
    const reason = event.reason
    track({
      type: 'error',
      name: `unhandledrejection: ${String(reason).slice(0, 100)}`,
      page: location.pathname,
      detail: reason instanceof Error ? String(reason.stack).slice(0, 500) : undefined
    })
  })

  // 采集器与 SPA 同生命周期（注册即常驻，无卸载路径 —— 全局单例）
  window.setInterval(() => {
    void flush()
  }, FLUSH_INTERVAL_MS)
  document.addEventListener('visibilitychange', onVisibleHidden)
}

/** Vue errorHandler 转发入口（main.ts 在 app.config.errorHandler 里调用）。 */
export function reportVueError(error: unknown, info: string): void {
  track({
    type: 'error',
    name: `vue: ${String(error).slice(0, 110)}`,
    page: location.pathname,
    detail: info.slice(0, 500)
  })
}
