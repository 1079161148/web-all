<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { NButton, NCard, NRadioButton, NRadioGroup, NTag, ensureEcharts } from '@admin/ui'
import { useAppStore } from '@/stores/app'
// ⚠️ 运行时一律走 ensureEcharts() 返回的实例（见 adapters/echarts.ts 的注释）：
// 应用侧直接 import echarts/core 会拿到另一份 pnpm 拷贝，renderer 注册分裂。
// 这里只引 CandlestickChart 安装器（install(registers) 跨实例可用）与纯类型。
import { CandlestickChart } from 'echarts/charts'
import type { EChartsCoreOption, EChartsType } from 'echarts/core'

/**
 * 百万级时序数据（股票 K 线）的图表最佳实践。
 *
 * <h3>这一页演示的完整手段链</h3>
 * <ol>
 *   <li><b>Web Worker 流水线</b>：生成与重采样都不占主线程（见 stock.worker.ts）；</li>
 *   <li><b>LTTB 降采样</b>：100 万点 → 2000 点，视觉形状无损（等间隔抽稀会抽掉极值）；</li>
 *   <li><b>缩放感知的自适应精度</b>：全览时折线，缩到 6 万点以内自动切成
 *       周期聚合的蜡烛图 —— "缩放越深、信息越细"，专业行情软件的做法；</li>
 *   <li><b>对照实验</b>：朴素全量 / 内置 sampling:'lttb' / Worker LTTB，
 *       同一份数据三种画法的渲染耗时与 FPS 直接对比；</li>
 *   <li><b>渲染细节</b>：animation 关闭、成交量 large:true + progressive、
 *       dataZoom 事件 120ms 尾随节流、Float32 存储省一半内存。</li>
 * </ol>
 *
 * <h3>为什么不用 ProChart</h3>
 * ProChart 只透传 option、刻意不暴露实例与事件（未验证的 API 不发布）。
 * 本页的核心交互（dataZoom 事件 → 重采样 → setOption）需要实例，
 * 因此在页面级直接用 echarts/core init —— 页面自担验证责任，
 * 不把这个开口提前塞给公共组件。
 */

const appStore = useAppStore()

// ============================== 状态 ==============================

const POINT_OPTIONS = [
  { label: '25 万', value: 250_000 },
  { label: '50 万', value: 500_000 },
  { label: '100 万', value: 1_000_000 }
]

const pointCount = ref(1_000_000)
const generating = ref(false)
const genMs = ref(0)
const dataBytes = ref(0)
const visiblePoints = ref(0)
const sampleMs = ref(0)
const renderMs = ref(0)
const viewMode = ref<'line' | 'candle'>('line')
const bucketLabel = ref('')
const fps = ref(0)
const labBusy = ref(false)
const labResults = ref<Record<'naive' | 'builtin' | 'worker', number | null>>({
  naive: null,
  builtin: null,
  worker: null
})

const mainEl = ref<HTMLDivElement | null>(null)
const labEl = ref<HTMLDivElement | null>(null)

// ============================== Worker ==============================

interface GeneratedMsg {
  type: 'generated'
  genMs: number
  count: number
  bytes: number
  time: Int32Array
  close: Float32Array
}

interface WindowedMsg {
  type: 'windowed'
  mode: 'line' | 'candle'
  sampleMs: number
  time: Int32Array
  close: Float32Array
  volume: Float32Array
  bars?: number
  bucketSize?: number
  open?: Float32Array
  low?: Float32Array
  high?: Float32Array
}

type WorkerMsg = GeneratedMsg | WindowedMsg

let worker: Worker | null = null

/** 主线程持有的全量数据（生成结果；lab 实验也要用） */
let dataset: { count: number; time: Int32Array; close: Float32Array } | null = null
/** 全览 LTTB 结果 —— lab 的第三种画法直接复用 */
let overviewLine: Array<[number, number]> = []

function handleWorkerMessage(event: MessageEvent<WorkerMsg>): void {
  const msg = event.data
  if (msg.type === 'generated') {
    dataset = { count: msg.count, time: msg.time, close: msg.close }
    genMs.value = msg.genMs
    dataBytes.value = msg.bytes
    generating.value = false
    if (mainChart) {
      mainChart.setOption(baseOption())
      requestResample()
    }
    return
  }
  if (msg.type === 'windowed') {
    sampleMs.value = msg.sampleMs
    viewMode.value = msg.mode
    applyWindowed(msg)
  }
}

// ============================== 主图 ==============================

/** 可见点数 ≤ 该阈值时切蜡烛（周期聚合）——全览时折线保形状。 */
const CANDLE_SWITCH_POINTS = 60_000
/** 蜡烛模式聚合上限（根）与折线采样目标（点）。 */
const MAX_CANDLES = 1500
const MAX_LINE_POINTS = 2000

let mainChart: EChartsType | null = null
let labChart: EChartsType | null = null
let resampleTimer: number | null = null

function volumeLabel(value: number): string {
  if (value >= 1e6) return `${(value / 1e6).toFixed(1)}M`
  if (value >= 1e3) return `${(value / 1e3).toFixed(0)}K`
  return String(Math.round(value))
}

function baseOption(): EChartsCoreOption {
  return {
    animation: false, // 大数据第一戒律：别让每一帧都在补间
    axisPointer: { link: [{ xAxisIndex: 'all' }] },
    grid: [
      { left: 64, right: 24, top: 28, height: '56%' },
      { left: 64, right: 24, top: '70%', height: '20%' }
    ],
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross' },
      confine: true,
      formatter: tooltipFormatter
    },
    xAxis: [
      { type: 'time', gridIndex: 0 },
      { type: 'time', gridIndex: 1, axisLabel: { show: false }, axisTick: { show: false } }
    ],
    yAxis: [
      { scale: true, gridIndex: 0 },
      { gridIndex: 1, splitNumber: 2, axisLabel: { formatter: volumeLabel } }
    ],
    dataZoom: [
      { type: 'inside', xAxisIndex: [0, 1], throttle: 0 },
      { type: 'slider', xAxisIndex: [0, 1], bottom: 4, height: 24 }
    ],
    series: [
      {
        id: 'price-line',
        name: '收盘价',
        type: 'line',
        data: [],
        showSymbol: false,
        lineStyle: { width: 1 },
        emphasis: { disabled: true }
      },
      {
        id: 'price-candle',
        name: 'K 线',
        type: 'candlestick',
        data: [],
        // A 股习惯：红涨绿跌
        itemStyle: { color: '#ef232a', color0: '#14b143', borderColor: '#ef232a', borderColor0: '#14b143' }
      },
      {
        id: 'volume',
        name: '成交量',
        type: 'bar',
        xAxisIndex: 1,
        yAxisIndex: 1,
        data: [],
        // large 模式：跳过逐条样式计算，progressive 分块渲染防长任务
        large: true,
        largeThreshold: 1000,
        progressive: 4000,
        progressiveThreshold: 5000
      }
    ]
  }
}

function tooltipFormatter(params: unknown): string {
  const list = Array.isArray(params) ? params : [params]
  const lines: string[] = []
  for (const item of list) {
    const p = item as { seriesId?: string; axisValue?: number; value?: unknown }
    const time = p.axisValue ? new Date(p.axisValue).toLocaleString() : ''
    if (p.seriesId === 'price-candle' && Array.isArray(p.value)) {
      const [, o, c, l, h] = p.value as number[]
      lines.push(`${time}<br/>开 ${o.toFixed(2)} · 收 ${c.toFixed(2)}<br/>低 ${l.toFixed(2)} · 高 ${h.toFixed(2)}`)
    } else if (p.seriesId === 'price-line' && Array.isArray(p.value)) {
      lines.push(`${time}<br/>收盘 ${Number(p.value[1]).toFixed(2)}`)
    } else if (p.seriesId === 'volume' && Array.isArray(p.value)) {
      lines.push(`量 ${volumeLabel(Number(p.value[1]))}`)
    }
  }
  return lines.join('<br/>')
}

/**
 * dataZoom 事件 → 120ms 尾随节流 → 向 Worker 请求当前窗口的重采样。
 *
 * <p>⚠️ 缩放范围必须从<b>事件参数</b>拿：getOption() 里 dataZoom 的
 * start/end 是初始配置值，不随 inside/滑块的运行时缩放更新 ——
 * 实测拿它判断可见窗口永远得到 100%，蜡烛模式永远不会触发。
 */
function scheduleResample(): void {
  if (resampleTimer !== null || !worker) return
  resampleTimer = window.setTimeout(() => {
    resampleTimer = null
    requestResample()
  }, 120)
}

/** 最近一次 dataZoom 事件报告的窗口（百分比）。restore 时重置。 */
let zoomRange: { start: number; end: number } | null = null

function handleDataZoom(params: unknown): void {
  const p = params as {
    start?: number
    end?: number
    batch?: Array<{ start?: number; end?: number }>
  }
  const b = p.batch?.[0]
  const s = b?.start ?? p.start
  const e = b?.end ?? p.end
  if (typeof s === 'number' && typeof e === 'number') {
    zoomRange = { start: s, end: e }
  }
  scheduleResample()
}

function requestResample(): void {
  if (!mainChart || !dataset || !worker) return
  const range = zoomRange ?? { start: 0, end: 100 }
  const count = dataset.count
  const start = Math.floor((range.start / 100) * (count - 1))
  const end = Math.max(start + 1, Math.ceil((range.end / 100) * count))
  const visible = end - start
  visiblePoints.value = visible
  const asCandle = visible <= CANDLE_SWITCH_POINTS
  worker.postMessage({
    type: 'window',
    start,
    end,
    maxPoints: asCandle ? MAX_CANDLES : MAX_LINE_POINTS,
    asCandle
  })
}

function applyWindowed(msg: WindowedMsg): void {
  if (!mainChart) return
  const t0 = performance.now()
  const times: number[] = []
  for (let i = 0; i < msg.time.length; i++) times.push(msg.time[i] * 1000)
  const values: number[] = []
  for (let i = 0; i < msg.close.length; i++) values.push(msg.close[i])

  if (msg.mode === 'line') {
    const lineData: Array<[number, number]> = times.map((t, i) => [t, values[i]])
    const volData: Array<[number, number]> = times.map((t, i) => [t, msg.volume[i]])
    overviewLine = lineData
    mainChart.setOption({
      series: [
        { id: 'price-line', data: lineData },
        { id: 'price-candle', data: [] },
        { id: 'volume', data: volData }
      ]
    })
    bucketLabel.value = ''
  } else {
    const o = msg.open ?? new Float32Array(0)
    const l = msg.low ?? new Float32Array(0)
    const h = msg.high ?? new Float32Array(0)
    const candleData: Array<[number, number, number, number, number]> = times.map((t, i) => [
      t,
      o[i],
      values[i],
      l[i],
      h[i]
    ])
    const volData: Array<[number, number]> = times.map((t, i) => [t, msg.volume[i]])
    mainChart.setOption({
      series: [
        { id: 'price-line', data: [] },
        { id: 'price-candle', data: candleData },
        { id: 'volume', data: volData }
      ]
    })
    const size = msg.bucketSize ?? 1
    bucketLabel.value =
      size >= 1440
        ? `≈ ${(size / 1440).toFixed(1)} 天`
        : size >= 60
          ? `≈ ${Math.round(size / 60)} 小时`
          : `≈ ${size} 分钟`
  }
  renderMs.value = Math.round(performance.now() - t0)
}

// ============================== 对照实验 ==============================

/**
 * 三种画法共用一个图容器（notMerge 全量替换），耗时口径统一：
 * setOption 的同步成本 + 下一帧（首帧绘制），双 rAF 计时。
 */
function runLab(kind: 'naive' | 'builtin' | 'worker'): void {
  if (!dataset || !labChart || labBusy.value) return
  labBusy.value = true
  let data: Array<[number, number]>
  if (kind === 'worker') {
    data = overviewLine
  } else {
    data = []
    for (let i = 0; i < dataset.count; i++) {
      data[i] = [dataset.time[i] * 1000, dataset.close[i]]
    }
  }
  const option: EChartsCoreOption = {
    animation: false,
    grid: { left: 64, right: 24, top: 24, bottom: 56 },
    tooltip: { trigger: 'axis', confine: true },
    xAxis: { type: 'time' },
    yAxis: { scale: true },
    dataZoom: [{ type: 'inside' }, { type: 'slider', height: 20, bottom: 4 }],
    series: [
      {
        name: '收盘价',
        type: 'line',
        data,
        showSymbol: false,
        lineStyle: { width: 1 },
        // 内置采样：交给 ECharts 在渲染时对当前可见窗口做 LTTB
        sampling: kind === 'builtin' ? 'lttb' : undefined
      }
    ]
  }
  const t0 = performance.now()
  labChart.setOption(option, true)
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      labResults.value[kind] = Math.round(performance.now() - t0)
      labBusy.value = false
    })
  })
}

// ============================== FPS 计 ==============================

let fpsFrames = 0
let fpsLast = performance.now()
let fpsRaf = 0

function fpsTick(now: number): void {
  fpsFrames++
  if (now - fpsLast >= 1000) {
    fps.value = fpsFrames
    fpsFrames = 0
    fpsLast = now
  }
  fpsRaf = requestAnimationFrame(fpsTick)
}

// ============================== 生命周期 ==============================

let resizeObserver: ResizeObserver | null = null

function disposeCharts(): void {
  mainChart?.dispose()
  labChart?.dispose()
  mainChart = null
  labChart = null
}

let initFn: typeof import('echarts/core')['init'] | null = null

function createCharts(): void {
  if (!mainEl.value || !labEl.value || !initFn) return
  const dark = appStore.resolvedTheme === 'dark'
  mainChart = initFn(mainEl.value, dark ? 'dark' : undefined)
  labChart = initFn(labEl.value, dark ? 'dark' : undefined)
  mainChart.setOption(baseOption())
  mainChart.on('dataZoom', handleDataZoom)
  mainChart.on('restore', () => {
    zoomRange = null
    scheduleResample()
  })
  resizeObserver = new ResizeObserver(() => {
    mainChart?.resize()
    labChart?.resize()
  })
  resizeObserver.observe(mainEl.value)
}

function regenerate(): void {
  if (!worker || generating.value) return
  generating.value = true
  labResults.value = { naive: null, builtin: null, worker: null }
  worker.postMessage({ type: 'generate', count: pointCount.value, seed: 20260919 })
}

watch(
  () => appStore.resolvedTheme,
  () => {
    disposeCharts()
    createCharts()
    if (dataset && mainChart) {
      mainChart.setOption(baseOption())
      requestResample()
    }
  }
)

onMounted(async () => {
  const kernel = await ensureEcharts()
  initFn = kernel.init
  // K 线不在适配器默认白名单 —— 安装器跨实例可用，在此按需补注册
  kernel.use([CandlestickChart])
  worker = new Worker(new URL('./stock.worker.ts', import.meta.url), { type: 'module' })
  worker.addEventListener('message', handleWorkerMessage)
  createCharts()
  fpsRaf = requestAnimationFrame(fpsTick)
  regenerate()
})

onBeforeUnmount(() => {
  cancelAnimationFrame(fpsRaf)
  if (resampleTimer !== null) window.clearTimeout(resampleTimer)
  resizeObserver?.disconnect()
  worker?.terminate()
  worker = null
  disposeCharts()
})
</script>

<template>
  <div class="big-chart">
    <NCard title="百万级时序数据 · 股票 K 线" class="big-chart__card" :bordered="false">
      <p class="big-chart__desc">
        1 根 K 线 = 1 分钟，{{ pointCount.toLocaleString() }} 根 ≈
        {{ Math.round(pointCount / 1440 / 30) }} 个月的完整分钟级行情。
        全部数据由 Web Worker 生成（确定性种子，可复现）；
        主图经 LTTB 降采样渲染，缩放时按窗口自动在「折线保形」与「蜡烛聚合」间切换。
      </p>
      <div class="big-chart__toolbar">
        <NRadioGroup v-model:value="pointCount" size="small">
          <NRadioButton
            v-for="opt in POINT_OPTIONS"
            :key="opt.value"
            :value="opt.value"
            :label="opt.label"
          />
        </NRadioGroup>
        <NButton size="small" type="primary" :loading="generating" @click="regenerate">
          重新生成
        </NButton>
        <NTag size="small" type="info">生成 {{ genMs }} ms</NTag>
        <NTag size="small">内存 {{ (dataBytes / 1048576).toFixed(1) }} MB（Float32）</NTag>
        <NTag size="small" :type="fps >= 50 ? 'success' : fps >= 30 ? 'warning' : 'error'">
          FPS {{ fps }}
        </NTag>
      </div>
      <ul class="big-chart__points">
        <li><b>Worker 流水线</b>：生成与重采样在 Worker 线程，主线程只做 setOption；</li>
        <li><b>LTTB 降采样</b>：百万点 → {{ 2000 }} 点，极值保留（等间隔抽稀会抽掉暴跌瞬间）；</li>
        <li>
          <b>缩放感知自适应精度</b>：可见 ≤ 6 万点自动切周期聚合蜡烛（当前
          {{ visiblePoints.toLocaleString() }} 点 → {{ viewMode === 'candle' ? `蜡烛 ${bucketLabel}` : '折线' }}，采样
          {{ sampleMs }} ms / 渲染 {{ renderMs }} ms）；
        </li>
        <li><b>渲染细节</b>：animation:false、成交量 large + progressive、dataZoom 120ms 尾随节流。</li>
      </ul>
    </NCard>

    <NCard
      title="主图：全览折线 ↔ 缩放蜡烛（拖动滑块体验）"
      class="big-chart__card"
      :bordered="false"
    >
      <div ref="mainEl" class="big-chart__main" />
    </NCard>

    <NCard
      title="对照实验：同一份百万点数据的三种画法（点击后盯住 FPS）"
      class="big-chart__card"
      :bordered="false"
    >
      <div class="big-chart__toolbar">
        <NButton size="small" :disabled="labBusy" @click="runLab('naive')">
          ① 朴素全量渲染
        </NButton>
        <NButton size="small" :disabled="labBusy" @click="runLab('builtin')">
          ② 内置 sampling:'lttb'
        </NButton>
        <NButton size="small" :disabled="labBusy" @click="runLab('worker')">
          ③ Worker LTTB 预采样
        </NButton>
        <NTag v-if="labResults.naive !== null" size="small" type="error">
          ① {{ labResults.naive }} ms
        </NTag>
        <NTag v-if="labResults.builtin !== null" size="small" type="warning">
          ② {{ labResults.builtin }} ms
        </NTag>
        <NTag v-if="labResults.worker !== null" size="small" type="success">
          ③ {{ labResults.worker }} ms
        </NTag>
      </div>
      <div ref="labEl" class="big-chart__lab" />
      <p class="big-chart__desc">
        ① 把 100 万对 [时间, 价格] 原样交给 ECharts —— 渲染耗时随数据线性增长，且缩放每一帧都要重算；
        ② 一行 sampling:'lttb'，渲染时对可见窗口自动降采样 —— <b>数据仍全量在内存里</b>，
        构建数组与 GC 压力没变，是"正确性地板"；③ 在 Worker 里把数据先降成 2000 点再 setOption ——
        内存、GC、渲染三项全部最小化，是"最优解"。
      </p>
    </NCard>
  </div>
</template>

<style scoped>
.big-chart__card {
  margin-bottom: 16px;
}

.big-chart__desc {
  margin: 0 0 12px;
  color: var(--n-text-color-2, #666);
  font-size: 13px;
  line-height: 1.8;
}

.big-chart__toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.big-chart__points {
  margin: 0;
  padding-left: 18px;
  color: var(--n-text-color-2, #666);
  font-size: 13px;
  line-height: 2;
}

.big-chart__main {
  height: 460px;
  width: 100%;
}

.big-chart__lab {
  height: 320px;
  width: 100%;
}
</style>
