import { computed, ref, type ComputedRef, type Ref } from 'vue'

/**
 * 字典数据来源注册表。
 *
 * <h3>为什么是一个「注册表」而不是直接在这里取数</h3>
 * 这是本组件库最容易被做错的一处依赖方向问题。
 *
 * <p>{@code @admin/ui} <b>不能依赖 {@code @admin/api}</b>：
 * <ul>
 *   <li>架构上：UI 库要能脱离本项目单独复用，一旦它 import 了业务接口，
 *       就与后端的契约、认证、请求封装绑死了</li>
 *   <li>可替换性上：字典的数据来源可能是接口、可能是本地静态表、
 *       将来还可能是 WebSocket 推送 —— 组件库不该关心是哪一种</li>
 * </ul>
 *
 * <p>因此反过来：<b>由应用在启动时把「怎么取字典」注入进来</b>。
 * 组件库只负责"拿到值 → 渲染成标签"，取数与缓存留在 app 层
 * （见 {@code apps/admin/src/composables/useDict.ts}）。
 *
 * <pre>{@code
 * // apps/admin/src/main.ts
 * import { setDictResolver } from '@admin/ui'
 * import { dictOptions } from '@/composables/useDict'
 *
 * setDictResolver(dictOptions)   // 只接线，不搬运
 * }</pre>
 *
 * <h3>为什么 resolver 返回 Ref 而不是数组</h3>
 * 字典是<b>异步</b>加载的：组件首次渲染时字典还没到。
 * 若 resolver 返回普通数组，组件拿到的是"那一刻的空数组"，
 * 字典到达后<b>不会重新渲染</b> —— 表格里的状态列会一直空白，
 * 而且刷新一下就正常了，属于最难复现的那类 bug。
 * 返回 Ref 让渲染自动跟随数据到达。
 */

/** 字典项。字段名与后端 `plt_dict_data` 对齐，减少一层心智转换。 */
export interface DictOption {
  /** 展示文案。 */
  label: string
  /** 存储值。 */
  value: string
  /** 标签样式语义：success / warning / error / info / primary / default。 */
  cssClass?: string
  isDefault?: boolean
}

/**
 * 字典解析函数：给定字典类型，返回**响应式**的选项数组。
 *
 * <p>同一类型可能被多个组件同时请求，去重与缓存由实现方负责 ——
 * 组件库这边不做缓存，因为"缓存该活多久"是业务问题
 * （字典会在管理页修改，缓存策略取决于应用想让它多久后生效）。
 */
export type DictResolver = (dictType: string) => Ref<DictOption[]>

const EMPTY: DictOption[] = []
const emptyRef = ref(EMPTY)

let resolver: DictResolver | null = null

/**
 * 注入字典解析函数。应用启动时调用一次。
 *
 * <p>未注入时不会抛错，而是降级为「按原始值渲染」——
 * 理由是：组件库的单元测试、Storybook/story 场景下通常不需要真字典，
 * 强制注入会让每个故事都要写一遍接线代码。
 * 但**生产环境必须注入**，否则所有字典列都会显示码值。
 */
export function setDictResolver(next: DictResolver | null): void {
  resolver = next
}

/** 是否已注入（供 story / 测试断言用）。 */
export function hasDictResolver(): boolean {
  return resolver !== null
}

/**
 * 取某个字典的响应式选项。
 *
 * <p>未注入 resolver 时返回一个恒定的空 Ref（而不是新建 Ref）——
 * 避免每次调用都创建新对象导致下游 computed 无谓重算。
 */
export function getDictOptions(dictType: string): Ref<DictOption[]> {
  if (!resolver || !dictType) {
    return emptyRef
  }
  return resolver(dictType)
}

/**
 * 按值查字典项；查不到返回 undefined（调用方据此决定是显示原值还是占位符）。
 *
 * <p>入参刻意包含 {@code boolean}：调用方常直接传行数据的字段
 * （如 {@code row.enabled} 是布尔），若签名只收 string，每个调用点都要写
 * {@code String(...)} 转换 —— 而这种转换漏掉时只会在运行时表现为"查不到"。
 * 统一在这里用 {@code String(value)} 归一，把转换收敛到一处。
 */
export function findDictOption(
  dictType: string,
  value: string | number | boolean | null | undefined
): ComputedRef<DictOption | undefined> {
  const options = getDictOptions(dictType)
  return computed(() => {
    if (value === null || value === undefined) {
      return undefined
    }
    const target = String(value)
    return options.value.find((option) => option.value === target)
  })
}

/**
 * 字典值 → 展示文案。
 *
 * <p>查不到时返回<b>原始值</b>而不是空串：见 {@code DictTag} 的说明 ——
 * 静默显示空白会让"字典漏配了一项"变成"页面上有个格子是空的"，
 * 排查成本极高。
 */
export function dictLabel(
  dictType: string,
  value: string | number | boolean | null | undefined
): ComputedRef<string> {
  const option = findDictOption(dictType, value)
  return computed(() => {
    if (option.value) {
      return option.value.label
    }
    return value === null || value === undefined || value === '' ? '-' : String(value)
  })
}
