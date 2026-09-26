<script setup lang="ts">
import { computed, ref } from 'vue'
import { NButton, NCard, NCheckbox, NInput, NSelect, NTag, feedback } from '@admin/ui'
import { listOpsTickets, type OpsTicket } from '@/api/dispatch'
import { onMounted } from 'vue'

/**
 * 打印中心：复杂报表打印的最佳实践。
 *
 * <h3>核心手段：打印在 iframe 里进行，而不是给主应用写 @media print</h3>
 * 给主应用写打印样式是条歧路：布局层的侧边栏、标签页、悬浮按钮全都要
 * 一条条"打印时隐藏"，任何一个新组件忘了写就是打印出来一页垃圾；
 * 而且主样式与打印样式的选择器互相污染，改一处崩一处。
 * iframe 里是<b>一份从零生成的干净文档</b>：里面只有报表自己的样式，
 * `iframe.contentWindow.print()` 只打印这份文档 —— 隔离是结构性的，
 * 不靠"记得隐藏什么"。
 *
 * <h3>分页与表头重复（有据可循的浏览器行为）</h3>
 * <ul>
 *   <li>`thead` 跨页重复是 HTML 标准表格分页行为（Chrome/Firefox 均实现）——
 *       用真表格而不是 div 网格，表头重复就免费拿到；</li>
 *   <li>`break-inside: avoid` 让一行数据不被从中间劈开；</li>
 *   <li>`@page` 控制纸张边距。</li>
 * </ul>
 *
 * <h3>水印</h3>
 * `position: fixed` 的元素在打印时<b>每一页都会重复</b>（标准行为），
 * 斜置半透明的文字水印就这一个元素，不需要按页复制。
 *
 * <h3>PDF 从哪来</h3>
 * 不内置 PDF 库：打印对话框里"另存为 PDF"就是用户路径 ——
 * 前端引 jsPDF 手绘表格会同时失去表头重复与分页控制两样浏览器原生能力。
 */

const tickets = ref<OpsTicket[]>([])
const loading = ref(false)

const reportTitle = ref('工单执行报表')
const watermarkText = ref('内部资料')
const withWatermark = ref(true)
const withSla = ref(true)

const frameRef = ref<HTMLIFrameElement | null>(null)

const columnOptions = [
  { label: '标题', value: 'title' },
  { label: '渠道', value: 'channel' },
  { label: '优先级', value: 'priority' },
  { label: '状态', value: 'state' },
  { label: '负责人', value: 'claimer' },
  { label: 'SLA(分)', value: 'slaMinutes' }
]
const selectedColumns = ref<string[]>(['title', 'channel', 'priority', 'state', 'claimer'])

const COLUMN_TITLE: Record<string, string> = {
  title: '标题',
  channel: '渠道',
  priority: '优先级',
  state: '状态',
  claimer: '负责人',
  slaMinutes: 'SLA(分)'
}

const STATE_LABEL: Record<string, string> = {
  PENDING: '待处理',
  CLAIMED: '处理中',
  DONE: '已完成',
  ESCALATED: '已升级'
}

/** 报表数据（按渠道过滤由外部筛选完成，这里全量，按状态与创建时间排序）。 */
const reportRows = computed(() =>
  [...tickets.value].sort((a, b) => b.id - a.id).slice(0, 200)
)

/** 单元格文本。所有插值都经 {@link escapeHtml} —— 报表数据可能来自任何用户输入。 */
function cellText(row: OpsTicket, column: string): string {
  const raw: unknown = row[column as keyof OpsTicket]
  if (column === 'state') {
    return STATE_LABEL[String(raw)] ?? String(raw ?? '')
  }
  return raw === null || raw === undefined ? '—' : String(raw)
}

/** HTML 转义：报表里的每个动态值都必须经过它（数据 → HTML 的唯一入口）。 */
function escapeHtml(value: string): string {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
}

/**
 * 报表文档（iframe 的 srcdoc）。
 *
 * <p>刻意生成完整 HTML 而不是用主应用的组件渲染再抓 DOM：
 * 打印文档需要自己的字体、字号与分页规则，它本来就该是一份独立产物；
 * 抓 DOM 的方案会连同学应用的 CSS 变量缺失、暗色主题等一堆运行时状态。
 */
const reportDoc = computed(() => {
  const cols = selectedColumns.value
  const head = cols.map((c) => `<th>${escapeHtml(COLUMN_TITLE[c] ?? c)}</th>`).join('')
  const body = reportRows.value
    .map(
      (row) =>
        `<tr>${cols
          .map((c) => `<td>${escapeHtml(cellText(row, c))}</td>`)
          .join('')}</tr>`
    )
    .join('\n')
  const watermark = withWatermark.value
    ? `<div class="watermark">${escapeHtml(watermarkText.value)}</div>`
    : ''
  const slaNote = withSla.value
    ? `<p class="note">SLA：工单自创建起 ${escapeHtml(
        String(Math.max(...reportRows.value.map((t) => t.slaMinutes), 0))
      )} 分钟内需被接手，超时自动升级。</p>`
    : ''
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>${escapeHtml(reportTitle.value)}</title>
<style>
  @page { margin: 14mm 12mm; }
  body { font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
         color: #222; font-size: 12px; margin: 0; }
  h1 { font-size: 18px; text-align: center; margin: 0 0 4px; }
  .meta { text-align: center; color: #666; font-size: 11px; margin-bottom: 12px; }
  table { width: 100%; border-collapse: collapse; }
  th, td { border: 1px solid #999; padding: 4px 6px; text-align: left; }
  th { background: #f0f0f0; }
  /* 行不被跨页劈开；thead 跨页重复由浏览器标准行为提供 */
  tr { break-inside: avoid; }
  .note { font-size: 11px; color: #555; }
  .watermark {
    position: fixed; inset: 0; display: flex; align-items: center;
    justify-content: center; pointer-events: none; z-index: -1;
    font-size: 72px; color: rgba(0, 0, 0, 0.05);
    transform: rotate(-24deg); white-space: nowrap;
  }
</style>
</head>
<body>
  ${watermark}
  <h1>${escapeHtml(reportTitle.value)}</h1>
  <p class="meta">共 ${reportRows.value.length} 条 · 生成于 ${new Date().toLocaleString()}</p>
  <table>
    <thead><tr>${head}</tr></thead>
    <tbody>${body}</tbody>
  </table>
  ${slaNote}
</body>
</html>`
})

/** 只打印 iframe 里的文档。打印对话框中"另存为 PDF"即得 PDF。 */
function printReport(): void {
  const frame = frameRef.value
  if (!frame?.contentWindow) {
    return
  }
  frame.contentWindow.focus()
  frame.contentWindow.print()
}

async function loadTickets(): Promise<void> {
  loading.value = true
  try {
    tickets.value = await listOpsTickets()
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '数据加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void loadTickets()
})
</script>

<template>
  <div class="print-center">
    <NCard title="打印中心 · iframe 打印隔离" :bordered="false" class="print-center__card">
      <p class="print-center__desc">
        打印在 iframe 里进行：报表是一份独立生成的干净文档，
        主应用的侧边栏/标签页/主题样式天然不会混进打印结果（结构性的隔离，
        而不是"@media print 里记得隐藏什么"）。表头跨页重复、行不劈开、
        水印每页重复均为浏览器标准行为。打印对话框选"另存为 PDF"即得 PDF。
      </p>
      <div class="print-center__toolbar">
        <NInput v-model:value="reportTitle" size="small" class="print-center__title" placeholder="报表标题" />
        <NSelect
          v-model:value="selectedColumns"
          multiple
          size="small"
          :options="columnOptions"
          class="print-center__cols"
          placeholder="选择列"
        />
        <NCheckbox v-model:checked="withWatermark">水印</NCheckbox>
        <NInput
          v-if="withWatermark"
          v-model:value="watermarkText"
          size="small"
          class="print-center__wm"
          placeholder="水印文字"
        />
        <NCheckbox v-model:checked="withSla">附 SLA 说明</NCheckbox>
        <NTag size="small" type="info">{{ reportRows.length }} 行</NTag>
        <NButton size="small" type="primary" :loading="loading" @click="printReport">
          打印 / 导出 PDF
        </NButton>
      </div>
    </NCard>

    <NCard title="打印预览（iframe 实时渲染）" :bordered="false" class="print-center__card">
      <iframe
        ref="frameRef"
        :srcdoc="reportDoc"
        class="print-center__frame"
        title="打印预览"
      />
    </NCard>
  </div>
</template>

<style scoped>
.print-center__card {
  margin-bottom: 16px;
}

.print-center__desc {
  margin: 0 0 12px;
  color: var(--n-text-color-2, #666);
  font-size: 13px;
  line-height: 1.8;
}

.print-center__toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}

.print-center__title {
  width: 200px;
}

.print-center__cols {
  width: 320px;
}

.print-center__wm {
  width: 140px;
}

.print-center__frame {
  width: 100%;
  height: 560px;
  border: 1px solid rgba(128, 128, 128, 0.3);
  background: #fff;
  border-radius: 4px;
}
</style>
