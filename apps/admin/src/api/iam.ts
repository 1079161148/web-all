import {
  assignRolePermissions,
  assignUserRoles,
  changeRoleStatus,
  changeUserStatus,
  createConfig,
  createDept,
  createDictData,
  createDictType,
  createMenu,
  createPost,
  createRole,
  createUser,
  deleteConfig,
  deleteDept,
  deleteDictData,
  deleteDictType,
  deleteMenu,
  deletePost,
  deleteRole,
  deleteUser,
  listDepts,
  listMenus,
  listUsablePosts,
  listUsableRoles,
  pageConfigs,
  pageDictData,
  pageDictTypes,
  pagePosts,
  pageRoles,
  pageUsers,
  resetUserPassword,
  unlockUser,
  updateConfig,
  updateDept,
  updateDictData,
  updateDictType,
  updateMenu,
  updatePost,
  updateRole,
  updateUser
} from '@admin/api'
import type {
  ConfigRequest,
  DeptRequest,
  DictDataRequest,
  DictTypeRequest,
  MenuRequest,
  PostRequest,
  RoleResponse,
  UserResponse
} from '@admin/api'
import type { ProTableQuery, ProTableRequest } from '@admin/ui'

/**
 * IAM / 组织 / 平台模块的业务组合层。
 *
 * <h3>这一层只做两件事</h3>
 * <ol>
 *   <li><b>参数翻译</b>：ProTable 传的是扁平的 {@code {page, size, 各搜索字段}}，
 *       生成的函数要的是结构化参数</li>
 *   <li><b>结果规整</b>：生成类型里字段是<b>可选</b>的（OpenAPI 无法表达"后端保证有值"），
 *       而组件契约要求必填。在这里一次性补齐默认值</li>
 * </ol>
 *
 * <h3>为什么 7 个模块的适配器放同一个文件</h3>
 * 它们都是 5~10 行的机械转换，拆成 7 个文件只会让"改一个搜索字段"要翻 7 处。
 * 当某个模块出现<b>真正的业务编排</b>（多接口组合、跨模块校验）时，
 * 再把它单独拆出去 —— <b>按复杂度决定结构，而不是按对称性。</b>
 */

// =====================================================================
// 通用：把生成的分页结果规整成 ProTable 契约
// =====================================================================

/**
 * 生成类型的字段都是可选的（`total?: number`），而分页结果一定有它们。
 * 在边界处一次性收敛，避免可选性扩散到所有页面。
 */
function toPage<T>(result: {
  records?: T[]
  total?: number
  page?: number
  size?: number
}, query: ProTableQuery) {
  return {
    records: result.records ?? [],
    total: result.total ?? 0,
    page: result.page ?? query.page,
    size: result.size ?? query.size
  }
}

/** 把可选的字符串搜索字段收敛为 `string | undefined`（空串一律视为未填）。 */
function text(value: unknown): string | undefined {
  const s = typeof value === 'string' ? value.trim() : ''
  return s === '' ? undefined : s
}

// =====================================================================
// 用户
// =====================================================================

export const fetchUserPage: ProTableRequest<UserResponse> = async (query) => {
  const result = await pageUsers({
    page: query.page,
    size: query.size,
    username: text(query.username),
    nickname: text(query.nickname),
    phone: text(query.phone),
    status: text(query.status),
    deptId: typeof query.deptId === 'number' ? query.deptId : undefined
  })
  return toPage(result, query)
}

export interface UserFormModel {
  username: string
  nickname: string
  password?: string
  deptId?: number | null
  phone?: string
  email?: string
  sex?: number
  roleIds?: number[]
}

export async function createUserAction(model: UserFormModel): Promise<number> {
  return createUser({
    username: model.username,
    nickname: model.nickname,
    password: model.password,
    deptId: model.deptId ?? undefined,
    phone: model.phone,
    email: model.email,
    sex: model.sex,
    roleIds: model.roleIds
  })
}

export async function updateUserAction(id: number, model: UserFormModel): Promise<void> {
  await updateUser(id, {
    nickname: model.nickname,
    deptId: model.deptId ?? undefined,
    phone: model.phone,
    email: model.email,
    sex: model.sex,
    roleIds: model.roleIds
  })
}

export async function deleteUserAction(id: number): Promise<void> {
  await deleteUser(id)
}

/**
 * 重置密码。
 *
 * <p>不传密码时由后端重置为平台初始密码 —— 这是最常用的场景
 * （用户忘记密码，管理员一键重置并告知），因此把它作为默认行为。
 */
export async function resetUserPasswordAction(id: number, password?: string): Promise<void> {
  await resetUserPassword(id, { password })
}

export async function changeUserStatusAction(
  id: number,
  status: string,
  reason: string
): Promise<void> {
  await changeUserStatus(id, { status, reason })
}

export async function unlockUserAction(id: number): Promise<void> {
  await unlockUser(id)
}

export async function assignUserRolesAction(id: number, roleIds: number[]): Promise<void> {
  await assignUserRoles(id, { roleIds })
}

// =====================================================================
// 角色
// =====================================================================

export const fetchRolePage: ProTableRequest<RoleResponse> = async (query) => {
  const result = await pageRoles({
    page: query.page,
    size: query.size,
    roleName: text(query.roleName),
    roleKey: text(query.roleKey),
    status: text(query.status)
  })
  return toPage(result, query)
}

export interface RoleFormModel {
  roleKey: string
  roleName: string
  sort?: number
  dataScope?: string
  remark?: string
}

export async function createRoleAction(model: RoleFormModel): Promise<number> {
  return createRole({
    roleKey: model.roleKey,
    roleName: model.roleName,
    sort: model.sort,
    dataScope: model.dataScope ?? 'SELF',
    remark: model.remark
  })
}

export async function updateRoleAction(
  id: number,
  model: { roleName: string; sort?: number }
): Promise<void> {
  await updateRole(id, model)
}

export async function deleteRoleAction(id: number): Promise<void> {
  await deleteRole(id)
}

export async function changeRoleStatusAction(id: number, status: string): Promise<void> {
  await changeRoleStatus(id, { status })
}

export async function assignRolePermissionsAction(
  id: number,
  menuIds: number[],
  dataScope: string,
  deptIds: number[]
): Promise<void> {
  await assignRolePermissions(id, { menuIds, dataScope, deptIds })
}

/** 可用角色（下拉用）。 */
export const loadUsableRoles = () => listUsableRoles()

// =====================================================================
// 部门 / 菜单（树形数据，前端建树）
// =====================================================================

export const loadDeptList = () => listDepts()
export const loadMenuList = () => listMenus()

export async function createDeptAction(body: DeptRequest): Promise<number> {
  return createDept(body)
}

export async function updateDeptAction(id: number, body: DeptRequest): Promise<void> {
  await updateDept(id, body)
}

export async function deleteDeptAction(id: number): Promise<void> {
  await deleteDept(id)
}

export async function createMenuAction(body: MenuRequest): Promise<number> {
  return createMenu(body)
}

export async function updateMenuAction(id: number, body: MenuRequest): Promise<void> {
  await updateMenu(id, body)
}

export async function deleteMenuAction(id: number): Promise<void> {
  await deleteMenu(id)
}

// =====================================================================
// 岗位
// =====================================================================

export const fetchPostPage: ProTableRequest<Record<string, unknown>> = async (query) => {
  const result = await pagePosts({
    page: query.page,
    size: query.size,
    postCode: text(query.postCode),
    postName: text(query.postName),
    status: text(query.status)
  })
  return toPage(result as { records?: Record<string, unknown>[] }, query)
}

export const loadUsablePosts = () => listUsablePosts()

export async function createPostAction(body: PostRequest): Promise<number> {
  return createPost(body)
}

export async function updatePostAction(id: number, body: PostRequest): Promise<void> {
  await updatePost(id, body)
}

export async function deletePostAction(id: number): Promise<void> {
  await deletePost(id)
}

// =====================================================================
// 字典（类型 + 字典项）
// =====================================================================

export const fetchDictTypePage: ProTableRequest<Record<string, unknown>> = async (query) => {
  const result = await pageDictTypes({
    page: query.page,
    size: query.size,
    dictName: text(query.dictName),
    dictType: text(query.dictType),
    status: text(query.status)
  })
  return toPage(result as { records?: Record<string, unknown>[] }, query)
}

export const fetchDictDataPage: ProTableRequest<Record<string, unknown>> = async (query) => {
  const result = await pageDictData({
    page: query.page,
    size: query.size,
    dictType: text(query.dictType),
    dictLabel: text(query.dictLabel),
    status: text(query.status)
  })
  return toPage(result as { records?: Record<string, unknown>[] }, query)
}

export async function createDictTypeAction(body: DictTypeRequest): Promise<number> {
  return createDictType(body)
}

export async function updateDictTypeAction(id: number, body: DictTypeRequest): Promise<void> {
  await updateDictType(id, body)
}

export async function deleteDictTypeAction(id: number): Promise<void> {
  await deleteDictType(id)
}

export async function createDictDataAction(body: DictDataRequest): Promise<number> {
  return createDictData(body)
}

export async function updateDictDataAction(id: number, body: DictDataRequest): Promise<void> {
  await updateDictData(id, body)
}

export async function deleteDictDataAction(id: number): Promise<void> {
  await deleteDictData(id)
}

// =====================================================================
// 参数配置
// =====================================================================

export const fetchConfigPage: ProTableRequest<Record<string, unknown>> = async (query) => {
  const result = await pageConfigs({
    page: query.page,
    size: query.size,
    configName: text(query.configName),
    configKey: text(query.configKey)
  })
  return toPage(result as { records?: Record<string, unknown>[] }, query)
}

export async function createConfigAction(body: ConfigRequest): Promise<number> {
  return createConfig(body)
}

export async function updateConfigAction(id: number, body: ConfigRequest): Promise<void> {
  await updateConfig(id, body)
}

export async function deleteConfigAction(id: number): Promise<void> {
  await deleteConfig(id)
}
