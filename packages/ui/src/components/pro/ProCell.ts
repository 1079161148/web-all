import { h, type FunctionalComponent, type VNode } from 'vue'
import type { ProCellRender } from '../../types'
import DictTag from './DictTag.vue'

/**
 * 表格单元格渲染器。
 *
 * <h3>为什么是一个「函数式组件」</h3>
 * 表格是渲染热路径：50 行 × 10 列 = 500 次渲染。
 * <ul>
 *   <li><b>函数式</b>：没有组件实例、没有生命周期、没有响应式代理 ——
 *       它只是一次"把 props 变成 VNode"的调用</li>
 *   <li><b>内部只用 span</b>：架构规范禁止在单元格里放 Naive 组件。
 *       一个 n-tag / n-progress 实例的开销，乘以 500 就是明显卡顿的来源</li>
 * </ul>
 *
 * <h3>渲染优先级</h3>
 * <pre>
 *   renderFn（自定义函数）  >  dict（字典标签）  >  render（内置格式）
 * </pre>
 * 与 vxe-table 自身的「slot > formatter > 默认」保持一致的心智模型：
 * 越具体、越靠近调用方的声明，优先级越高。
 *
 * <p>⚠️ 只有真正需要 VNode 的列才会走到本组件（纯文本列由 vxe 直接渲染）。
 * 见 ProTable 的 {@code needsCellRender}。
 */

/**
 * 单元格渲染所需的列信息。
 *
 * <h3>为什么是独立类型，而不是直接用 `ProColumn`</h3>
 * 函数式组件（{@link ProCell}）<b>无法携带泛型参数</b>，因此它的 props 里
 * 不能出现行类型 `T`。而 `ProColumn<T>` 的 `renderFn: (row: T) => ...`
 * 需要一个 `T`。
 *
 * <h3>解法：在逆变位置擦除 T —— 零断言</h3>
 * 函数参数处于<b>逆变</b>位置，而 `never` 可赋给任何类型，因此：
 * <pre>
 *   (row: T) => VNode       可以赋给      (row: never) => VNode
 * </pre>
 * 于是页面里的 `ProColumn<UserResponse>` <b>可以直接赋给</b> { 本类型 }，
 * 既不需要 `any`，也不需要 `as` 断言，更不需要给 `ProTable` 的泛型加
 * `T extends Record<string, unknown>` 这种约束。
 *
 * <p>⚠️ 反面教材（实测踩过）：给 `ProTable` 加 `generic="T extends Record<string, unknown>"`
 * 会让全部页面编译失败，报「Index signature for type 'string' is missing in type 'MenuDTO'」——
 * 因为 <b>TypeScript 的 interface 不生成隐式索引签名</b>，
 * 后端生成的 `MenuDTO` / `UserResponse` 都不满足 `Record<string, unknown>`，
 * 泛型于是退回约束值，最终报出「request 类型不匹配」这种<b>完全指不到真因</b>的错误。
 */
export interface ProCellColumn {
  /** 字段名。 */
  key: string
  /** 字典类型编码。 */
  dict?: string
  /** 内置渲染方式。 */
  render?: ProCellRender
  /** 自定义渲染函数（T 已在逆变位置擦除）。 */
  renderFn?: (row: never) => VNode | string
}

/** 单元格渲染的 props。 */
export interface ProCellProps {
  /** 列信息。 */
  column: ProCellColumn
  /** 行数据。 */
  row: Record<string, unknown>
}

const EMPTY_PLACEHOLDER = '-'

/** 空值判定：null / undefined / 空串都算空（0 与 false 是有效值）。 */
function isEmptyValue(value: unknown): boolean {
  return value === null || value === undefined || value === ''
}

/** 字节数转可读文案。 */
function formatBytes(value: unknown): string {
  const bytes = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(bytes) || bytes < 0) {
    return EMPTY_PLACEHOLDER
  }
  if (bytes < 1024) {
    return `${bytes} B`
  }
  const units = ['KB', 'MB', 'GB', 'TB']
  let size = bytes / 1024
  let unitIndex = 0
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024
    unitIndex += 1
  }
  return `${size.toFixed(unitIndex === 0 ? 0 : 1)} ${units[unitIndex]}`
}

/**
 * 时间格式化。
 *
 * <p>用原生 {@code Intl} 而不是引 dayjs：这里只需要"把一个 ISO 时间串
 * 渲染成固定格式的文本"，属于显示逻辑的收尾，不是日期运算。
 * 为一个格式化函数引入一套日期库（且要处理它的 locale 包）不划算。
 * 需要做日期<b>运算</b>时再引 —— 那才是 dayjs 的场景。
 */
const dateTimeFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false
})

const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit'
})

function formatDateTime(value: unknown, dateOnly = false): string {
  if (isEmptyValue(value)) {
    return EMPTY_PLACEHOLDER
  }
  const date = value instanceof Date ? value : new Date(String(value))
  if (Number.isNaN(date.getTime())) {
    // 解析不了就把原值显示出来 —— 静默显示 "-" 会掩盖后端返回了非时间格式的问题
    return String(value)
  }
  return (dateOnly ? dateFormatter : dateTimeFormatter).format(date)
}

/**
 * 进度条：用两个 span 拼出来，不引组件。
 *
 * <p>条 + 数字同时给出：只画条时"65%"仅存在于悬浮提示里，
 * 而进度恰恰是要一眼扫出来的信息（表格里没人会逐格悬浮）。
 */
function renderProgress(value: unknown) {
  const raw = Number(value)
  const percent = Number.isFinite(raw) ? Math.min(100, Math.max(0, raw)) : 0
  return h('span', { class: 'pro-cell-progress-wrap', title: `${percent}%` }, [
    h('span', { class: 'pro-cell-progress' }, [
      h('span', {
        class: 'pro-cell-progress__inner',
        style: { width: `${percent}%` }
      })
    ]),
    h('span', { class: 'pro-cell-progress__text' }, `${percent}%`)
  ])
}

export const ProCell: FunctionalComponent<ProCellProps> = (props) => {
  const { column, row } = props
  const value = row[column.key]

  // ---- 1. 自定义渲染（最高优先级）----
  if (column.renderFn) {
    // 断言把 `(row: never) => VNode` 还原成"接受行数据"的形态。
    // 运行时安全：传进去的正是 ProTable 从 vxe 拿到的那一行本身，
    // 也就是 renderFn 当初被定义时期望的 T 实例。
    const renderFn = column.renderFn as (row: Record<string, unknown>) => VNode | string
    return renderFn(row)
  }

  // ---- 2. 字典 ----
  if (column.dict) {
    return h(DictTag, { dictType: column.dict, value: value as string | number | null })
  }

  // ---- 3. 内置格式 ----
  switch (column.render) {
    case 'datetime':
      return formatDateTime(value)
    case 'bytes':
      return formatBytes(value)
    case 'progress':
      return isEmptyValue(value) ? EMPTY_PLACEHOLDER : renderProgress(value)
    case 'link':
      return isEmptyValue(value)
        ? EMPTY_PLACEHOLDER
        : h('span', { class: 'pro-cell-link' }, String(value))
    case 'tag':
      return isEmptyValue(value)
        ? EMPTY_PLACEHOLDER
        : h('span', { class: 'pro-cell-tag' }, String(value))
    case 'dict-tag':
      // 声明了 render: 'dict-tag' 但没给 dict 属于配置错误。
      // 不抛错（会把整个表格打挂），而是给出可见的提示 ——
      // 让配置错误在界面上自己暴露，比在生产日志里出现一次异常好。
      return column.dict
        ? h(DictTag, { dictType: column.dict, value: value as string | number | null })
        : h('span', { class: 'pro-cell-config-error' }, '缺少 dict 配置')
    default:
      return isEmptyValue(value) ? EMPTY_PLACEHOLDER : String(value)
  }
}

ProCell.displayName = 'ProCell'
