import { request } from '@admin/api'

/** 工单（调度演示后端）。 */
export interface OpsTicket {
  id: number
  title: string
  channel: '客服' | '运维' | '配送'
  priority: 'P1' | 'P2' | 'P3'
  state: 'PENDING' | 'CLAIMED' | 'DONE' | 'ESCALATED'
  claimer: string | null
  slaMinutes: number
  /** 服务端时间戳（毫秒）—— SLA 倒计时的基准，避免客户端时钟漂移 */
  createdAt: number
}

export function listOpsTickets(): Promise<OpsTicket[]> {
  return request<OpsTicket[]>('/api/v1/dispatch/tickets')
}

export async function claimOpsTicket(id: number): Promise<void> {
  await request<void>(`/api/v1/dispatch/tickets/${id}/claim`, { method: 'POST', body: {} })
}

export async function completeOpsTicket(id: number): Promise<void> {
  await request<void>(`/api/v1/dispatch/tickets/${id}/complete`, { method: 'POST', body: {} })
}

/**
 * 放回待处理池（仅接单人；看板"处理中 → 待处理"拖拽）。
 *
 * <p>失败（10006 = 非接单人/状态已变）由调用方捕获并<b>回滚乐观更新</b> ——
 * 这是看板拖拽"先改界面、后问服务端"模式的另一半契约。
 */
export async function releaseOpsTicket(id: number): Promise<void> {
  await request<void>(`/api/v1/dispatch/tickets/${id}/release`, { method: 'POST', body: {} })
}