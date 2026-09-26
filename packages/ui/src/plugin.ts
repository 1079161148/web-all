import type { App, Component } from 'vue'
import * as naiveElements from './naive'

/**
 * 注册 UI 能力到 Vue 应用。
 *
 * <h3>三个内核的分工与加载策略</h3>
 * <table>
 *   <tr><th>内核</th><th>gzip</th><th>策略</th></tr>
 *   <tr><td>Naive UI</td><td>约 320 KB</td><td><b>精确注册</b>（只注册清单内的组件）</td></tr>
 *   <tr><td>vxe</td><td>约 200 KB</td><td><b>按需加载</b>（见 {@code adapters/lazy.ts}）</td></tr>
 *   <tr><td>form-create</td><td>约 60 KB</td><td><b>按需加载</b>（同上）</td></tr>
 * </table>
 *
 * <h3>为什么不能写 `app.use(naive)`</h3>
 * `naive` 是整包命名空间对象。`app.use(naive)` 会把它的全部导出<b>都变成"被使用"</b>，
 * 打包器无从 tree-shake —— 于是 Calendar / Carousel / ColorPicker / Watermark /
 * Transfer / Cascader 等几十个<b>一个页面都没用到</b>的组件全部进入首屏。
 *
 * <p>实测：改为精确注册后首屏减少约 <b>150 KB gzip</b>。
 *
 * <h3>⚠️ 精确注册的代价，以及为什么它是安全的</h3>
 * 漏注册一个组件<b>不会报错</b>，Vue 只会把它渲染成一个空的自定义元素 ——
 * 表现是"某个按钮不见了"或"弹窗打不开"，控制台里最多一条容易忽略的警告。
 *
 * <p>因此精确注册<b>必须</b>配一条门禁：
 * {@code scripts/check-naive-registration.mjs} 会扫描所有 {@code .vue} 里的
 * {@code <n-xxx>} 标签，与 {@code naive.ts} 的清单比对，有缺即构建失败。
 * 它已经上线并当场抓出了一个真实漏注册（{@code NCheckboxGroup}）。
 *
 * <p><b>没有这条门禁，精确注册就是在拿静默故障换体积。</b>
 *
 * <h3>⚠️ 只注册 Naive，vxe 与 form-create 是懒加载的</h3>
 * 这两者的使用面窄（表格页 / 表单页），静态注册会让登录页也下载 331 KB。
 * ProTable / ProForm 会在自己的 setup 里 `await ensureVxe(app)`。见 {@code adapters/lazy.ts}。
 */
export function installAdminUi(app: App): void {
  registerNaiveElements(app)
}

/**
 * 从 {@code naive.ts} 的导出清单精确注册 Naive 组件。
 *
 * <h3>为什么从 `./naive` 推导，而不是在这里再写一份清单</h3>
 * 两份清单一定会漂移：加了组件到 `naive.ts`（业务代码能用它了），
 * 却忘了在 `plugin.ts` 注册（页面上渲染不出来）—— 而这两处相距很远、
 * 没有任何编译期联系。<b>让"可用的清单"与"注册的清单"是同一份数据</b>，
 * 从结构上消除这类不一致。
 *
 * <h3>为什么用 `import * as naiveElements from './naive'` 而不是 `from 'naive-ui'`</h3>
 * 两者的 tree-shaking 结果完全不同：
 * <ul>
 *   <li>{@code naive.ts} 是<b>我们自己的</b>薄再导出文件，它的导出集合
 *       正是我们要注册的那一份 —— 全部保留是预期行为</li>
 *   <li>{@code 'naive-ui'} 是上游整包 barrel。对它做命名空间导入会把
 *       全部几百个组件都标记为"已使用"，精确注册的意义荡然无存</li>
 * </ul>
 */
function registerNaiveElements(app: App): void {
  let count = 0
  for (const [name, element] of Object.entries(naiveElements)) {
    // 只注册组件：naive.ts 同时导出了 darkTheme / zhCN 等非组件值与若干类型
    // （类型在编译后已被擦除，不会出现在这里）
    if (!/^N[A-Z]/.test(name) || !element) {
      continue
    }
    app.component(name, element as Component)
    count += 1
  }

  if (count === 0) {
    // 一个都没注册说明 naive.ts 的导出格式变了 —— 与其静默让全站无样式/无组件，
    // 不如立刻把它暴露出来
    console.error(
      '[admin-ui] 未能从 naive.ts 注册任何 Naive 组件。' +
        '请检查该文件的导出是否仍是 `export { NXxx, ... } from "naive-ui"`。'
    )
  }
}
