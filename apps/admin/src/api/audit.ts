import { request } from '@admin/api'

/** 操作审计条目（只读）。 */
export interface AuditLogView {
  id: number
  username: string
  action: 'CREATED' | 'UPDATED' | string
  bizType: string
  bizId: string
  summary: string
  /** 字段级变更：每行"字段: 旧值 → 新值"（CREATED 事件为空） */
  diffText: string | null
  createTime: string
}

export function pageAuditLogs(page: number, size: number): Promise<{ records: AuditLogView[]; total: number }> {
  return request(`/api/v1/tools/audit-logs?page=${page}&size=${size}`)
}