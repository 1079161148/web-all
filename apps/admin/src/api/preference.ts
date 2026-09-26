import { request } from '@admin/api'
import type { ProTablePersistence } from '@admin/ui'

/**
 * 用户偏好的客户端（本人配置的读写，服务端校验见 UserPreferenceService）。
 *
 * <p>值是<b>不透明的 JSON</b>：每个键的结构由它的读写两端（前端功能自身）约定，
 * 服务端只守键格式与 8KB 上限 —— 这让"新增一种偏好"不需要动后端。
 */

/** 读取一项偏好；未设置返回 null。 */
export async function getUserPreference<T>(key: string): Promise<T | null> {
  const result = await request<T | null>(`/api/v1/profile/preferences/${key}`)
  return result ?? null
}

/** 写入一项偏好（upsert）。请求体即值本身（任意 JSON）。 */
export async function putUserPreference(key: string, value: unknown): Promise<void> {
  await request<void>(`/api/v1/profile/preferences/${key}`, {
    method: 'PUT',
    body: value
  })
}

/**
 * ProTable 的偏好适配器（依赖倒置的实现端）。
 *
 * <p>用法：表格传 {@code persistence-key="table:xxx"} 与
 * {@code :persistence="tablePreferenceAdapter"} —— 列布局改动防抖 800ms 后
 * 自动保存，换设备/重登录后恢复。
 */
export const tablePreferenceAdapter: ProTablePersistence = {
  load: (key) => getUserPreference(key),
  save: (key, value) => putUserPreference(key, value)
}
