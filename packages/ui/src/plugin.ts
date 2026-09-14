import type { App } from 'vue'
import naive from 'naive-ui'

/**
 * 注册 UI 能力到 Vue 应用。
 *
 * <p>把 `app.use(naive)` 收进本包，这样应用入口不必直接接触 naive-ui ——
 * 一旦替换 UI 库，只需要改这一个文件（设计文档 §11.2）。
 *
 * <pre>{@code
 * // apps/admin/src/main.ts
 * import { installAdminUi } from '@admin/ui'
 * installAdminUi(app)
 * }</pre>
 *
 * <h3>⚠️ 全量注册的取舍</h3>
 * 这里用 `app.use(naive)` 全量注册组件，实现简单、开发体验好，但会让构建产物偏大。
 * 更彻底的做法是只注册用到的组件（见 `naive.ts` 的显式清单）。
 * P1 应在体积门禁（设计文档 §12.1，首屏 JS &lt; 250KB）建立后，
 * 按实际用量改为精确注册 —— 届时本文件的改动是单点的。
 */
export function installAdminUi(app: App): void {
  app.use(naive)
}
