import type { VNode } from 'vue'

/**
 * 页面状态矩阵（设计文档 §11.9）。
 *
 * 大多数后台系统只做了 `ready` 一种状态，这正是「不好用」的主要来源之一：
 * 加载时一片空白、无数据时只显示"暂无数据"、出错时白屏。
 */
export type AppStateStatus = 'loading' | 'ready' | 'empty' | 'error' | 'denied'

/** 搜索控件类型。声明后搜索区自动生成对应控件。 */
export type ProSearchType =
  | 'input'
  | 'textarea'
  | 'number'
  | 'select'
  | 'date'
  | 'date-range'
  | 'datetime-range'

/** 单元格渲染类型。 */
export type ProCellRender =
  | 'text'
  | 'dict-tag'
  | 'tag'
  | 'link'
  | 'progress'
  | 'datetime'
  | 'bytes'

/** 列定义：在 vxe-table 列的基础上扩展业务语义。 */
export interface ProColumn<T = Record<string, unknown>> {
  /** 字段名，同时作为搜索参数名与排序字段名。 */
  key: string
  /** 列标题。 */
  title: string
  /** 宽度。 */
  width?: number | string
  /** 最小宽度，列多时用于横向滚动。 */
  minWidth?: number | string
  /** 固定列。 */
  fixed?: 'left' | 'right'
  /** 是否可排序（服务端排序）。 */
  sortable?: boolean
  /** 是否默认隐藏。 */
  hidden?: boolean
  /** 是否禁止用户在「列设置」中切换显隐。 */
  disableColumnSetting?: boolean

  /** 声明后，搜索区自动生成该字段的控件。 */
  search?: ProSearchType
  /** 搜索控件的占位提示。 */
  searchPlaceholder?: string
  /** select 类搜索控件的选项来源（字典编码或静态选项）。 */
  options?: Array<{ label: string; value: string | number }>

  /**
   * 字典编码。
   *
   * 声明后：单元格自动按字典渲染为标签、搜索区自动变为下拉、
   * **导出时自动翻译**（避免"页面显示中文、导出是码值"）。
   */
  dict?: string

  /** 权限码：当前用户无此权限时该列隐藏。 */
  permission?: string

  /** 渲染方式。 */
  render?: ProCellRender
  /** 自定义渲染函数（优先级高于 render）。 */
  renderFn?: (row: T) => VNode | string

  /** 导出时是否包含该列。 */
  exportable?: boolean
}

/** 工具栏标准动作。 */
export type ProToolbarKey = 'create' | 'export' | 'refresh' | 'columnSetting' | 'density'

/** 行操作。 */
export interface ProRowAction<T = Record<string, unknown>> {
  key: string
  label: string
  /** 权限码。 */
  permission?: string
  /** 是否为危险操作（红色显示 + 二次确认）。 */
  danger?: boolean
  /** 二次确认文案；为空则不确认。 */
  confirm?: string | ((row: T) => string)
  /** 是否禁用。 */
  disabled?: boolean | ((row: T) => boolean)
  onClick: (row: T) => void | Promise<void>
}

/** 分页参数（与后端 PageQuery 对齐）。 */
export interface ProTableQuery {
  page: number
  size: number
  sortField?: string
  sortOrder?: 'asc' | 'desc'
  [key: string]: unknown
}

/** 分页结果（与后端 PageResult 对齐）。 */
export interface ProTablePage<T> {
  records: T[]
  total: number
  page: number
  size: number
}

/** ProTable 的请求函数签名。 */
export type ProTableRequest<T> = (query: ProTableQuery) => Promise<ProTablePage<T>>
