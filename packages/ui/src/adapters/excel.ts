import { Workbook } from 'exceljs'

/**
 * Excel 读写适配层（内核：exceljs）。
 *
 * <h3>⚠️ 这是所有内核里最重的一个：浏览器构建 925 KB</h3>
 * 比 ECharts 还大一倍多。因此它<b>必须</b>走按需加载 ——
 * 若入口静态依赖它，登录页就要下载近 1 MB，
 * 而绝大多数用户根本不会用到导入导出。
 *
 * <h3>为什么不需要处理 Node 内置模块</h3>
 * exceljs 是 CJS，且它的 <b>Node 构建</b>会引用 {@code fs} / {@code stream}。
 * 但它的 package.json 有：
 * <pre>
 *   browser: "./dist/exceljs.min.js"
 * </pre>
 * 打包器会据此选用浏览器构建，于是不存在"Node 内置模块被外部化"的问题。
 *
 * <p>这与 `adapters/editor.ts` 那边的处境形成对照：wangEditor 的问题是
 * <b>类型找不到</b>（`exports` 字段挡住 `types`），需要写 shim；
 * exceljs 没有 `exports` 字段，类型解析正常 —— <b>不需要 shim</b>。
 * 两个包都是"打包配置问题"，但症状与解法完全不同，
 * 只能逐个核实，不能凭"上次那个包怎么处理的"照搬。
 *
 * <h3>关于 {@code exceljs.bare.min.js}</h3>
 * 包内还有一份 842 KB 的 `bare` 构建（去掉了一部分内置依赖），
 * 体积略小但要求使用方自行提供被去掉的部分。
 * 这里<b>不选它</b>：为一个可选依赖的 80 KB 差异去承担"运行期缺东西"的风险不划算。
 */
export { Workbook }
export type { Cell, CellValue, Row, Worksheet } from 'exceljs'
