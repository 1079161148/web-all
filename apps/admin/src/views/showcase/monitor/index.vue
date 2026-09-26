<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import {
  NButton,
  NCard,
  NProgress,
  NRadioButton,
  NRadioGroup,
  NSwitch,
  NTag,
  ProChart,
  feedback,
  type EChartsOption
} from '@admin/ui'
import { MockTransport, MonitorFeed, WsTransport, type FeedSnapshot } from './engine'

/**
 * 实时订单监控大屏：WebSocket 高频推送下的渲染优化演示。
 *
 * <h3>四个生产问题 → 四个对应机制（引擎见 ./engine.ts）</h3>
 * <table>
 *   <tr><th>问题</th><th>机制</th><th>在本页怎么验证</th></tr>
 *   <tr>
 *     <td>每秒几十条 tick 直接 ref 赋值 → 整树重渲染</td>
 *     <td>非响应式缓冲 + 400ms 节流 flush，快照整体替换 shallowRef</td>
 *     <td>推送 60 条/秒，页面以 2.5 次/秒更新；「缓冲区」数字实时可见</td>
 *   </tr>
 *   <tr>
 *     <td>多数据源时钟不一致 → 折线"时间倒流"</td>
 *     <td>全部 tick 按 floor(ts/1000) 落秒级时间桶</td>
 *     <td>打开"时钟偏移"，华南源带 0~2s 抖动，曲线依旧单调</td>
 *   </tr>
 *   <tr>
 *     <td>断线 → 曲线断档</td>
 *     <td>单调 seq 空洞检测 → backfill 补拉 → 与实时数据同管线聚合</td>
 *     <td>点"模拟断线 5s"：重连后「已补偿」计数跳升，曲线无缺口</td>
 *   </tr>
 *   <tr>
 *     <td>点数超阈值硬渲染 → 卡死</td>
 *     <td>桶数 > 240 自动切 5 秒聚合桶（点数 ≈1/5）</td>
 *     <td>挂机几分钟，"渲染模式"从秒级自动变为 5s 聚合</td>
 *   </tr>
 * </table>
 *
 * <h3>传输层说明</h3>
 * 演示用 MockTransport（本地生成 + 可模拟断线）。真实 WebSocket 只需实现
 * 同一接口：服务端每 100~500ms **批量**推送一帧（批量推与前端节流是
 * 同一个思想在两端的体现），断档由 `backfill(fromSeq)` REST 接口补齐。
 */

const snapshot = shallowRef<FeedSnapshot | null>(null)
const skewEnabled = ref(false)
const running = ref(true)

type TransportKind = 'WS' | 'MOCK'
const transportKind = ref<TransportKind>('WS')

let transport: MockTransport | WsTransport | null = null
let feed: MonitorFeed | null = null

onMounted(() => {
  startFeed('WS')
})

onBeforeUnmount(() => {
  feed?.destroy()
  feed = null
  transport = null
})

function startFeed(kind: TransportKind): void {
  // 重建引擎（旧引擎销毁）：传输切换 = 全新数据源
  feed?.destroy()
  transport = kind === 'WS' ? new WsTransport() : new MockTransport()
  feed = new MonitorFeed(transport)
  feed.onSnapshot = (snapshotValue) => {
    snapshot.value = snapshotValue
  }
  feed.start()
  running.value = true
  snapshot.value = null
}

// ---------------------------------------------------------------------
// 控制
// ---------------------------------------------------------------------

function simulateDisconnect(): void {
  if (!transport || !feed) {
    return
  }
  if (transport instanceof WsTransport) {
    // 真实 WS：关闭连接（传输层内部自动重连 + 服务端历史补拉）
    transport.stop()
    transport = new WsTransport()
    const oldFeed = feed
    feed = new MonitorFeed(transport)
    feed.onSnapshot = (snapshotValue) => {
      snapshot.value = snapshotValue
    }
    feed.start()
    oldFeed.destroy()
    feedback.warning('已断开 WebSocket：传输层将自动重连并补偿缺口')
    return
  }
  transport.setOffline(5, () => {})
  feed.notifyDisconnect()
  feedback.warning('已模拟断线：5 秒后自动重连并补偿缺口数据')
}

function toggleSkew(value: boolean): void {
  skewEnabled.value = value
  if (transport instanceof MockTransport) {
    transport.setSkew(value ? 2000 : 0)
  }
}

function toggleRun(value: boolean): void {
  running.value = value
  if (!feed) {
    return
  }
  if (value) {
    feed.start()
  } else {
    feed.destroy()
  }
}

function onTransportChange(kind: TransportKind): void {
  transportKind.value = kind
  startFeed(kind)
}

// ---------------------------------------------------------------------
// 渲染数据（全部从快照派生 —— 每次快照替换只更新真正变化的绑定）
// ---------------------------------------------------------------------

const stats = computed(() => snapshot.value?.stats)

const qps = computed(() => snapshot.value?.stats.qps ?? 0)
const total = computed(() => snapshot.value?.stats.total.toLocaleString() ?? '0')

const connectionTag = computed(() => {
  const state = stats.value
  if (!state || state.connected) {
    return { label: '已连接', type: 'success' as const }
  }
  return state.reconnecting
    ? { label: '重连中…', type: 'warning' as const }
    : { label: '已断开', type: 'error' as const }
})

const renderMode = computed(() => {
  const seconds = stats.value?.bucketSeconds ?? 1
  return seconds === 1
    ? { label: '秒级', type: 'info' as const }
    : { label: `${seconds}s 聚合（降级）`, type: 'warning' as const }
})

const maxRegionAmount = computed(() =>
  Math.max(1, ...(snapshot.value?.regions.map((region) => region.amount) ?? [1]))
)

const chartOption = computed<EChartsOption | null>(() => {
  const buckets = snapshot.value?.buckets ?? []
  if (buckets.length < 2) {
    return null
  }
  const labels = buckets.map((bucket) =>
    new Date(bucket.ts).toLocaleTimeString('zh-CN', { hour12: false })
  )
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['订单量', '成交金额'], textStyle: { fontSize: 11 } },
    grid: { left: 48, right: 56, top: 36, bottom: 28 },
    xAxis: { type: 'category', data: labels, boundaryGap: false, axisLabel: { fontSize: 10 } },
    yAxis: [
      { type: 'value', name: '单/秒', axisLabel: { fontSize: 10 } },
      { type: 'value', name: '金额', axisLabel: { fontSize: 10 }, splitLine: { show: false } }
    ],
    series: [
      {
        name: '订单量',
        type: 'line',
        smooth: true,
        showSymbol: false,
        data: buckets.map((bucket) => bucket.count),
        areaStyle: { opacity: 0.15 },
        lineStyle: { width: 2 }
      },
      {
        name: '成交金额',
        type: 'line',
        smooth: true,
        showSymbol: false,
        yAxisIndex: 1,
        data: buckets.map((bucket) => bucket.amount),
        lineStyle: { width: 1.5, type: 'dashed' }
      }
    ]
  }
})

function formatTime(ts: number): string {
  return new Date(ts).toLocaleTimeString('zh-CN', { hour12: false })
}
</script>

<template>
  <div class="monitor">
    <!-- ===== 状态条 ===== -->
    <div class="monitor__stats">
      <NCard size="small" :bordered="false" class="monitor__hero">
        <div class="monitor__stat">
          <span class="monitor__stat-label">每秒订单量</span>
          <span class="monitor__stat-value">{{ qps }}</span>
          <span class="monitor__stat-unit">单/秒</span>
        </div>
      </NCard>
      <NCard size="small" :bordered="false">
        <div class="monitor__stat">
          <span class="monitor__stat-label">累计订单</span>
          <span class="monitor__stat-value">{{ total }}</span>
        </div>
      </NCard>
      <NCard size="small" :bordered="false">
        <div class="monitor__stat">
          <span class="monitor__stat-label">补偿数据</span>
          <span class="monitor__stat-value">{{ stats?.backfilled ?? 0 }}</span>
        </div>
      </NCard>
      <NCard size="small" :bordered="false">
        <div class="monitor__stat">
          <span class="monitor__stat-label">迟到丢弃</span>
          <span class="monitor__stat-value">{{ stats?.lateDropped ?? 0 }}</span>
        </div>
      </NCard>
      <NCard size="small" :bordered="false">
        <div class="monitor__stat">
          <span class="monitor__stat-label">缓冲区</span>
          <span class="monitor__stat-value">{{ stats?.queue ?? 0 }}</span>
        </div>
      </NCard>
    </div>

    <!-- ===== 控制条 ===== -->
    <div class="monitor__controls">
      <NTag :type="connectionTag.type" size="small">{{ connectionTag.label }}</NTag>
      <NTag :type="renderMode.type" size="small">渲染：{{ renderMode.label }}</NTag>
      <NRadioGroup
        :value="transportKind"
        size="small"
        @update:value="(value: TransportKind) => onTransportChange(value)"
      >
        <NRadioButton value="WS">WebSocket（真实）</NRadioButton>
        <NRadioButton value="MOCK">本地模拟</NRadioButton>
      </NRadioGroup>
      <div v-if="transportKind === 'MOCK'" class="monitor__control">
        <span>时钟偏移（华南 0~2s）</span>
        <NSwitch :value="skewEnabled" size="small" @update:value="toggleSkew" />
      </div>
      <div v-if="transportKind === 'MOCK'" class="monitor__control">
        <span>推送暂停</span>
        <NSwitch :value="!running" size="small" @update:value="(value: boolean) => toggleRun(!value)" />
      </div>
      <NButton size="small" type="warning" @click="simulateDisconnect">模拟断线</NButton>
    </div>

    <div class="monitor__grid">
      <!-- ===== 主曲线 ===== -->
      <NCard title="订单量 / 成交金额（节流 400ms · 时间桶对齐）" :bordered="false" class="monitor__card">
        <ProChart :option="chartOption" :height="300" :autoresize="true" />
      </NCard>

      <!-- ===== 区域热力 ===== -->
      <NCard title="区域热度（成交金额）" :bordered="false" class="monitor__card">
        <div v-for="region in snapshot?.regions ?? []" :key="region.region" class="monitor__region">
          <span class="monitor__region-name">{{ region.region }}</span>
          <NProgress
            type="line"
            :percentage="Math.round((region.amount / maxRegionAmount) * 100)"
            :show-indicator="false"
            :height="10"
            class="monitor__region-bar"
          />
          <span class="monitor__region-value">¥{{ region.amount.toLocaleString() }}</span>
          <NTag
            size="small"
            :type="region.errorRate > 0.05 ? 'warning' : 'default'"
            :bordered="false"
          >
            异常 {{ (region.errorRate * 100).toFixed(1) }}%
          </NTag>
        </div>
      </NCard>

      <!-- ===== 异常告警 ===== -->
      <NCard title="异常告警（最近 8 条）" :bordered="false" class="monitor__card">
        <div v-for="(alert, index) in snapshot?.alerts ?? []" :key="index" class="monitor__alert">
          <NTag size="small" type="error" :bordered="false">异常单</NTag>
          <span class="monitor__alert-region">{{ alert.region }}</span>
          <span class="monitor__alert-amount">¥{{ alert.amount }}</span>
          <span class="monitor__alert-time">{{ formatTime(alert.ts) }}</span>
        </div>
        <p v-if="(snapshot?.alerts ?? []).length === 0" class="monitor__hint">
          暂无异常单（生成概率约 3%）。
        </p>
      </NCard>

      <!-- ===== 工程说明 ===== -->
      <NCard title="这页在演示什么" :bordered="false" class="monitor__card">
        <ul class="monitor__notes">
          <li>
            <b>节流 + 局部更新</b>：tick 进非响应式缓冲，400ms 清算一次聚合快照
            （shallowRef 整体替换）—— 推送 60 条/秒，渲染 2.5 次/秒，
            每次只更新真正变化的绑定。
          </li>
          <li>
            <b>时间轴对齐</b>：所有 tick 按 floor(ts/1000) 落秒级桶，
            乱序 / 迟到 / 时钟偏移（打开上面的开关试试）都被桶吸收，x 轴永远单调。
          </li>
          <li>
            <b>断线重连与补偿</b>：点「模拟断线 5s」—— 重连后 seq 空洞检测触发
            backfill 补拉，「补偿数据」计数跳升，曲线无缺口；
            比保留窗口还旧的补偿数据计数丢弃（防止历史灌屏）。
          </li>
          <li>
            <b>性能降级</b>：桶数 &gt; 240 自动切 5 秒聚合桶 ——
            「渲染」标签会从"秒级"变为"5s 聚合（降级）"。
          </li>
          <li>
            <b>真实 WebSocket</b>：实现与 Mock 相同的传输接口即可替换 ——
            服务端每 100~500ms 批量推一帧，断档用 backfill(fromSeq) 补齐。
          </li>
        </ul>
      </NCard>
    </div>
  </div>
</template>

<style scoped>
.monitor__stats {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
  margin-bottom: 12px;
}

@media (max-width: 1100px) {
  .monitor__stats {
    grid-template-columns: repeat(2, 1fr);
  }
}

.monitor__hero {
  border: 1.5px solid var(--wa-color-primary, #2563eb);
}

.monitor__stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.monitor__stat-label {
  color: var(--wa-text-secondary, #5c6570);
  font-size: var(--wa-font-size-xs, 12px);
}

.monitor__stat-value {
  font-size: 26px;
  font-weight: 600;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}

.monitor__stat-unit {
  font-size: 12px;
  color: var(--wa-text-disabled, #a8b0ba);
}

.monitor__controls {
  display: flex;
  gap: 16px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.monitor__control {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: var(--wa-font-size-sm, 13px);
  color: var(--wa-text-secondary, #5c6570);
}

.monitor__grid {
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: 12px;
}

.monitor__card {
  margin-bottom: 12px;
}

.monitor__grid .monitor__card:last-child {
  grid-column: 1 / -1;
}

@media (max-width: 1100px) {
  .monitor__grid {
    grid-template-columns: 1fr;
  }
}

.monitor__region {
  display: grid;
  grid-template-columns: 48px 1fr 110px 84px;
  gap: 10px;
  align-items: center;
  padding: 6px 0;
  font-size: var(--wa-font-size-sm, 13px);
}

.monitor__region-bar {
  width: 100%;
}

.monitor__region-value {
  text-align: right;
  font-variant-numeric: tabular-nums;
  color: var(--wa-text-secondary, #5c6570);
}

.monitor__alert {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 5px 0;
  border-bottom: 1px dashed var(--wa-border-light, #f0f2f5);
  font-size: var(--wa-font-size-sm, 13px);
}

.monitor__alert-region {
  flex: 1;
}

.monitor__alert-amount {
  font-variant-numeric: tabular-nums;
}

.monitor__alert-time {
  color: var(--wa-text-disabled, #a8b0ba);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.monitor__notes {
  margin: 0;
  padding-left: 18px;
  font-size: var(--wa-font-size-sm, 13px);
  line-height: 1.9;
  color: var(--wa-text-secondary, #5c6570);
}
</style>
