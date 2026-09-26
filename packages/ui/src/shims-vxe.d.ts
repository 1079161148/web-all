/**
 * vxe 深路径导入的类型声明。
 *
 * <h3>为什么需要这份文件</h3>
 * {@code adapters/vxe.ts} 刻意走深路径导入（{@code vxe-table/es/grid} 而非
 * {@code vxe-table}），以避免 barrel 把整个组件库拖进首屏 —— 详见该文件的说明。
 *
 * <p>但 vxe 的类型声明只放在包的 {@code types/} 目录并在 {@code package.json}
 * 的 {@code typings} 字段里被引用，<b>深路径旁边没有同名的 .d.ts</b>，
 * 因此 TS 会报 TS7016（隐含 any）。
 *
 * <h3>这份声明做了什么、没做什么</h3>
 * 每个深路径模块被映射到<b>barrel 上同名导出的类型</b>：
 * <pre>
 *   declare module 'vxe-table/es/grid' {
 *     export const VxeGrid: typeof import('vxe-table')['VxeGrid']
 *   }
 * </pre>
 * 于是：
 * <ul>
 *   <li>✅ 组件在模板里的 props 校验依然生效（类型是真的，不是 any）</li>
 *   <li>✅ 不引入任何运行时代码 —— {@code import type} 在编译后被完全擦除</li>
 *   <li>✅ barrel 只在<b>类型层面</b>被引用，不会因为这里而被打包进去</li>
 * </ul>
 *
 * <h3>⚠️ 维护约定</h3>
 * 往 {@code RUNTIME_COMPONENTS} / {@code TABLE_COMPONENTS} 里加组件时，
 * 必须在这里同步补一条声明 —— 否则会退回 TS7016。
 * 两处清单的一致性由编译期保证（漏了就编译不过），不需要额外的检查脚本。
 */

// ---- vxe-table（表格侧） ----
declare module 'vxe-table/es/table' {
  export const VxeTable: (typeof import('vxe-table'))['VxeTable']
  export default VxeTable
}

declare module 'vxe-table/es/column' {
  export const VxeColumn: (typeof import('vxe-table'))['VxeColumn']
  export default VxeColumn
}

declare module 'vxe-table/es/colgroup' {
  export const VxeColgroup: (typeof import('vxe-table'))['VxeColgroup']
  export default VxeColgroup
}

declare module 'vxe-table/es/grid' {
  export const VxeGrid: (typeof import('vxe-table'))['VxeGrid']
  export default VxeGrid
}

declare module 'vxe-table/es/toolbar' {
  export const VxeToolbar: (typeof import('vxe-table'))['VxeToolbar']
  export default VxeToolbar
}

// ---- vxe-pc-ui（运行时被 vxe-grid 动态取用） ----
declare module 'vxe-pc-ui/es/button' {
  export const VxeButton: (typeof import('vxe-pc-ui'))['VxeButton']
  export default VxeButton
}

declare module 'vxe-pc-ui/es/checkbox' {
  export const VxeCheckbox: (typeof import('vxe-pc-ui'))['VxeCheckbox']
  export default VxeCheckbox
}

declare module 'vxe-pc-ui/es/context-menu' {
  export const VxeContextMenu: (typeof import('vxe-pc-ui'))['VxeContextMenu']
  export default VxeContextMenu
}

declare module 'vxe-pc-ui/es/drawer' {
  export const VxeDrawer: (typeof import('vxe-pc-ui'))['VxeDrawer']
  export default VxeDrawer
}

declare module 'vxe-pc-ui/es/form' {
  export const VxeForm: (typeof import('vxe-pc-ui'))['VxeForm']
  export default VxeForm
}

declare module 'vxe-pc-ui/es/input' {
  export const VxeInput: (typeof import('vxe-pc-ui'))['VxeInput']
  export default VxeInput
}

declare module 'vxe-pc-ui/es/loading' {
  export const VxeLoading: (typeof import('vxe-pc-ui'))['VxeLoading']
  export default VxeLoading
}

declare module 'vxe-pc-ui/es/modal' {
  export const VxeModal: (typeof import('vxe-pc-ui'))['VxeModal']
  export default VxeModal
}

declare module 'vxe-pc-ui/es/number-input' {
  export const VxeNumberInput: (typeof import('vxe-pc-ui'))['VxeNumberInput']
  export default VxeNumberInput
}

declare module 'vxe-pc-ui/es/pager' {
  export const VxePager: (typeof import('vxe-pc-ui'))['VxePager']
  export default VxePager
}

declare module 'vxe-pc-ui/es/radio-group' {
  export const VxeRadioGroup: (typeof import('vxe-pc-ui'))['VxeRadioGroup']
  export default VxeRadioGroup
}

declare module 'vxe-pc-ui/es/select' {
  export const VxeSelect: (typeof import('vxe-pc-ui'))['VxeSelect']
  export default VxeSelect
}

declare module 'vxe-pc-ui/es/tooltip' {
  export const VxeTooltip: (typeof import('vxe-pc-ui'))['VxeTooltip']
  export default VxeTooltip
}
