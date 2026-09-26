// oxlint 的 typescript/triple-slash-reference 规则建议改用 import，但这里**必须**用 reference：
// 该规则针对的是「用 reference 引入类型定义」这种可被 import 取代的用法；
// 而本行的目的是把一个**环境模块声明文件**（declare module 'vxe-table/es/grid'）
// 加入消费方的 TS 程序 —— 环境声明只有在文件本身属于程序时才生效，
// 改用 import 会让该文件变成模块，其中的 declare module 从"环境声明"降级为
// "模块增强"，而增强一个无法解析的路径会直接报错。
// eslint-disable-next-line typescript/triple-slash-reference
/// <reference path="./shims-vxe.d.ts" />
// eslint-disable-next-line typescript/triple-slash-reference
/// <reference path="./shims-wangeditor.d.ts" />

import { defineAsyncComponent } from 'vue'

/**
 * @admin/ui 对外入口 —— 业务代码引入 UI 能力的唯一出口（设计文档 §11.2）。
 *
 * <h3>⚠️ 顶部那行三斜线 reference 不能删</h3>
 * `shims-vxe.d.ts` 里是 vxe 深路径导入的环境声明（`declare module 'vxe-table/es/grid'`）。
 * 环境声明只有在<b>该 .d.ts 本身属于当前 TS 程序</b>时才生效 ——
 * 而 tsconfig 的 include 是各包独立的：apps/admin 通过 paths 把 `@admin/ui`
 * 解析到本文件，却<b>不会</b>自动把 packages/ui/src 下的其它文件纳入程序。
 *
 * <p>现象（实测）：packages/ui 自己的 typecheck 通过，但 apps/admin 的类型检查与构建
 * 全部报 TS7016「Could not find a declaration file for module 'vxe-table/es/grid'」——
 * 同一个问题在两个包里表现不一致，很容易被误判成缓存或构建顺序问题。
 *
 * <p>三斜线 reference 是 TS 的标准机制：它把目标文件显式加入程序，
 * 因此无论哪个包引用本入口，声明都会一起被加载。
 *
 * <h3>为什么存在这一层</h3>
 * Naive UI / vxe-table / form-create 都只允许在本包内被引入。
 * 业务代码（`apps/**`、`packages/{api,theme,...}`）必须从这里取用，
 * 由 Oxlint 的 `no-restricted-imports` 规则强制（见 packages/config/oxlint.json）。
 *
 * <p>代价是薄薄一层封装；收益是<b>UI 库可替换</b>：一旦某个内核的维护活跃度
 * 下降到不可接受，只需替换本包内的适配层与组件实现，业务代码零改动。
 *
 * <h3>内容分五部分</h3>
 * <ul>
 *   <li><b>基础元素</b>（`./naive`）—— 原样透传，零封装</li>
 *   <li><b>Pro 组件</b>（`./components/pro`）—— 加约定，如 ProTable / ProForm</li>
 *   <li><b>状态矩阵</b>（`./components/state`）—— 加载/空/错误/无权/骨架</li>
 *   <li><b>适配层</b>（`./adapters`）—— 全局反馈、主题桥接</li>
 *   <li><b>注册表</b>（`./registry`）—— 字典与权限的数据来源注入点</li>
 * </ul>
 *
 * <h3>⚠️ 应用启动必须做两件接线（否则功能降级）</h3>
 * <pre>{@code
 * import { installAdminUi, setDictResolver, setPermissionResolver } from '@admin/ui'
 *
 * installAdminUi(app)                        // 注册三个内核
 * setDictResolver(dictOptions)               // 字典从哪来 —— 不接线则字典列显示码值
 * setPermissionResolver(hasPermission)       // 权限怎么判 —— 不接线则所有按钮都显示
 * }</pre>
 */

// ---- 安装插件 ----
export { installAdminUi } from './plugin'

// ---- 基础元素（透传 Naive UI，零封装） ----
export * from './naive'

// ---- 适配层 ----
export { feedback, setupFeedbackTheme } from './adapters/feedback'

// ---- 内核按需加载 ----
//
// ⚠️ 这里**不能**写 `export { installVxe } from './adapters/vxe'` 这类静态再导出。
// 静态再导出会把内核模块拉回入口 chunk 的静态依赖图，使 vxe 虽被切成独立文件
// 却仍被 index.html 以 `<link rel="modulepreload">` 预加载 ——
// 切分了却不省字节，而且不报任何错（实测踩过，详见 adapters/lazy.ts 的说明）。
//
// ProTable / ProForm 内部已自动按需加载内核；下面这些导出供应用
// 显式预加载，或在主题切换时同步内核主题。
export {
  ensureCron,
  ensureEcharts,
  ensureEditor,
  ensureExcel,
  ensureFormCreate,
  ensureFuse,
  ensureVxe,
  getFormCreate,
  installFormCreate,
  installVxe,
  isCronReady,
  isEchartsReady,
  isFormCreateReady,
  isVxeReady,
  syncVxeTheme
} from './adapters/lazy'

// ---- 注册表：数据来源注入点 ----
export {
  dictLabel,
  findDictOption,
  getDictOptions,
  hasDictResolver,
  setDictResolver
} from './registry/dict'
export { hasAnyPermission, hasPermission, setPermissionResolver } from './registry/permission'
export { getUploadAuthHeaders, setUploadAuthResolver } from './registry/upload'
export type { UploadAuthResolver } from './registry/upload'
export { hasIcon, hasIconRegistry, registerIcons, resolveIcon } from './registry/icon'
export type { IconRegistration } from './registry/icon'

// ---- 类型 ----
export type {
  AppStateStatus,
  ProBatchAction,
  ProCellRender,
  ProColumn,
  ProDescriptionItem,
  ProEditorUploadHandler,
  ProEditorUploadResult,
  ProExcelColumn,
  ProExcelExpose,
  ProExcelRowError,
  ProCommandItem,
  ProCommandSelectPayload,
  ProFormControlType,
  ProFormExpose,
  ProFormGroup,
  ProFormItem,
  ProLayoutMenu,
  ProLayoutTab,
  ProLayoutTabAction,
  ProRowAction,
  ProSearchExpose,
  ProSearchItem,
  ProSearchOption,
  ProSearchType,
  ProTableExpose,
  ProTablePage,
  ProTablePersistence,
  ProTablePreference,
  ProTableQuery,
  ProTableRequest,
  ProToolbarKey,
  ProTreeDropInfo,
  ProTreeNode,
  ProTreePreset
} from './types'
export type { DictOption, DictResolver } from './registry/dict'
export type { PermissionResolver } from './registry/permission'
// 类型再导出（编译期擦除，不产生运行时依赖）：应用层写图表 option 时
// 不需要把 echarts 声明为自己的依赖 —— 那会破坏「第三方库只在 @admin/ui 引入」的隔离
export type { EChartsOption } from 'echarts'
export type {
  FormCreateApi,
  FormCreateOptions,
  FormCreateRule
} from './adapters/form-create'
export type {
  VxeColumnProps,
  VxeGridConstructor,
  VxeGridDefines,
  VxeGridProps,
  VxeTableDefines,
  VxeTablePropTypes
} from './adapters/vxe'

// ---- 全局配置容器 ----
export { default as AdminConfigProvider } from './components/admin/AdminConfigProvider.vue'

// ---- 状态矩阵 ----
export { default as AppState } from './components/state/AppState.vue'

// ---- Pro 组件 ----
export { default as ProTable } from './components/pro/ProTable.vue'
export { default as ProSearch } from './components/pro/ProSearch.vue'
export { default as ProForm } from './components/pro/ProForm.vue'
export { default as ProModal } from './components/pro/ProModal.vue'
export { default as ProDescriptions } from './components/pro/ProDescriptions.vue'
export { default as ProUpload } from './components/pro/ProUpload.vue'
export { default as ProTreeSelect } from './components/pro/ProTreeSelect.vue'
export { default as ProTree } from './components/pro/ProTree.vue'
export { default as AuthButton } from './components/pro/AuthButton.vue'
export { default as IconPicker } from './components/pro/IconPicker.vue'
export { default as PageContainer } from './components/pro/PageContainer.vue'
export { default as ProLayout } from './components/pro/ProLayout.vue'
export { default as ProMenu } from './components/pro/ProMenu.vue'
export { default as ProChart } from './components/pro/ProChart.vue'
export { default as ProCron } from './components/pro/ProCron.vue'
export { default as ProEditor } from './components/pro/ProEditor.vue'
export { default as ProExcel } from './components/pro/ProExcel.vue'
export { default as ProCommand } from './components/pro/ProCommand.vue'
export { default as ProIcon } from './components/pro/ProIcon.vue'
/**
 * 二维码 / 条形码：**异步组件**（按需加载，不进首屏 chunk）。
 *
 * <p>这两个组件各自带一个不轻的第三方库（qrcode / jsbarcode），而它们是
 * "少数页面偶尔才用"的能力 —— 静态导出等于让所有用户为主页用不到的功能买单
 * （实测：静态导出直接把首屏体积顶穿门禁）。改用 defineAsyncComponent 后，
 * 组件本体与库都只在首次使用时拉取；Vue 会从 loader 推导出组件的 props 类型，
 * 消费方的类型提示不受影响。
 *
 * <p>类型（QrcodeLevel / BarcodeFormat）仍是静态导出 —— 类型只在编译期存在，
 * 不产生运行时代码，两处导出并不矛盾。
 */
export const ProQrcode = defineAsyncComponent(() => import('./components/pro/ProQrcode.vue'))
export const ProBarcode = defineAsyncComponent(() => import('./components/pro/ProBarcode.vue'))
export { default as DictTag } from './components/pro/DictTag.vue'
export { default as DictSelect } from './components/pro/DictSelect.vue'
export { ProCell } from './components/pro/ProCell'
export type { ProCellColumn, ProCellProps } from './components/pro/ProCell'
export type { ProIconOption } from './components/pro/IconPicker.vue'
export type { QrcodeLevel } from './components/pro/ProQrcode.vue'
export type { BarcodeFormat } from './components/pro/ProBarcode.vue'
export type { ProLayoutMode, ProLayoutTabStyle } from './components/pro/ProLayout.vue'

// ---- 树：共享的建树与预设 ----
//
// 导出它们是刻意的：树的用途不止"渲染成一棵树"——
// 面包屑要"根到当前节点的路径"、树表格要扁平行、级联选择要另一套结构。
// 这些场景需要的是**同一份建树规则**，而不是同一个组件。
// 只导出组件会把规则锁在组件里，逼得那些场景再写一份
// （实测已经重复了 5 处，详见 components/pro/tree.ts 的说明）。
export {
  TREE_PRESETS,
  filterFlatTreeByLabel,
  flatOptionsWithDepth,
  flatToTableTree,
  flatToTree
} from './components/pro/tree'
export type {
  FlatOption,
  FlatToTreeOptions,
  FlatTreeRow,
  TableTreeRow,
  TreePresetConfig
} from './components/pro/tree'
export type { UploadFileItem } from './components/pro/ProUpload.vue'

// ---- 状态矩阵 ----
export { default as AppSkeleton } from './components/state/AppSkeleton.vue'
