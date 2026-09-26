<script setup lang="ts">
import { ref, watch } from 'vue'
import { NButton, NSpace, NTag, ProModal, feedback, hasPermission } from '@admin/ui'
import type { MessageViewResponse } from '@admin/api'
import {
  announceAction,
  fetchMyMessages,
  markAllMessagesReadAction,
  markMessageReadAction
} from '@/api/message'
import { useNotifications } from '@/composables/useNotifications'

/**
 * 消息面板（收件箱 + 公告发布）。
 *
 * <h3>交互设计的取舍</h3>
 * 消息中心做成<b>顶栏面板</b>而不是独立页面：看消息是一个高频、
 * 低停留的动作（扫一眼角标 → 点开 → 处理 → 关掉），独立页面
 * 会把"看一眼"变成"跳走再跳回来"。公告发布也放在面板里
 * （有权限才可见），运营不用为了发一条公告记住另一个页面在哪。
 */

const visible = defineModel<boolean>('visible', { default: false })

const { unread, refresh } = useNotifications()

const messages = ref<MessageViewResponse[]>([])
const loading = ref(false)
const readAllLoading = ref(false)
const announceVisible = ref(false)
const announceSubmitting = ref(false)

const canPublish = hasPermission('plt:message:publish')

const announceItems = [
  {
    field: 'title',
    title: '公告标题',
    required: true,
    message: '请输入公告标题',
    placeholder: '如：系统将于本周六凌晨升级维护'
  },
  { field: 'content', title: '正文', type: 'textarea' as const }
]

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await fetchMyMessages(1, 20)
    messages.value = result.records ?? []
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '读取消息失败')
  } finally {
    loading.value = false
  }
}

watch(visible, (open) => {
  if (open) {
    void load()
  }
})

async function openMessage(message: MessageViewResponse): Promise<void> {
  if (message.isRead || message.id === undefined) {
    return
  }
  try {
    await markMessageReadAction(message.id)
    message.isRead = true
    await refresh()
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '标记已读失败')
  }
}

async function readAll(): Promise<void> {
  readAllLoading.value = true
  try {
    const count = await markAllMessagesReadAction()
    feedback.success(count > 0 ? `已将 ${count} 条消息标记为已读` : '没有未读消息')
    await load()
    await refresh()
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    readAllLoading.value = false
  }
}

async function submitAnnounce(values: Record<string, unknown>): Promise<void> {
  const count = await announceAction({
    title: String(values.title ?? ''),
    content: values.content ? String(values.content) : ''
  })
  feedback.success(`公告已发布给本租户 ${count} 位用户`)
  announceVisible.value = false
  await load()
  await refresh()
}

function formatTime(value: string | undefined): string {
  return value ? new Date(value).toLocaleString() : '-'
}
</script>

<template>
  <ProModal
    v-model:visible="visible"
    title="消息中心"
    :width="640"
    :loading="loading"
    submit-text="刷新"
    @success="load"
  >
    <n-space justify="space-between" align="center" style="margin-bottom: 8px">
      <span class="message-panel__summary">
        未读 <b>{{ unread }}</b> 条
      </span>
      <n-space :size="8">
        <NButton
          v-if="canPublish"
          size="small"
          type="primary"
          ghost
          @click="announceVisible = true"
        >
          发布公告
        </NButton>
        <NButton size="small" :loading="readAllLoading" @click="readAll">全部已读</NButton>
      </n-space>
    </n-space>

    <div
      v-for="message in messages"
      :key="message.id"
      class="message-panel__item"
      :class="{ 'message-panel__item--unread': !message.isRead }"
      @click="openMessage(message)"
    >
      <div class="message-panel__main">
        <div class="message-panel__title">
          <span v-if="!message.isRead" class="message-panel__dot"></span>
          {{ message.title }}
        </div>
        <div class="message-panel__meta">
          {{ formatTime(message.createTime) }}
        </div>
        <div v-if="message.content" class="message-panel__content">{{ message.content }}</div>
      </div>
      <NTag size="small" :bordered="false" :type="message.isRead ? 'default' : 'info'">
        {{ message.isRead ? '已读' : '未读' }}
      </NTag>
    </div>

    <p v-if="!loading && messages.length === 0" class="message-panel__empty">
      暂无消息
    </p>
  </ProModal>

  <!-- 公告发布：标准两字段表单，走 ProModal 的表单模式 -->
  <ProModal
    v-model:visible="announceVisible"
    title="发布公告"
    :items="announceItems"
    :cols="1"
    :loading="announceSubmitting"
    submit-text="发布"
    :submit="submitAnnounce"
    @error="(error: unknown) => feedback.error(error instanceof Error ? error.message : '发布失败')"
  >
    <p class="message-panel__tip">
      公告将发给本租户<b>全部用户</b>，在线用户会立即收到提醒。发布后不可撤回，请确认内容。
    </p>
  </ProModal>
</template>

<style scoped>
.message-panel__summary {
  font-size: var(--wa-font-size-sm, 13px);
  color: var(--wa-text-primary, #1f2329);
}

.message-panel__item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--wa-spacing-md, 12px);
  padding: var(--wa-spacing-sm, 8px) var(--wa-spacing-md, 12px);
  border: 1px solid var(--wa-border, #e4e7ed);
  border-radius: var(--wa-radius-md, 4px);
  cursor: pointer;
}

.message-panel__item + .message-panel__item {
  margin-top: var(--wa-spacing-sm, 8px);
}

.message-panel__item--unread {
  background: var(--wa-color-primary-light, #f0f7ff);
  border-color: var(--wa-color-primary-light, #b7d4ff);
}

.message-panel__main {
  min-width: 0;
}

.message-panel__title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--wa-font-size-md, 14px);
  color: var(--wa-text-primary, #1f2329);
}

.message-panel__dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--wa-color-error, #d03050);
}

.message-panel__meta {
  margin-top: 2px;
  font-size: var(--wa-font-size-xs, 12px);
  color: var(--wa-text-disabled, #a8b0ba);
}

.message-panel__content {
  margin-top: 4px;
  font-size: var(--wa-font-size-sm, 13px);
  color: var(--wa-text-secondary, #57606a);
  white-space: pre-wrap;
  word-break: break-all;
}

.message-panel__empty {
  margin: var(--wa-spacing-lg, 16px) 0 0;
  text-align: center;
  font-size: var(--wa-font-size-xs, 12px);
  color: var(--wa-text-disabled, #a8b0ba);
}

.message-panel__tip {
  margin: 0;
  font-size: var(--wa-font-size-xs, 12px);
  line-height: 1.6;
  color: var(--wa-text-disabled, #a8b0ba);
}
</style>
