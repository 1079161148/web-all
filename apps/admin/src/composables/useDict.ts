import { ref, type Ref } from 'vue'
import { listDictDataByType } from '@admin/api'
import type { DictDataResponse } from '@admin/api'

/**
 * 字典数据的获取与缓存（前端侧）。
 *
 * <h3>为什么放在 app 层而不是 @admin/ui</h3>
 * 字典的数据来自后端接口，而 {@code @admin/ui} 刻意<b>不依赖 &commat;admin/api</b>
 * （见该包的 package.json）—— 它只管"怎么渲染"，不管"数据从哪来"。
 * 把取数逻辑放进去会让 UI 包被后端契约绑架，也让"UI 库可替换"这个目标失效。
 *
 * <p>因此职责切分为：
 * <pre>
 *   useDict（本文件，app 层）  —— 取数、缓存、并发去重
 *   DictTag（app 层组件）      —— 把值渲染成带颜色的标签
 * </pre>
 *
 * <h3>三层缓存/去重，缺一不可</h3>
 * <ol>
 *   <li><b>结果缓存</b>（cache）：同一字典类型在整个会话期只取一次。
 *       字典是"低频变更、高频读取"的数据，每个页面各取一次纯属浪费</li>
 *   <li><b>并发去重</b>（inflight）：一个页面上有 3 个下拉都用同一个字典类型时，
 *       首次渲染会同时触发 3 个请求。不去重就是 3 次相同请求 ——
 *       这正是设计文档 §12.7 说的"接口瀑布"的微观形态</li>
 *   <li><b>失败降级</b>：取字典失败<b>不能阻塞页面</b>。
 *       页面主体数据与字典无关，字典取不到最多是"标签显示成原始值"，
 *       不能让整个列表打不开</li>
 * </ol>
 */

/** 前端使用的字典项。字段名刻意与后端对齐，减少心智负担。 */
export interface DictOption {
  label: string
  value: string
  /** 标签样式：success / warning / error / info / default。 */
  cssClass?: string
  isDefault: boolean
}

const cache = new Map<string, Ref<DictOption[]>>()
const inflight = new Map<string, Promise<DictOption[]>>()

function toOption(row: DictDataResponse): DictOption {
  return {
    label: row.dictLabel ?? '',
    value: row.dictValue ?? '',
    cssClass: row.cssClass ?? undefined,
    isDefault: row.isDefault === true
  }
}

/** 取（或创建）某个字典类型的响应式容器。 */
function ensureRef(dictType: string): Ref<DictOption[]> {
  let holder = cache.get(dictType)
  if (!holder) {
    holder = ref<DictOption[]>([])
    cache.set(dictType, holder)
  }
  return holder
}

/**
 * 加载字典项（带并发去重）。
 *
 * <p>返回的 Promise <b>永不 reject</b>：失败时返回空数组并打印警告。
 * 这是刻意的 —— 见类注释第 3 点，字典失败不该让调用方被迫写 try/catch，
 * 更不该让页面崩掉。
 */
export function loadDict(dictType: string): Promise<DictOption[]> {
  if (!dictType) {
    return Promise.resolve([])
  }
  const existing = inflight.get(dictType)
  if (existing) {
    return existing
  }

  const task = listDictDataByType(dictType)
    .then((rows) => {
      const options = (rows ?? []).map(toOption)
      ensureRef(dictType).value = options
      return options
    })
    .catch((error: unknown) => {
      // 只警告、不抛出。字典取不到是"降级"，不是"故障"。
      console.warn(
        `[useDict] 字典「${dictType}」加载失败，相关标签将显示原始值。`, error
      )
      return [] as DictOption[]
    })
    .finally(() => {
      inflight.delete(dictType)
    })

  inflight.set(dictType, task)
  return task
}

/**
 * 在组件 setup 中声明并订阅若干字典。
 *
 * <pre>{@code
 * const dicts = useDict('sys_user_sex', 'sys_normal_disable')
 * // 模板中：dicts.sys_normal_disable.value 即为选项数组
 * }</pre>
 *
 * <p>返回的对象键就是传入的类型名，因此模板里能拿到完整类型提示，
 * 不需要用字符串下标去猜。
 */
export function useDict<T extends string>(...dictTypes: T[]): Record<T, Ref<DictOption[]>> {
  const result = {} as Record<T, Ref<DictOption[]>>
  for (const dictType of dictTypes) {
    result[dictType] = ensureRef(dictType)
    void loadDict(dictType)
  }
  return result
}

/**
 * 从未加载过的字典类型。用于「首次访问时自动加载，之后不再重复请求」。
 */
const loadedTypes = new Set<string>()

/**
 * 读取某个字典的响应式选项，**首次访问时自动触发加载**。
 *
 * <h3>为什么改成自动加载（这是一次修正）</h3>
 * 早先的版本要求页面先用 {@link useDict} 显式声明，理由是"让依赖可见"。
 * 实践下来这个理由站不住：
 * <ul>
 *   <li>{@code <DictTag dict-type="sys_user_status" :value="row.status" />} 是<b>自解释</b>的 ——
 *       组件名与 dict-type 已经把依赖说清楚了，再要求页面顶部补一句声明属于重复</li>
 *   <li>漏声明的后果是<b>标签静默显示成码值</b>（不报错、不空白），
 *       而"忘了在哪一行加声明"极难定位。这类"漏了也不报错"的设计是负债</li>
 *   <li>动态字典类型（如字典管理页里按当前类型渲染）根本无法预先声明</li>
 * </ul>
 *
 * <p>因此改为：<b>谁用到谁触发，缓存与并发去重由本模块统一负责。</b>
 * 这正是"配置优先"在数据层的对应做法 —— 让调用方少写一行，且写错也没有代价。
 */
export function dictOptions(dictType: string): Ref<DictOption[]> {
  const holder = ensureRef(dictType)
  if (dictType && !loadedTypes.has(dictType)) {
    loadedTypes.add(dictType)
    // 用 async IIFE 而不是 .then(回调)：
    // ① 加载失败（返回空数组）时要移除标记，允许下次访问重试 ——
    //    否则一次网络抖动会让这个字典在整个会话里永久为空
    // ② `.then` 回调整体是个"只做副作用、无返回值"的形态，
    //    会被 lint 规则 promise/always-return 拦下；加上一个无意义的 return 去迎合规则
    //    不如直接用 await 表达"这段是顺序逻辑"
    void (async () => {
      const options = await loadDict(dictType)
      if (options.length === 0) {
        loadedTypes.delete(dictType)
      }
    })()
  }
  return holder
}

/** 按值查字典项（用于把存储值翻译成展示文案）。 */
export function findDictOption(
  dictType: string,
  value: string | number | null | undefined
): DictOption | undefined {
  if (value === null || value === undefined) {
    return undefined
  }
  const target = String(value)
  return dictOptions(dictType).value.find((option) => option.value === target)
}

/**
 * 把字典项转成 Naive 的 select / radio 选项。
 *
 * <p>单独抽出来是因为这个转换在表单里出现频率极高，
 * 而它有个容易踩的点：Naive 的 select 选项字段是 {@code label/value}，
 * 与我们的 {@link DictOption} 恰好同名，但类型更严格（value 不能为 null）。
 * 集中一处转换，避免每个页面各写一遍 `{ label: o.label, value: o.value }`。
 */
export function toSelectOptions(dictType: string): Array<{ label: string; value: string }> {
  return dictOptions(dictType).value.map((option) => ({
    label: option.label,
    value: option.value
  }))
}

/** 清空缓存（退出登录时调用，避免下一个用户看到上一个租户的字典覆盖值）。 */
export function clearDictCache(): void {
  cache.clear()
  inflight.clear()
  loadedTypes.clear()
}
