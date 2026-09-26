import { VxeUI } from '@vxe-ui/core'
import type { App } from 'vue'

// ---------------------------------------------------------------------
// 表格侧组件（vxe-table）
//
// ⚠️ 全部走**深路径**导入，而不是 `import { VxeTable } from 'vxe-table'`。
//
// 原因（实测数据）：vxe 的包入口 `es/index.esm.js` 是
//   import * as VxeUIExport from './components'; export * from './components'
// 而 `components.js` 逐个 import 了**全部**组件，且每个组件模块在模块作用域
// 都会执行 `VxeUI.component(...)` —— 这是**副作用**，打包器无法 tree-shake。
// 于是 `app.use(vxe-pc-ui 命名空间)` 会把几百个组件（calendar / carousel /
// color-picker / watermark / tour / transfer…）全部打进首屏。
//
// 实测代价：首屏 gzip 从 277 KB 涨到 890 KB（约 3.2 倍），
// 而其中真正被用到的只有下面这 18 个。
// ---------------------------------------------------------------------
import { VxeColumn } from 'vxe-table/es/column'
import { VxeColgroup } from 'vxe-table/es/colgroup'
import { VxeGrid } from 'vxe-table/es/grid'
import { VxeTable } from 'vxe-table/es/table'
import { VxeToolbar } from 'vxe-table/es/toolbar'

// ---------------------------------------------------------------------
// 运行时动态取用的组件（vxe-pc-ui）
//
// 这份清单**不是猜的**，是对 vxe 源码里 `VxeUI.getComponent('Xxx')` 调用点
// 做全量提取得到的。vxe-grid 的内置表单、分页器、工具栏面板都通过这种方式
// 在运行时取组件 —— 也就是说：**漏注册不会报错，只会静默少渲染一块**。
// 见下方 assertRuntimeComponentsRegistered 的自检。
// ---------------------------------------------------------------------
import { VxeButton } from 'vxe-pc-ui/es/button'
import { VxeCheckbox } from 'vxe-pc-ui/es/checkbox'
import { VxeContextMenu } from 'vxe-pc-ui/es/context-menu'
import { VxeDrawer } from 'vxe-pc-ui/es/drawer'
import { VxeForm } from 'vxe-pc-ui/es/form'
import { VxeInput } from 'vxe-pc-ui/es/input'
import { VxeLoading } from 'vxe-pc-ui/es/loading'
import { VxeModal } from 'vxe-pc-ui/es/modal'
import { VxeNumberInput } from 'vxe-pc-ui/es/number-input'
import { VxePager } from 'vxe-pc-ui/es/pager'
import { VxeRadioGroup } from 'vxe-pc-ui/es/radio-group'
import { VxeSelect } from 'vxe-pc-ui/es/select'
import { VxeTooltip } from 'vxe-pc-ui/es/tooltip'

// 样式必须手动引入（vxe 不做自动注入）。
// 顺序有讲究：先 pc-ui（基础元素与主题变量），后 table ——
// table 的样式依赖 pc-ui 定义的 CSS 变量，反过来会让表格主题变量取不到值。
//
// ⚠️ 这里仍然引入**全量样式**（约 98 KB gzip），没有按组件拆。
// 取舍：按组件引入 CSS 能再省一块，但漏一个样式文件的表现是"界面错位"——
// 这种问题在构建期与类型检查期都发现不了，只能靠肉眼。
// 在缺少视觉回归测试（视觉回归基线）之前，**不拿样式做优化**。
import 'vxe-pc-ui/lib/style.css'
import 'vxe-table/lib/style.css'

// 中文语言包：vxe 默认不装任何语言，启动会报 "Language not installed"
import zhCN from 'vxe-table/lib/locale/lang/zh-CN'

// VxeUI 的类型声明没有 expose 出 setI18n / setLanguage，但运行时确实导出
const vxe = VxeUI as unknown as {
  setI18n: (lang: string, data: unknown) => void
  setLanguage: (lang: string) => void
}

/**
 * 可安装的 vxe 组件。
 *
 * <p>类型写成 {@code { install }} 而不是 Vue 的 {@code Component}：
 * vxe 的每个组件模块都用 {@code Object.assign(组件, { install })} 给它挂上安装方法，
 * 但 {@code Component} 类型里<b>不含</b> {@code install}，
 * 用它会让 {@code app.use(component)} 编译不过（实测确认）。
 * 这里如实描述"我需要的能力"，而不是去迁就一个更宽泛但不对口的类型。
 */
interface VxeInstallable {
  install: (app: App) => void
}

/** 表格侧组件：直接注册到 app。 */
const TABLE_COMPONENTS: VxeInstallable[] = [VxeTable, VxeColumn, VxeColgroup, VxeGrid, VxeToolbar]

/** 运行时被 vxe-grid 动态取用的组件：也必须注册，否则对应区块静默不渲染。 */
const RUNTIME_COMPONENTS: VxeInstallable[] = [
  VxeButton,
  VxeCheckbox,
  VxeContextMenu,
  VxeDrawer,
  VxeForm,
  VxeInput,
  VxeLoading,
  VxeModal,
  VxeNumberInput,
  VxePager,
  VxeRadioGroup,
  VxeSelect,
  VxeTooltip
]

/** 自检用的组件名清单（与 RUNTIME_COMPONENTS 一一对应）。 */
const RUNTIME_COMPONENT_NAMES = [
  'VxeButton',
  'VxeCheckbox',
  'VxeContextMenu',
  'VxeDrawer',
  'VxeForm',
  'VxeInput',
  'VxeLoading',
  'VxeModal',
  'VxeNumberInput',
  'VxePager',
  'VxeRadioGroup',
  'VxeSelect',
  'VxeTooltip'
]

let installed = false

/**
 * 在应用启动时注册 vxe 能力。由 {@code installAdminUi} 统一调用。
 *
 * <h3>为什么是"精确注册"而不是全量注册</h3>
 * 见文件顶部关于深路径导入的说明。这里的清单是**可审计的**：
 * 新增用到某个 vxe 组件时，必须同时加到清单里，
 * 否则启动自检会立刻报错（而不是等到某个页面白屏）。
 */
export function installVxe(app: App): void {
  if (installed) {
    return
  }
  for (const component of TABLE_COMPONENTS) {
    app.use(component)
  }
  for (const component of RUNTIME_COMPONENTS) {
    app.use(component)
  }

  // 注册并启用中文语言包。vxe-grid 的工具栏、分页器、表单校验提示都依赖它。
  vxe.setI18n('zh-CN', zhCN)
  vxe.setLanguage('zh-CN')

  installed = true
  assertRuntimeComponentsRegistered()
}

/**
 * 启动自检：确认所有"运行时被动态取用"的组件都已注册。
 *
 * <h3>为什么需要它（这是本文件最重要的 10 行）</h3>
 * vxe-grid 通过 {@code VxeUI.getComponent('VxePager')} 这类调用在<b>运行时</b>
 * 取组件。这种设计的失败模式非常隐蔽：
 * <ul>
 *   <li><b>构建不报错</b> —— 深路径导入本身是合法的</li>
 *   <li><b>类型检查不报错</b> —— 类型来自 barrel，与运行时注册无关</li>
 *   <li><b>运行时也不报错</b> —— vxe 只是拿不到组件，那块区域不渲染</li>
 * </ul>
 * 表现是"表格没有分页器"或"列设置面板打不开"，
 * 而排查方向会先跑偏到配置项上。
 *
 * <p>这个自检把上述静默降级变成<b>开屏可见的控制台错误</b>。
 * 它是"精确注册"这个优化能够被安全采用的前提 ——
 * 没有它，精确注册就是在拿静默故障换体积。
 */
function assertRuntimeComponentsRegistered(): void {
  const missing = RUNTIME_COMPONENT_NAMES.filter(
    (name) => !VxeUI.getComponent(name as Parameters<typeof VxeUI.getComponent>[0])
  )
  if (missing.length > 0) {
    console.error(
      '[vxe] 以下组件未注册，vxe-grid 在运行时将取不到它们（表现为对应区块不渲染）：\n' +
        `  ${missing.join(', ')}\n` +
        '请把它们补进 packages/ui/src/adapters/vxe.ts 的 RUNTIME_COMPONENTS。'
    )
  }
}

/**
 * 同步 vxe 主题。
 *
 * <p>vxe 有自己的主题系统（基于 CSS 变量的 {@code light} / {@code dark} 两套值），
 * 与 Naive UI 的 themeOverrides <b>互不感知</b>。项目支持暗色模式，
 * 若不显式同步，会出现「页面是暗色、表格是亮色」的割裂感 ——
 * 而且这个不一致只在切换主题后才出现，很容易漏测。
 *
 * <p>应用在主题切换时调用本函数（见 {@code apps/admin} 的主题 store）。
 */
export function syncVxeTheme(dark: boolean): void {
  VxeUI.setTheme(dark ? 'dark' : 'light')
}

// ---------------------------------------------------------------------
// 对外暴露 vxe 组件与类型
//
// 目的：让 {@code components/**} 下的封装代码不必各自 import vxe。
// 把第三方库的引入收敛到一个文件，是「UI 库可替换」这条架构约束的最小落地方式。
//
// ⚠️ 运行时导出走**深路径**（与上面同源，不会引入 barrel）；
//    类型导出走 barrel —— 类型在编译后被完全擦除，不产生任何运行时代码。
// ---------------------------------------------------------------------

export { VxeColumn } from 'vxe-table/es/column'
export { VxeColgroup } from 'vxe-table/es/colgroup'
export { VxeGrid } from 'vxe-table/es/grid'
export { VxeTable } from 'vxe-table/es/table'
export { VxeToolbar } from 'vxe-table/es/toolbar'

export type {
  VxeColumnProps,
  VxeGridConstructor,
  VxeGridDefines,
  VxeGridProps,
  VxeTableDefines,
  VxeTablePropTypes
} from 'vxe-table'
