<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { NButton, NCard, NTag, ProTable } from '@admin/ui'
import { pageAuditLogs, type AuditLogView } from '@/api/audit'

/**
 * 操作审计页：谁、在何时、对哪条业务数据、改了哪些字段。
 *
 * 数据来源链路：业务方法捕获 before/after 快照 → 发布领域事件
 * （事务性 Outbox，事件与业务数据同事务落库）→ 消费者计算字段级 diff
 * 写入审计表。业务失败则事件不投递；消费者失败 Outbox 重试 ——
 * "业务成功则审计必达"由这条链路保证。
 *
 * 审计纪律：审计表只增不改不删 —— 查询端点刻意不提供任何写操作。
 */
const tableRef = ref()
const refreshing = ref(false)
const expanded = ref<AuditLogView | null>(null)

const columns = [
  { key: 'createTime', title: '时间' },
  { key: 'username', title: '操作人' },
  { key: 'action', title: '动作' },
  { key: 'bizType', title: '业务类型' },
  { key: 'summary', title: '摘要' }
]

async function requestAudit(query: { page: number; size: number }) {
  const result = await pageAuditLogs(query.page, query.size)
  return {
    records: result.records ?? [],
    total: result.total ?? 0,
    page: query.page,
    size: query.size
  }
}

async function refresh(): Promise<void> {
  refreshing.value = true
  try {
    await tableRef.value?.refresh()
  } finally {
    refreshing.value = false
  }
}

/** 点行展开/收起字段级 diff。 */
function onRowClick(row: AuditLogView): void {
  expanded.value = expanded.value?.id === row.id ? null : row
}

function actionColor(action: string): 'success' | 'warning' {
  return action === 'CREATED' ? 'success' : 'warning'
}

let autoTimer: number | null = null

onMounted(() => {
  autoTimer = window.setInterval(refresh, 15_000)
})

onBeforeUnmount(() => {
  if (autoTimer !== null) {
    window.clearInterval(autoTimer)
  }
})
</script>

<template>
  <div class="audit">
    <NCard :bordered="false" class="audit__card">
      <div class="audit__toolbar">
        <NTag size="small" type="info">审计链：业务成功 → 事件必达 → 字段级 diff 落库（点击行查看变更明细）</NTag>
        <NButton size="small" :loading="refreshing" @click="refresh">刷新</NButton>
      </div>

      <ProTable
        ref="tableRef"
        :columns="columns"
        :request="requestAudit"
        :row-key="'id'"
        :toolbar="[]"
        :default-page-size="10"
        @row-click="onRowClick"
      >
        <template #empty>
          <p class="audit__empty">
            暂无审计记录 —— 在「AI调研」里新建或编辑一条任务后回到本页查看。
          </p>
        </template>
      </ProTable>

      <div v-if="expanded" class="audit__diff">
        <div class="audit__diff-head">
          <NTag size="small" :type="actionColor(expanded.action)">{{ expanded.action }}</NTag>
          <span>{{ expanded.summary }}</span>
        </div>
        <pre v-if="expanded.diffText" class="audit__diff-text">{{ expanded.diffText }}</pre>
        <p v-else class="audit__hint">新建事件无字段 diff（summary 已包含全部信息）。</p>
      </div>
    </NCard>
  </div>
</template>

<style scoped>
.audit__card {
  margin-bottom: 16px;
}

.audit__toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
}

.audit__diff {
  margin-top: 14px;
  padding: 12px;
  border: 1px solid var(--wa-border-light, #f0f2f5);
  border-radius: var(--wa-radius-md, 4px);
  background: var(--wa-fill-light, #f5f7fa);
}

.audit__diff-head {
  display: flex;
  gap: 10px;
  align-items: center;
  font-size: var(--wa-font-size-sm, 13px);
  margin-bottom: 8px;
}

.audit__diff-text {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--wa-text-primary, #1f2329);
}

.audit__hint {
  margin: 0;
  color: var(--wa-text-secondary, #5c6570);
  font-size: var(--wa-font-size-sm, 13px);
}

.audit__empty {
  margin: 0;
  color: var(--wa-text-disabled, #a8b0ba);
  font-size: var(--wa-font-size-sm, 13px);
}
</style>