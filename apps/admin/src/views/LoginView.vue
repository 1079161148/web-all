<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NInput, feedback } from '@admin/ui'
import { useAuthStore } from '@/stores/auth'
import { usePermissionStore } from '@/stores/permission'

/**
 * 登录页（已接入真实认证）。
 *
 * <h3>相对上一版的区别</h3>
 * 之前是"跳过认证的占位实现"：直接往 sessionStorage 写一个假令牌。
 * 现在真正调用 `/api/v1/auth/login`，由后端校验密码并签发 JWT。
 *
 * <h3>租户输入框为什么还在</h3>
 * 多租户环境下，登录必须先确定"在哪个租户里校验这个账号"。
 * 生产形态通常是"租户编码 + 账号 + 密码"三个输入框
 * （或先访问 `<tenant>.example.com` 由域名决定租户）。
 * 这里保留手填是因为后端目前只支持请求头传递租户
 * （见 `TenantContextFilter` 与 `AuthAppService.tenantRepositoryLookup` 的 TODO）——
 * <b>等按 tenantCode 解析租户实现后，这个输入框应改为正式字段并做校验。</b>
 */
const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const permissionStore = usePermissionStore()

const tenantId = ref('1')
const username = ref('')
const password = ref('')
const submitting = ref(false)

const canSubmit = computed(
  () => username.value.trim().length > 0 && password.value.length > 0 && !submitting.value
)

async function handleSubmit(): Promise<void> {
  if (!canSubmit.value) {
    return
  }
  submitting.value = true
  try {
    await authStore.login(username.value.trim(), password.value, tenantId.value)

    // ⚠️ 必须重置权限 store：上一个用户（或上一次会话）可能残留菜单与权限，
    //    而路由守卫只在 `loaded === false` 时才重新拉取。
    //    不重置会出现"新用户看到旧用户的菜单"，刷新后才恢复正常。
    permissionStore.reset()

    const redirect = (route.query.redirect as string | undefined) ?? '/'
    await router.push(redirect)
  } catch (error) {
    // 后端对"用户不存在"与"密码错误"返回<b>同一个错误码</b>（防用户名枚举），
    // 因此这里直接展示服务端消息即可，不需要也不应该在前端做区分。
    feedback.error(error instanceof Error ? error.message : '登录失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login">
    <div class="login__card">
      <h1 class="login__title">中台管理系统</h1>
      <p class="login__subtitle">企业级多租户 SaaS 中台</p>

      <div class="login__field">
        <label class="login__label">租户 ID</label>
        <n-input v-model:value="tenantId" placeholder="如 1" />
      </div>

      <div class="login__field">
        <label class="login__label">账号</label>
        <n-input v-model:value="username" placeholder="请输入账号" @keyup.enter="handleSubmit" />
      </div>

      <div class="login__field">
        <label class="login__label">密码</label>
        <n-input
          v-model:value="password"
          type="password"
          show-password-on="click"
          placeholder="请输入密码"
          @keyup.enter="handleSubmit"
        />
      </div>

      <n-button type="primary" block :loading="submitting" :disabled="!canSubmit" @click="handleSubmit">
        登录
      </n-button>
    </div>
  </div>
</template>

<style scoped>
.login {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: var(--wa-bg, #f5f7fa);
}

.login__card {
  width: 380px;
  padding: var(--wa-spacing-xxl, 32px);
  border-radius: var(--wa-radius-lg, 8px);
  background: var(--wa-bg-elevated, #fff);
  box-shadow: var(--wa-shadow-lg, 0 6px 20px rgba(0, 0, 0, 0.12));
}

.login__title {
  margin: 0 0 var(--wa-spacing-xs, 4px);
  font-size: var(--wa-font-size-display, 28px);
  color: var(--wa-text-primary, #1f2329);
}

.login__subtitle {
  margin: 0 0 var(--wa-spacing-xl, 24px);
  font-size: var(--wa-font-size-md, 14px);
  color: var(--wa-text-secondary, #5c6570);
}

.login__field {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-xs, 4px);
  margin-bottom: var(--wa-spacing-lg, 16px);
}

.login__label {
  font-size: var(--wa-font-size-md, 14px);
  color: var(--wa-text-primary, #1f2329);
}
</style>
