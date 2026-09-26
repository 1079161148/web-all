<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import {
  NButton,
  NCard,
  NGrid,
  NModal,
  NProgress,
  NRadioButton,
  NRadioGroup,
  NSelect,
  NTag,
  feedback
} from '@admin/ui'
import {
  claimOpsTicket,
  completeOpsTicket,
  listOpsTickets,
  releaseOpsTicket,
  type OpsTicket
} from '@/api/dispatch'
import { streamAiChat } from '@/api/ai'
import DispatchBoard from './Board.vue'
import AiMarkdown from '@/components/AiMarkdown.vue'

/**
 * 工单调度台（真实后端版）。
 *
 * 与上一版的差别：模拟全部撤掉，换成真并发。
 * - 工单数据来自后端 ops_ticket 表（轮询 2 秒）：开两个标签页，
 *   A 抢到的工单 B 的列表会在下一次轮询变成"已接单"。
 * - 抢单原子性：后端条件 UPDATE（数据库行锁），抢不到会收到
 *   "已被他人抢走"的明确错误 —— 这是并发正确性的来源，前端只负责呈现。
 * - SLA 超时升级在后端：列表查询前先执行惰性升级 SQL，
 *   因此升级状态对所有人一致。
 * - 倒计时基准是服务端时间戳 —— 客户端时钟不可信。
 *
 * 实时性路径：状态推送复用消息中心已验证的 SSE 通道（工单领域事件 → 通知）；
 * 本页以 2 秒轮询兜底，两条路径互为冗余。
 */

const STATE_LABEL: Record<string, string> = {
  PENDING: '待接单',
  CLAIMED: '已接单',
  DONE: '已完成',
  ESCALATED: '已升级'
}

const STATE_COLOR: Record<string, 'default' | 'info' | 'success' | 'warning' | 'error'> = {
  PENDING: 'warning',
  CLAIMED: 'info',
  DONE: 'success',
  ESCALATED: 'error'
}

const PRIORITY_COLOR: Record<string, 'error' | 'warning' | 'default'> = {
  P1: 'error',
  P2: 'warning',
  P3: 'default'
}

const tickets = shallowRef<OpsTicket[]>([])
const now = ref(Date.now())
const channelFilter = ref<'all' | string>('all')

const channelOptions = [
  { label: '全部渠道', value: 'all' },
  { label: '客服', value: '客服' },
  { label: '运维', value: '运维' },
  { label: '配送', value: '配送' }
]

const pendingTickets = computed(() =>
  tickets.value.filter(
    (ticket) =>
      (ticket.state === 'PENDING' || ticket.state === 'ESCALATED') &&
      (channelFilter.value === 'all' || ticket.channel === channelFilter.value)
  )
)

const activeTickets = computed(() =>
  tickets.value.filter((ticket) => ticket.state === 'CLAIMED')
)

const doneCount = computed(() => tickets.value.filter((ticket) => ticket.state === 'DONE').length)
const escalatedCount = computed(() => tickets.value.filter((ticket) => ticket.state === 'ESCALATED').length)

/** 视图：列表（原布局）或看板（三列拖拽）。 */
const viewMode = ref<'list' | 'board'>('list')

/** 看板数据 = 渠道过滤后的全量工单（DONE 也在看板里占一列）。 */
const boardTickets = computed(() =>
  tickets.value.filter(
    (ticket) => channelFilter.value === 'all' || ticket.channel === channelFilter.value
  )
)

/**
 * 看板拖拽的唯一入口：先改界面，后问服务端。
 *
 * <h3>为什么是乐观更新</h3>
 * 拖拽是<b>高频率、低风险</b>的操作（状态机由后端把关）——
 * 每次都等接口回来再动卡片，看板会有"卡一下再弹过去"的手感；
 * 先动 + 失败弹回，手感与正确性兼得。
 * 失败的弹回不是重置整个列表，而是<b>精确恢复这一张卡</b>的旧状态 ——
 * 轮询可能在这期间带来别人的更新，整表回滚会把别人的变更也抹掉。
 */
async function handleBoardMove(
  ticket: OpsTicket,
  target: 'PENDING' | 'CLAIMED' | 'DONE'
): Promise<void> {
  if (ticket.state === target) {
    return
  }
  const previous = ticket.state

  // 乐观更新：shallowRef 必须换数组引用才会触发响应
  tickets.value = tickets.value.map((t) => (t.id === ticket.id ? { ...t, state: target } : t))

  try {
    if (target === 'CLAIMED') {
      await claimOpsTicket(ticket.id)
      feedback.success(`你接手了 ${ticket.id}`)
    } else if (target === 'DONE') {
      await completeOpsTicket(ticket.id)
      feedback.success(`${ticket.id} 已办结`)
    } else {
      await releaseOpsTicket(ticket.id)
      feedback.success(`${ticket.id} 已放回待处理`)
    }
  } catch (error) {
    // 回滚：只恢复这一张卡，且恢复到"服务端真实状态的下一次轮询"到来之前
    tickets.value = tickets.value.map((t) => (t.id === ticket.id ? { ...t, state: previous } : t))
    feedback.error(error instanceof Error ? error.message : '操作失败，已还原')
  }
}

function remainingMs(ticket: OpsTicket): number {
  return ticket.createdAt + ticket.slaMinutes * 60_000 - now.value
}

function slaPercent(ticket: OpsTicket): number {
  return Math.max(0, Math.min(100, (remainingMs(ticket) / (ticket.slaMinutes * 60_000)) * 100))
}

function slaText(ticket: OpsTicket): string {
  if (ticket.state === 'ESCALATED') {
    return '已超时升级'
  }
  const totalSeconds = Math.max(0, Math.floor(remainingMs(ticket) / 1000))
  return `剩余 ${String(Math.floor(totalSeconds / 60)).padStart(2, '0')}:${String(totalSeconds % 60).padStart(2, '0')}`
}

function slaStatus(ticket: OpsTicket): 'normal' | 'warning' | 'error' {
  if (ticket.state === 'ESCALATED') {
    return 'error'
  }
  if (ticket.state === 'DONE') {
    return 'normal'
  }
  const percent = slaPercent(ticket)
  return percent <= 0 ? 'error' : percent < 25 ? 'warning' : 'normal'
}

async function refresh(): Promise<void> {
  try {
    tickets.value = await listOpsTickets()
  } catch {
    // 轮询失败静默：下一轮重试（网络抖动不该刷一屏错误）
  }
}

async function claim(ticket: OpsTicket): Promise<void> {
  try {
    await claimOpsTicket(ticket.id)
    feedback.success(`你抢到了 ${ticket.id}`)
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '抢单失败')
  }
  await refresh()
}

async function complete(ticket: OpsTicket): Promise<void> {
  try {
    await completeOpsTicket(ticket.id)
    feedback.success(`${ticket.id} 已办结`)
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '办结失败')
  }
  await refresh()
}

let tickTimer: number | null = null
let pollTimer: number | null = null

// ---------------------------------------------------------------------
// AI 运维摘要（Harness 嵌入业务页面的示范）
// ---------------------------------------------------------------------

/**
 * 不跳去聊天页、不复制任何 AI 代码 —— 复用同一个后端代理与
 * streamAiChat 封装，只在业务语义上换一段指示词。
 * "AI 包装进后台"的正确形态：AI 是页面上的一个按钮，
 * 而不是一个必须切换场景去使用的独立应用。
 */
const summaryVisible = ref(false)
const summaryText = ref('')
const summaryStreaming = ref(false)
const summaryMode = ref<'real' | 'demo' | null>(null)
let summaryAbort: (() => void) | null = null

function openAiSummary(): void {
  if (tickets.value.length === 0) {
    feedback.warning('暂无工单数据，无法生成摘要')
    return
  }
  summaryVisible.value = true
  summaryText.value = ''
  summaryStreaming.value = true

  const brief = tickets.value
    .slice(0, 20)
    .map((t) => `#${t.id} [${t.state}/${t.priority}] ${t.title}（${t.channel}，SLA ${t.slaMinutes} 分钟）`)
    .join('\n')

  summaryAbort = streamAiChat('dispatch-summary', [
    {
      role: 'user',
      content: `以下是当前工单列表：\n${brief}\n\n请生成一份简短的运维交接摘要（Markdown）：` +
        '1. 整体态势一句话；2. 按优先级列出需要立即处理的工单及理由；3. 一条风险提示。'
    }
  ], {
    onMeta: (mode) => {
      summaryMode.value = mode
    },
    onDelta: (piece) => {
      summaryText.value += piece
    },
    onError: (message) => {
      feedback.error(message)
    },
    onDone: () => {
      summaryStreaming.value = false
      summaryAbort = null
    }
  })
}

function closeAiSummary(): void {
  summaryAbort?.()
  summaryAbort = null
  summaryStreaming.value = false
  summaryVisible.value = false
}

onMounted(async () => {
  tickTimer = window.setInterval(() => {
    now.value = Date.now()
  }, 1000)
  await refresh()
  pollTimer = window.setInterval(refresh, 2000)
})

onBeforeUnmount(() => {
  if (tickTimer !== null) {
    window.clearInterval(tickTimer)
  }
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
  }
})
</script>
<template>
  <div class="dispatch">
    <NGrid :cols="4" :x-gap="12" class="dispatch__stats">
      <NCard size="small" :bordered="false">
        <div class="dispatch__stat">
          <span class="dispatch__stat-label">待接单</span>
          <span class="dispatch__stat-value">{{ pendingTickets.length }}</span>
        </div>
      </NCard>
      <NCard size="small" :bordered="false">
        <div class="dispatch__stat">
          <span class="dispatch__stat-label">处理中</span>
          <span class="dispatch__stat-value">{{ activeTickets.length }}</span>
        </div>
      </NCard>
      <NCard size="small" :bordered="false">
        <div class="dispatch__stat">
          <span class="dispatch__stat-label">已完成</span>
          <span class="dispatch__stat-value dispatch__stat-value--ok">{{ doneCount }}</span>
        </div>
      </NCard>
      <NCard size="small" :bordered="false">
        <div class="dispatch__stat">
          <span class="dispatch__stat-label">超时升级</span>
          <span class="dispatch__stat-value dispatch__stat-value--bad">{{ escalatedCount }}</span>
        </div>
      </NCard>
    </NGrid>

    <NCard :bordered="false" class="dispatch__card">
      <div class="dispatch__toolbar">
        <NRadioGroup v-model:value="viewMode" size="small">
          <NRadioButton value="list">列表</NRadioButton>
          <NRadioButton value="board">看板</NRadioButton>
        </NRadioGroup>
        <NSelect v-model:value="channelFilter" :options="channelOptions" size="small" class="dispatch__channel" />
        <NTag size="small" type="info">开两个标签页同时抢单 —— 体验真实并发竞争</NTag>
        <NButton size="small" type="primary" ghost @click="openAiSummary">AI 运维摘要</NButton>
      </div>

      <!-- AI 运维摘要（流式 Markdown；Harness 嵌入业务页面的示范） -->
      <NModal
        v-model:show="summaryVisible"
        preset="card"
        title="AI 运维摘要"
        class="dispatch__summary-modal"
        :style="{ width: '640px' }"
        :mask-closable="!summaryStreaming"
      >
        <template #header-extra>
          <NTag size="small" :type="summaryMode === 'real' ? 'success' : summaryMode === 'demo' ? 'warning' : 'default'">
            {{ summaryMode === 'real' ? '真实模型' : summaryMode === 'demo' ? '演示模式' : '生成中…' }}
          </NTag>
        </template>
        <AiMarkdown :text="summaryText || '正在基于当前工单数据生成…'" :streaming="summaryStreaming" />
        <template #footer>
          <NButton size="small" :disabled="!summaryStreaming" @click="closeAiSummary">
            {{ summaryStreaming ? '停止生成' : '关闭' }}
          </NButton>
        </template>
      </NModal>

      <!-- 看板视图：拖拽即状态迁移（乐观更新 + 失败回滚在 handleBoardMove） -->
      <DispatchBoard
        v-if="viewMode === 'board'"
        :tickets="boardTickets"
        @move="handleBoardMove"
      />
      <template v-if="viewMode === 'list'">
      <NCard title="待接单" :bordered="false" class="dispatch__card dispatch__card--inner">
      <div
        v-for="ticket in pendingTickets"
        :key="ticket.id"
        class="dispatch__ticket"
        :class="`dispatch__ticket--${slaStatus(ticket)}`"
      >
        <div class="dispatch__ticket-head">
          <NTag size="small" :type="PRIORITY_COLOR[ticket.priority]">{{ ticket.priority }}</NTag>
          <NTag size="small" :bordered="false">{{ ticket.channel }}</NTag>
          <span class="dispatch__ticket-title">{{ ticket.title }}</span>
          <span class="dispatch__ticket-id">{{ ticket.id }}</span>
        </div>
        <div class="dispatch__ticket-sla">
          <NProgress
            type="line"
            :percentage="slaPercent(ticket)"
            :show-indicator="false"
            :height="6"
            :status="slaStatus(ticket) === 'error' ? 'error' : slaStatus(ticket) === 'warning' ? 'warning' : 'success'"
          />
          <span class="dispatch__sla-text" :class="`dispatch__sla-text--${slaStatus(ticket)}`">
            {{ slaText(ticket) }}
          </span>
        </div>
        <div class="dispatch__ticket-actions">
          <NButton size="tiny" type="primary" @click="claim(ticket)">抢单</NButton>
        </div>
      </div>

      <p v-if="pendingTickets.length === 0" class="dispatch__empty">当前渠道没有待接单工单。</p>
      </NCard>

      <NCard title="处理中 / 已接单" :bordered="false" class="dispatch__card dispatch__card--inner">
      <div v-for="ticket in activeTickets" :key="ticket.id" class="dispatch__ticket dispatch__ticket--compact">
        <div class="dispatch__ticket-head">
          <NTag size="small" :type="STATE_COLOR[ticket.state]">{{ STATE_LABEL[ticket.state] }}</NTag>
          <span class="dispatch__ticket-title">{{ ticket.title }}</span>
          <span class="dispatch__ticket-id">{{ ticket.claimer ?? '—' }} · {{ ticket.id }}</span>
        </div>
        <div class="dispatch__ticket-sla">
          <NProgress type="line" :percentage="slaPercent(ticket)" :show-indicator="false" :height="6" />
          <span class="dispatch__sla-text">{{ slaText(ticket) }}</span>
        </div>
        <div class="dispatch__ticket-actions">
          <NButton size="tiny" type="primary" ghost @click="complete(ticket)">办结</NButton>
        </div>
      </div>
      <p v-if="activeTickets.length === 0" class="dispatch__empty">暂无处理中的工单。</p>
      </NCard>
      </template>
    </NCard>
  </div>
</template>

<style scoped>
.dispatch__stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 12px;
}

@media (max-width: 1100px) {
  .dispatch__stats {
    grid-template-columns: repeat(2, 1fr);
  }
}

.dispatch__stat {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}

.dispatch__stat-label {
  color: var(--wa-text-secondary, #5c6570);
  font-size: var(--wa-font-size-sm, 13px);
}

.dispatch__stat-value {
  font-size: 24px;
  font-variant-numeric: tabular-nums;
}

.dispatch__stat-value--ok {
  color: var(--wa-color-success, #18a058);
}

.dispatch__stat-value--bad {
  color: var(--wa-color-error, #d03050);
}

.dispatch__card {
  margin-bottom: 12px;
}

.dispatch__toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.dispatch__channel {
  width: 130px;
}

.dispatch__ticket {
  padding: 10px 12px;
  margin-bottom: 8px;
  border: 1px solid var(--wa-border, #e4e7ed);
  border-left-width: 3px;
  border-radius: var(--wa-radius-md, 4px);
}

.dispatch__ticket--warning {
  border-left-color: var(--wa-color-warning, #f0a020);
}

.dispatch__ticket--error {
  border-left-color: var(--wa-color-error, #d03050);
  background: color-mix(in srgb, var(--wa-color-error, #d03050) 4%, transparent);
}

.dispatch__ticket--compact {
  padding: 8px 12px;
}

.dispatch__ticket-head {
  display: flex;
  gap: 8px;
  align-items: center;
}

.dispatch__ticket-title {
  flex: 1;
  font-size: var(--wa-font-size-sm, 13px);
}

.dispatch__ticket-id {
  color: var(--wa-text-disabled, #a8b0ba);
  font-size: 12px;
}

.dispatch__ticket-sla {
  display: flex;
  gap: 10px;
  align-items: center;
  margin: 6px 0 4px;
}

.dispatch__ticket-sla > :first-child {
  flex: 1;
}

.dispatch__sla-text {
  flex: none;
  width: 96px;
  text-align: right;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--wa-text-secondary, #5c6570);
}

.dispatch__sla-text--warning {
  color: var(--wa-color-warning, #f0a020);
  font-weight: 600;
}

.dispatch__sla-text--error {
  color: var(--wa-color-error, #d03050);
  font-weight: 600;
}

.dispatch__ticket-actions {
  display: flex;
  gap: 8px;
}

.dispatch__empty {
  margin: 0;
  padding: 20px 0;
  text-align: center;
  color: var(--wa-text-disabled, #a8b0ba);
  font-size: var(--wa-font-size-sm, 13px);
}
</style>
