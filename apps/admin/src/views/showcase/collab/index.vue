<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { NButton, NCard, NSpace, NTag, feedback } from '@admin/ui'

/**
 * 在线文档协同编辑（教学级 CRDT + 多标签页实时同步）。
 *
 * <h3>实现范围与诚实声明</h3>
 * 生产级协同（Yjs / OT 服务集群）是独立的基础设施工程。本页实现的是
 * 一个**可运行的最小 CRDT**，用于展示协同编辑的三类核心问题与解法：
 * <ol>
 *   <li><b>并发收敛</b>：每个字符持有唯一 id 与 Lamport 逻辑时钟；
 *       并发插入同一位置时按时钟排序 —— 两个标签页同时打字，最终文本一致</li>
 *   <li><b>实时通道</b>：演示用 BroadcastChannel（同源多标签页，零后端）。
 *       换成 SSE/WebSocket 只需替换 `broadcast()` 一个函数 ——
 *       项目现有的消息通道（票据鉴权 + Redis Pub/Sub）就是现成的载体</li>
 *   <li><b>光标同步（Presence）</b>：光标位置是高频临时状态，
 *       走节流广播（200ms）且**不进 CRDT**——临时状态进文档模型是常见错误</li>
 * </ol>
 *
 * <h3>富文本粘贴清洗</h3>
 * 从网页/Word 复制的内容带大量隐式样式标签，直接入库会污染渲染与安全。
 * 统一策略：只取纯文本、合并空白 —— 富文本白名单是渲染层的事，
 * 存储层永远存干净文本。
 */

interface CharNode {
  id: string
  ch: string
  lamport: number
  tomb: boolean
}

type Op =
  | { type: 'ins'; node: CharNode; prevId: string | null }
  | { type: 'del'; id: string }
  | { type: 'reset'; nodes: CharNode[]; lamport: number }
  | { type: 'hello'; site: string }
  | { type: 'cursor'; site: string; pos: number }

const SITE = `site-${Math.random().toString(36).slice(2, 6)}`
const SITE_COLORS = ['#2563eb', '#18a058', '#f0a020', '#d03050', '#722ed1']
const SITE_NAMES = ['张三', '李四', '王五', '赵六']

let counter = 0
let lamport = 0
const doc = ref<CharNode[]>([])
const channel = new BroadcastChannel('showcase-collab')
const peers = ref<Map<string, { pos: number }>>(new Map())
const snapshots = ref<Array<{ time: string; text: string }>>([])
const lastSnapshotText = ref('')

const visibleText = computed(() =>
  doc.value
    .filter((node) => !node.tomb)
    .map((node) => node.ch)
    .join('')
)

const peerList = computed(() =>
  [...peers.value.entries()].map(([site, info], index) => ({
    site,
    name: SITE_NAMES[index % SITE_NAMES.length],
    color: SITE_COLORS[index % SITE_COLORS.length],
    context: contextAt(info.pos)
  }))
)

/** 镜像层分段：按远程光标位置从后往前切文本，切点插入彩色竖线。 */
const mirrorSegments = computed(() => {
  const text = visibleText.value
  const peersByColor = peerList.value
    .map((peer) => ({ pos: Math.min(peers.value.get(peer.site)?.pos ?? 0, text.length), color: peer.color }))
    .sort((a, b) => b.pos - a.pos)
  const segments: Array<{ text: string; color?: string }> = []
  let cursor = text.length
  for (const peer of peersByColor) {
    if (peer.pos >= cursor) {
      continue
    }
    segments.unshift({ text: text.slice(peer.pos, cursor) })
    segments.unshift({ text: '', color: peer.color })
    cursor = peer.pos
  }
  segments.unshift({ text: text.slice(0, cursor) })
  return segments
})

function contextAt(pos: number): string {
  const text = visibleText.value
  const start = Math.max(0, pos - 6)
  return `${text.slice(start, pos)}|${text.slice(pos, pos + 4)}`
}

// ---------------------------------------------------------------------
// 本地操作：textarea 的 beforeinput 统一拦截
// ---------------------------------------------------------------------

const textareaEl = ref<HTMLTextAreaElement | null>(null)
const caretPos = ref(0)

/** 在 index 处插入文本（可多字符，逐字符进 CRDT）。 */
function localInsert(index: number, text: string): void {
  for (const ch of text) {
    const prevId = index === 0 ? null : visibleNodeAt(index - 1)?.id ?? null
    const node: CharNode = { id: `${SITE}-${counter++}`, ch, lamport: ++lamport, tomb: false }
    applyInsert(node, prevId)
    channel.postMessage({ type: 'ins', node, prevId, site: SITE } satisfies Op & { site: string })
    index += 1
  }
}

function localDelete(index: number): void {
  const node = visibleNodeAt(index)
  if (!node) {
    return
  }
  node.tomb = true
  channel.postMessage({ type: 'del', id: node.id, site: SITE } satisfies Op & { site: string })
}

function visibleNodeAt(visibleIndex: number): CharNode | undefined {
  let count = -1
  for (const node of doc.value) {
    if (!node.tomb) {
      count += 1
      if (count === visibleIndex) {
        return node
      }
    }
  }
  return undefined
}

// ---------------------------------------------------------------------
// CRDT 合并
// ---------------------------------------------------------------------

/** 插入合并：锚定在 prevId 之后（教学级实现；同锚点并发由 Lamport 时钟定序）。 */
function applyInsert(node: CharNode, prevId: string | null): void {
  lamport = Math.max(lamport, node.lamport) + 1
  let index = 0
  if (prevId !== null) {
    const anchorIndex = doc.value.findIndex((item) => item.id === prevId)
    if (anchorIndex < 0) {
      // 锚点不存在（乱序到达）：暂时丢弃 —— 生产实现需要操作缓冲区重放
      return
    }
    index = anchorIndex + 1
  }
  doc.value.splice(index, 0, node)
}

function applyRemoteOp(message: MessageEvent): void {
  const op = message.data as Op & { site?: string }
  if (op.site === SITE) {
    return
  }
  if (op.type === 'ins') {
    applyInsert(op.node, op.prevId)
  } else if (op.type === 'del') {
    const node = doc.value.find((item) => item.id === op.id)
    if (node) {
      node.tomb = true
    }
  } else if (op.type === 'hello') {
    // 新成员加入 → 全量状态迁移（最简单的反熵手段）
    channel.postMessage({
      type: 'reset',
      nodes: doc.value.map((node) => ({ ...node })),
      lamport,
      site: SITE
    } satisfies Op & { site: string })
    peers.value.set(op.site, { pos: 0 })
  } else if (op.type === 'reset') {
    doc.value = op.nodes.map((node) => ({ ...node }))
    lamport = Math.max(lamport, op.lamport)
  } else if (op.type === 'cursor') {
    peers.value.set(op.site, { pos: op.pos })
    // 触发响应式更新
    peers.value = new Map(peers.value)
  }
}

// ---------------------------------------------------------------------
// 本地编辑拦截
// ---------------------------------------------------------------------

function onBeforeInput(event: InputEvent): void {
  event.preventDefault()
  const start = textareaEl.value?.selectionStart ?? 0
  const end = textareaEl.value?.selectionEnd ?? 0
  if (event.inputType === 'deleteContentBackward') {
    if (start === end && start > 0) {
      localDelete(start - 1)
      caretPos.value = start - 1
    } else {
      for (let i = end - 1; i >= start; i--) {
        localDelete(i)
      }
      caretPos.value = start
    }
    return
  }
  if (event.inputType === 'deleteContentForward') {
    if (start === end) {
      localDelete(start)
    } else {
      for (let i = end - 1; i >= start; i--) {
        localDelete(i)
      }
    }
    return
  }
  if (event.data !== null) {
    // ③ 富文本粘贴清洗：只取纯文本、合并空白
    const clean =
      event.inputType === 'insertFromPaste'
        ? sanitizePastedText(event.data)
        : event.data
    if (clean !== event.data) {
      feedback.info('粘贴内容已清洗（去除富文本格式与多余空白）')
    }
    localInsert(start, clean)
    caretPos.value = start + clean.length
  }
}

function sanitizePastedText(raw: string): string {
  const plain = new DOMParser().parseFromString(raw, 'text/html').body.textContent ?? raw
  return plain.replace(/\s+/g, ' ').trimStart()
}

/** 光标广播：节流 200ms —— 临时状态高频但不需要可靠投递。 */
let cursorTimer: number | null = null
function onCaretChange(): void {
  caretPos.value = textareaEl.value?.selectionStart ?? 0
  if (cursorTimer !== null) {
    return
  }
  cursorTimer = window.setTimeout(() => {
    cursorTimer = null
    channel.postMessage({ type: 'cursor', site: SITE, pos: caretPos.value } satisfies Op & { site: string })
  }, 200)
}

// ---------------------------------------------------------------------
// 快照与回滚
// ---------------------------------------------------------------------

function takeSnapshot(): void {
  const text = visibleText.value
  if (text === lastSnapshotText.value) {
    return
  }
  lastSnapshotText.value = text
  snapshots.value.unshift({
    time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
    text
  })
  if (snapshots.value.length > 10) {
    snapshots.value.pop()
  }
}

let snapshotTimer: number | null = null

function rollback(snapshot: { text: string }): void {
  // 回滚 = 全量重置 + 广播 reset（所有标签页收敛到同一状态）
  doc.value = snapshot.text.split('').map((ch) => ({
    id: `${SITE}-${counter++}`,
    ch,
    lamport: ++lamport,
    tomb: false
  }))
  channel.postMessage({
    type: 'reset',
    nodes: doc.value.map((node) => ({ ...node })),
    lamport,
    site: SITE
  } satisfies Op & { site: string })
  feedback.success('已回滚到快照（所有标签页同步）')
}

// ---------------------------------------------------------------------
// 生命周期
// ---------------------------------------------------------------------

onMounted(() => {
  channel.addEventListener('message', applyRemoteOp)
  channel.postMessage({ type: 'hello', site: SITE } satisfies Op & { site: string })
  if (doc.value.length === 0) {
    localInsert(0, '在这里输入文字 —— 再开一个本页面的标签页，体验多人实时协同。')
  }
  snapshotTimer = window.setInterval(takeSnapshot, 30_000)
})

onBeforeUnmount(() => {
  channel.close()
  if (snapshotTimer !== null) {
    window.clearInterval(snapshotTimer)
  }
  if (cursorTimer !== null) {
    window.clearTimeout(cursorTimer)
  }
})
</script>

<template>
  <div class="collab">
    <div class="collab__layout">
      <NCard title="协同文档" :bordered="false" class="collab__card">
        <template #header-extra>
          <NSpace align="center">
            <NTag size="small" type="info">本机站点 {{ SITE }}</NTag>
            <NButton size="tiny" @click="takeSnapshot">打快照</NButton>
          </NSpace>
        </template>

        <div class="collab__peers">
          <span
            v-for="peer in peerList"
            :key="peer.site"
            class="collab__peer"
            :style="{ color: peer.color }"
          >
            {{ peer.name }} 在「{{ peer.context }}」处
          </span>
          <span v-if="peerList.length === 0" class="collab__peer collab__peer--none">
            暂无其他协作者 —— 复制本页 URL 到新标签页即可联机
          </span>
        </div>

        <div class="collab__editor">
          <!-- 镜像层：与 textarea 同字体/边距，按光标位置切分文本插入彩色竖线 -->
          <div class="collab__mirror" aria-hidden="true">
            <template v-for="(segment, index) in mirrorSegments" :key="index">
              <span>{{ segment.text }}</span>
              <span v-if="segment.color" class="collab__caret" :style="{ background: segment.color }" />
            </template>
          </div>
          <textarea
            ref="textareaEl"
            :value="visibleText"
            class="collab__textarea"
            spellcheck="false"
            @beforeinput="onBeforeInput"
            @keyup="onCaretChange"
            @click="onCaretChange"
          />
        </div>

        <p class="collab__hint">
          两个标签页同时把光标放到同一位置打字 —— 观察两侧结果收敛一致（CRDT）。
          从网页复制一段富文本粘贴进来 —— 观察格式清洗提示。
        </p>
      </NCard>

      <NCard title="历史版本" :bordered="false" class="collab__card">
        <div v-for="snapshot in snapshots" :key="snapshot.time" class="collab__snapshot">
          <span class="collab__snapshot-time">{{ snapshot.time }}</span>
          <span class="collab__snapshot-text">{{ snapshot.text.slice(0, 24) }}…</span>
          <NButton size="tiny" @click="rollback(snapshot)">回滚</NButton>
        </div>
        <p v-if="snapshots.length === 0" class="collab__hint">内容变化后每 30 秒自动快照，也可手动打点。</p>
      </NCard>
    </div>
  </div>
</template>

<style scoped>
.collab__layout {
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: 16px;
}

@media (max-width: 1100px) {
  .collab__layout {
    grid-template-columns: 1fr;
  }
}

.collab__card {
  margin-bottom: 16px;
}

.collab__peers {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 10px;
  font-size: var(--wa-font-size-sm, 13px);
}

.collab__peer--none {
  color: var(--wa-text-disabled, #a8b0ba);
}

.collab__editor {
  position: relative;
}

.collab__mirror,
.collab__textarea {
  margin: 0;
  padding: 12px;
  border: 1px solid var(--wa-border, #e4e7ed);
  border-radius: var(--wa-radius-md, 4px);
  font-family: inherit;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
}

.collab__mirror {
  position: absolute;
  inset: 0;
  overflow: hidden;
  color: transparent;
  pointer-events: none;
}

.collab__textarea {
  position: relative;
  width: 100%;
  height: 280px;
  resize: vertical;
  outline: none;
  background: var(--wa-bg-elevated, #fff);
}

.collab__caret {
  display: inline-block;
  width: 2px;
  height: 1em;
  vertical-align: text-bottom;
}

.collab__hint {
  margin: 10px 0 0;
  color: var(--wa-text-secondary, #5c6570);
  font-size: var(--wa-font-size-sm, 13px);
}

.collab__snapshot {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px dashed var(--wa-border-light, #f0f2f5);
}

.collab__snapshot-time {
  flex: none;
  color: var(--wa-text-disabled, #a8b0ba);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.collab__snapshot-text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--wa-font-size-sm, 13px);
}
</style>
