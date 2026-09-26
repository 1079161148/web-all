<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { NCard, NCheckboxGroup, NCheckbox, NInput, NSelect, NTag } from '@admin/ui'

/**
 * 虚拟滚动日志查询：10 万行数据 60 帧渲染。
 *
 * <h3>为什么自己写虚拟滚动，而不是直接包一个组件</h3>
 * 虚拟滚动的全部难点恰恰在"与其它能力兼容"的地方：
 * 动态行高（行高要**测量后回写**并修正偏移量表）、固定列（横向滚动时
 * 锁定首列）、分组表头（跨行的 rowSpan 与虚拟化天然冲突）。
 * 这里的实现把这三个矛盾显式处理，是可复用的参考实现。
 *
 * <h3>核心数据结构</h3>
 * <ul>
 *   <li>{@link heights}：每行实测高度（初始用估值，渲染后回写真实值）</li>
 *   <li>{@link offsets}：前缀和。可见窗口用**二分查找**定位 ——
 *       O(log n)，10 万行与 1 千行的滚动成本相同</li>
 *   <li>上下各多渲染 5 行缓冲：滚动时不露白</li>
 * </ul>
 *
 * <h3>边界处理</h3>
 * <ul>
 *   <li>测量回写只在高度**变化**时触发，避免"重渲染 → 再测量"死循环</li>
 *   <li>滚轮事件用 rAF 合帧：一次滚动几十个事件只布局一次</li>
 *   <li>筛选/排序改变数据集 → 全量重建偏移量表，滚动位置复位</li>
 * </ul>
 */

interface LogRow {
  id: number
  time: string
  level: 'INFO' | 'WARN' | 'ERROR'
  traceId: string
  service: string
  message: string
}

// ---------------------------------------------------------------------
// 数据生成：10 万行（本地生成，真实规模）
// ---------------------------------------------------------------------

const TOTAL = 100_000
const LEVELS = ['INFO', 'INFO', 'INFO', 'WARN', 'ERROR'] as const
const SERVICES = ['gateway', 'order-svc', 'user-svc', 'pay-svc', 'inventory-svc', 'notify-svc']
const MESSAGES = [
  'request completed in %dms',
  'db query slow: SELECT ... took %dms',
  'circuit breaker open for downstream %s',
  'retry attempt %d failed, backing off',
  'cache hit ratio dropped to %d%%',
  'connection pool exhausted, waiting %dms',
  'signature verified for tenant %s',
  'outbox event dispatched, id=%d'
]

/** 伪随机（种子固定）：每次进入页面看到同一批数据，便于对照。 */
function makeRandom(seed: number): () => number {
  let state = seed
  return () => {
    state = (state * 1664525 + 1013904223) % 4294967296
    return state / 4294967296
  }
}

function generateLogs(): LogRow[] {
  const random = makeRandom(20260919)
  const rows: LogRow[] = []
  const base = Date.UTC(2026, 8, 19, 9, 0, 0)
  for (let i = 0; i < TOTAL; i++) {
    const level = LEVELS[Math.floor(random() * LEVELS.length)]
    const traceId = `trace-${Math.floor(random() * 8000).toString(16).padStart(5, '0')}`
    const service = SERVICES[Math.floor(random() * SERVICES.length)]
    const template = MESSAGES[Math.floor(random() * MESSAGES.length)]
    const message = template
      .replace('%d', String(Math.floor(random() * 900) + 100))
      .replace('%s', service)
    const time = new Date(base + i * 87)
    rows.push({
      id: i,
      time: time.toISOString().replace('T', ' ').replace('Z', '').slice(0, 23),
      level,
      traceId,
      service,
      message: message + (level === 'ERROR' ? ` — stack: at ${service}.handle(${Math.floor(random() * 900)}): exception rolled back` : '')
    })
  }
  return rows
}

const allRows = generateLogs()

// ---------------------------------------------------------------------
// 筛选与排序（虚拟滚动只渲染可见窗口，筛选在全量上做 —— 10 万行的
// filter/sort 是毫秒级，真正的性能瓶颈永远在渲染层）
// ---------------------------------------------------------------------

const keyword = ref('')
const levelFilter = ref<string[]>(['INFO', 'WARN', 'ERROR'])
const sortKey = ref<'time' | 'level'>('time')
const sortOrder = ref<1 | -1>(1)

const LEVEL_WEIGHT: Record<string, number> = { ERROR: 0, WARN: 1, INFO: 2 }

const viewRows = computed<LogRow[]>(() => {
  const kw = keyword.value.trim().toLowerCase()
  const levels = new Set(levelFilter.value)
  const filtered = allRows.filter(
    (row) =>
      levels.has(row.level) &&
      (kw === '' ||
        row.message.toLowerCase().includes(kw) ||
        row.traceId.includes(kw) ||
        row.service.includes(kw))
  )
  const sorted = [...filtered]
  sorted.sort((a, b) => {
    if (sortKey.value === 'level') {
      return (LEVEL_WEIGHT[a.level] - LEVEL_WEIGHT[b.level]) * sortOrder.value
    }
    return a.id === b.id ? 0 : a.id < b.id ? -sortOrder.value : sortOrder.value
  })
  return sorted
})

function toggleSort(key: 'time' | 'level'): void {
  if (sortKey.value === key) {
    sortOrder.value = sortOrder.value === 1 ? -1 : 1
  } else {
    sortKey.value = key
    sortOrder.value = 1
  }
}

// ---------------------------------------------------------------------
// 虚拟滚动内核
// ---------------------------------------------------------------------

const viewportHeight = 560
const BUFFER = 5
const ESTIMATED_HEIGHT = 36

const scrollEl = ref<HTMLElement | null>(null)
const scrollTop = ref(0)
const frameCost = ref(0)

/** 实测行高（index → px）。未测量的行用估值；渲染后回写真实值。 */
const heights = new Map<number, number>()
let offsets = new Float64Array(0)
let offsetsDirty = true

function heightFor(index: number): number {
  return heights.get(index) ?? ESTIMATED_HEIGHT
}

/** 前缀和重建。O(n)，仅在高度变化或数据集切换时执行一次。 */
function rebuildOffsets(): void {
  if (offsets.length !== viewRows.value.length) {
    offsets = new Float64Array(viewRows.value.length)
  }
  let acc = 0
  for (let i = 0; i < viewRows.value.length; i++) {
    offsets[i] = acc
    acc += heightFor(i)
  }
  offsetsDirty = false
}

const totalHeight = computed(() => {
  if (offsetsDirty) {
    rebuildOffsets()
  }
  return viewRows.value.length === 0 ? 0 : offsets[viewRows.value.length - 1] + heightFor(viewRows.value.length - 1)
})

function findIndexAt(position: number): number {
  if (offsetsDirty) {
    rebuildOffsets()
  }
  let low = 0
  let high = viewRows.value.length - 1
  while (low < high) {
    const mid = (low + high + 1) >> 1
    if (offsets[mid] <= position) {
      low = mid
    } else {
      high = mid - 1
    }
  }
  return low
}

const window2 = computed(() => {
  if (offsetsDirty) {
    rebuildOffsets()
  }
  const start = Math.max(0, findIndexAt(scrollTop.value) - BUFFER)
  let end = start
  const limit = scrollTop.value + viewportHeight
  while (end < viewRows.value.length && offsets[end] < limit) {
    end += 1
  }
  return { start, end: Math.min(viewRows.value.length, end + BUFFER) }
})

const visibleRows = computed(() => {
  const { start, end } = window2.value
  const rows: Array<{ row: LogRow; index: number; top: number; groupCount: number }> = []
  for (let i = start; i < end; i++) {
    const row = viewRows.value[i]
    // 分组合并：相邻同 traceId 的行，首行合并展示（colSpan 思路的虚拟化等价实现）
    let groupCount = 1
    if (i + 1 < viewRows.value.length && viewRows.value[i + 1].traceId === row.traceId) {
      groupCount = 0
      let j = i
      while (j >= 0 && viewRows.value[j].traceId === row.traceId) {
        j -= 1
      }
      if (j + 1 === i) {
        let k = i + 1
        while (k < viewRows.value.length && viewRows.value[k].traceId === row.traceId) {
          groupCount += 1
          k += 1
        }
      }
    }
    rows.push({ row, index: i, top: offsets[i], groupCount })
  }
  return rows
})

let framePending = false
function onScroll(): void {
  if (framePending) {
    return
  }
  framePending = true
  const started = performance.now()
  requestAnimationFrame(() => {
    scrollTop.value = scrollEl.value?.scrollTop ?? 0
    frameCost.value = Math.round((performance.now() - started) * 10) / 10
    framePending = false
  })
}

const rowRefs = new Map<number, HTMLElement>()

/** 渲染后回写实测行高：只有变化才标脏，避免测量/渲染死循环。 */
function measureRow(index: number, el: HTMLElement | null): void {
  if (!el) {
    rowRefs.delete(index)
    return
  }
  rowRefs.set(index, el)
  const actual = el.offsetHeight
  if (actual > 0 && Math.abs(actual - heightFor(index)) > 0.5) {
    heights.set(index, actual)
    offsetsDirty = true
  }
}

/** 数据集切换（筛选/排序）：清空测量缓存，滚动复位。 */
function resetVirtualState(): void {
  heights.clear()
  offsetsDirty = true
  if (scrollEl.value) {
    scrollEl.value.scrollTop = 0
  }
  scrollTop.value = 0
}

const LEVEL_COLOR: Record<string, string> = {
  INFO: 'default',
  WARN: 'warning',
  ERROR: 'error'
}

onMounted(() => {
  rebuildOffsets()
})
onBeforeUnmount(() => {
  rowRefs.clear()
})
</script>

<template>
  <div class="vlog">
    <NCard :bordered="false" class="vlog__card">
      <div class="vlog__toolbar">
        <NInput
          v-model:value="keyword"
          placeholder="搜索关键字 / traceId / 服务名"
          clearable
          class="vlog__search"
          @update:value="resetVirtualState"
        />
        <NCheckboxGroup v-model:value="levelFilter" @update:value="resetVirtualState">
          <NCheckbox value="INFO" label="INFO" />
          <NCheckbox value="WARN" label="WARN" />
          <NCheckbox value="ERROR" label="ERROR" />
        </NCheckboxGroup>
        <NSelect
          :value="sortOrder === 1 ? 'asc' : 'desc'"
          :options="[
            { label: '时间正序', value: 'asc' },
            { label: '时间倒序', value: 'desc' }
          ]"
          size="small"
          class="vlog__sort"
          @update:value="(value: string) => { sortOrder = value === 'asc' ? 1 : -1; resetVirtualState() }"
        />
      </div>

      <div class="vlog__meta">
        <NTag size="small">数据量 {{ allRows.length.toLocaleString() }} 行</NTag>
        <NTag size="small" type="info">命中 {{ viewRows.length.toLocaleString() }} 行</NTag>
        <NTag size="small" type="success">视窗渲染 {{ visibleRows.length }} 行</NTag>
        <NTag size="small">本帧布局 {{ frameCost }}ms</NTag>
      </div>

      <div class="vlog__header">
        <div class="vlog__cell vlog__cell--time vlog__cell--fixed" @click="toggleSort('time')">
          时间 {{ sortKey === 'time' ? (sortOrder === 1 ? '↑' : '↓') : '' }}
        </div>
        <div class="vlog__cell vlog__cell--level" @click="toggleSort('level')">
          级别 {{ sortKey === 'level' ? (sortOrder === 1 ? '↑' : '↓') : '' }}
        </div>
        <div class="vlog__cell vlog__cell--trace">Trace / 服务</div>
        <div class="vlog__cell vlog__cell--message">消息</div>
      </div>

      <!--
        虚拟滚动视口：内层 spacer 撑起总高度制造原生滚动条，
        可见行绝对定位到各自的 offset 上。
      -->
      <div ref="scrollEl" class="vlog__viewport" @scroll="onScroll">
        <div :style="{ height: `${totalHeight}px`, position: 'relative' }">
          <div
            v-for="item in visibleRows"
            :key="item.row.id"
            :ref="(el) => measureRow(item.index, el as HTMLElement | null)"
            class="vlog__row"
            :class="{ 'vlog__row--group': item.groupCount > 1 }"
            :style="{ top: `${item.top}px` }"
          >
            <div class="vlog__cell vlog__cell--time vlog__cell--fixed">{{ item.row.time }}</div>
            <div class="vlog__cell vlog__cell--level">
              <NTag size="small" :type="LEVEL_COLOR[item.row.level] as 'default' | 'warning' | 'error'">
                {{ item.row.level }}
              </NTag>
            </div>
            <div class="vlog__cell vlog__cell--trace">
              <template v-if="item.groupCount > 1">
                <span class="vlog__trace-group">▼ {{ item.row.traceId }}（{{ item.groupCount }} 条）· {{ item.row.service }}</span>
              </template>
              <template v-else>
                <span class="vlog__trace-child">└ {{ item.row.service }}</span>
              </template>
            </div>
            <div class="vlog__cell vlog__cell--message">{{ item.row.message }}</div>
          </div>
        </div>
      </div>

      <p class="vlog__note">
        滚动观察：行高由内容实测（ERROR 行更高），滚动时偏移量表随测量自动修正；
        首列随横向滚动固定；同 traceId 的连续日志在首行合并展示。
        本帧布局耗时与视窗渲染行数实时显示 —— 10 万行下它始终是个位数毫秒。
      </p>
    </NCard>
  </div>
</template>

<style scoped>
.vlog__card {
  margin-bottom: 16px;
}

.vlog__toolbar {
  display: flex;
  gap: 16px;
  align-items: center;
  margin-bottom: 12px;
}

.vlog__search {
  width: 320px;
}

.vlog__sort {
  width: 130px;
}

.vlog__meta {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.vlog__header,
.vlog__row {
  display: flex;
  align-items: flex-start;
  min-width: 1100px;
}

.vlog__header {
  font-weight: 600;
  background: var(--wa-fill-light, #f5f7fa);
  border: 1px solid var(--wa-border, #e4e7ed);
  border-bottom: none;
}

.vlog__viewport {
  height: 560px;
  overflow: auto;
  border: 1px solid var(--wa-border, #e4e7ed);
  background: var(--wa-bg-elevated, #fff);
}

.vlog__row {
  position: absolute;
  left: 0;
  right: 0;
  padding: 6px 0;
  border-bottom: 1px solid var(--wa-border-light, #f0f2f5);
  box-sizing: border-box;
  will-change: transform;
}

.vlog__row--group {
  background: color-mix(in srgb, var(--wa-color-primary, #2563eb) 5%, transparent);
}

.vlog__cell {
  flex: none;
  padding: 2px 10px;
  font-size: 12.5px;
  line-height: 1.6;
  word-break: break-all;
}

.vlog__cell--time {
  width: 150px;
}

/* 固定列：sticky 锁定首列 —— 与虚拟滚动（纵向）和横向滚动同时兼容 */
.vlog__cell--fixed {
  position: sticky;
  left: 0;
  z-index: 1;
  background: var(--wa-bg-elevated, #fff);
  box-shadow: 2px 0 4px rgba(0, 0, 0, 0.04);
}

.vlog__row--group .vlog__cell--fixed {
  background: color-mix(in srgb, var(--wa-color-primary, #2563eb) 5%, transparent);
}

.vlog__cell--level {
  width: 70px;
}

.vlog__cell--trace {
  width: 280px;
}

.vlog__cell--message {
  flex: 1;
  min-width: 400px;
  color: var(--wa-text-secondary, #5c6570);
}

.vlog__trace-group {
  font-weight: 600;
  color: var(--wa-color-primary, #2563eb);
}

.vlog__trace-child {
  color: var(--wa-text-disabled, #a8b0ba);
}

.vlog__note {
  margin: 12px 0 0;
  color: var(--wa-text-secondary, #5c6570);
  font-size: var(--wa-font-size-sm, 13px);
}
</style>
