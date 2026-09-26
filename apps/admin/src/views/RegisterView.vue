<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NInput, feedback } from '@admin/ui'
import { register as registerApi } from '@admin/api'
import { setTenantId } from '@/utils/session'
import CaptchaField from '@/components/CaptchaField.vue'

/**
 * 自助注册页。
 *
 * <h3>注册完为什么不能直接用</h3>
 * 新账号<b>不分配任何角色</b>（见后端 RegisterAppService 的安全模型）——
 * 这是刻意的：注册是对匿名用户开放的写入入口，如果它顺手给一个角色，
 * 就等于"任何能打开这个页面的人都能给自己发权限"。
 * 因此注册成功后的流程是"联系管理员授权"，页面上直接说清楚，
 * 而不是让用户登录后发现"什么都没有"再来问。
 *
 * <h3>租户字段为什么在这里</h3>
 * 账号是租户内的（{@code (tenant_id, username)} 唯一），注册必须先确定租户。
 * 前端把它写进请求头（{@link setTenantId}），与登录页的机制完全一致 ——
 * 后端的租户解析路径因此只有一条，不会出现"登录按头、注册按 body"的双标准。
 */
const router = useRouter()

const tenantId = ref('1')
const username = ref('')
const nickname = ref('')
const password = ref('')
const confirmPassword = ref('')
const email = ref('')
const captchaCode = ref('')
const captchaAvailable = ref(false)
const captchaRef = ref<InstanceType<typeof CaptchaField> | null>(null)
const submitting = ref(false)

/** 与平台策略一致的最小长度；服务端还会做强度校验（这里是即时反馈，不是防线）。 */
const MIN_PASSWORD_LENGTH = 6

const passwordMismatch = computed(
  () => confirmPassword.value.length > 0 && password.value !== confirmPassword.value
)

const canSubmit = computed(
  () =>
    tenantId.value.trim().length > 0 &&
    username.value.trim().length > 0 &&
    password.value.length >= MIN_PASSWORD_LENGTH &&
    password.value === confirmPassword.value &&
    // 验证码可用时才要求填写（服务端关闭验证码时不该卡住注册）
    (!captchaAvailable.value || captchaCode.value.trim().length > 0) &&
    !submitting.value
)

async function handleSubmit(): Promise<void> {
  if (!canSubmit.value) {
    return
  }
  submitting.value = true
  try {
    // 租户必须在请求之前写入：注册接口靠请求头确定在哪个租户下建号
    setTenantId(tenantId.value.trim())

    await registerApi({
      username: username.value.trim(),
      nickname: nickname.value.trim() || undefined,
      password: password.value,
      email: email.value.trim() || undefined,
      captchaId: captchaRef.value?.captchaId ?? '',
      captchaCode: captchaCode.value.trim()
    })

    feedback.success('注册成功，请联系管理员为你分配权限后再登录')
    await router.push('/login')
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '注册失败，请稍后重试')
    // 验证码一次性：失败后必须换新，否则用户重试时一直拿到"验证码错误"
    void captchaRef.value?.refresh()
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="register">
    <div class="register__card">
      <h1 class="register__title">注册账号</h1>
      <p class="register__subtitle">注册后需管理员分配权限才能使用系统</p>

      <div class="register__field">
        <label class="register__label">租户 ID</label>
        <n-input v-model:value="tenantId" placeholder="如 1" />
      </div>

      <div class="register__field">
        <label class="register__label">账号</label>
        <n-input
          v-model:value="username"
          placeholder="登录用的账号，租户内唯一"
          @keyup.enter="handleSubmit"
        />
      </div>

      <div class="register__field">
        <label class="register__label">昵称（可选）</label>
        <n-input v-model:value="nickname" placeholder="留空则与账号同名" />
      </div>

      <div class="register__field">
        <label class="register__label">密码</label>
        <n-input
          v-model:value="password"
          type="password"
          show-password-on="click"
          :placeholder="`至少 ${MIN_PASSWORD_LENGTH} 位`"
        />
      </div>

      <div class="register__field">
        <label class="register__label">确认密码</label>
        <n-input
          v-model:value="confirmPassword"
          type="password"
          show-password-on="click"
          placeholder="请再次输入密码"
        />
        <p v-if="passwordMismatch" class="register__error">两次输入的密码不一致</p>
      </div>

      <div class="register__field">
        <label class="register__label">邮箱（可选）</label>
        <n-input v-model:value="email" placeholder="用于后续联系" />
      </div>

      <!-- 验证码：与登录页共用组件，一次性语义与刷新时机完全一致 -->
      <CaptchaField
        ref="captchaRef"
        v-model:code="captchaCode"
        @update:available="captchaAvailable = $event"
        @submit="handleSubmit"
      />

      <n-button
        type="primary"
        block
        :loading="submitting"
        :disabled="!canSubmit"
        @click="handleSubmit"
      >
        注册
      </n-button>

      <p class="register__back">
        已有账号？
        <a class="register__link" @click="router.push('/login')">返回登录</a>
      </p>
    </div>
  </div>
</template>

<style scoped>
.register {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: var(--wa-bg, #f5f7fa);
}

.register__card {
  width: 420px;
  padding: var(--wa-spacing-xxl, 32px);
  border-radius: var(--wa-radius-lg, 8px);
  background: var(--wa-bg-elevated, #fff);
  box-shadow: var(--wa-shadow-lg, 0 6px 20px rgba(0, 0, 0, 0.12));
}

.register__title {
  margin: 0 0 var(--wa-spacing-xs, 4px);
  font-size: var(--wa-font-size-display, 28px);
  color: var(--wa-text-primary, #1f2329);
}

.register__subtitle {
  margin: 0 0 var(--wa-spacing-xl, 24px);
  font-size: var(--wa-font-size-md, 14px);
  color: var(--wa-text-secondary, #5c6570);
}

.register__field {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-xs, 4px);
  margin-bottom: var(--wa-spacing-lg, 16px);
}

.register__label {
  font-size: var(--wa-font-size-md, 14px);
  color: var(--wa-text-primary, #1f2329);
}

.register__error {
  margin: 0;
  font-size: var(--wa-font-size-xs, 12px);
  color: var(--wa-color-error, #d03050);
}

.register__back {
  margin: var(--wa-spacing-lg, 16px) 0 0;
  text-align: center;
  font-size: var(--wa-font-size-sm, 13px);
  color: var(--wa-text-secondary, #5c6570);
}

.register__link {
  color: var(--wa-color-primary, #2563eb);
  cursor: pointer;
}
</style>
