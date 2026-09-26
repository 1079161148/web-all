<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NCard, NInput, NTag, feedback } from '@admin/ui'
import { changeMyPassword, listMySessions, revokeMySession } from '@admin/api'
import type { SessionViewResponse } from '@admin/api'
import { useAuthStore } from '@/stores/auth'
import { useCommands } from '@/composables/useCommands'

/**
 * 个人中心。
 *
 * <h3>为什么它是静态路由、不进业务菜单</h3>
 * 业务菜单由后端菜单树驱动（谁能看到什么由权限决定），而"我自己的账号信息"
 * 是<b>每个登录用户都必然拥有</b>的东西 —— 它不该依赖任何权限配置。
 * 因此它挂在 Root 布局的静态子路由上，入口在右上角头像下拉里。
 *
 * <h3>三块内容的顺序是有意的</h3>
 * 先"我是什么身份"（只读，认知）→ 再"改密码"（写操作，风险最高）→
 * 最后"在线会话"（治理，需要清点时才会用）。把风险最高的一块放在
 * 需要滚动才能看到的位置，而不是页面第一屏。
 */
const authStore = useAuthStore()
const router = useRouter()
const { logout } = useCommands()

const profile = computed(() => authStore.user)

// ---------------------------------------------------------------------
// 修改密码
// ---------------------------------------------------------------------
const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const changing = ref(false)

/** 与平台策略一致的最小长度（服务端另有强度校验，这里是即时反馈）。 */
const MIN_PASSWORD_LENGTH = 6

const mismatch = computed(
  () => confirmPassword.value.length > 0 && newPassword.value !== confirmPassword.value
)

const canChange = computed(
  () =>
    oldPassword.value.length > 0 &&
    newPassword.value.length >= MIN_PASSWORD_LENGTH &&
    newPassword.value === confirmPassword.value &&
    !changing.value
)

async function handleChangePassword(): Promise<void> {
  if (!canChange.value) {
    return
  }
  changing.value = true
  try {
    await changeMyPassword({
      oldPassword: oldPassword.value,
      newPassword: newPassword.value
    })
    feedback.success('密码已修改，请用新密码重新登录')
    // 服务端在改密时吊销了该用户的全部会话与令牌（含当前设备，这是刻意的）——
    // 因此这里必须清理本地状态并回登录页，否则用户会停留在一个已经失效的会话上，
    // 后续每个请求都 401，看起来像"系统坏了"
    await logout()
    await router.push('/login')
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '修改失败，请稍后重试')
  } finally {
    changing.value = false
  }
}

// ---------------------------------------------------------------------
// 在线会话
// ---------------------------------------------------------------------
const sessions = ref<SessionViewResponse[]>([])
const loadingSessions = ref(false)

/**
 * 列表只渲染最近 N 条。
 *
 * <p>会话是"每次登录产生一条、直到过期"的累积型数据 —— 长期运行的账号可能
 * 有几十上百条（实测见过 78 条），全量渲染会把页面变成几十屏的清单，
 * 而用户真正关心的只有"最近在哪几台设备登录过"。后端已按最后活跃倒序返回，
 * 因此这里直接取前 N 条；需要完整清单时用「刷新」+ 注销动作逐步清理。
 */
const MAX_VISIBLE_SESSIONS = 10
const visibleSessions = computed(() => sessions.value.slice(0, MAX_VISIBLE_SESSIONS))

async function loadSessions(): Promise<void> {
  loadingSessions.value = true
  try {
    sessions.value = await listMySessions()
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '会话列表加载失败')
  } finally {
    loadingSessions.value = false
  }
}

async function handleRevoke(sessionId: string | undefined): Promise<void> {
  if (!sessionId) {
    return
  }
  try {
    await revokeMySession(sessionId)
    feedback.success('该设备已下线')
    await loadSessions()
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '操作失败')
  }
}

/** epoch 毫秒或 ISO 字符串 → 本地可读时间（字段来源见 SessionViewResponse）。 */
function formatTime(value: unknown): string {
  if (value === null || value === undefined || value === '') {
    return '-'
  }
  const date = typeof value === 'number' ? new Date(value) : new Date(String(value))
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString()
}

onMounted(loadSessions)
</script>

<template>
  <div class="profile">
    <!-- ① 账号信息（只读） -->
    <NCard size="small" title="账号信息">
      <div class="profile__grid">
        <div class="profile__item">
          <span class="profile__label">账号</span>
          <span class="profile__value">{{ profile?.username ?? '-' }}</span>
        </div>
        <div class="profile__item">
          <span class="profile__label">昵称</span>
          <span class="profile__value">{{ profile?.nickname ?? '-' }}</span>
        </div>
        <div class="profile__item">
          <span class="profile__label">所属租户</span>
          <span class="profile__value">{{ profile?.tenantId ?? '-' }}</span>
        </div>
        <div class="profile__item">
          <span class="profile__label">用户 ID</span>
          <span class="profile__value">{{ profile?.userId ?? '-' }}</span>
        </div>
      </div>
    </NCard>

    <!-- ② 修改密码 -->
    <NCard size="small" title="修改密码">
      <div class="profile__form">
        <div class="profile__field">
          <label class="profile__label">原密码</label>
          <NInput
            v-model:value="oldPassword"
            type="password"
            show-password-on="click"
            placeholder="请输入当前密码"
          />
        </div>
        <div class="profile__field">
          <label class="profile__label">新密码</label>
          <NInput
            v-model:value="newPassword"
            type="password"
            show-password-on="click"
            :placeholder="`至少 ${MIN_PASSWORD_LENGTH} 位`"
          />
        </div>
        <div class="profile__field">
          <label class="profile__label">确认新密码</label>
          <NInput
            v-model:value="confirmPassword"
            type="password"
            show-password-on="click"
            placeholder="请再次输入新密码"
          />
          <p v-if="mismatch" class="profile__error">两次输入的密码不一致</p>
        </div>
        <p class="profile__hint">
          修改成功后，该账号在<b>所有设备</b>上的登录都会失效（包括本机），需要用新密码重新登录。
        </p>
        <NButton type="primary" :loading="changing" :disabled="!canChange" @click="handleChangePassword">
          确认修改
        </NButton>
      </div>
    </NCard>

    <!-- ③ 在线会话 -->
    <NCard size="small" title="在线会话">
      <template #header-extra>
        <NButton size="tiny" quaternary :loading="loadingSessions" @click="loadSessions">
          刷新
        </NButton>
      </template>
      <p v-if="sessions.length === 0" class="profile__empty">当前没有其它在线设备。</p>
      <p v-else-if="sessions.length > MAX_VISIBLE_SESSIONS" class="profile__empty">
        共 {{ sessions.length }} 条在线会话，仅显示最近 {{ MAX_VISIBLE_SESSIONS }} 条。
      </p>
      <div v-for="session in visibleSessions" :key="session.sessionId" class="profile__session">
        <div class="profile__session-main">
          <span class="profile__value">{{ session.ip || '未知 IP' }}</span>
          <NTag v-if="session.current" size="small" type="success">当前设备</NTag>
          <span class="profile__session-meta">{{ session.userAgent || '未知客户端' }}</span>
        </div>
        <div class="profile__session-side">
          <span class="profile__session-meta">登录 {{ formatTime(session.loginTime) }}</span>
          <span class="profile__session-meta">最后活跃 {{ formatTime(session.lastActive) }}</span>
          <NButton
            v-if="!session.current"
            size="tiny"
            quaternary
            type="error"
            @click="handleRevoke(session.sessionId)"
          >
            下线
          </NButton>
        </div>
      </div>
    </NCard>
  </div>
</template>

<style scoped>
.profile {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-lg, 16px);
  max-width: 760px;
}

.profile__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--wa-spacing-md, 12px);
}

.profile__item {
  display: flex;
  align-items: center;
  gap: var(--wa-spacing-sm, 8px);
}

.profile__label {
  flex: none;
  font-size: var(--wa-font-size-sm, 13px);
  color: var(--wa-text-secondary, #5c6570);
}

.profile__value {
  font-size: var(--wa-font-size-md, 14px);
  color: var(--wa-text-primary, #1f2329);
  word-break: break-all;
}

.profile__tag {
  margin-right: 6px;
}

.profile__form {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-md, 12px);
  max-width: 360px;
}

.profile__field {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-xs, 4px);
}

.profile__error {
  margin: 0;
  font-size: var(--wa-font-size-xs, 12px);
  color: var(--wa-color-error, #d03050);
}

.profile__hint {
  margin: 0;
  font-size: var(--wa-font-size-xs, 12px);
  line-height: 1.6;
  color: var(--wa-text-secondary, #5c6570);
}

.profile__empty {
  margin: 0;
  font-size: var(--wa-font-size-sm, 13px);
  color: var(--wa-text-secondary, #5c6570);
}

.profile__session {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--wa-spacing-md, 12px);
  padding: var(--wa-spacing-sm, 8px) 0;
  border-bottom: 1px solid var(--wa-border, #e5e6eb);
}

.profile__session:last-child {
  border-bottom: none;
}

.profile__session-main {
  display: flex;
  align-items: center;
  gap: var(--wa-spacing-sm, 8px);
  min-width: 0;
}

.profile__session-side {
  display: flex;
  align-items: center;
  gap: var(--wa-spacing-md, 12px);
  flex: none;
}

.profile__session-meta {
  font-size: var(--wa-font-size-xs, 12px);
  color: var(--wa-text-secondary, #5c6570);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 220px;
}
</style>
