import { request } from '@admin/api'

/**
 * 数据导入导出 API。
 *
 * <h3>下载为什么不用统一 request()</h3>
 * request() 把响应体当 JSON 剥壳；而 xlsx 下载要的是**原始字节**，
 * 且错误场景（如"任务尚无结果文件"）返回的是 JSON 错误体 ——
 * 两者的 Content-Type 分流只能由调用方自己处理。这里的下载实现：
 * fetch → 非 2xx 按 JSON 解析抛出带服务端文案的错，2xx 取 blob 触发保存。
 */

const BASE = '/api/v1/tools/transfers'

function authHeaders(): Record<string, string> {
  const headers: Record<string, string> = { 'X-Tenant-Id': sessionStorage.getItem('tenantId') ?? '' }
  const token = sessionStorage.getItem('accessToken')
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  return headers
}

export interface TransferTaskView {
  id: string
  kind: 'IMPORT' | 'EXPORT'
  status:
    | 'QUEUED'
    | 'PARSING'
    | 'VALIDATING'
    | 'VALIDATED'
    | 'IMPORTING'
    | 'DONE'
    | 'PARTIAL'
    | 'FAILED'
    | 'CANCELLED'
  progress: number
  phase: string
  totalRows: number
  successRows: number
  failedRows: number
  errors: Array<{ row: number; col: string; value: string; message: string }>
  preview: Array<Record<string, string>>
  hasResultFile: boolean
  resultFileName: string | null
}

/** 统一错误文案提取（页面 catch 里都用它）。 */
export function getErrorMessage(error: unknown): string {
  return error instanceof Error && error.message ? error.message : '操作失败，请稍后重试'
}

export function downloadTransferTemplate(): Promise<void> {
  return downloadBlob(`${BASE}/template`, '调研任务导入模板.xlsx')
}

export async function createImportTask(file: File, mapping?: number[]): Promise<string> {
  const form = new FormData()
  form.append('file', file)
  if (mapping) {
    // mapping 按模板列序给出对应的文件列下标（-1 = 该字段缺席）；
    // 拼接成逗号串由后端解析 —— multipart 里的列表参数没有天然表达
    form.append('mapping', mapping.join(','))
  }
  const result = await request<{ taskId: string }>(`${BASE}/import`, {
    method: 'POST',
    body: form
  })
  return result.taskId
}

/** 表头预览（列映射界面的数据源；后端只读表头与前 3 行，不建任务）。 */
export interface ImportHeaderPreview {
  headers: string[]
  previewRows: string[][]
}

export function previewImportHeaders(file: File): Promise<ImportHeaderPreview> {
  const form = new FormData()
  form.append('file', file)
  return request<ImportHeaderPreview>(`${BASE}/import-headers`, {
    method: 'POST',
    body: form
  })
}

export function getTransferTask(taskId: string): Promise<TransferTaskView> {
  return request<TransferTaskView>(`${BASE}/${taskId}`)
}

export function confirmImportTask(taskId: string): Promise<void> {
  return request<void>(`${BASE}/${taskId}/confirm`, { method: 'POST', body: {} })
}

export function retryImportFailures(taskId: string): Promise<void> {
  return request<void>(`${BASE}/${taskId}/retry`, { method: 'POST', body: {} })
}

export function cancelTransferTask(taskId: string): Promise<void> {
  return request<void>(`${BASE}/${taskId}/cancel`, { method: 'POST', body: {} })
}

export function createExportTask(
  scope: 'ALL' | 'PAGE' | 'SELECTED',
  ids: number[],
  fields: string[]
): Promise<string> {
  return request<{ taskId: string }>(`${BASE}/export`, {
    method: 'POST',
    body: { scope, ids, fields }
  }).then((result) => result.taskId)
}

/** 通用 blob 下载（模板 / 错误报告 / 导出结果）。 */
export async function downloadBlob(url: string, fileName: string): Promise<void> {
  const response = await fetch(url, { headers: authHeaders() })
  if (!response.ok) {
    let message = `下载失败（HTTP ${response.status}）`
    try {
      const payload = (await response.json()) as { msg?: string }
      if (payload.msg) {
        message = payload.msg
      }
    } catch {
      // 非 JSON 错误体：用默认文案
    }
    throw new Error(message)
  }
  const blob = await response.blob()
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = fileName
  link.click()
  URL.revokeObjectURL(link.href)
}
