<script setup lang="ts">
import { ref, watch } from 'vue'
import { NButton, NTag, ProModal, feedback } from '@admin/ui'
import type { SessionViewResponse } from '@admin/api'
import { listMySessions, revokeMySession } from '@admin/api'

/**
 * 在线会话管理（当前用户自己的）。
 *
 * <h3>它回答的问题</h3>
 * "我的账号在哪些设备上登录着？"—— 这是会话治理对普通用户可见的那一面：
 * 忘了退出网吧的机器、发现一个陌生 IP，都可以在这里直接处理，
 * 而不必找管理员"把我的号踢下线"。
 *
 * <h3>为什么注销当前会话要单独提醒</h3>
 * 注销 {@code current=true} 的会话等价于退出登录 ——
 * 下一个动作就是跳登录页。交互上必须让用户意识到这一点，
 * 否则"我点了注销然后就莫名要重新登录"会被当成 bug 上报。
 */

const visible = defineModel<boolean>('visible', { default: false })

const sessions = ref<SessionViewResponse[]>([])
const loading = ref(false)
const revoking = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  try {
    sessions.value = await listMySessions()
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '读取在线会话失败')
  } finally {
    loading.value = false
  }
}

watch(visible, (open) => {
  if (open) {
    void load()
  }
})

async function revoke(session: SessionViewResponse): Promise<void> {
  // 生成契约里 sessionId 是可选字段；缺它就无法指定要注销谁 —— 直接不渲染操作，
  // 这里兜底拒绝（否则会把 undefined 发给后端）
  const sessionId = session.sessionId
  if (!sessionId) {
    return
  }
  const confirmed = await feedback.confirm({
    title: session.current ? '注销当前会话' : '注销会话',
    content: session.current
      ? '这是你当前正在使用的会话，注销后需要重新登录。确定继续吗？'
      : `确定注销该会话吗？该设备将被退出登录（IP：${session.ip || '未知'}）。`,
    positiveText: '注销',
    negativeText: '取消'
  })
  if (!confirmed) {
    return
  }
  revoking.value = sessionId
  try {
    await revokeMySession(sessionId)
    feedback.success(session.current ? '当前会话已注销' : '会话已注销')
    if (session.current) {
      // 当前会话已死，后续请求会 401 → 由请求层跳登录；这里只刷新列表呈现结果
      await load()
      return
    }
    await load()
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '注销失败')
  } finally {
    revoking.value = null
  }
}

function formatTime(value: string | undefined): string {
  if (!value) {
    return '-'
  }
  return new Date(value).toLocaleString()
}
</script>

<template>
  <!-- 底部按钮是"刷新"：这是一个只读+按需操作的弹窗，不需要"保存"语义 -->
  <ProModal
    v-model:visible="visible"
    title="在线会话"
    :width="640"
    :loading="loading"
    submit-text="刷新"
    @success="load"
  >
    <p class="session-dialog__tip">
      以下是你账号当前处于登录状态的设备。发现不认识的会话，直接注销即可 ——
      该设备的访问令牌与刷新令牌会同时失效。
    </p>

    <div
      v-for="session in sessions"
      :key="session.sessionId"
      class="session-dialog__item"
      :class="{ 'session-dialog__item--current': session.current }"
    >
      <div class="session-dialog__main">
        <div class="session-dialog__head">
          <NTag v-if="session.current" size="small" type="success" :bordered="false">当前设备</NTag>
          <span class="session-dialog__ip">{{ session.ip || '未知 IP' }}</span>
        </div>
        <div class="session-dialog__meta">
          登录：{{ formatTime(session.loginTime) }} · 最后活跃：{{ formatTime(session.lastActive) }}
        </div>
        <div v-if="session.userAgent" class="session-dialog__meta">{{ session.userAgent }}</div>
      </div>
      <NButton
        size="small"
        :type="session.current ? 'warning' : 'error'"
        :loading="revoking === session.sessionId"
        @click="revoke(session)"
      >
        注销
      </NButton>
    </div>

    <p v-if="!loading && sessions.length === 0" class="session-dialog__empty">
      暂无在线会话（可能是存储刚被清理，重新登录后可见）。
    </p>
  </ProModal>
</template>

<style scoped>
.session-dialog__tip {
  margin: 0 0 var(--wa-spacing-md, 12px);
  font-size: var(--wa-font-size-xs, 12px);
  line-height: 1.6;
  color: var(--wa-text-disabled, #a8b0ba);
}

.session-dialog__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--wa-spacing-md, 12px);
  padding: var(--wa-spacing-sm, 8px) var(--wa-spacing-md, 12px);
  border: 1px solid var(--wa-border, #e4e7ed);
  border-radius: var(--wa-radius-md, 4px);
}

.session-dialog__item + .session-dialog__item {
  margin-top: var(--wa-spacing-sm, 8px);
}

.session-dialog__item--current {
  border-color: var(--wa-color-success, #18a058);
}

.session-dialog__main {
  min-width: 0;
}

.session-dialog__head {
  display: flex;
  align-items: center;
  gap: var(--wa-spacing-sm, 8px);
}

.session-dialog__ip {
  font-size: var(--wa-font-size-md, 14px);
  color: var(--wa-text-primary, #1f2329);
}

.session-dialog__meta {
  margin-top: 2px;
  font-size: var(--wa-font-size-xs, 12px);
  color: var(--wa-text-disabled, #a8b0ba);
  word-break: break-all;
}

.session-dialog__empty {
  margin: var(--wa-spacing-md, 12px) 0 0;
  font-size: var(--wa-font-size-xs, 12px);
  color: var(--wa-text-disabled, #a8b0ba);
  text-align: center;
}
</style>
