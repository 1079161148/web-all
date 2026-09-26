import { CronNaive } from '@vue-js-cron/naive-ui'

/**
 * ⚠️ 这个 CSS 必须显式引入。
 *
 * <p>该包<b>没有</b> `style` 字段、也没有 `sideEffects` 声明，
 * 打包器不会自动带上它。134 B 的排版样式，漏了不会报错 ——
 * 表现是编辑器里的分段控件挤成一团，
 * 而"看起来能用但排版错乱"最容易被人当成 Naive 主题的问题去查。
 */
import '@vue-js-cron/naive-ui/dist/naive-ui.css'

/**
 * cron 表达式编辑器适配层（内核：@vue-js-cron）。
 *
 * <h3>中文界面是内核能力，不需要自研</h3>
 * 该内核的设计是"用本地化片段把选择项拼成一句可读的话"，
 * 并自带语言包（`locale/cn.ts` 等十几个）。
 *
 * <h3>⚠️ locale 码是 `cn` 而不是 `zh-CN`</h3>
 * 语言包的<b>文件名</b>就是可用的码：{@code cn / en / de / ja / ko / ru …}。
 * 传 `zh-CN` 不会报错 —— 它会静默回退到英文（内核以内置英文兜底）。
 * 于是"配了中文却还是英文"，而排查方向通常会先跑到语言包没装上去。
 *
 * <h3>⚠️ 没有「整条表达式 → 一句话摘要」的接口</h3>
 * 内核的 {@code L10nEngine.render()} 是<b>按字段</b>渲染片段的
 * （periodId + fieldId + pattern + position），用于让每个选项读起来通顺。
 * 若将来需要"每天 12:00 执行"这种整句摘要，那需要另做一个描述器 ——
 * 而那属于<b>自研描述逻辑</b>，需要单独评估，不能顺手写在这里。
 */
export { CronNaive }
export type { CronNaiveProps } from '@vue-js-cron/naive-ui'
