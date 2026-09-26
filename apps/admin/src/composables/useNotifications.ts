import { ref } from 'vue'
import {
  fetchUnreadCount,
  issueStreamTicket
} from '@/api/message'

/**
 * 消息通知：未读角标 + SSE 实时刷新。
 *
 * <h3>推送只是信号（与后端同一设计）</h3>
 * SSE 事件里<b>没有消息内容</b>，收到任何事件都重新拉取未读数 ——
 * 单一事实来源在数据库。这换来两个性质：
 * 推送丢失只造成几秒延迟（下一次事件或页面刷新就纠正）；
 * 多实例部署下哪台机器收到连接都无所谓。
 *
 * <h3>连接生命周期</h3>
 * <ul>
 *   <li>布局挂载时 {@link connect}：先拉一次未读数（角标首屏就有），
 *       再用一次性票据开流（票据 30 秒有效、用过即焚）</li>
 *   <li>断线（网络/代理超时）→ {@code onerror} → 关闭旧连接，
 *       5 秒后<b>换新票据</b>重连 —— 旧票据已作废，必须重新签发</li>
 *   <li>退出登录时 {@link disconnect}：停止重连并清零角标，
 *       否则登录页上还会挂着一条不断重试的 EventSource</li>
 * </ul>
 *
 * <h3>为什么用模块级单例</h3>
 * 未读数是<b>应用级</b>状态：布局、面板、角标看到的是同一份。
 * 组件局部 ref 会让每个使用方各自连一条 SSE、各自记一个数 ——
 * 既浪费连接又必然不一致。与 {@code useDict} 的缓存同一思路。
 */

/** 未读数（应用级单例状态）。 */
const unread = ref(0)

let source: EventSource | null = null
let started = false
let reconnectTimer: number | null = null
let refreshTimer: number | null = null

const RECONNECT_DELAY_MS = 5000

export function useNotifications() {
  async function refresh(): Promise<void> {
    try {
      unread.value = await fetchUnreadCount()
    } catch {
      // 未读数拉取失败不打断主流程：下一个推送信号或页面刷新会重试
    }
  }

  /** 收到推送信号后合并刷新（节流：一次公告产生的多条信号只拉一次）。 */
  function scheduleRefresh(): void {
    if (refreshTimer !== null) {
      return
    }
    refreshTimer = window.setTimeout(() => {
      refreshTimer = null
      void refresh()
    }, 300)
  }

  function closeStream(): void {
    if (source) {
      source.close()
      source = null
    }
  }

  async function openStream(): Promise<void> {
    if (!started) {
      return
    }
    try {
      const ticket = await issueStreamTicket()
      source = new EventSource(`/api/v1/messages/stream?ticket=${encodeURIComponent(ticket)}`)
      source.onmessage = () => scheduleRefresh()
      source.onerror = () => {
        // EventSource 对 HTTP 错误也会走 onerror 并自动重试同一 URL ——
        // 但票据是一次性的，重试旧 URL 必然再失败，因此必须断开换新票
        closeStream()
        scheduleReconnect()
      }
    } catch {
      // 拿票据失败（网络/后端不可用）：稍后重试，不无限递归（由 started 把门）
      scheduleReconnect()
    }
  }

  function scheduleReconnect(): void {
    if (!started || reconnectTimer !== null) {
      return
    }
    reconnectTimer = window.setTimeout(() => {
      reconnectTimer = null
      void openStream()
    }, RECONNECT_DELAY_MS)
  }

  async function connect(): Promise<void> {
    if (started) {
      return
    }
    started = true
    await refresh()
    await openStream()
  }

  function disconnect(): void {
    started = false
    if (reconnectTimer !== null) {
      window.clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    closeStream()
    unread.value = 0
  }

  return { unread, refresh, connect, disconnect }
}
