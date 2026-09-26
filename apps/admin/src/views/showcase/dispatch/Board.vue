<script setup lang="ts">
import { computed, ref } from 'vue'
import { NTag } from '@admin/ui'
import type { OpsTicket } from '@/api/dispatch'

/**
 * 工单看板（HTML5 原生拖拽，零依赖）。
 *
 * <h3>职责边界：本组件只管"拖"，不管"动"</h3>
 * 拖拽落下的结果是 emit 一个意图 {@link move}（票据 + 目标列），
 * <b>真正改数据的是父组件</b> —— 乐观更新、接口调用、失败回滚都在父级。
 * 理由：看板交互必须知道"这次移动会不会被拒绝"，而答案在服务端；
 * 把两件事焊在同一个组件里，就会出现"为了回滚把请求也搬进来"的耦合。
 *
 * <h3>为什么用原生 DnD 而不是 vuedraggable</h3>
 * 看板这里<b>没有列内排序</b>（工单按创建时间倒序展示，排序权在服务端），
 * 需要的只有"卡片 → 列"一个方向的能力 —— 原生 dragstart/dragover/drop
 * 三十行就够了。为这个需求引入一个排序库，是把复杂度买进来再关掉。
 *
 * <h3>非法迁移在拖拽阶段就给出反馈</h3>
 * 状态机的合法迁移表见 {@link legalTargets}：不合法的目标列在拖动时
 * 就不响应（不高亮、不接 drop），而不是"放上去之后弹一个错误提示" ——
 * 允许用户做一个注定失败的动作用户界面，是界面对用户的欺骗。
 */

const props = defineProps<{
  tickets: OpsTicket[]
}>()

const emit = defineEmits<{
  (e: 'move', ticket: OpsTicket, target: 'PENDING' | 'CLAIMED' | 'DONE'): void
}>()

interface Column {
  state: 'PENDING' | 'CLAIMED' | 'DONE'
  title: string
  tone: 'default' | 'info' | 'success'
}

const COLUMNS: Column[] = [
  { state: 'PENDING', title: '待处理', tone: 'default' },
  { state: 'CLAIMED', title: '处理中', tone: 'info' },
  { state: 'DONE', title: '已完成', tone: 'success' }
]

/** 状态机：每个目标列允许的来源状态（与后端条件 UPDATE 严格一致）。 */
function legalTargets(state: OpsTicket['state']): Array<Column['state']> {
  switch (state) {
    case 'PENDING':
      return ['CLAIMED']
    case 'ESCALATED':
      return ['CLAIMED']
    case 'CLAIMED':
      return ['PENDING', 'DONE']
    default:
      // DONE 是终态：什么都不允许
      return []
  }
}

const byColumn = computed<Record<Column['state'], OpsTicket[]>>(() => ({
  PENDING: props.tickets.filter((t) => t.state === 'PENDING' || t.state === 'ESCALATED'),
  CLAIMED: props.tickets.filter((t) => t.state === 'CLAIMED'),
  DONE: props.tickets.filter((t) => t.state === 'DONE')
}))

const dragId = ref<number | null>(null)
const overColumn = ref<Column['state'] | null>(null)

function onDragStart(ticket: OpsTicket, event: DragEvent): void {
  if (legalTargets(ticket.state).length === 0) {
    // 终态卡片不可拖：阻止默认行为，dragstart 都不会有效开始
    event.preventDefault()
    return
  }
  dragId.value = ticket.id
  // Firefox 需要 setData 才会启动拖拽；text/plain 是各浏览器公约
  event.dataTransfer?.setData('text/plain', String(ticket.id))
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
  }
}

function onDragEnd(): void {
  dragId.value = null
  overColumn.value = null
}

function clearOver(column: Column['state']): void {
  if (overColumn.value === column) {
    overColumn.value = null
  }
}

function allowDrop(column: Column['state'], event: DragEvent): void {
  const ticket = props.tickets.find((t) => t.id === dragId.value)
  if (!ticket) {
    return
  }
  if (legalTargets(ticket.state).includes(column)) {
    event.preventDefault()
    overColumn.value = column
  }
}

function onDrop(column: Column['state'], event: DragEvent): void {
  event.preventDefault()
  const raw = event.dataTransfer?.getData('text/plain')
  const id = raw === undefined || raw === '' ? dragId.value : Number(raw)
  const ticket = props.tickets.find((t) => t.id === id)
  overColumn.value = null
  dragId.value = null
  if (!ticket) {
    return
  }
  if (legalTargets(ticket.state).includes(column) && ticket.state !== column) {
    emit('move', ticket, column)
  }
}

const PRIORITY_COLOR: Record<string, 'error' | 'warning' | 'default'> = {
  P1: 'error',
  P2: 'warning',
  P3: 'default'
}
</script>

<template>
  <div class="board">
    <div
      v-for="col in COLUMNS"
      :key="col.state"
      class="board__col"
      :class="{ 'board__col--over': overColumn === col.state }"
      @dragover="allowDrop(col.state, $event)"
      @dragleave="clearOver(col.state)"
      @drop="onDrop(col.state, $event)"
    >
      <div class="board__col-head">
        <span class="board__col-title">{{ col.title }}</span>
        <NTag size="small" :type="col.tone">{{ byColumn[col.state].length }}</NTag>
      </div>
      <div class="board__cards">
        <div
          v-for="ticket in byColumn[col.state]"
          :key="ticket.id"
          class="board__card"
          :class="{
            'board__card--dragging': dragId === ticket.id,
            'board__card--escalated': ticket.state === 'ESCALATED'
          }"
          :draggable="ticket.state !== 'DONE'"
          @dragstart="onDragStart(ticket, $event)"
          @dragend="onDragEnd"
        >
          <div class="board__card-title">{{ ticket.title }}</div>
          <div class="board__card-meta">
            <NTag size="tiny" :type="PRIORITY_COLOR[ticket.priority] ?? 'default'">
              {{ ticket.priority }}
            </NTag>
            <span class="board__card-channel">{{ ticket.channel }}</span>
            <span v-if="ticket.claimer" class="board__card-claimer">{{ ticket.claimer }}</span>
          </div>
          <div v-if="ticket.state === 'ESCALATED'" class="board__card-sla">已超时升级</div>
        </div>
        <p v-if="byColumn[col.state].length === 0" class="board__empty">拖工单到这里</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.board {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  align-items: start;
}

.board__col {
  background: rgba(128, 128, 128, 0.06);
  border: 1px dashed transparent;
  border-radius: 8px;
  padding: 10px;
  min-height: 220px;
  transition: border-color 0.15s, background 0.15s;
}

.board__col--over {
  border-color: var(--n-primary, #18a058);
  background: rgba(24, 160, 88, 0.06);
}

.board__col-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.board__col-title {
  font-weight: 600;
  font-size: 13px;
}

.board__cards {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.board__card {
  background: var(--n-color, #fff);
  border: 1px solid rgba(128, 128, 128, 0.2);
  border-radius: 6px;
  padding: 8px 10px;
  cursor: grab;
  user-select: none;
}

.board__card:active {
  cursor: grabbing;
}

.board__card--dragging {
  opacity: 0.45;
}

.board__card--escalated {
  border-color: #d03050;
}

.board__card-title {
  font-size: 13px;
  margin-bottom: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.board__card-meta {
  display: flex;
  gap: 6px;
  align-items: center;
  font-size: 12px;
  opacity: 0.75;
}

.board__card-claimer {
  margin-left: auto;
}

.board__card-sla {
  margin-top: 6px;
  font-size: 12px;
  color: #d03050;
}

.board__empty {
  margin: 0;
  font-size: 12px;
  opacity: 0.5;
  text-align: center;
  padding: 14px 0;
}
</style>
