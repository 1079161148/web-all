<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NButton,
  NCheckbox,
  NInput,
  NRadioButton,
  NRadioGroup,
  NTabs,
  feedback
} from '@admin/ui'
import CaptchaField from '@/components/CaptchaField.vue'
import { useAuthStore } from '@/stores/auth'
import { usePermissionStore } from '@/stores/permission'

/**
 * 登录页（平台运营 / 企业租户 双入口）。
 *
 * <h3>为什么分成两个页签而不是一个表单</h3>
 * 两类登录者的心智模型完全不同：
 * <ul>
 *   <li><b>平台运营</b>：平台方自己的运维/管理账号，只存在于平台默认租户 ——
 *       让他们见到"租户 ID"输入框只会困惑（"我是平台，哪来的租户？"）</li>
 *   <li><b>企业租户</b>：租户用户，登录必须先确定"在哪个租户里校验这个账号"，
 *       租户 ID 是必填凭证的一部分</li>
 * </ul>
 * 混在一个表单里，平台用户要"无视"一个看不懂的字段，
 * 租户用户则容易漏填 —— 分开后各自的表单都只剩必要的输入。
 *
 * <h3>平台运营的技术形态</h3>
 * 与租户登录走<b>同一个</b>登录接口，只是租户固定为平台默认租户（ID=1，
 * 见迁移脚本 V1.0.0 的种子租户）。后端不感知"入口类型"——
 * 账号在哪个租户由数据决定，前端只是替用户省掉了一次输入。
 */
const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const permissionStore = usePermissionStore()

/**
 * 登录预填：开发态与公开演示站共用同一机制。
 *
 * <h3>两个触发条件</h3>
 * <ul>
 *   <li>{@code import.meta.env.DEV} —— 本地开发（`pnpm dev`）；</li>
 *   <li>{@code VITE_DEMO_MODE=true} —— <b>公开演示站</b>：部署到免费托管
 *       （Render 等）供任何人体验时，构建时注入该变量，登录页预填
 *       管理员账号。演示凭据本就公开（取后端种子账号默认值），预填
 *       只是省掉访客"猜账号"这一步；验证码保留，防脚本刷库。</li>
 * </ul>
 *
 * <h3>⚠️ 正式生产环境不要设 VITE_DEMO_MODE</h3>
 * 不设时生产包里没有任何凭据 —— 由构建机制保证，而不是靠纪律。
 *
 * <h3>想换成别的账号</h3>
 * 在 {@code apps/admin/.env.local} 里覆盖
 * {@code VITE_DEV_LOGIN_USERNAME} / {@code VITE_DEV_LOGIN_PASSWORD} 即可
 * （该文件已在 .gitignore 中，不会入库）。
 */
const PREFILL_CREDENTIALS = {
  username: import.meta.env.VITE_DEV_LOGIN_USERNAME ?? 'admin',
  password: import.meta.env.VITE_DEV_LOGIN_PASSWORD ?? 'Admin@123456'
}
const isPrefilled =
  import.meta.env.DEV || import.meta.env.VITE_DEMO_MODE === 'true'
const DEV_PREFILL = isPrefilled
  ? PREFILL_CREDENTIALS
  : { username: '', password: '' }

/** 是否处于"已预填"态。仅用于显示一行提示，避免有人以为密码框有残留。 */
const isDevPrefilled = isPrefilled

/** 预填提示文案：演示站与本地开发措辞不同（受众一个是访客，一个是开发者）。 */
const prefillHint = import.meta.env.VITE_DEMO_MODE === 'true'
  ? '演示站点：已预填管理员账号，输入验证码后点登录'
  : '本地开发：已预填种子账号，可直接点登录'

/** 平台默认租户（种子租户，平台运营账号所在处）。 */
const PLATFORM_TENANT_ID = '1'

const REMEMBER_KEY = 'login:rememberedUsername'

type LoginMode = 'platform' | 'tenant'
const mode = ref<LoginMode>('platform')
const tenantId = ref('1')
const username = ref(DEV_PREFILL.username)
const password = ref(DEV_PREFILL.password)
const rememberUsername = ref(false)
const submitting = ref(false)

// ---------------------------------------------------------------------
// 免登录天数
// ---------------------------------------------------------------------
/** 是否免登录（勾选后才提交 rememberDays）。 */
const autoLogin = ref(true)
/** 免登录天数：1 / 7 / 30。默认 7 天与参考交互一致。 */
const rememberDays = ref(7)

/**
 * 可选的免登录天数。
 *
 * <p><b>这里只是"给用户看"的选项，不是安全边界</b>：服务端对 rememberDays
 * 做白名单校验（非 1/7/30 直接拒绝）。前端与服务端各存一份是刻意的 ——
 * 前端这份是为了渲染，服务端那份是为了防篡改；只留前端那份等于没有校验。
 */
const REMEMBER_DAY_OPTIONS = [1, 7, 30] as const

// ---------------------------------------------------------------------
// 图形验证码
// ---------------------------------------------------------------------
// 取图 / 一次性刷新 / 可用性降级都封装在 CaptchaField 里（登录与注册共用，
// 避免两处实现漂移）。本页只持有"用户输入"与"是否可用"两个状态。
const captchaCode = ref('')
const captchaAvailable = ref(false)
const captchaRef = ref<InstanceType<typeof CaptchaField> | null>(null)

/** "记住账号"的回填：上次勾选过就读回来（localStorage 持久，跨会话有效）。 */
onMounted(() => {
  try {
    const saved = localStorage.getItem(REMEMBER_KEY)
    if (saved) {
      username.value = saved
      rememberUsername.value = true
    }
  } catch {
    // localStorage 不可用（隐私模式等）：放弃记忆即可，不影响登录
  }
})

const canSubmit = computed(
  () => username.value.trim().length > 0 && password.value.length > 0 && !submitting.value
)

/** 提交时的租户：平台运营固定平台租户；企业租户取用户输入。 */
const resolvedTenantId = computed(() =>
  mode.value === 'platform' ? PLATFORM_TENANT_ID : tenantId.value.trim()
)

const canSubmitWithTenant = computed(() => {
  const base =
    mode.value === 'tenant' ? resolvedTenantId.value.length > 0 && canSubmit.value : canSubmit.value
  // 启用验证码时，验证码为空不允许提交（省掉一次注定失败的往返）
  return base && (!captchaAvailable.value || captchaCode.value.trim().length > 0)
})

async function handleSubmit(): Promise<void> {
  if (!canSubmitWithTenant.value) {
    return
  }
  submitting.value = true
  try {
    // "记住账号"只记住用户名 —— 密码永远不落本地存储
    try {
      if (rememberUsername.value) {
        localStorage.setItem(REMEMBER_KEY, username.value.trim())
      } else {
        localStorage.removeItem(REMEMBER_KEY)
      }
    } catch {
      // 存储不可用时静默放弃
    }

    await authStore.login(username.value.trim(), password.value, resolvedTenantId.value, {
      captchaId: captchaAvailable.value ? (captchaRef.value?.captchaId ?? undefined) : undefined,
      captchaCode: captchaAvailable.value ? captchaCode.value.trim() : undefined,
      // 只有明确勾选"免登录"才传天数；否则不记住（服务端按会话级 Cookie 处理）
      rememberDays: autoLogin.value ? rememberDays.value : undefined
    })

    // ⚠️ 必须重置权限 store：上一个用户（或上一次会话）可能残留菜单与权限，
    //    而路由守卫只在 `loaded === false` 时才重新拉取。
    //    不重置会出现"新用户看到旧用户的菜单"，刷新后才恢复正常。
    permissionStore.reset()

    feedback.success('登录成功')

    const redirect = (route.query.redirect as string | undefined) ?? '/'
    await router.push(redirect)
  } catch (error) {
    // 后端对"用户不存在"与"密码错误"返回<b>同一个错误码</b>（防用户名枚举），
    // 因此这里直接展示服务端消息即可，不需要也不应该在前端做区分。
    feedback.error(error instanceof Error ? error.message : '登录失败，请稍后重试')
    // 验证码一次性：无论上一步是验证码错还是密码错，它都已经作废，必须换新
    void captchaRef.value?.refresh()
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

      <!--
        开发态提示。它同时回答一个会被反复问到的问题：
        "为什么密码框里已经有东西了？" —— 没有这行提示，
        预填会被当成浏览器自动填充或"上一个人的残留"。
      -->
      <p v-if="isDevPrefilled" class="login__dev-hint">
        {{ prefillHint }}
      </p>

      <!--
        登录入口切换：segmented 形态让"这是两个互斥的入口"一眼可见，
        而不是靠一个可有可无的输入框暗示。
      -->
      <n-tabs v-model:value="mode" type="segment" class="login__tabs" :animated="false">
        <n-tab name="platform" tab="平台运营" />
        <n-tab name="tenant" tab="企业租户" />
      </n-tabs>

      <div v-if="mode === 'tenant'" class="login__field">
        <label class="login__label">租户 ID</label>
        <n-input v-model:value="tenantId" placeholder="如 1" />
      </div>

      <div class="login__field">
        <label class="login__label">用户名</label>
        <n-input v-model:value="username" placeholder="请输入用户名" @keyup.enter="handleSubmit" />
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

      <!-- 图形验证码：与注册页共用组件（取图 / 一次性刷新 / 降级都在组件内） -->
      <CaptchaField
        ref="captchaRef"
        v-model:code="captchaCode"
        @update:available="captchaAvailable = $event"
        @submit="handleSubmit"
      />

      <div class="login__options">
        <n-checkbox v-model:checked="rememberUsername" class="login__remember">
          记住账号
        </n-checkbox>

        <!--
          免登录天数：勾选后才提交 rememberDays。
          文案明确写出"请勿在公用电脑勾选"—— 免登录是便利与风险的取舍，
          把取舍说清楚比默认替用户决定更负责任。
        -->
        <div class="login__auto-login">
          <n-checkbox v-model:checked="autoLogin">免登录</n-checkbox>
          <n-radio-group v-model:value="rememberDays" size="small" :disabled="!autoLogin">
            <n-radio-button v-for="day in REMEMBER_DAY_OPTIONS" :key="day" :value="day">
              {{ day }}天
            </n-radio-button>
          </n-radio-group>
        </div>
      </div>
      <p v-if="autoLogin" class="login__auto-login-hint">
        在 {{ rememberDays }} 天内，本浏览器无需重新登录。请勿在公用电脑勾选。
      </p>

      <n-button
        type="primary"
        block
        :loading="submitting"
        :disabled="!canSubmitWithTenant"
        @click="handleSubmit"
      >
        登录
      </n-button>

      <!-- 自助注册入口：注册后需管理员授权（见 RegisterView 的说明） -->
      <p class="login__register">
        还没有账号？
        <a class="login__link" @click="router.push('/register')">注册账号</a>
      </p>
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

/* 入口切换：与下方表单留出呼吸空间 */
.login__tabs {
  margin-bottom: var(--wa-spacing-xl, 24px);
}

/* 开发态提示：弱化但可读。用 primary 的浅底而不是灰色 ——
   灰色容易被当成"错误信息"或"禁用说明" */
.login__dev-hint {
  margin: 0 0 var(--wa-spacing-lg, 16px);
  padding: var(--wa-spacing-sm, 8px) var(--wa-spacing-md, 12px);
  border-radius: var(--wa-radius-sm, 4px);
  background: color-mix(in srgb, var(--wa-color-primary, #2563eb) 10%, transparent);
  color: var(--wa-color-primary, #2563eb);
  font-size: var(--wa-font-size-sm, 12px);
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

/* 记住账号：与登录按钮之间留出间距，独占一行更接近常见登录页的节奏 */
.login__remember {
  display: flex;
  font-size: var(--wa-font-size-sm, 13px);
}

/* 注册入口：弱化为辅助文字，不与主按钮抢焦点 */
.login__register {
  margin: var(--wa-spacing-lg, 16px) 0 0;
  text-align: center;
  font-size: var(--wa-font-size-sm, 13px);
  color: var(--wa-text-secondary, #5c6570);
}

.login__link {
  color: var(--wa-color-primary, #2563eb);
  cursor: pointer;
}

/* 选项行：记住账号与免登录分居两侧，避免两行复选框的松散感 */
.login__options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--wa-spacing-md, 12px);
  margin-bottom: var(--wa-spacing-md, 12px);
}

.login__auto-login {
  display: flex;
  align-items: center;
  gap: var(--wa-spacing-sm, 8px);
  font-size: var(--wa-font-size-sm, 13px);
}

/* 免登录说明：弱化为辅助文字，但必须在勾选时可见（用户要知道自己选了什么） */
.login__auto-login-hint {
  margin: 0 0 var(--wa-spacing-lg, 16px);
  font-size: var(--wa-font-size-xs, 12px);
  line-height: 1.6;
  color: var(--wa-text-secondary, #5c6570);
}
</style>
