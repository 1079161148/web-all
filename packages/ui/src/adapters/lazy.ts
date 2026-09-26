import type { App } from 'vue'
import type * as CronAdapter from './cron'
import type * as EchartsAdapter from './echarts'
import type * as FormCreateAdapter from './form-create'
import type * as VxeAdapter from './vxe'

/**
 * 内核的按需加载。
 *
 * <h3>为什么必须"按需"而不是启动时注册</h3>
 * 实测数据：vxe-table + vxe-pc-ui + form-create 三者合计约 <b>577 KB gzip</b>。
 * 而它们是<b>真正用到的页面才需要</b>的东西 —— 登录页、布局页、看板页
 * 一个表格都没有。
 *
 * <p>但只要依赖链是静态的，打包器就必须把它们放进首屏：
 * <pre>
 *   main.ts → installAdminUi → plugin.ts → adapters/vxe.ts → vxe（全部）
 * </pre>
 * 结果是<b>登录页也要下载 577 KB</b>，而它渲染的只是一个表单。
 *
 * <h3>做法：把静态 import 改成动态 import</h3>
 * 组件在自己的 setup 里 await 内核就绪，再渲染依赖内核的部分，
 * 期间显示骨架屏。打包器据此把内核切到<b>按需 chunk</b>，
 * 只有真正打开表格/表单页才会加载。
 *
 * <h3>⚠️ 为什么加载后仍然调用 app.use()</h3>
 * vxe 的内部实现会在运行时通过 {@code VxeUI.getComponent('VxePager')} 这类调用
 * 取组件（工具栏面板、分页器、内嵌表单）。这些组件虽然会在模块加载时
 * 自注册到 VxeUI 的注册表，但全局标签注册（app.component）仍需要 app.use ——
 * 少了它，vxe 自己的模板里用到的组件可能解析不到。
 *
 * <p>模块级的 {@code app.use} 只执行一次（见下方的模块缓存），
 * 因此多个页面先后打开不会重复注册。
 */

/**
 * 模块缓存。
 *
 * <p>为什么不依赖动态 import 自身的缓存：{@code app.use} 需要<b>只</b>调用一次。
 * 动态 import 确实会返回同一个模块对象，但"这里是否已经注册过 app"是另一件事 ——
 * 每个 App 实例都需要注册一次。用模块级变量显式记录当前已注册的 app，
 * 比在组件里各自维护一个 isReady 标记更可靠（后者在多个组件并发挂载时会重复注册）。
 */
let vxeModule: typeof VxeAdapter | null = null
let formCreateModule: typeof FormCreateAdapter | null = null
let echartsModule: typeof EchartsAdapter | null = null
let cronModule: typeof CronAdapter | null = null
/**
 * 富文本编辑器内核。
 *
 * <p>这里用 `typeof import(...)` 而不是在文件顶部写 `import type * as ...`：
 * 效果相同，但少一处需要同步维护的 import —— 下面几个内核当初是逐个加的，
 * 每加一个都要同时改顶部 import 与这里，漏一处就是编译错误。
 */
let editorModule: typeof import('./editor') | null = null
/** Excel 读写内核（exceljs，CJS 且体积大，必须按需）。 */
let excelModule: typeof import('./excel') | null = null
/**
 * 模糊检索内核（fuse.js）。
 *
 * <p>它只有约 12 KB —— 是这一批内核里最小的，但<b>仍然走按需加载</b>。
 * 判据不是"大不大"，而是"<b>是不是每个页面都要</b>"：
 * 命令面板只存在于有全局快捷键的界面里，
 * 让它进入口就等于让所有页面都为它付一次下载与解析成本。
 */
let fuseModule: typeof import('fuse.js') | null = null

/** 已完成注册的 app（用于判断是否需要为新 app 重新注册）。 */
let registeredVxeApp: App | null = null
let registeredFormCreateApp: App | null = null

/** vxe 内核是否已就绪（供组件决定是否渲染依赖内核的部分）。 */
export function isVxeReady(): boolean {
  return vxeModule !== null
}

/** form-create 内核是否已就绪。 */
export function isFormCreateReady(): boolean {
  return formCreateModule !== null
}

/** ECharts 内核是否已就绪。 */
export function isEchartsReady(): boolean {
  return echartsModule !== null
}

/** cron 编辑器内核是否已就绪。 */
export function isCronReady(): boolean {
  return cronModule !== null
}

/** 富文本编辑器内核是否已就绪。 */
export function isEditorReady(): boolean {
  return editorModule !== null
}

/** Excel 读写内核是否已就绪。 */
export function isExcelReady(): boolean {
  return excelModule !== null
}

/** 模糊检索内核（fuse.js）是否已就绪。 */
export function isFuseReady(): boolean {
  return fuseModule !== null
}

/**
 * 确保 vxe 内核已加载并注册，返回其模块。
 *
 * <p>并发调用安全：多个组件同时挂载时，动态 import 自身会去重，
 * 而下面的判断保证 {@code app.use} 不会被重复执行。
 */
export async function ensureVxe(app: App | null | undefined): Promise<typeof VxeAdapter> {
  if (!vxeModule) {
    vxeModule = await import('./vxe')
  }
  if (app && registeredVxeApp !== app) {
    vxeModule.installVxe(app)
    registeredVxeApp = app
  }
  return vxeModule
}

/** 确保 form-create 内核已加载并注册。 */
export async function ensureFormCreate(
  app: App | null | undefined
): Promise<typeof FormCreateAdapter> {
  if (!formCreateModule) {
    formCreateModule = await import('./form-create')
  }
  if (app && registeredFormCreateApp !== app) {
    formCreateModule.installFormCreate(app)
    registeredFormCreateApp = app
  }
  return formCreateModule
}

/**
 * 确保 ECharts 内核已加载，返回其模块。
 *
 * <p>与 vxe / form-create 不同，它<b>不需要 app 参数</b> ——
 * ECharts 没有全局插件注册这一步，`use([...])` 在模块加载时就完成了。
 * 因此这里也就没有"已注册的 app"要记。
 *
 * <p>这是内核里最重的一个（按需注册后仍有约 150 KB gzip），
 * 也是"必须按需"最典型的例子：一个没有任何图表的登录页不该下载它。
 */
export async function ensureEcharts(): Promise<typeof EchartsAdapter> {
  if (!echartsModule) {
    echartsModule = await import('./echarts')
  }
  return echartsModule
}

/**
 * 确保 cron 编辑器内核已加载，返回其模块。
 *
 * <p>它体积不大（约 5 KB 组件 + 55 KB core），但同样走按需加载 ——
 * 理由是一致的：定时任务编辑只出现在个别页面，
 * 而入口多带一份东西就是让每个用户都替它付下载成本。
 * <b>"小"不是静态引入的理由，"不是每个页面都要"才是判据。</b>
 */
export async function ensureCron(): Promise<typeof CronAdapter> {
  if (!cronModule) {
    cronModule = await import('./cron')
  }
  return cronModule
}

/**
 * 确保富文本编辑器内核已加载，返回其模块。
 *
 * <p>wangEditor 的编辑器实例必须在组件卸载时 `destroy()`（见 ProEditor），
 * 而"实例已销毁但模块仍缓存"是正确状态 —— 模块缓存与实例生命周期无关，
 * 下一个编辑器会创建新实例。所以这里不需要清理逻辑。
 */
export async function ensureEditor(): Promise<typeof import('./editor')> {
  if (!editorModule) {
    editorModule = await import('./editor')
  }
  return editorModule
}

/**
 * 确保 Excel 内核已加载，返回其模块。
 *
 * <p>这是所有内核里最重的一个（浏览器构建 925 KB），
 * 因此按需加载在这里不是优化而是<b>必要</b>：
 * 若它进了入口，登录页就要多下近 1 MB，而导入导出只出现在个别页面。
 */
export async function ensureExcel(): Promise<typeof import('./excel')> {
  if (!excelModule) {
    excelModule = await import('./excel')
  }
  return excelModule
}

/**
 * 确保模糊检索内核已加载，返回其模块。
 *
 * <p>fuse.js 没有独立的适配层文件 —— 它不需要注册、不需要主题桥接，
 * 也不需要类型补丁（`exports` 里有 `types` 条件）。
 * <b>只有一个 re-export 的"适配层"是多余的</b>，因此直接用 `typeof import()`。
 * 这条判据也适用于其它新内核：先看它是否真的需要那一层。
 */
export async function ensureFuse(): Promise<typeof import('fuse.js')> {
  if (!fuseModule) {
    fuseModule = await import('fuse.js')
  }
  return fuseModule
}

// ---------------------------------------------------------------------
// 对外的显式入口（全部走动态加载）
//
// ⚠️ 这些包装函数存在的唯一理由是：**对外入口不能静态再导出内核模块**。
//
// 实测踩过：`export { installVxe, syncVxeTheme } from './adapters/vxe'` 这种
// 静态再导出，会把 adapters/vxe.ts 重新拉进入口 chunk 的**静态依赖图**。
// 结果是 vxe 虽然被切成了独立 chunk，却依然出现在 index.html 的
// `<link rel="modulepreload">` 里 —— 浏览器照样在首屏下载它，
// **切分了但没省下任何字节**（首屏反而从 2.06 MB 涨到 2.86 MB）。
//
// 这类"看起来优化了、实际没优化"的情况不会报任何错，只能靠核对
// 构建产物（index.html 引用了什么）才能发现。
// ---------------------------------------------------------------------

/** 显式加载并注册 vxe 内核（等价于 ensureVxe，语义更明确）。 */
export function installVxe(app: App): Promise<typeof VxeAdapter> {
  return ensureVxe(app)
}

/** 显式加载并注册 form-create 内核。 */
export function installFormCreate(app: App): Promise<typeof FormCreateAdapter> {
  return ensureFormCreate(app)
}

/**
 * 同步 vxe 主题。
 *
 * <p>签名是 async 的（内核按需加载）。应用在主题切换时调用即可，
 * 不必 await —— 若内核尚未加载（用户没打开过表格页），这次同步没有意义，
 * 因为表格本来就不存在。
 */
export async function syncVxeTheme(dark: boolean): Promise<void> {
  const mod = await ensureVxe(null)
  mod.syncVxeTheme(dark)
}

/**
 * 取 form-create 的工厂实例（命令式创建表单时用）。
 *
 * <p>做成异步是因为它同样不能静态导出 —— 见上方说明。
 */
export async function getFormCreate(): Promise<typeof FormCreateAdapter.formCreate> {
  const mod = await ensureFormCreate(null)
  return mod.formCreate
}
