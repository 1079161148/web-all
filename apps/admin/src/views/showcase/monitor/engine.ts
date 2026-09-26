/**
 * 实时监控数据引擎：高频推送 → 节流聚合 → 局部更新。
 *
 * <h3>本文件回答四个生产问题（页面只是这四个问题的可视化）</h3>
 *
 * <h4>① 高频推送为什么不能直接 ref 赋值</h4>
 * 每秒几十条 tick，若每条都写响应式状态，Vue 会以同等频率触发组件树
 * 重渲染 —— 页面温度升高、掉帧，而用户根本看不出"每条"与"每 0.4 秒一批"
 * 的区别。因此 tick 进入**非响应式缓冲区**（普通数组），
 * 由固定频率的 flush 聚合成快照后一次性替换 shallowRef ——
 * 渲染频率与推送频率解耦（本页：60/s 进 → 2.5/s 出）。
 *
 * <h4>② 时间轴对齐</h4>
 * 各数据源的时钟不保证一致（演示里"华南"源带 0~2s 随机偏移）。
 * 直接按到达顺序画折线会出现"时间倒流"。做法：所有 tick 按
 * `floor(ts / 1000)` 落进**秒级时间桶**，桶内累加 ——
 * 乱序/迟到/偏移天然被桶吸收，x 轴永远单调递增。
 *
 * <h4>③ 断线重连与补偿</h4>
 * 每条 tick 带**单调递增 seq**。断线恢复后引擎比对 seq：
 * 出现空洞 → 调用 `transport.backfill(fromSeq)` 补拉缺失段 →
 * 补偿数据与实时数据走同一条聚合管线（时间桶会正确回填空洞）。
 * 重连用指数退避（1s/2s/4s，上限 8s），并带抖动避免惊群。
 *
 * <h4>④ 性能降级</h4>
 * 图表点数随运行时间线性增长，超过阈值（240 桶）后自动切换到
 * **5 秒聚合桶** —— 点数回到 ~1/5，曲线形状几乎不变。
 * 是"采样或聚合"，不是"硬渲染到卡死"。
 *
 * <h3>传输层是策略（与真实 WebSocket 的契约）</h3>
 * 页面演示用 MockTransport（本地生成 + 可模拟断线）。
 * 生产替换为真实 WS 时实现同一接口即可：
 * <pre>
 *   onTicks(batch)      ← 服务端每 100~500ms 批量推（不要单条推）
 *   backfill(fromSeq)   ← REST：GET /metrics/backfill?fromSeq=
 * </pre>
 * 关键纪律：<b>批量推</b>。服务端把 1 秒的 tick 打包成 1 帧下发，
 * 与前端节流是同一个思想在两端的体现。
 */

export interface Tick {
  seq: number
  /** 数据源时间戳（各源时钟不保证对齐）。 */
  ts: number
  region: string
  amount: number
  ok: boolean
}

/** 快照：一次 flush 聚合出的全部渲染数据（整体替换 shallowRef）。 */
export interface FeedSnapshot {
  buckets: Array<{ ts: number; count: number; amount: number; errors: number }>
  regions: Array<{ region: string; count: number; amount: number; errorRate: number }>
  alerts: Array<{ ts: number; region: string; amount: number }>
  stats: {
    /** 最近一秒的订单量（大屏主数字）。 */
    qps: number
    total: number
    received: number
    backfilled: number
    lateDropped: number
    queue: number
    /** 当前聚合级别（秒）。 */
    bucketSeconds: number
    connected: boolean
    reconnecting: boolean
  }
}

export interface MonitorTransport {
  start(onTicks: (ticks: Tick[]) => void, onDisconnect: () => void): void
  stop(): void
  /** 断线恢复后补拉指定 seq 之后的数据（服务端有保留窗口）。 */
  backfill(fromSeq: number): Promise<Tick[]>
  /** 是否仍连接。 */
  isConnected(): boolean
}

/**
 * 真实 WebSocket 传输（对接后端 /ws/monitor）。
 *
 * <h3>协议</h3>
 * <ul>
 *   <li>握手：{@code /ws/monitor?ticket=xxx} —— 一次性短票据，
 *       复用消息中心的票据池（浏览器 WS 不能带自定义请求头）</li>
 *   <li>下行：{"type":"ticks","ticks":[Tick…]}（服务端 120ms 批量一帧）</li>
 *   <li>上行：{"type":"backfill","fromSeq":N} —— seq 空洞补拉</li>
 * </ul>
 *
 * <h3>重连在传输层内部完成</h3>
 * 与 Mock 不同：WS 的断线由传输层自己指数退避重连（每次重连前
 * 取<b>新</b>票据 —— 旧票一次性已作废），引擎对此无感知。
 * 断线期间引擎的 seq 空洞检测照常工作：重连后回发 backfill 请求补齐。
 */
export class WsTransport implements MonitorTransport {
  private ws: WebSocket | null = null
  private handler: ((ticks: Tick[]) => void) | null = null
  private onDisconnectCb: (() => void) | null = null
  private attempt = 0
  private retryTimer: number | null = null
  private closed = false
  private lastSeq = 0

  isConnected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN
  }

  start(onTicks: (ticks: Tick[]) => void, onDisconnect: () => void): void {
    this.handler = onTicks
    this.onDisconnectCb = onDisconnect
    this.closed = false
    void this.connect()
  }

  stop(): void {
    this.closed = true
    if (this.retryTimer !== null) {
      window.clearTimeout(this.retryTimer)
      this.retryTimer = null
    }
    this.ws?.close()
    this.ws = null
  }

  async backfill(fromSeq: number): Promise<Tick[]> {
    if (this.ws?.readyState !== WebSocket.OPEN) {
      return []
    }
    // 补拉走同一条 WS 连接：服务端回发的 ticks 帧会自然进入 onTicks 管线，
    // 引擎的 seq 判重/基线推进由 detectGap 侧处理 —— 无需在此等待结果
    this.ws.send(JSON.stringify({ type: 'backfill', fromSeq }))
    return []
  }

  private async connect(): Promise<void> {
    try {
      const { issueStreamTicket } = await import('@/api/message')
      const ticket = await issueStreamTicket()
      const proto = location.protocol === 'https:' ? 'wss' : 'ws'
      const ws = new WebSocket(`${proto}://${location.host}/ws/monitor?ticket=${encodeURIComponent(ticket)}`)
      this.ws = ws
      ws.onmessage = (event) => {
        try {
          const frame = JSON.parse(event.data) as { type: string; ticks: Tick[] }
          if (frame.type === 'ticks' && Array.isArray(frame.ticks)) {
            this.attempt = 0
            this.handler?.(frame.ticks)
            for (const tick of frame.ticks) {
              this.lastSeq = Math.max(this.lastSeq, tick.seq)
            }
          }
        } catch {
          // 非 JSON 帧：忽略
        }
      }
      ws.onclose = () => {
        if (this.closed) {
          return
        }
        this.onDisconnectCb?.()
        this.scheduleReconnect()
      }
      ws.onerror = () => ws.close()
    } catch {
      this.scheduleReconnect()
    }
  }

  private scheduleReconnect(): void {
    if (this.closed || this.retryTimer !== null) {
      return
    }
    const delay = Math.min(1000 * 2 ** this.attempt, 8000)
    this.attempt += 1
    this.retryTimer = window.setTimeout(() => {
      this.retryTimer = null
      void this.connect()
    }, delay)
  }
}

/** 华南源的时钟偏移量（演示"时间轴对齐"用；0 = 关闭）。 */
export const REGIONS = ['华东', '华北', '华南', '西南', '东北', '海外']

export class MockTransport implements MonitorTransport {
  private seq = 0
  private timer: number | null = null
  private history: Tick[] = []
  private handler: ((ticks: Tick[]) => void) | null = null
  private offline = false
  /** 离线期间的 tick 照常生成并进 history（服务端视角：数据没丢，只是没消费者）。 */
  private offlineTicks: Tick[] = []
  private skewMs = 0

  setSkew(ms: number): void {
    this.skewMs = ms
  }

  isConnected(): boolean {
    return !this.offline
  }

  start(onTicks: (ticks: Tick[]) => void, _onDisconnect: () => void): void {
    this.handler = onTicks
    this.offline = false
    if (this.timer !== null) {
      return
    }
    // 每 120ms 生成一帧（每帧 4~8 条 → 每秒约 30~60 条）
    this.timer = window.setInterval(() => {
      const batch: Tick[] = []
      const size = 4 + Math.floor(Math.random() * 5)
      for (let i = 0; i < size; i++) {
        this.seq += 1
        const region = REGIONS[Math.floor(Math.random() * REGIONS.length)]
        const ts = Date.now() - (region === '华南' ? Math.floor(Math.random() * this.skewMs) : 0)
        const tick: Tick = {
          seq: this.seq,
          ts,
          region,
          amount: Math.round(Math.random() * 800 + 20),
          ok: Math.random() > 0.03
        }
        batch.push(tick)
        this.history.push(tick)
        if (this.history.length > 6000) {
          this.history.splice(0, 1000)
        }
      }
      // 离线时数据照常产生（服务端不依赖消费者存在）
      if (this.offline) {
        this.offlineTicks.push(...batch)
      } else if (this.handler) {
        this.handler(batch)
      }
    }, 120)
  }

  stop(): void {
    if (this.timer !== null) {
      window.clearInterval(this.timer)
      this.timer = null
    }
    this.handler = null
  }

  setOffline(seconds: number, onReconnect: () => void): void {
    if (this.offline) {
      return
    }
    this.offline = true
    window.setTimeout(() => {
      this.offline = false
      // ⚠️ 刻意丢弃离线期间到达连接的数据（模拟"连接断了就丢了"）：
      // 这样客户端 seq 出现空洞 → 引擎走 backfill 补偿路径 ——
      // 这正是要演示的机制。若在这里把积压塞回 handler，补偿计数器永远不动
      this.offlineTicks = []
      onReconnect()
    }, seconds * 1000)
  }

  async backfill(fromSeq: number): Promise<Tick[]> {
    // 模拟网络往返
    await new Promise((resolve) => window.setTimeout(resolve, 150))
    return this.history.filter((tick) => tick.seq > fromSeq)
  }
}

const BUCKET_MS = 1000
const AGGREGATE_THRESHOLD = 240
const AGGREGATE_TO = 5
const RETENTION_MS = 120_000

export class MonitorFeed {
  private transport: MonitorTransport
  private buffer: Tick[] = []
  private lastSeq = 0
  private flushTimer: number | null = null
  private reconnectTimer: number | null = null
  private reconnectAttempt = 0
  private buckets = new Map<number, { count: number; amount: number; errors: number }>()
  private regionTotals = new Map<string, { count: number; amount: number; errors: number }>()
  private alerts: FeedSnapshot['alerts'] = []
  private counters = { received: 0, backfilled: 0, lateDropped: 0, total: 0 }
  private connected = false
  private reconnecting = false
  private destroyed = false

  /** 渲染快照（shallowRef 由页面持有，这里通过回调吐出）。 */
  onSnapshot: ((snapshot: FeedSnapshot) => void) | null = null

  constructor(transport: MonitorTransport) {
    this.transport = transport
  }

  start(): void {
    this.connect()
    // 节流 flush：缓冲区 400ms 清算一次（推送 60/s → 渲染 2.5/s）
    this.flushTimer = window.setInterval(() => this.flush(), 400)
  }

  destroy(): void {
    this.destroyed = true
    if (this.flushTimer !== null) {
      window.clearInterval(this.flushTimer)
    }
    if (this.reconnectTimer !== null) {
      window.clearTimeout(this.reconnectTimer)
    }
    this.transport.stop()
  }

  private connect(): void {
    this.transport.start(
      (ticks) => {
        this.connected = true
        this.reconnecting = false
        this.reconnectAttempt = 0
        this.buffer.push(...ticks)
        this.counters.received += ticks.length
        this.detectGap()
      },
      () => this.onDisconnected()
    )
  }

  /** seq 空洞检测：收到了 100、104 → 中间的 101~103 需要补拉。 */
  private detectGap(): void {
    const maxSeq = this.buffer.reduce((max, tick) => Math.max(max, tick.seq), this.lastSeq)
    if (maxSeq - this.lastSeq > this.buffer.length) {
      void this.backfill(this.lastSeq)
    }
    this.lastSeq = Math.max(this.lastSeq, maxSeq)
  }

  private async backfill(fromSeq: number): Promise<void> {
    try {
      const missed = await this.transport.backfill(fromSeq)
      if (missed.length === 0 || this.destroyed) {
        return
      }
      this.buffer.push(...missed)
      this.counters.backfilled += missed.length
      // 补偿数据 seq 可能小于已见最大值：重算基线
      this.lastSeq = missed.reduce((max, tick) => Math.max(max, tick.seq), this.lastSeq)
    } catch {
      // 补偿失败不致命：下一条 tick 到达时会再次检测到空洞并重试
    }
  }

  private onDisconnected(): void {
    this.connected = false
    this.reconnecting = true
    this.scheduleReconnect()
  }

  /** 通知引擎"连接已断"（真实传输层在 onDisconnect 回调里触发；
   *  Mock 传输由页面在模拟断线时显式调用，让重连指示与补偿流程可见）。 */
  notifyDisconnect(): void {
    this.onDisconnected()
  }

  /** 指数退避重连（1s/2s/4s/8s 封顶），真实 WS 场景还应有随机抖动。 */
  private scheduleReconnect(): void {
    if (this.destroyed || this.reconnectTimer !== null) {
      return
    }
    const delay = Math.min(1000 * 2 ** this.reconnectAttempt, 8000)
    this.reconnectAttempt += 1
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null
      if (!this.transport.isConnected()) {
        this.scheduleReconnect()
      }
    }, delay)
  }

  /** 清算缓冲区：聚合进时间桶 → 产出快照。 */
  private flush(): void {
    const pending = this.buffer
    this.buffer = []

    for (const tick of pending) {
      const bucketTs = Math.floor(tick.ts / BUCKET_MS) * BUCKET_MS
      // 时间对齐的边界：比保留窗口还旧的数据（补偿迟到太久）计数丢弃，
      // 否则补偿会把几万条历史一次性灌进图表
      if (bucketTs < Date.now() - RETENTION_MS) {
        this.counters.lateDropped += 1
        continue
      }
      const bucket = this.buckets.get(bucketTs) ?? { count: 0, amount: 0, errors: 0 }
      bucket.count += 1
      bucket.amount += tick.amount
      if (!tick.ok) {
        bucket.errors += 1
      }
      this.buckets.set(bucketTs, bucket)

      const region = this.regionTotals.get(tick.region) ?? {
        count: 0,
        amount: 0,
        errors: 0
      }
      region.count += 1
      region.amount += tick.amount
      if (!tick.ok) {
        region.errors += 1
      }
      this.regionTotals.set(tick.region, region)

      this.counters.total += 1
      if (!tick.ok && this.alerts.length < 50) {
        this.alerts.unshift({ ts: tick.ts, region: tick.region, amount: tick.amount })
      }
    }

    // 保留窗口外的桶移除（Map 有序插入，旧的在前面）
    const cutoff = Date.now() - RETENTION_MS
    for (const key of this.buckets.keys()) {
      if (key < cutoff) {
        this.buckets.delete(key)
      } else {
        break
      }
    }

    this.emit()
  }

  /** 自动降级：桶数超阈值 → 合并为 5 秒桶（点数 ≈ 1/5，形状几乎不变）。 */
  private buildChartBuckets(): FeedSnapshot['buckets'] {
    const sorted = [...this.buckets.entries()].sort((a, b) => a[0] - b[0])
    const bucketSeconds = sorted.length > AGGREGATE_THRESHOLD ? AGGREGATE_TO : 1
    if (bucketSeconds === 1) {
      return sorted.map(([ts, bucket]) => ({ ts, ...bucket }))
    }
    const merged = new Map<number, { count: number; amount: number; errors: number }>()
    for (const [ts, bucket] of sorted) {
      const key = Math.floor(ts / (AGGREGATE_TO * BUCKET_MS)) * (AGGREGATE_TO * BUCKET_MS)
      const target = merged.get(key) ?? { count: 0, amount: 0, errors: 0 }
      target.count += bucket.count
      target.amount += bucket.amount
      target.errors += bucket.errors
      merged.set(key, target)
    }
    return [...merged.entries()].map(([ts, bucket]) => ({ ts, ...bucket }))
  }

  private emit(): void {
    if (!this.onSnapshot) {
      return
    }
    const buckets = this.buildChartBuckets()
    const lastBucket = buckets[buckets.length - 1]
    const regions = [...this.regionTotals.entries()]
      .map(([region, totals]) => ({
        region,
        count: totals.count,
        amount: totals.amount,
        errorRate: totals.count === 0 ? 0 : totals.errors / totals.count
      }))
      .sort((a, b) => b.amount - a.amount)

    this.onSnapshot({
      buckets,
      regions,
      alerts: this.alerts.slice(0, 8),
      stats: {
        qps: lastBucket?.count ?? 0,
        total: this.counters.total,
        received: this.counters.received,
        backfilled: this.counters.backfilled,
        lateDropped: this.counters.lateDropped,
        queue: this.buffer.length,
        bucketSeconds: this.buckets.size > AGGREGATE_THRESHOLD ? AGGREGATE_TO : 1,
        connected: this.connected,
        reconnecting: this.reconnecting
      }
    })
  }
}
