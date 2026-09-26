import { BarChart, GaugeChart, LineChart, PieChart, RadarChart, ScatterChart } from 'echarts/charts'
import {
  DataZoomComponent,
  DatasetComponent,
  GridComponent,
  LegendComponent,
  MarkLineComponent,
  TitleComponent,
  ToolboxComponent,
  TooltipComponent
} from 'echarts/components'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'

import 'vue-echarts/style.css'

/**
 * ECharts 适配层。
 *
 * <h3>⚠️ 必须用 `echarts/core` + 按需注册，不能 `import * as echarts from 'echarts'`</h3>
 * 全量引入 echarts 约 <b>330 KB gzip</b>，而本项目的首屏预算棘轮只剩十几 KB 余量。
 * 更要紧的是：**它没有"只影响图表页"这回事** —— 只要入口静态依赖了它，
 * 登录页也会下载它（同 `adapters/lazy.ts` 里记录的 vxe 那条教训）。
 *
 * <p>按需注册把体积压到约一半，代价是<b>用到的图表类型必须在下面列出来</b>。
 * 因此导出了内核的 {@code use} —— 应用需要额外图表（如桑基图）时自己补注册：
 * <pre>{@code
 * const { use } = await ensureEcharts()
 * const { SankeyChart } = await import('echarts/charts')
 * use([SankeyChart])
 * }</pre>
 * <b>白名单 + 显式逃生口</b>，而不是"为了省体积把应用锁死在白名单里"。
 *
 * <h3>默认注册了什么</h3>
 * 图表：折线 / 柱状 / 饼图 / 仪表盘 / 雷达 / 散点 —— 覆盖管理后台的绝大多数看板。
 * 组件：坐标轴网格 / 提示框 / 图例 / 标题 / 数据集 / 工具箱 / 缩放 / 标线。
 * 渲染器：Canvas（SVG 渲染器对一个后台系统的收益不足以再付一份体积）。
 */

use([
  // 渲染器
  CanvasRenderer,
  // 图表
  LineChart,
  BarChart,
  PieChart,
  GaugeChart,
  RadarChart,
  ScatterChart,
  // 组件
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  DatasetComponent,
  ToolboxComponent,
  DataZoomComponent,
  MarkLineComponent
])

/**
 * ⚠️ init 也从这里出，不要在应用里直接 `import { init } from 'echarts/core'`：
 * pnpm 会按 peer 组合解析出多个 echarts 拷贝，应用侧的 echarts/charts 安装器
 * 与适配器注册的 CanvasRenderer 可能落在不同实例上 —— 实测症状是
 * `Renderer 'undefined' is not imported` 与 `Cannot read properties of
 * undefined (reading 'get')`。走这里的 init 才保证与 use 注册同一实例。
 * （需要实例事件/动态 setOption 的页面：`const { init, use } = await ensureEcharts()`）
 */
export { VChart, use }
export { init } from 'echarts/core'
export type { EChartsType, EChartsCoreOption } from 'echarts/core'
