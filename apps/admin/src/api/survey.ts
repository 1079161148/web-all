import {
  applySurveyTemplate,
  batchCreateSurveyCollects,
  batchCreateSurveyPapers,
  batchCreateSurveyReports,
  batchCreateSurveyTasks,
  batchCreateSurveyTemplates,
  bumpSurveyPaperVersion,
  createSurveyCollect,
  createSurveyPaper,
  createSurveyReport,
  createSurveyTask,
  createSurveyTemplate,
  deleteSurveyCollect,
  deleteSurveyPaper,
  deleteSurveyReport,
  deleteSurveyTask,
  deleteSurveyTemplate,
  getDownloadSurveyFileUrl,
  getSurveyPaper,
  getSurveyReport,
  getSurveyTemplate,
  listSurveyTaskOptions,
  pageSurveyCollects,
  pageSurveyPapers,
  pageSurveyReports,
  pageSurveyTasks,
  pageSurveyTemplates,
  updateSurveyCollect,
  updateSurveyPaper,
  updateSurveyReport,
  updateSurveyTask,
  updateSurveyTemplate,
  uploadSurveyFile
} from '@admin/api'
import type {
  CollectRequest,
  CollectResponse,
  FileResponse,
  PaperRequest,
  PaperResponse,
  ReportRequest,
  ReportResponse,
  TaskOption,
  TaskRequest,
  TaskResponse,
  TemplateRequest,
  TemplateResponse
} from '@admin/api'
import { getUploadAuthHeaders } from '@admin/ui'
import type { ProTableQuery, ProTableRequest } from '@admin/ui'

/**
 * AI 调研模块的业务组合层。
 *
 * <h3>这一层只做两件事</h3>
 * <ol>
 *   <li><b>参数翻译</b>：ProTable 传扁平的 {@code {page, size, 各搜索字段}}，
 *       生成的函数要的是结构化参数</li>
 *   <li><b>结果规整</b>：生成类型字段全是可选的（OpenAPI 无法表达"一定有值"），
 *       而组件契约要求必填 —— 在边界处一次性收敛</li>
 * </ol>
 * 与 {@code @admin/api} 的关系同 iam.ts：契约产物只读，加工全在这里。
 */

/** 分页结果规整（生成类型可选 → 组件契约必填）。 */
function toPage<T>(
  result: { records?: T[]; total?: number; page?: number; size?: number },
  query: ProTableQuery
) {
  return {
    records: result.records ?? [],
    total: result.total ?? 0,
    page: result.page ?? query.page,
    size: result.size ?? query.size
  }
}

/** 空白串一律视为"未填"，不下发该条件（与后端"空串不过滤"的语义对齐）。 */
function text(value: unknown): string | undefined {
  const s = typeof value === 'string' ? value.trim() : ''
  return s === '' ? undefined : s
}

function num(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

/**
 * 日期控件值 → 后端 {@code LocalDate}（yyyy-MM-dd）。
 *
 * <p>控件配了 {@code valueFormat} 时给的是字符串（正常路径）；
 * 未生效时会退化成毫秒时间戳 —— 这里兜底转换，避免出现
 * "提交了 1758576000000 这种后端无法解析的值，报 400 却看不懂原因"。
 */
export function toDateString(value: unknown): string | undefined {
  if (typeof value === 'string' && value.trim() !== '') {
    return value.slice(0, 10)
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    return new Date(value).toISOString().slice(0, 10)
  }
  return undefined
}

/** 取行主键。生成类型里 id 可选，业务上必然存在（用于行操作与勾选）。 */
export function requireId(row: { id?: number }): number {
  if (row.id === undefined) {
    throw new Error('行数据缺少 id')
  }
  return row.id
}

// =====================================================================
// 调研任务
// =====================================================================

export const fetchSurveyTaskPage: ProTableRequest<TaskResponse> = async (query) => {
  const result = await pageSurveyTasks({
    page: query.page,
    size: query.size,
    taskCode: text(query.taskCode),
    taskName: text(query.taskName),
    taskType: text(query.taskType),
    status: text(query.status),
    priority: text(query.priority),
    sortField: text(query.sortField),
    sortOrder: text(query.sortOrder)
  })
  return toPage(result, query)
}

export const loadSurveyTaskOptions = (): Promise<TaskOption[]> => listSurveyTaskOptions()

export const createSurveyTaskAction = (body: TaskRequest) => createSurveyTask(body)

export async function updateSurveyTaskAction(id: number, body: TaskRequest): Promise<void> {
  await updateSurveyTask(id, body)
}

export async function deleteSurveyTaskAction(id: number): Promise<void> {
  await deleteSurveyTask(id)
}

export const batchCreateSurveyTasksAction = (rows: TaskRequest[]) => batchCreateSurveyTasks(rows)

// =====================================================================
// 问卷 / 提纲
// =====================================================================

export const fetchSurveyPaperPage: ProTableRequest<PaperResponse> = async (query) => {
  const result = await pageSurveyPapers({
    page: query.page,
    size: query.size,
    paperCode: text(query.paperCode),
    title: text(query.title),
    paperType: text(query.paperType),
    status: text(query.status),
    sortField: text(query.sortField),
    sortOrder: text(query.sortOrder)
  })
  return toPage(result, query)
}

export const createSurveyPaperAction = (body: PaperRequest) => createSurveyPaper(body)

/**
 * 问卷详情（含富文本正文）。
 *
 * <p><b>编辑前必须先调它</b>：列表接口不返回 content，
 * 用列表行填表单再保存会把正文清空。
 */
export const loadSurveyPaperDetail = (id: number) => getSurveyPaper(id)

export async function updateSurveyPaperAction(id: number, body: PaperRequest): Promise<void> {
  await updateSurveyPaper(id, body)
}

export async function deleteSurveyPaperAction(id: number): Promise<void> {
  await deleteSurveyPaper(id)
}

export async function bumpSurveyPaperVersionAction(id: number): Promise<void> {
  await bumpSurveyPaperVersion(id)
}

export const batchCreateSurveyPapersAction = (rows: PaperRequest[]) => batchCreateSurveyPapers(rows)

// =====================================================================
// 数据采集
// =====================================================================

export const fetchSurveyCollectPage: ProTableRequest<CollectResponse> = async (query) => {
  const result = await pageSurveyCollects({
    page: query.page,
    size: query.size,
    taskId: num(query.taskId),
    collector: text(query.collector),
    channel: text(query.channel),
    status: text(query.status),
    sortField: text(query.sortField),
    sortOrder: text(query.sortOrder)
  })
  return toPage(result, query)
}

export const createSurveyCollectAction = (body: CollectRequest) => createSurveyCollect(body)

export async function updateSurveyCollectAction(id: number, body: CollectRequest): Promise<void> {
  await updateSurveyCollect(id, body)
}

export async function deleteSurveyCollectAction(id: number): Promise<void> {
  await deleteSurveyCollect(id)
}

export const batchCreateSurveyCollectsAction = (rows: CollectRequest[]) =>
  batchCreateSurveyCollects(rows)

// =====================================================================
// 分析报告
// =====================================================================

export const fetchSurveyReportPage: ProTableRequest<ReportResponse> = async (query) => {
  const result = await pageSurveyReports({
    page: query.page,
    size: query.size,
    taskId: num(query.taskId),
    reportTitle: text(query.reportTitle),
    reportType: text(query.reportType),
    status: text(query.status),
    sortField: text(query.sortField),
    sortOrder: text(query.sortOrder)
  })
  return toPage(result, query)
}

export const createSurveyReportAction = (body: ReportRequest) => createSurveyReport(body)

/** 报告详情（含富文本正文）。编辑/预览前调它。 */
export const loadSurveyReportDetail = (id: number) => getSurveyReport(id)

export async function updateSurveyReportAction(id: number, body: ReportRequest): Promise<void> {
  await updateSurveyReport(id, body)
}

export async function deleteSurveyReportAction(id: number): Promise<void> {
  await deleteSurveyReport(id)
}

export const batchCreateSurveyReportsAction = (rows: ReportRequest[]) => batchCreateSurveyReports(rows)

// =====================================================================
// 模板库
// =====================================================================

export const fetchSurveyTemplatePage: ProTableRequest<TemplateResponse> = async (query) => {
  const result = await pageSurveyTemplates({
    page: query.page,
    size: query.size,
    templateCode: text(query.templateCode),
    templateName: text(query.templateName),
    category: text(query.category),
    status: text(query.status),
    sortField: text(query.sortField),
    sortOrder: text(query.sortOrder)
  })
  return toPage(result, query)
}

export const createSurveyTemplateAction = (body: TemplateRequest) => createSurveyTemplate(body)

/** 模板详情（含富文本正文）。编辑/预览前调它。 */
export const loadSurveyTemplateDetail = (id: number) => getSurveyTemplate(id)

export async function updateSurveyTemplateAction(id: number, body: TemplateRequest): Promise<void> {
  await updateSurveyTemplate(id, body)
}

export async function deleteSurveyTemplateAction(id: number): Promise<void> {
  await deleteSurveyTemplate(id)
}

/** 套用模板：返回模板全文（含正文）并把引用次数 +1。 */
export const applySurveyTemplateAction = (id: number) => applySurveyTemplate(id)

export const batchCreateSurveyTemplatesAction = (rows: TemplateRequest[]) =>
  batchCreateSurveyTemplates(rows)

// =====================================================================
// 附件
// =====================================================================

/** 业务类型（与后端 SurveyFileController 的约定一致）。 */
export const FILE_BIZ_TYPE = {
  collect: 'COLLECT_FILE',
  report: 'REPORT_FILE',
  editor: 'EDITOR_IMAGE'
} as const

/**
 * 上传附件。
 *
 * <p>走的仍是契约客户端（自带 Authorization 与 X-Tenant-Id），
 * 不是 n-upload 自己的 XHR —— 那些请求需要 {@code setUploadAuthResolver} 单独注头。
 * ProUpload 的 {@code action} 只是它内部 XHR 的目标地址，因此本模块的"表单内上传"
 * 统一走这个函数（见各页面的 {@code beforeUpload} 用法）。
 */
export const uploadSurveyFileAction = (
  file: File,
  bizType: string,
  bizId?: number
): Promise<FileResponse> => uploadSurveyFile({ bizType, bizId }, { file })

/** 附件下载地址（相对路径，由 Vite 代理转发到后端）。 */
export const surveyFileContentUrl = (fileId: number): string => getDownloadSurveyFileUrl(fileId)

/**
 * 取附件内容的 Blob URL（预览 / 下载用）。
 *
 * <h3>为什么不用 &lt;img src="/api/v1/survey/files/x/content"&gt; 直接引</h3>
 * 该接口需要认证，而浏览器加载 img/link 时<b>不会带上 Authorization 头</b> ——
 * 直接引会得到 401。因此这里用带头的 fetch 取回内容再转成本地对象 URL。
 * 代价是每个文件都要过一次 JS，且对象 URL 仅在当前会话有效（页面刷新后失效）。
 */
export async function fetchSurveyFileBlobUrl(fileId: number): Promise<string> {
  const response = await fetch(surveyFileContentUrl(fileId), {
    headers: getUploadAuthHeaders()
  })
  if (!response.ok) {
    throw new Error(`附件加载失败（HTTP ${response.status}）`)
  }
  return URL.createObjectURL(await response.blob())
}

/** 下载附件（浏览器保存为文件）。 */
export async function downloadSurveyFile(fileId: number, fileName: string): Promise<void> {
  const url = await fetchSurveyFileBlobUrl(fileId)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  link.remove()
  // 主动回收：不释放会让 blob 一直占着内存（尤其是多次预览大图）
  URL.revokeObjectURL(url)
}

/**
 * 取附件内容并转成 data URL（供富文本插图使用）。
 *
 * <h3>取舍：为什么插图用 data URL 而不是文件地址</h3>
 * 富文本正文以 HTML 形式存库。若正文里存的是受保护的接口地址，
 * 那么"任何脱离当前会话的阅读场景"（导出、他人查看、刷新后重渲染）
 * 都会因为缺少 Authorization 头而显示裂图。
 * 转成 data URL 后正文是自包含的，代价是正文体积变大 ——
 * 因此这里对插图单独设了更小的上限（{@value EDITOR_IMAGE_MAX_BYTES} 字节）。
 *
 * <p>生产环境更合适的做法是对象存储 + 签名 URL（有效期短、可直接被 img 引用），
 * 本模块的存储端口已为此预留（{@code srvy_attachment.storage_type}）。
 */
export const EDITOR_IMAGE_MAX_BYTES = 2 * 1024 * 1024

export async function fetchSurveyFileDataUrl(fileId: number): Promise<string> {
  const response = await fetch(surveyFileContentUrl(fileId), { headers: getUploadAuthHeaders() })
  if (!response.ok) {
    throw new Error(`图片加载失败（HTTP ${response.status}）`)
  }
  const blob = await response.blob()
  if (blob.size > EDITOR_IMAGE_MAX_BYTES) {
    throw new Error('图片超过 2MB，请压缩后再插入正文')
  }
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(new Error('图片读取失败'))
    reader.readAsDataURL(blob)
  })
}
