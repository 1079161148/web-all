import {
  activateTenant,
  closeTenant,
  createTenant,
  pageTenants,
  renewTenant,
  suspendTenant
} from '@admin/api'
import type { CreateTenantRequest, RenewTenantRequest, TenantResponse } from '@admin/api'
import type { ProTableQuery, ProTableRequest } from '@admin/ui'

/**
 * 租户模块的业务组合层。
 *
 * <h3>这一层为什么存在</h3>
 * `@admin/api` 是**契约生成产物**，函数签名必须与后端接口一一对应，不应被改动。
 * 但业务侧常需要额外加工，典型的有两类：
 * <ol>
 *   <li><b>参数翻译</b>：ProTable 传的是扁平的 `{page, size, 各搜索字段}`，
 *       而后端接口要的是结构化的查询对象</li>
 *   <li><b>结果规整</b>：生成的类型里字段是<b>可选</b>的
 *       （OpenAPI 无法表达"后端保证一定有值"），而组件契约要求必填。
 *       在这里一次性补齐默认值，比让每个页面各自写 `?? 0` 更可靠</li>
 * </ol>
 *
 * <h3>红线</h3>
 * <b>禁止在本文件重写接口类型</b>（如再定义一遍 `interface TenantVO`）——
 * 类型必须从 `@admin/api` 导入。一旦手写，契约优先就失效了（设计文档 §十）。
 */

/**
 * ProTable 的请求适配器。
 *
 * <p>注意 `records ?? []` 与 `total ?? 0`：生成类型的可选性来自 OpenAPI 的局限，
 * 而"分页结果一定有这两个字段"是后端契约的一部分。
 * <b>在边界处一次性收敛，而不是让可选性扩散到所有调用方</b> ——
 * 否则 UI 层会被迫到处写空值判断。
 */
export const fetchTenantPage: ProTableRequest<TenantResponse> = async (query: ProTableQuery) => {
  const result = await pageTenants({
    page: query.page,
    size: query.size,
    sortField: (query.sortField as string | undefined) ?? undefined,
    sortOrder: query.sortOrder as 'asc' | 'desc' | undefined,
    code: query.code as string | undefined,
    name: query.name as string | undefined,
    // status 在生成类型里是字面量联合（PageTenantsStatus），
    // 而 ProTable 传过来的是 string（搜索控件是运行时配置）。
    // 这里做一次收窄并<b>只接受已知取值</b>：非法值直接丢弃，
    // 而不是强转后传给后端 —— 后者会得到一个"查不到任何数据"的诡异结果，
    // 使用者只会以为筛选没生效。
    status: toTenantStatus(query.status),
    planCode: query.planCode as string | undefined
  })

  return {
    records: result.records ?? [],
    total: result.total ?? 0,
    page: result.page ?? query.page,
    size: result.size ?? query.size
  }
}

/** 合法的租户状态取值（与后端 TenantStatus 枚举一致）。 */
const TENANT_STATUSES = ['PENDING', 'ACTIVE', 'SUSPENDED', 'EXPIRED', 'CLOSED'] as const

function toTenantStatus(value: unknown): (typeof TENANT_STATUSES)[number] | undefined {
  if (typeof value !== 'string' || value === '') {
    return undefined
  }
  return (TENANT_STATUSES as readonly string[]).includes(value)
    ? (value as (typeof TENANT_STATUSES)[number])
    : undefined
}

/**
 * ⚠️ 下面这些 void 接口的包装不是多余的。
 *
 * <p>orval 对"无返回体"的接口（后端 `R<Void>`，在 spec 预处理时被置空）
 * 生成的是 `Promise<unknown>`，而业务侧的语义是 `Promise<void>`。
 * 直接 `return activateTenant(id)` 会因 `unknown` 不能赋给 `void` 而编译失败。
 *
 * <p>这里的 `await` + 不返回，把类型收敛到 `void` 的同时，
 * 也让调用方无法误用返回值 —— <b>把"没有返回值"这个事实表达进类型里</b>。
 */
export async function createTenantAction(body: CreateTenantRequest): Promise<number> {
  return createTenant(body)
}

/** 激活租户。 */
export async function activateTenantAction(id: number): Promise<void> {
  await activateTenant(id)
}

/**
 * 暂停租户。
 *
 * <p>`reason` 是<b>必填</b>的（后端用 `@NotBlank` 校验）。
 * 这不是形式主义的要求：暂停会让整个租户无法访问，
 * 事后排查"谁在什么时候因为什么暂停了它"时，这条原因是唯一的线索。
 */
export async function suspendTenantAction(id: number, reason: string): Promise<void> {
  await suspendTenant(id, { reason })
}

/** 续期。 */
export async function renewTenantAction(id: number, body: RenewTenantRequest): Promise<void> {
  await renewTenant(id, body)
}

/** 关闭租户（终态，不可恢复）。 */
export async function closeTenantAction(id: number, reason: string): Promise<void> {
  await closeTenant(id, { reason })
}
