/**
 * @admin/api 对外入口。
 *
 * <h3>红线（设计文档 §十）</h3>
 * 业务代码统一从这里引入类型与请求函数，**禁止自行手写接口类型或 fetch 调用**。
 *
 * <h3>两部分的边界</h3>
 * <ul>
 *   <li><b>生成产物</b>（{@code ./generated}）—— 由 orval 从后端 OpenAPI 生成，
 *       禁止手工修改。它保证"后端改字段 → 前端编译期报错"</li>
 *   <li><b>请求客户端</b>（{@code ./client}）—— 手写的统一请求层，
 *       负责剥壳统一响应体、把业务错误码转成 {@link ApiError}、
 *       注入令牌与租户头。它是生成产物的 mutator</li>
 * </ul>
 *
 * <h3>为什么生成产物能直接用</h3>
 * 流水线中的 {@code scripts/prepare-spec.mjs} 已经把统一响应体 {@code R<T>}
 * 的外壳从 spec 中剥离，因此生成函数的返回类型就是业务数据类型本身
 * （如 {@code pageTenants(): Promise<PageResultTenantResponse>}），
 * 与客户端剥壳后的实际返回值完全一致。<b>不需要在调用处再写 .data.data。</b>
 */

// ---- 请求客户端（手写）----
export { ApiError, request, SUCCESS_CODE } from './client'
export type { ApiResponse, PageResult, RequestOptions } from './client'

// ---- 契约生成产物（orval 生成，禁止手工修改）----
export * from './generated/endpoints'
export * from './generated/model'
