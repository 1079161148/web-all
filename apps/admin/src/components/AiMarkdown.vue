<script setup lang="ts">
import { computed, nextTick, ref, shallowRef, onMounted, onBeforeUnmount, watch } from 'vue'
import type MarkdownIt from 'markdown-it'
import { ensureEcharts } from '@admin/ui'

/**
 * AI 回答的 Markdown 流式渲染。
 *
 * <h3>安全边界：markdown-it 的 {@code html: false}</h3>
 * 模型输出可能包含任意内容（提示词注入的典型载荷就是 `<img onerror>`）。
 * {@code html: false} 让 markdown-it 把内联 HTML **原样转义为文本**，
 * 生成物里不存在可执行的原始 HTML —— 不需要再叠一层 DOMPurify。
 * 这条配置是本组件的安全前提，改动前必须重新评估。
 *
 * <h3>流式渲染的成本控制</h3>
 * 每个 delta 到达都会重算一次 markdown —— 对话级别的文本量（几 KB）
 * 完全可接受；若未来渲染长文档，应改为"已确认段渲染一次、
 * 尾部未闭合块纯文本追加"的分段策略，而不是无脑上虚拟滚动。
 */

const props = withDefaults(
  defineProps<{
    /** Markdown 源文本（流式时持续增长）。 */
    text: string
    /** 是否流式中（末尾显示打字光标）。 */
    streaming?: boolean
  }>(),
  { streaming: false }
)

const ready = ref(false)
const mdRef = shallowRef<InstanceType<typeof MarkdownIt> | null>(null)

onMounted(async () => {
  // 懒加载：只有出现 AI 回答的页面才付出这个体积
  const MarkdownIt = (await import('markdown-it')).default
  mdRef.value = new MarkdownIt({
    html: false, // ⚠️ 安全前提，见组件注释
    linkify: true,
    breaks: true
  })
  ready.value = true
})

/**
 * 容错规整：小型模型经常输出非标准 markdown —— `##标题`（# 后缺空格）、
 * `-项目`（- 后缺空格）。标准解析器会把它们当普通文本原样显示。
 * 这里只做两种最低风险的补空格，不做任何语义改写。
 */
function normalize(source: string): string {
  return source
    .replace(/^(#{1,6})([^ #\n])/gm, '$1 $2')
    .replace(/^([*+-])([^\s*+-])/gm, '$1 $2')
}

const html = computed(() => {
  if (!ready.value || !mdRef.value) {
    // 内核未就绪时退化为纯文本（转义换行），绝不显示空白
    return displayText.value
      ? displayText.value
          .replaceAll('&', '&amp;')
          .replaceAll('<', '&lt;')
          .replaceAll('>', '&gt;')
          .replaceAll('\n', '<br>')
      : ''
  }
  return mdRef.value.render(normalize(displayText.value))
})

/**
 * 流式渲染 + 打字机缓冲。
 *
 * <h3>为什么需要缓冲层</h3>
 * 理想情况下 delta 逐字到达，text 自然逐步增长；但上游并非总是如此 ——
 * 非流式回退、网络抖动后的整段补发、demo 模式，都会让 text <b>一次性</b>
 * 变成全文。没有缓冲层时表现为"内容啪一下全出来"，丢失打字机体验。
 *
 * <h3>时间驱动的匀速放字</h3>
 * displayText 每帧向 target 追赶，速度按真实流逝时间计算：基础速率
 * 160 字/秒（60Hz 下约 2~3 字/帧 —— 更小的等步长会给人"一个个字蹦"的
 * 顿挫感），积压越大追得越快（最多约 2.5 秒放完积压）。上游真的逐字
 * 来时几乎零附加延迟；掉帧时自动多放，恢复后不会报复性 burst。
 *
 * <h3>渲染成本控制</h3>
 * 每个 delta 到达都会重算一次 markdown —— 对话级别的文本量（几 KB）
 * 完全可接受；若未来渲染长文档，应改为"已确认段渲染一次、
 * 尾部未闭合块纯文本追加"的分段策略，而不是无脑上虚拟滚动。
 */
const displayText = ref('')
let rafId = 0
let target = ''
/** 上一帧时间戳 —— 速度按真实流逝时间算，帧率波动不影响观感。 */
let lastTs = 0

/** 缓冲未追平（打字机还在放字）—— 光标要保持闪烁到放完。 */
const catchingUp = computed(() => displayText.value.length < target.length)

/** 每帧放字并重渲染 markdown；追上 target 后停止空转。 */
function pump(ts: number): void {
  rafId = 0
  if (displayText.value.length < target.length) {
    const dt = lastTs > 0 ? Math.min(0.1, (ts - lastTs) / 1000) : 0.016
    lastTs = ts
    const remain = target.length - displayText.value.length
    const speed = Math.max(160, remain / 2.5)
    const advance = Math.max(1, Math.round(speed * dt))
    displayText.value = target.slice(0, displayText.value.length + advance)
  } else {
    lastTs = 0
  }
  // ⚠️ 必须等 Vue 把新 html patch 进 DOM 之后再找 echarts 代码块 ——
  // 同步查会拿到旧 DOM（新输出的 fence 尚未上树），图表永远不渲染（实测踩过）
  void nextTick(() => {
    void renderEchartsBlocks()
  })
  if (displayText.value.length < target.length && rafId === 0) {
    rafId = requestAnimationFrame(pump)
  }
}

watch(
  () => props.text,
  (next) => {
    target = next
    if (rafId === 0) {
      rafId = requestAnimationFrame(pump)
    }
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  if (rafId) {
    cancelAnimationFrame(rafId)
  }
  disposeCharts()
})

// ---------------------------------------------------------------------
// echarts 代码块渲染
// ---------------------------------------------------------------------
// 约定协议：模型输出 ```echarts 代码块，内容为合法的 ECharts option JSON
// （后端系统提示词约束 type 仅用 bar/line/pie）。markdown-it 会把它渲染成
// <pre><code class="language-echarts">，这里在 DOM 层把它替换成真实图表。

const rootEl = ref<HTMLDivElement | null>(null)
const charts: Array<{ chart: { dispose: () => void; resize: () => void }; resizeHandler: () => void }> = []

async function renderEchartsBlocks(): Promise<void> {
  // v-html 每次更新都会重置 DOM —— 上一轮 init 的图表实例已随旧 DOM 销毁，先清账
  disposeCharts()
  const blocks = rootEl.value?.querySelectorAll('code.language-echarts')
  if (!blocks || blocks.length === 0) {
    return
  }
  // 复用 @admin/ui 的 ECharts 单例（init 同实例，bar/line/pie 已注册，
  // 避免应用侧再引一份 echarts 造成双实例分裂 —— 见 adapters/echarts.ts）
  const { init } = await ensureEcharts()
  for (const codeEl of Array.from(blocks)) {
    const pre = codeEl.parentElement
    if (!pre || pre.dataset.rendered === '1') {
      continue
    }
    pre.dataset.rendered = '1'
    const holder = document.createElement('div')
    holder.className = 'ai-md__chart'
    try {
      const option = JSON.parse(codeEl.textContent ?? '{}')
      pre.replaceWith(holder)
      const chart = init(holder)
      chart.setOption(option)
      const resizeHandler = () => chart.resize()
      window.addEventListener('resize', resizeHandler)
      charts.push({ chart, resizeHandler })
    } catch {
      // JSON 非法：保留原始代码块（模型输出的兜底，绝不显示空白图）
    }
  }
}

function disposeCharts(): void {
  for (const item of charts) {
    window.removeEventListener('resize', item.resizeHandler)
    item.chart.dispose()
  }
  charts.length = 0
}
</script>

<template>
  <div ref="rootEl" class="ai-md">
    <!-- eslint-disable-next-line vue/no-v-html —— 内容经 markdown-it html:false 转义，安全边界见注释 -->
    <div class="ai-md__body" v-html="html" />
    <span v-if="streaming || catchingUp" class="ai-md__cursor" />
  </div>
</template>

<style scoped>
.ai-md__body {
  font-size: 13px;
  line-height: 1.8;
  word-break: break-word;
}

.ai-md__body :deep(p) {
  margin: 0 0 8px;
}

.ai-md__body :deep(p:last-child) {
  margin-bottom: 0;
}

.ai-md__body :deep(ul),
.ai-md__body :deep(ol) {
  margin: 4px 0 8px;
  padding-left: 20px;
}

.ai-md__body :deep(code) {
  background: rgba(128, 128, 128, 0.15);
  border-radius: 3px;
  padding: 1px 5px;
  font-size: 12px;
}

.ai-md__body :deep(pre) {
  background: rgba(128, 128, 128, 0.12);
  border-radius: 6px;
  padding: 10px;
  overflow-x: auto;
}

.ai-md__body :deep(a) {
  color: var(--n-primary, #18a058);
}

.ai-md__chart {
  width: 100%;
  height: 240px;
  margin: 8px 0;
}

.ai-md__cursor {
  display: inline-block;
  width: 7px;
  height: 15px;
  margin-left: 2px;
  vertical-align: -2px;
  background: currentColor;
  animation: ai-md-blink 0.9s steps(1) infinite;
}

@keyframes ai-md-blink {
  50% {
    opacity: 0;
  }
}
</style>
