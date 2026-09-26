/**
 * 组件库的**首屏最小入口**（`@admin/ui/core`）。
 *
 * <h3>为什么需要它：barrel 会被"预加载"</h3>
 * 首屏体积门禁（{@code scripts/check-bundle-size.mjs}）统计的是
 * {@code index.html} 里那些 {@code <script>} 与 {@code modulepreload} 链接 ——
 * 也就是浏览器首屏真正要下载的东西。
 *
 * <p>问题在于：只要**首屏可达**的任一文件从 barrel（{@code @admin/ui}）
 * 引入哪怕一个符号，整个 barrel 模块就会进入首屏依赖图。而当这些模块
 * 也被懒加载页面共用时，打包器会把它抽成一个**共享 chunk 并写进预加载列表**——
 * 于是"只有懒加载页面用到的 ProTable / ProForm / ECharts"也会在首屏被下载。
 * 实测该 chunk 达 <b>151 KB gzip</b>（占首屏 45%）。
 *
 * <p>所以启动期需要的东西必须有一个**不含业务组件**的入口，本文件就是它。
 *
 * <h3>成员规则（新增导出前先问这句话）</h3>
 * <b>「首屏渲染之前就需要它吗？」</b>
 * <ul>
 *   <li>{@link installAdminUi}：注册三个内核 —— 不注册页面组件渲染不出来</li>
 *   <li>{@link AdminConfigProvider}：根组件，包住整个应用</li>
 *   <li>{@link feedback}：全局提示与路由进度条，应用的错误出口依赖它</li>
 *   <li>{@link registerIcons} / {@link hasIcon}：侧栏图标解析，首屏侧栏即需要</li>
 *   <li>{@link setDictResolver} / {@link setPermissionResolver} /
 *       {@link setUploadAuthResolver}：接线，漏了不会报错只会降级</li>
 *   <li>{@link syncVxeTheme}：主题桥接（vxe 实例创建时即需要）</li>
 * </ul>
 * 反之，<b>凡是"某个页面才用到"的东西一律不进这里</b> ——
 * 它们留在 barrel 里，随页面懒加载。判断标准是"首屏渲染之前"，
 * 而不是"常见 / 重要"。
 *
 * <h3>⚠️ 不要把它当成新的 barrel</h3>
 * 往里加一个 Pro 组件，就等于把它拉回首屏，也让这个入口失去存在意义。
 * 本文件只做<b>再导出</b>，不实现任何逻辑 —— 它与 barrel 指向同一份实现，
 * 因此不存在"两套行为"的风险。
 */
export { installAdminUi } from './plugin'

export { default as AdminConfigProvider } from './components/admin/AdminConfigProvider.vue'

export { feedback } from './adapters/feedback'

export { hasIcon, registerIcons } from './registry/icon'

export { setDictResolver } from './registry/dict'
export { setPermissionResolver } from './registry/permission'
export { setUploadAuthResolver } from './registry/upload'

export { syncVxeTheme } from './adapters/lazy'

// 类型：编译期即被擦除，不产生运行时依赖
export type { ProIconOption } from './components/pro/IconPicker.vue'
