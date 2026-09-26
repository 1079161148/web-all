import type { Component, VNode } from 'vue'

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
  /**
   * 树表格的展开列。
   *
   * <p>声明后该列渲染展开/收起按钮与层级缩进（vxe {@code treeNode} 的透传，
   * 数据侧配合 {@code gridProps.treeConfig} 与 {@link flatToTableTree} 产出的
   * 带_children行使用）。一张树表格只有一列应声明它 —— 通常是首列。
   */
  treeNode?: boolean
  /** 是否默认隐藏。 */
  hidden?: boolean
  /** 是否禁止用户在「列设置」中切换显隐。 */
  disableColumnSetting?: boolean

  /** 声明后，搜索区自动生成该字段的控件。 */
  search?: ProSearchType
  /**
   * 搜索区提交给后端的<b>参数名</b>，默认取列 key。
   *
   * <p>存在的原因：展示字段与查询字段常常不同名 —— 例如「套餐」列展示的是
   * {@code planName}（企业版），而接口的筛选参数是 {@code planCode}（ENTERPRISE）。
   * 不声明它时搜索条件会以列 key 提交，后端收不到 → <b>筛选静默失效</b>
   * （页面不报错、列表不变化，最难排查的一类问题）。
   */
  searchParam?: string
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

/**
 * 表格列布局偏好（ProTable 持久化的数据形状）。
 *
 * <p>持久化的只有<b>布局</b>：列顺序、隐藏列、列宽、每页条数。
 * 排序/筛选/当前页是"这次在看什么"，不是"我喜欢怎么摆"，刻意不存。
 */
export interface ProTablePreference {
  /** 列顺序（按列 field）。未提及的列保持原相对顺序。 */
  order?: string[]
  /** 被隐藏的列 field。 */
  hidden?: string[]
  /** 列宽覆盖（像素）。 */
  widths?: Record<string, number>
  /** 每页条数。 */
  pageSize?: number
}

/**
 * 偏好持久化适配器（依赖倒置）。
 *
 * <p>UI 包不关心偏好存在哪 —— 应用层用服务端偏好 API 实现它并传入，
 * 本包只负责"何时读、何时写、读写什么形状"。这也让测试可以注入内存实现。
 */
export interface ProTablePersistence {
  load: (key: string) => Promise<unknown>
  save: (key: string, value: unknown) => Promise<void>
}

/**
 * 批量操作按钮（多选后出现）。
 *
 * <p>声明了它，表格才启用多选列 —— 这是刻意的：
 * 「多选」本身没有意义，只有存在批量操作时勾选框才有用。
 * 让多选**由批量操作派生**而不是单独一个开关，能避免出现
 * 「能勾选但没有任何批量动作」的空转交互。
 */
export interface ProBatchAction<T = Record<string, unknown>> {
  key: string
  label: string
  /** 权限码。无权限时按钮不渲染。 */
  permission?: string
  danger?: boolean
  /** 二次确认文案。选择多于 1 行时自动在文案后补充数量提示。 */
  confirm?: string | ((rows: T[]) => string)
  /** 图标（@vicons 组件）。 */
  icon?: Component
  onClick: (rows: T[]) => void | Promise<void>
}

/**
 * ProTable 通过 ref 暴露的方法。
 *
 * <p>之所以显式导出：父组件在模板里用 `ref` 时，TS 默认推断出的类型是
 * `any` 或 `ComponentPublicInstance`，拿不到补全。有了它父组件可以写
 * `ref<ProTableExpose<User>>()` 从而获得完整提示。
 */
export interface ProTableExpose<T = Record<string, unknown>> {
  /** 按主键设置勾选（回选 / 初始化）。单选模式下只取第一个命中的主键。 */
  setCheckedKeys: (keys: Array<string | number>) => Promise<void>
  /**
   * 重载：回到第一页并重新请求。
   *
   * <p>语义："查询条件变了" —— 新增、搜索、筛选后调用。
   * 当前页很可能已不存在（total 变小），留在原页只会看到空列表。
   */
  reload: () => Promise<void>
  /**
   * 刷新：保持当前页重新请求。
   *
   * <p>语义："数据变了但条件没变" —— 编辑保存、状态变更、删除后调用。
   * 用户在第 3 页改完数据，刷新完跳回第 1 页是会被骂的。
   * 若当前页已越界（如删除了最后一页的唯一一条），组件会自动回退到有效页。
   */
  refresh: () => Promise<void>
  /** 仅重新请求当前页，不重置页码（与 refresh 等价，保留给习惯旧 API 的调用方）。 */
  load: () => Promise<void>
  /** 当前勾选的行。 */
  getSelection: () => T[]
  /** 清空勾选。 */
  clearSelection: () => void
  /** 当前查询参数（含分页与搜索条件），用于"把当前视图的条件导出"。 */
  getQuery: () => ProTableQuery
}

/**
 * 搜索控件的选项。
 *
 * <p>{@code value} 刻意不含 {@code boolean}：选项值最终要参与 URL query 与
 * 请求参数序列化，布尔在其中会被转成字符串，导致"传了 true 却匹配不上"。
 * 需要布尔语义时用字典的 {@code '1' / '0'}（如 {@code sys_yes_no}）——
 * 这也是后端契约的实际形态。
 */
export interface ProSearchOption {
  label: string
  value: string | number
}

/**
 * 搜索项（由列上的 `search` 声明自动派生，也可直接传入 ProSearch）。
 *
 * <p>之所以让 ProSearch 既能"从列派生"又能"独立声明"：
 * 绝大多数页面两者的内容是一致的（搜索字段就是表格列），派生可以少写一份；
 * 但存在"搜索字段不是表格列"的场景（如按创建时间区间搜索，而表格只显示创建日期），
 * 此时需要独立声明。<b>默认派生，例外显式覆盖。</b>
 */
export interface ProSearchItem {
  /** 参数名。 */
  key: string
  /** 标签文案。 */
  label: string
  /** 控件类型。 */
  type: ProSearchType
  placeholder?: string
  /** 字典编码：声明后下拉选项自动来自字典。 */
  dict?: string
  /** 静态选项（与 dict 二选一，dict 优先）。 */
  options?: ProSearchOption[]
  /** 默认值。 */
  defaultValue?: unknown
  /**
   * 选项是否可多选。多选时值以数组提交，后端需按数组解析。
   */
  multiple?: boolean
  /**
   * 是否"高级"条件：默认折叠，点「展开」后才出现。
   *
   * <p>默认折叠的条件不计入"已生效条件数"角标 ——
   * 否则角标会一直亮着，失去提示作用。
   */
  advanced?: boolean
}

/** ProSearch 的暴露方法。 */
export interface ProSearchExpose {
  /** 触发一次搜索（重置到第一页）。 */
  search: () => void
  /** 重置全部条件并触发搜索。 */
  reset: () => void
  /** 当前条件值。 */
  getModel: () => Record<string, unknown>
}

// =====================================================================
// ProForm
// =====================================================================

/**
 * 表单控件类型。
 *
 * <p>这些是**业务语义名**，而不是 form-create 的原始控件名。
 * 映射在 ProForm 内完成（见 {@code CONTROL_TYPES}）——
 * 这样业务代码里看不到内核的用词，换内核时只改映射表。
 */
export type ProFormControlType =
  | 'input'
  | 'textarea'
  | 'password'
  | 'number'
  | 'select'
  | 'multi-select'
  | 'radio'
  | 'checkbox'
  | 'date'
  | 'datetime'
  | 'date-range'
  | 'time'
  | 'switch'
  | 'slider'
  | 'rate'
  | 'color'
  | 'tree'
  | 'treeSelect'
  | 'cascader'
  | 'upload'

/** 表单字段定义。 */
export interface ProFormItem {
  /** 字段名，同时作为提交数据的 key。 */
  field: string
  /** 标签文案。 */
  title: string
  /** 控件类型。默认 `input`。 */
  type?: ProFormControlType
  /** 字典编码：声明后选项自动来自字典（与 `options` 二选一，dict 优先）。 */
  dict?: string
  /**
   * 静态选项。
   *
   * <p>select / radio / checkbox 等平铺控件用 {@link ProSearchOption}（label/value）；
   * treeSelect / cascader 等层级控件用 {@link ProTreeNode}（key/label/children）。
   * 两者结构不同，因此是联合类型而不是一个"都塞得下"的宽接口。
   */
  options?: ProSearchOption[] | ProTreeNode[]
  /** 是否必填。 */
  required?: boolean
  /** 自定义校验消息（`required` 为 true 时的提示语）。 */
  message?: string
  /** 占位提示。 */
  placeholder?: string
  /** 默认值。 */
  value?: unknown
  /** 字段下方的说明文字。 */
  tip?: string
  /** 栅格占宽（1~24）。不传则按 `cols` 自动分配。 */
  span?: number
  disabled?: boolean
  /** 权限码：当前用户无此权限时该字段不渲染。 */
  permission?: string
  /** 是否为「高级」字段：默认折叠到「更多」分组内。 */
  advanced?: boolean
  /** 透传给底层控件的 props（逃生舱）。 */
  props?: Record<string, unknown>
}

/** 表单分组（复杂表单用）。 */
export interface ProFormGroup {
  /** 分组标题。 */
  title: string
  /** 分组内字段。 */
  items: ProFormItem[]
  /** 是否默认折叠。 */
  collapsed?: boolean
}

/** ProForm 的暴露方法。 */
export interface ProFormExpose {
  /** 是否通过校验（不通过时不会抛出，便于调用方分支处理）。 */
  validate: () => Promise<boolean>
  /** 取当前表单值。 */
  getValues: () => Record<string, unknown>
  /** 批量赋值（编辑回显用）。 */
  setValues: (values: Record<string, unknown>) => void
  /** 重置为初始值。 */
  reset: () => void
  /** 触发提交（校验 → 调用 submit → 抛成功/失败事件）。 */
  submit: () => Promise<void>
  /**
   * 动态追加字段（动态增减行）。
   *
   * <p>刻意只做"把内核的 append 暴露出来"而不是自研一个数组控件：
   * 动态行的难点在值同步与校验状态维护，内核已经处理好了，
   * 再包一层只会把它的能力挡住。
   */
  appendRule: (item: ProFormItem, afterField?: string) => void
  /** 动态移除字段。 */
  removeField: (field: string) => void
}

/** 行操作。 */
export interface ProRowAction<T = Record<string, unknown>> {
  key: string
  /**
   * 按钮文案。传函数时按行求值。
   *
   * <p>典型场景是「状态开关」：同一行操作在启用态叫「禁用」、在禁用态叫「启用」，
   * 文案必须跟着行状态走 —— 写成固定的「启用/禁用」会让用户分不清
   * 按钮描述的是<b>当前状态</b>还是<b>点击后的动作</b>。
   */
  label: string | ((row: T) => string)
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

// =====================================================================
// ProCommand
// =====================================================================

/**
 * 命令面板的一条命令。
 *
 * <p>{@code keywords} 与 {@code label} 分开是有意的：
 * 用户看到的文案与用户会输入的词往往不一致
 * （显示「新建用户」，但会输入"add" / "create" / "tianjia"）。
 * 塞进 label 会让面板里显示一串别名，拆开则两者都能用。
 */
export interface ProCommandItem {
  /** 唯一标识。 */
  key: string
  /** 显示文案。 */
  label: string
  /** 分组标题。同组连续展示。 */
  group?: string
  /** 右侧补充说明（如快捷键、所属模块）。 */
  hint?: string
  /** 图标组件。 */
  icon?: Component
  /** 额外检索关键词（不显示）。 */
  keywords?: string[]
  /**
   * 是否禁用。
   *
   * <p>禁用项<b>照常显示但不参与键盘导航与回车选中</b> ——
   * 直接过滤掉会让人以为"这个功能不存在"，而不是"当前不可用"，
   * 而这两者的排查方向完全不同。
   */
  disabled?: boolean
}

/** 选中命令时抛出的载荷。 */
export interface ProCommandSelectPayload {
  /** 命令标识。 */
  key: string
  /** 完整命令项（调用方常需要 group / hint 等附加信息）。 */
  item: ProCommandItem
}

// =====================================================================
// ProExcel
// =====================================================================

/**
 * 导入/导出的列定义。
 *
 * <p>与 {@link ProColumn} 的关系：字段名刻意保持一致（`key` / `title`），
 * 这样同一份数据既能喂给表格也能喂给导入导出，不需要两套心智翻译。
 */
export interface ProExcelColumn<T = Record<string, unknown>> {
  /** 字段名（行对象的 key）。 */
  key: string
  /** 列标题（表头，同时用于导入时按标题匹配）。 */
  title: string
  /** 是否必填。 */
  required?: boolean
  /**
   * 单元格校验。
   *
   * <p>返回字符串 = 错误原因；返回 {@code null} / {@code undefined} 或空串 = 通过。
   * 之所以让<b>校验返回文案</b>而不是布尔值：错误原因最终要写回 Excel 给用户看，
   * 若只返回布尔，调用方还得在别处再维护一份"字段 → 原因"的映射，
   * 两份信息一定会漂移。
   */
  validate?: (value: unknown, row: T) => string | null | undefined
  /** 导出时的取值（默认取 {@code row[key]}）。 */
  format?: (row: T) => string | number | null | undefined
  /** 列宽（字符数）。不传按标题长度估算。 */
  width?: number
  /** 是否只用于导入（导出时不出现），如"错误原因"这类提示列。 */
  importOnly?: boolean
}

/**
 * 一行的校验结果。
 *
 * <p>{@code rowIndex} 是<b>数据行序号</b>（从 1 开始，不含表头）——
 * 而不是 Excel 里的物理行号。做这个区分是有意的：
 * 用户看到的 Excel 行号 = 数据行序号 + 1（表头占一行），
 * 若直接把物理行号抛出去，提示"第 3 行有错"会让用户去看 Excel 的第 3 行（实际是第 2 条数据），
 * <b>于是改错了行</b>。因此对外统一用"数据行序号"，并在 UI 上说明换算关系。
 */
export interface ProExcelRowError<T = Record<string, unknown>> {
  /** 数据行序号，从 1 开始。 */
  rowIndex: number
  /** 该行的原始数据（用于回写文件）。 */
  row?: T
  /** 错误原因。服务端返回多个错误时可用 {@code '；'} 连接。 */
  message: string
}

/** ProExcel 通过 ref 暴露的方法。 */
export interface ProExcelExpose<T = Record<string, unknown>> {
  /** 下载仅含表头的导入模板。 */
  downloadTemplate: () => Promise<void>
  /** 导出数据行（可选附带错误原因列，见实现说明）。 */
  exportRows: (rows: T[]) => Promise<void>
  /**
   * 把校验失败的行连同错误原因导出，供用户修正后重新上传。
   *
   * <p>这是导入向导真正的价值所在：只报"第 3 行邮箱格式不对"，
   * 用户还得回去在大表里找第 3 行；而给一份<b>只含出错行、并多一列写明原因</b>的文件，
   * 改完直接重传即可。
   */
  downloadErrorRows: (errors: Array<ProExcelRowError<T>>) => Promise<void>
}

// =====================================================================
// ProEditor
// =====================================================================

/** 图片/附件上传成功后的引用信息。 */
export interface ProEditorUploadResult {
  /** 可访问地址。 */
  url: string
  /** 替代文本（无障碍与图片加载失败时显示）。不传则用文件名。 */
  alt?: string
  /** 点击跳转地址。不传则指向 url。 */
  href?: string
}

/**
 * 上传动作。
 *
 * <p>本包只负责「什么时候需要上传」，<b>「上传到哪」由应用决定</b> ——
 * 接口地址、租户路径、OSS 直传签名都是业务决策。
 * 与字典/权限的处理方式一致：组件库给注入点，不给实现。
 *
 * <p>应用实现里可以从 {@code registry/upload.ts} 取鉴权头，
 * 这样"上传鉴权"在全项目只有一处定义。
 */
export type ProEditorUploadHandler = (file: File) => Promise<ProEditorUploadResult>

// =====================================================================
// ProLayout
// =====================================================================

/**
 * 布局菜单项。
 *
 * <h3>为什么 key 建议直接用路由的完整路径</h3>
 * 因为菜单项要同时承担<i>标识</i>与<i>导航目标</i>两个角色。
 * 拆成 {@code id} + {@code path} 的话，每次点击都要先查 id → path，
 * 而查表失败就变成"点了没反应"。
 * 直接用路径作 key，点击时把 key 原样抛给路由即可。
 */
export interface ProLayoutMenu {
  /** 唯一键。建议用完整路由路径（见上方说明）。 */
  key: string
  label: string
  /**
   * 图标。两种形态：
   * <ul>
   *   <li><b>字符串</b>——后端菜单存的图标名，由 ProIcon 经 {@code registry/icon}
   *       注册表解析（应用启动时 registerIcons 注入 @vicons 图标集）；</li>
   *   <li><b>组件</b>——纯前端场景直接传图标组件，不走注册表。</li>
   * </ul>
   * 名字未注册时 ProIcon 降级为名字首字形，不静默空白。
   */
  icon?: string | Component
  children?: ProLayoutMenu[]
}

/**
 * 标签栏页签。
 *
 * <p>标签栏的状态由应用维护（它是"用户访问过哪些页面"的记录，
 * 属于应用级会话状态），本组件只负责渲染与抛出交互事件。
 */
export interface ProLayoutTab {
  /** 与菜单 key 一致。 */
  key: string
  label: string
  /**
   * 是否固定（不可关闭），如首页。
   *
   * <p>没有固定页签时，用户可以关掉所有页签 —— 此时界面上没有任何可点的地方，
   * 只剩空白。至少留一个固定页签能避免这个死角。
   */
  affix?: boolean
}

// =====================================================================
// 树
// =====================================================================

/**
 * 树节点。字段名与 Naive 的 {@code TreeOption} 对齐。
 *
 * <h3>为什么放在这里而不是某个组件文件里</h3>
 * 它是 {@code ProTree}（树控件）与 {@code ProTreeSelect}（树选择）
 * 共用的<b>数据契约</b>。原先定义在 ProTreeSelect.vue 里，
 * 后者要共用就只能"组件 import 组件"——那会让两个组件之间出现
 * 一个与渲染无关的文件依赖，也让"节点长什么样"这件事的归属变得含糊。
 *
 * <p>统一放这里与其它 Pro* 类型（{@code ProColumn} / {@code ProFormItem}…）
 * 一致：<b>跨组件共用的形状属于契约，不属于某一个组件。</b>
 *
 * <p>保留 {@code [key: string]: unknown} 索引签名是有意的：
 * 业务常需要挂额外字段（{@code code} / {@code type} / {@code perms}…），
 * 而这些都是透传的，组件不解释它们。
 */
export interface ProTreeNode {
  label: string
  key: string | number
  children?: ProTreeNode[]
  disabled?: boolean
  /** 是否叶子（懒加载时用来决定要不要显示展开箭头）。 */
  isLeaf?: boolean
  [key: string]: unknown
}

/**
 * 树预设。
 *
 * <p>预设只做一件事：登记"用哪个字段当标题"（部门的 {@code deptName}、
 * 菜单的 {@code menuName}）以及各自的禁用规则。
 * 树的其余部分（{@code id / parentId / sort / status}）两个预设完全一致，
 * 这也是它们能共用同一份建树逻辑的原因。
 */
export type ProTreePreset = 'dept' | 'menu'

/**
 * 页签右键动作。
 *
 * <p>由 ProLayout 定义语义、抛出事件，<b>执行在应用侧</b>：
 * 「刷新」与「关闭后跳转」都涉及路由与页面缓存，内核不碰路由。
 */
export type ProLayoutTabAction =
  /** 重新挂载该页签对应的页面（含缓存页面，会真正重新创建实例）。 */
  | 'refresh'
  /** 关闭该页签（固定页签无效）。 */
  | 'close'
  /** 只保留该页签与固定页签。 */
  | 'close-others'
  | 'close-left'
  | 'close-right'
  /** 只保留固定页签。 */
  | 'close-all'

/**
 * 拖拽落点信息。
 *
 * <p>刻意自定义而不直接用内核的 {@code TreeDropInfo}：
 * 业务代码不应该看见内核的类型与用词（同理见 {@code ProFormControlType}
 * 的"业务语义名"原则）。换内核时只需改组件内部的映射。
 *
 * <h3>⚠️ dragNode 为什么是可空的</h3>
 * 内核把一次拖拽拆给了两个回调，而它们的载荷<b>不一样</b>：
 * <pre>
 *   onDrop     → { event, node, dragNode, dropPosition }   ← 有 dragNode
 *   allowDrop  → { dropPosition, node, phase }             ← 没有 dragNode
 * </pre>
 * 而拖拽校验恰恰需要两个节点都在场 ——
 * 例如"不能把部门移动到自己的子部门下"必须同时知道"谁"和"到哪"。
 * 因此 {@code ProTree} 在 {@code dragstart} 记录源节点，再合并进两种回调的载荷。
 *
 * <p>声明为可空是<b>不撒谎</b>：若内核没有报告 dragstart（正常流程中不会发生），
 * 它确实是 null。
 */
export interface ProTreeDropInfo {
  /** 被拖拽的节点。 */
  dragNode: ProTreeNode | null
  /** 落点目标节点。 */
  dropNode: ProTreeNode
  /** 落点相对目标的位置。 */
  position: 'before' | 'inside' | 'after'
}

// =====================================================================
// ProDescriptions
// =====================================================================

/**
 * 详情项定义。
 *
 * <p>与 {@link ProColumn} 的关系：非空值的渲染走的是同一套能力
 * （{@code dict} / {@code render} / {@code renderFn}），只是没有
 * 表格专有的字段（宽度、排序、搜索）。
 * <b>刻意保持字段名一致</b> —— 同一份数据既能喂给 ProTable 也能喂给
 * ProDescriptions 时，不需要在两套字段名之间做心智翻译。
 */
export interface ProDescriptionItem<T = Record<string, unknown>> {
  /** 字段名。 */
  key: string
  /** 标签文案。 */
  label: string
  /** 字典编码：声明后值自动翻译为中文标签。 */
  dict?: string
  /** 内置渲染方式。 */
  render?: ProCellRender
  /** 自定义渲染函数（优先级最高）。 */
  renderFn?: (data: T) => VNode | string
  /** 权限码：无权限时该项不渲染。 */
  permission?: string
  /**
   * 无权限时的替代文案。
   *
   * <p>不声明 = 整项隐藏（默认）；声明后该项保留，标签可见、值替换为这段文案。
   * 用于"用户需要知道有这个字段，但看不到值"的场景。
   */
  deniedText?: string
  /**
   * 占宽，单位是**列**（{@code columns} 的一份），<b>不是 24 栅格</b>。
   *
   * <p>与 {@link ProFormItem.span} 的 24 栅格制不同 —— 这是 n-descriptions
   * 与 n-grid 的固有差异，如实透传而非在中间造一层换算。
   */
  span?: number
  /**
   * 值为空时是否隐藏该项。
   *
   * <p>默认 {@code false}（显示占位符）。保持默认的理由：
   * 详情页的字段构成本身是信息，静默少一行会让人怀疑"是不是没查到"。
   * 字段很多、空值很多时才建议打开。
   */
  hideWhenEmpty?: boolean
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
