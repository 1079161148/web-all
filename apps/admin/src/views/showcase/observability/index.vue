<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { NButton, NCard, NTag, feedback } from '@admin/ui'
import { getObservabilitySummary, type ObservabilitySummary } from '@/api/observability'

/**
 * 前端可观测看板。
 *
 * <h3>这一页回答的三个问题（数据来自 fe_event 聚合）</h3>
 * <ol>
 *   <li><b>哪个接口在拖慢用户？</b>近 1 小时按平均耗时排序的 Top 10
 *       （含调用次数 —— 平均值要配合次数看，100 次里 1 次慢不叫慢）；</li>
 *   <li><b>用户正在遭遇什么错误？</b>最近 50 条前端错误（含页面与堆栈摘要）——
 *       这在"用户说页面坏了但说不清"时是唯一线索；</li>
 *   <li><b>用户在看哪里？</b>近 1 天页面访问 Top 10 —— 优化要花在
 *       用户真正在用的页面上。</li>
 * </ol>
 *
 * <p>15 秒轮询：观测数据不需要秒级实时（那是监控大屏的事），
 * 15 秒足够"看着它动"，又不会让查询本身成为负担。
 */

const summary = shallowRef<ObservabilitySummary | null>(null)
const loading = ref(false)
let pollTimer: number | null = null

async function refresh(): Promise<void> {
  loading.value = true
  try {
    summary.value = await getObservabilitySummary()
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function fmtMs(value: number): string {
  return value >= 1000 ? `${(value / 1000).toFixed(2)} s` : `${Math.round(value)} ms`
}

function fmtTime(value: string | null): string {
  if (!value) {
    return ''
  }
  return new Date(value).toLocaleString()
}

onMounted(() => {
  void refresh()
  pollTimer = window.setInterval(() => {
    void refresh()
  }, 15_000)
})

onBeforeUnmount(() => {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
  }
})
</script>

<template>
  <div class="fe-obs">
    <NCard :bordered="false" class="fe-obs__card">
      <div class="fe-obs__toolbar">
        <NTag size="small" type="info">数据来源：所有登录用户的浏览器（Resource Timing + 错误兜底 + Web Vitals）</NTag>
        <NButton size="small" :loading="loading" @click="refresh">立即刷新</NButton>
      </div>
    </NCard>

    <NCard title="慢接口 Top 10（近 1 小时 · 按平均耗时）" :bordered="false" class="fe-obs__card">
      <table v-if="summary && summary.slowApis.length > 0" class="fe-obs__table">
        <thead>
          <tr><th>接口</th><th>调用</th><th>平均</th><th>最大</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in summary.slowApis" :key="row.name">
            <td class="fe-obs__mono">{{ row.name }}</td>
            <td>{{ row.calls }}</td>
            <td>{{ fmtMs(row.avgMs) }}</td>
            <td>{{ fmtMs(row.maxMs) }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="fe-obs__empty">近 1 小时暂无接口请求（数据由访问行为累积）。</p>
    </NCard>

    <NCard title="最近前端错误（50 条）" :bordered="false" class="fe-obs__card">
      <table v-if="summary && summary.errors.length > 0" class="fe-obs__table">
        <thead>
          <tr><th>时间</th><th>页面</th><th>错误</th></tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in summary.errors" :key="index">
            <td class="fe-obs__nowrap">{{ fmtTime(row.createdAt) }}</td>
            <td class="fe-obs__mono">{{ row.page ?? '—' }}</td>
            <td>
              <div class="fe-obs__err">{{ row.name }}</div>
              <div v-if="row.detail" class="fe-obs__detail">{{ row.detail }}</div>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="fe-obs__empty">✓ 暂无前端错误记录。</p>
    </NCard>

    <NCard title="页面访问 Top 10（近 1 天）" :bordered="false" class="fe-obs__card">
      <table v-if="summary && summary.topPages.length > 0" class="fe-obs__table">
        <thead>
          <tr><th>页面</th><th>访问次数</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in summary.topPages" :key="row.page">
            <td class="fe-obs__mono">{{ row.page }}</td>
            <td>{{ row.views }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="fe-obs__empty">暂无路由访问记录。</p>
    </NCard>
  </div>
</template>

<style scoped>
.fe-obs__card {
  margin-bottom: 16px;
}

.fe-obs__toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}

.fe-obs__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.fe-obs__table th,
.fe-obs__table td {
  border-bottom: 1px solid rgba(128, 128, 128, 0.18);
  padding: 8px 10px;
  text-align: left;
}

.fe-obs__mono {
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
  word-break: break-all;
}

.fe-obs__nowrap {
  white-space: nowrap;
}

.fe-obs__err {
  color: #d03050;
}

.fe-obs__detail {
  font-family: Consolas, Menlo, monospace;
  font-size: 11px;
  opacity: 0.65;
  word-break: break-all;
}

.fe-obs__empty {
  color: var(--n-text-color-2, #666);
  font-size: 13px;
  margin: 8px 0;
}
</style>
