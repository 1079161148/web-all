import {
  announceMessage,
  getUnreadCount,
  issueMessageStreamTicket,
  markAllMessagesRead,
  markMessageRead,
  pageMyMessages
} from '@admin/api'
import type { AnnounceRequest } from '@admin/api'

/**
 * 消息中心的业务组合层。
 *
 * <h3>SSE 连接为什么不用生成的 streamMessages</h3>
 * 契约客户端基于 fetch，而 SSE 必须用浏览器的 {@code EventSource}
 * （自动重连、事件解析都是它内置的）。因此前端直接
 * {@code new EventSource('/api/v1/messages/stream?ticket=...')}，
 * 生成的那个函数不会被调用 —— 保留它只是契约完整性的需要。
 */

export function fetchMyMessages(page = 1, size = 20, isRead?: boolean) {
  return pageMyMessages({ page, size, ...(isRead === undefined ? {} : { isRead }) })
}

export function fetchUnreadCount(): Promise<number> {
  return getUnreadCount()
}

export async function markMessageReadAction(id: number): Promise<void> {
  await markMessageRead(id)
}

export async function markAllMessagesReadAction(): Promise<number> {
  return markAllMessagesRead()
}

/**
 * 取 SSE 连接票据。
 *
 * <p>生成类型里票据是可选的（契约未标 required），但业务上它必然存在 ——
 * 在边界显式拒绝，而不是把 undefined 拼进 URL。
 */
export async function issueStreamTicket(): Promise<string> {
  const ticket = await issueMessageStreamTicket()
  if (!ticket) {
    throw new Error('获取消息连接票据失败')
  }
  return ticket
}

export function announceAction(body: AnnounceRequest): Promise<number> {
  return announceMessage(body)
}
