// 本文件运行在 DedicatedWorker 环境：postMessage 没有（也不需要）targetOrigin
// 参数 —— oxlint 的 require-post-message-target-origin 按 window 语义误报，此处禁用。
// eslint-disable unicorn/require-post-message-target-origin
/// <reference lib="webworker" />

/**
 * 百万级 K 线数据：生成与重采样全部在 Worker 线程完成。
 *
 * <h3>为什么这条流水线必须放在 Worker</h3>
 * <ul>
 *   <li><b>生成</b>：100 万根 K 线的随机游走计算约数百毫秒 —— 主线程做会把
 *       页面冻住整段时间（所有 rAF / 交互全部停摆）；</li>
 *   <li><b>重采样</b>：缩放联动时每次都要对可见窗口重算，若在主线程做，
 *       "拖动滑块"会变成"拖一下卡一下"。</li>
 * </ul>
 *
 * <h3>Transferable 的取舍（踩过才会写进注释的坑）</h3>
 * 生成的 24 MB TypedArray <b>刻意不用 Transferable 传给主线程</b>：
 * transfer 会让 Worker 侧的 buffer 变成 detached —— 而它必须留在 Worker 里
 * 持续响应缩放重采样。这里"Worker 要持续服务"的收益大于一次 24 MB 的
 * 结构化克隆拷贝（实测几十毫秒，且只发生一次）。
 * 对比：上传页的 hash.worker 把整文件 transfer 过去后 Worker 就此销毁 ——
 * 那才是 Transferable 的标准场景。
 *
 * <h3>确定性随机</h3>
 * mulberry32 + 固定种子 —— 同种子生成完全相同的数据，
 * 演示与截图可复现，也便于对比"朴素 / 内置采样 / Worker 采样"三种渲染。
 */

interface GenerateMsg {
  type: 'generate'
  /** K 线根数（每根 = 1 分钟） */
  count: number
  seed: number
}

interface WindowMsg {
  type: 'window'
  /** 可见窗口 [start, end)（数据索引） */
  start: number
  end: number
  maxPoints: number
  /** true = 聚合成更大的周期 K 线；false = LTTB 折线 */
  asCandle: boolean
}

type Req = GenerateMsg | WindowMsg

/** mulberry32：确定性伪随机，同种子同序列。 */
function mulberry32(seed: number): () => number {
  let a = seed >>> 0
  return () => {
    a |= 0
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/** Box-Muller 高斯随机数。 */
function gauss(rand: () => number): number {
  let u = 0
  let v = 0
  while (u === 0) u = rand()
  while (v === 0) v = rand()
  return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v)
}

const MINUTES_PER_DAY = 1440
/** 2024-01-01T00:00:00Z（秒）—— 每根 K 线 1 分钟。 */
const START_TS = 1704038400

interface Dataset {
  count: number
  time: Int32Array
  open: Float32Array
  close: Float32Array
  low: Float32Array
  high: Float32Array
  volume: Float32Array
}

let dataset: Dataset | null = null

/**
 * 几何布朗运动（GBM）+ 三层真实感：
 * 1. 日内 U 型波动率（开盘/收盘高、午间低）—— 真实市场最稳定的规律之一；
 * 2. 波动率聚集（GARCH 思想的极简版）：shock 缓慢均值回归，大涨大跌会"扎堆"；
 * 3. 趋势段轮换：每 3~12 天切换漂移方向，产生肉眼可见的牛熊段。
 */
function generate(count: number, seed: number): Dataset {
  const rand = mulberry32(seed)
  const time = new Int32Array(count)
  const open = new Float32Array(count)
  const close = new Float32Array(count)
  const low = new Float32Array(count)
  const high = new Float32Array(count)
  const volume = new Float32Array(count)

  let price = 100
  const baseVol = 0.0009
  let shock = 1
  // 长期微成长：每分钟 +0.00015%，100 万根后约 4.5 倍 —— 真实股市量级
  const drift = 0.0000015
  let trendDrift = 0
  let trendLeft = 8000

  for (let i = 0; i < count; i++) {
    const minuteOfDay = i % MINUTES_PER_DAY
    if (minuteOfDay === 0) {
      // 隔夜跳空
      price *= 1 + gauss(rand) * baseVol * 8
      if (trendLeft <= 0) {
        // 对称轮换（均值为 0）—— 偏置会让百万级复利爆炸成天文数字
        trendDrift = (rand() - 0.5) * 0.00006
        trendLeft = 3000 + Math.floor(rand() * 15000)
      }
      trendLeft--
    }
    const half = MINUTES_PER_DAY / 2
    const uShape = 1 + 1.6 * Math.pow(Math.abs(minuteOfDay - half) / half, 2)
    shock = Math.max(0.4, Math.min(3, shock * 0.995 + 0.005 + gauss(rand) * 0.03))
    const sigma = baseVol * uShape * shock

    const o = price
    const c = o * (1 + drift + trendDrift + gauss(rand) * sigma)
    const wick = o * sigma * (0.5 + rand())
    const h = Math.max(o, c) + wick * rand()
    const l = Math.min(o, c) - wick * rand()
    const v = (0.4 + rand() * 0.6) * uShape * (0.5 + shock * 0.5) * 10000

    time[i] = START_TS + i * 60
    open[i] = o
    close[i] = c
    high[i] = h
    low[i] = l
    volume[i] = v
    price = c
  }
  return { count, time, open, close, low, high, volume }
}

/**
 * LTTB（Largest-Triangle-Three-Buckets）降采样。
 *
 * <h3>为什么不是等间隔抽稀</h3>
 * 等间隔抽稀（每 N 个取 1 个）会把稀有的极端值（暴跌瞬间）几乎必然抽掉 ——
 * 而"哪里有极值"恰恰是看图的人最关心的。LTTB 按"与前后参考点构成的
 * 三角形面积"选代表点：偏离整体趋势越远的点越容易被保留，
 * 所以视觉形状与全量数据几乎无差（这正是它的卖点）。
 *
 * <p>x 轴用数组下标而不是时间戳：数据等间隔（1 分钟），下标等价于时间，
 * 且避免了 Int32 → number 的转换开销。
 */
function lttb(
  values: Float32Array,
  start: number,
  end: number,
  target: number
): { values: Float32Array; indices: Int32Array } {
  const n = end - start
  if (n <= target) {
    const idx = new Int32Array(n)
    for (let i = 0; i < n; i++) idx[i] = start + i
    return { values: values.slice(start, end), indices: idx }
  }
  const indices = new Int32Array(target)
  const out = new Float32Array(target)
  indices[0] = start
  out[0] = values[start]
  indices[target - 1] = end - 1
  out[target - 1] = values[end - 1]

  const every = (n - 2) / (target - 2)
  let prevIndex = start
  for (let i = 1; i < target - 1; i++) {
    const bucketStart = start + 1 + Math.floor((i - 1) * every)
    const bucketEnd = Math.min(start + 1 + Math.floor(i * every), end - 1)
    // 下一桶的平均值作为"第三点"
    const nextStart = bucketEnd
    const nextEnd = Math.min(start + 1 + Math.floor((i + 1) * every), end)
    let avgV = 0
    for (let j = nextStart; j < nextEnd; j++) avgV += values[j]
    avgV /= Math.max(1, nextEnd - nextStart)
    const avgX = nextStart + (nextEnd - nextStart - 1) / 2

    let bestIdx = bucketStart
    let bestArea = -1
    for (let j = bucketStart; j < bucketEnd; j++) {
      const area = Math.abs(
        (prevIndex - avgX) * (values[j] - values[prevIndex]) -
          (prevIndex - j) * (avgV - values[prevIndex])
      )
      if (area > bestArea) {
        bestArea = area
        bestIdx = j
      }
    }
    indices[i] = bestIdx
    out[i] = values[bestIdx]
    prevIndex = bestIdx
  }
  return { values: out, indices }
}

/**
 * 分钟线 → 更大周期 K 线的聚合。
 *
 * <h3>为什么缩放深了要切 K 线而不是继续 LTTB</h3>
 * LTTB 回答"形状长什么样"，K 线回答"这一段里发生了什么"（开高低收、量）。
 * 缩放到较小窗口后用户要的是后者 —— 聚合到合适的周期
 * （100 万根全览 → 2000 根 ≈ 周线量级；缩到一天 → 15 分钟量级），
 * 这正是专业行情软件"缩放越深、周期越细"的自适应精度。
 */
function aggregate(
  d: Dataset,
  start: number,
  end: number,
  maxBars: number
): {
  bars: number
  size: number
  time: Int32Array
  open: Float32Array
  close: Float32Array
  low: Float32Array
  high: Float32Array
  volume: Float32Array
} {
  const n = end - start
  const size = Math.max(1, Math.ceil(n / maxBars))
  const bars = Math.ceil(n / size)
  const time = new Int32Array(bars)
  const open = new Float32Array(bars)
  const close = new Float32Array(bars)
  const low = new Float32Array(bars)
  const high = new Float32Array(bars)
  const volume = new Float32Array(bars)

  for (let b = 0; b < bars; b++) {
    const s = start + b * size
    const e = Math.min(s + size, end)
    time[b] = d.time[s]
    open[b] = d.open[s]
    close[b] = d.close[e - 1]
    let hi = -Infinity
    let lo = Infinity
    let vv = 0
    for (let j = s; j < e; j++) {
      if (d.high[j] > hi) hi = d.high[j]
      if (d.low[j] < lo) lo = d.low[j]
      vv += d.volume[j]
    }
    high[b] = hi
    low[b] = lo
    volume[b] = vv
  }
  return { bars, size, time, open, close, low, high, volume }
}

self.onmessage = (event: MessageEvent<Req>) => {
  const msg = event.data
  if (msg.type === 'generate') {
    const t0 = performance.now()
    dataset = generate(msg.count, msg.seed)
    const genMs = Math.round(performance.now() - t0)
    // 刻意不 transfer（见文件头注释）：Worker 还要留着数据响应缩放
    const payload = {
      type: 'generated',
      genMs,
      count: dataset.count,
      bytes: dataset.time.byteLength + dataset.open.byteLength * 5,
      time: dataset.time,
      close: dataset.close
    }
    ;(self as unknown as Worker).postMessage(payload)
    return
  }

  if (msg.type === 'window') {
    if (!dataset) {
      ;(self as unknown as Worker).postMessage({ type: 'error', message: '数据尚未生成' })
      return
    }
    const t0 = performance.now()
    const start = Math.max(0, Math.min(msg.start, dataset.count - 1))
    const end = Math.max(start + 1, Math.min(msg.end, dataset.count))
    if (msg.asCandle) {
      const agg = aggregate(dataset, start, end, msg.maxPoints)
      ;(self as unknown as Worker).postMessage({
        type: 'windowed',
        mode: 'candle',
        sampleMs: Math.round(performance.now() - t0),
        bars: agg.bars,
        bucketSize: agg.size,
        time: agg.time,
        open: agg.open,
        close: agg.close,
        low: agg.low,
        high: agg.high,
        volume: agg.volume
      })
    } else {
      const r = lttb(dataset.close, start, end, msg.maxPoints)
      const times = new Int32Array(r.indices.length)
      for (let i = 0; i < r.indices.length; i++) {
        times[i] = dataset.time[r.indices[i]]
      }
      // 成交量取选中下标处的值：与折线时间严格对齐
      // （蜡烛模式下才是真正的周期求和聚合）
      const volume = new Float32Array(r.indices.length)
      for (let i = 0; i < r.indices.length; i++) {
        volume[i] = dataset.volume[r.indices[i]]
      }
      ;(self as unknown as Worker).postMessage({
        type: 'windowed',
        mode: 'line',
        sampleMs: Math.round(performance.now() - t0),
        points: r.values.length,
        time: times,
        close: r.values,
        volume
      })
    }
  }
}
