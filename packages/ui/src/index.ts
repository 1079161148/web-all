/**
 * @admin/ui 对外入口 —— 业务代码引入 UI 能力的唯一出口（设计文档 §11.2）。
 *
 * <h3>为什么存在这一层</h3>
 * Naive UI / vxe-table / form-create 都只允许在本包内被引入。
 * 业务代码（`apps/**`、`packages/{api,theme,...}`）必须从这里取用，
 * 由 Oxlint 的 `no-restricted-imports` 规则强制（见 packages/config/oxlint.json）。
 *
 * <p>代价是薄薄一层封装；收益是<b>UI 库可替换</b>：一旦 Naive UI 的维护活跃度
 * 下降到不可接受，只需替换本包内的适配层与组件实现，业务代码零改动。
 * 对一个要长期演进的平台，这个保险是必需项。
 *
 * <h3>三部分内容</h3>
 * <ul>
 *   <li><b>基础元素</b>（`./naive`）—— 原样透传，零封装</li>
 *   <li><b>Pro 组件</b>（`./components/pro`）—— 加约定，如 ProTable</li>
 *   <li><b>适配层</b>（`./adapters`）—— 全局反馈 API 等</li>
 * </ul>
 */

// ---- 安装插件 ----
export { installAdminUi } from './plugin'

// ---- 基础元素（透传 Naive UI，零封装） ----
export * from './naive'

// ---- 适配层 ----
export { feedback, setupFeedbackTheme } from './adapters/feedback'

// ---- 类型 ----
export type {
  AppStateStatus,
  ProCellRender,
  ProColumn,
  ProRowAction,
  ProSearchType,
  ProTablePage,
  ProTableQuery,
  ProTableRequest,
  ProToolbarKey
} from './types'

// ---- 全局配置容器 ----
export { default as AdminConfigProvider } from './components/admin/AdminConfigProvider.vue'

// ---- 状态矩阵 ----
export { default as AppState } from './components/state/AppState.vue'

// ---- Pro 组件 ----
export { default as ProTable } from './components/pro/ProTable.vue'
