import { request } from '@admin/api'

/** 聚合视图（后端 FeEventPort.Summary）。 */
export interface ObservabilitySummary {
  slowApis: Array<{ name: string; calls: number; avgMs: number; maxMs: number }>
  errors: Array<{ name: string; page: string | null; detail: string | null; createdAt: string }>
  topPages: Array<{ page: string; views: number }>
}

/** 聚合视图（慢接口 / 错误 / 页面访问）。 */
export function getObservabilitySummary(): Promise<ObservabilitySummary> {
  return request<ObservabilitySummary>('/api/v1/observability/summary')
}
