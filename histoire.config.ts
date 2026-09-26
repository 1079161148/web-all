import { HstVue } from '@histoire/plugin-vue'
import { defineConfig } from 'histoire'

/**
 * Histoire 组件工作台配置。
 *
 * <h3>为什么必须补这个（这是 `ui-component-policy` 的硬性要求）</h3>
 * 规则里写着：「<b>新增 Pro 组件必须同时提交 Histoire story + 文档，否则 PR 不通过</b>」。
 * 这条规则解决的是一个真实问题：组件库的改动很难被评审 ——
 * 看 diff 只能知道 props 变了，看不出<b>渲染出来是什么样、边界情况如何</b>。
 *
 * <h3>为什么 story 放在 packages/ui/src/stories</h3>
 * 与 `frontend-architecture` 约定的目录一致。story 文件与组件同包的好处是
 * 它的 import 路径与真实使用场景一致 —— story 里能跑通，业务里就没问题。
 *
 * <h3>⚠️ setupFile 的必要性</h3>
 * 本组件库的组件依赖三类"应用级接线"（字典来源、权限判定、上传鉴权），
 * 不接线时它们会降级（字典显示码值、权限全部放行）。
 * story 若不接线，看到的就不是真实行为 —— 那 story 会给出<b>错误的信心</b>。
 * 因此 `stories/setup.ts` 提供一套确定性的假数据来完成接线。
 */
export default defineConfig({
  plugins: [HstVue()],

  // 只收集 Pro 组件与状态组件的 story；业务页面不属于组件库
  storyMatch: ['packages/ui/src/stories/**/*.story.vue'],

  // 接线内核与三个注册表（见上方说明）
  setupFile: 'packages/ui/src/stories/setup.ts',

  theme: {
    title: '中台组件库',
    // 使用 Design Token 的主色，让工作台与真实系统观感一致
    colors: {
      primary: {
        50: '#eff6ff',
        100: '#dbeafe',
        200: '#bfdbfe',
        300: '#93c5fd',
        400: '#60a5fa',
        500: '#2563eb',
        600: '#1d4ed8',
        700: '#1e40af',
        800: '#1e3a8a',
        900: '#172554'
      }
    }
  },

  background: {
    // 亮/暗两套背景，与 Token 的双套值对应
    light: '#f5f7fa',
    dark: '#101014'
  },

  tree: {
    // 按组件族分组，而不是平铺 —— 组件数量到十几个后平铺会很难找
    groups: [
      { id: 'pro', title: 'Pro 组件' },
      { id: 'state', title: '状态矩阵' },
      { id: 'registry', title: '注册表与适配层' }
    ]
  },

  /**
   * ⚠️ 已知未解问题：story 收集阶段无法加载 Naive UI（v1.0.0-beta.1 实测）。
   *
   * <h3>现状</h3>
   * 配置本身能正常加载，收集在遇到第一个引用了 Naive UI 的 story 时中断：
   * <pre>
   * Using 19 threads for story collection
   * Error while collecting story .../ProTable.story.vue:
   * file:///.../naive-ui/es/_internal/scrollbar/src/Scrollbar.mjs:15
   *   import { VResizeObserver } from "vueuc";
   * SyntaxError: Named export 'VResizeObserver' not found.
   *   The requested module 'vueuc' is a CommonJS module
   * </pre>
   *
   * <h3>根因</h3>
   * 收集阶段在 <b>Node</b> 里执行（vite-node，SSR 语义），与浏览器加载路径不同：
   * <ul>
   *   <li>浏览器：Vite 对 CJS 依赖做 interop，{@code import { X } from 'cjs包'} 正常</li>
   *   <li>Node ESM：具名导出能否从 CJS 取到，取决于 cjs-module-lexer 的静态识别</li>
   * </ul>
   * 具体到 vueuc：{@code module} 指向 {@code es/index.js}，但包<b>没有 {@code "type": "module"}</b>，
   * 该 `.js` 在 Node 眼里是 CJS；而它的 {@code lib/index.js} 用了 {@code __exportStar}
   * （tslib 的星号再导出），词法器看不穿，因此具名导出取不到。
   *
   * <h3>已证伪的修法（不要再试）</h3>
   * {@code vite.ssr.noExternal} —— <b>两种写法都试过，都无效</b>：
   * 先按包名列举（含 vueuc），后改成整体 {@code true}，收集阶段报错完全不变。
   * 说明该版本的收集服务器<b>不合并用户传入的 {@code vite.ssr} 配置</b>。
   * 这段配置因此已删除 —— 留着会让人以为"这里已经处理过了"。
   *
   * <h3>版本约束（决定了为什么不能简单降级）</h3>
   * 本项目用 <b>Vite 8</b>。Histoire 0.17 用 {@code jiti@1.x}（编译为 CJS）加载配置，
   * 而加载链会拉进 Vite 8 —— Vite 8 自身用 {@code import.meta}，CJS 下非法，实测：
   * <pre>
   * Error while loading histoire.config.ts
   *   at jiti@1.21.0 ... /dist/node/config.js:5:13
   * SyntaxError: Cannot use 'import.meta' outside a module
   * </pre>
   * 这是 Vite 版本鸿沟（0.17 支持 Vite 2–5），<b>不是配置能绕过的</b>；
   * 而 1.0-beta 能加载配置却过不了收集阶段。两条路各自的堵点不同。
   * <p>顺带记一笔：0.17 支持的配置文件名是
   * {@code histoire.config.ts|js} / {@code .histoire.ts|js}，<b>不含 .mjs</b>，
   * 改名成 .mjs 会被静默忽略并落回默认配置（试过，无效）。
   *
   * <h3>下一步的候选（按可行性排序）</h3>
   * <ol>
   *   <li>{@code pnpm patch vueuc} 给它补 {@code "type": "module"}：
   *       定向、可回滚、随仓库版本化。风险是 {@code lib/index.js}（CJS）会因此失效，
   *       需要确认没有走 {@code main} 的消费方</li>
   *   <li>等 Histoire 1.0 正式版 —— 这是上游兼容性问题，不是本项目配置问题</li>
   *   <li>向上游提 issue（症状与版本组合已定位清晰，可直接作为复现步骤）</li>
   * </ol>
   */
})
