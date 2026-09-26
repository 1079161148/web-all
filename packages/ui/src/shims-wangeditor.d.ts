/**
 * `@wangeditor/editor-for-vue` 的环境声明。
 *
 * <h3>为什么需要它（实测报错）</h3>
 * <pre>
 * src/adapters/editor.ts(1,33): error TS7016:
 *   Could not find a declaration file for module '@wangeditor/editor-for-vue'.
 *   '.../dist/index.esm.js' implicitly has an 'any' type.
 * </pre>
 * 该包的 `package.json` <b>同时</b>有 `types: "dist/src/index.d.ts"`（文件确实存在）
 * 与一个只映射了 `"."` 的 `exports` 字段。而现代 `moduleResolution`（bundler / node16）
 * <b>优先走 {@code exports}</b>，`exports` 里没有 `types` 条件时，
 * TS 就不再去看 `types` 字段 —— 于是声明找不到，
 * 而运行期一切正常（`index.esm.js` 能加载）。
 *
 * <p>这与 {@code shims-vxe.d.ts} 是同一类问题、同一类解法：
 * <b>第三方包的打包配置问题不该由我们的业务代码承担</b>。
 * 声明只需覆盖我们真正用到的部分。
 *
 * <h3>⚠️ 这个文件必须被消费方（apps/**）的 TS 程序加载</h3>
 * 环境声明只在"文件本身属于程序"时生效。因此 `index.ts` 顶部用三斜线
 * reference 显式引入它 —— 原因与做法同 `shims-vxe.d.ts`，详见 `index.ts` 的说明。
 */
declare module '@wangeditor/editor-for-vue' {
  import type { DefineComponent } from 'vue'
  import type { IDomEditor, IToolbarConfig } from '@wangeditor/editor'

  /** 编辑区。`modelValue` 是 HTML 字符串。 */
  export const Editor: DefineComponent<{
    modelValue?: string
    defaultConfig?: Record<string, unknown>
    mode?: 'default' | 'simple'
  }>

  /** 工具栏。必须拿到编辑器实例才能工作（`editor` 传实例，不是 ref）。 */
  export const Toolbar: DefineComponent<{
    editor?: IDomEditor
    defaultConfig?: Partial<IToolbarConfig>
    mode?: 'default' | 'simple'
  }>
}
