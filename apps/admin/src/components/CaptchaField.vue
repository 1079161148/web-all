<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { NInput } from '@admin/ui'
import { getLoginCaptcha } from '@admin/api'

/**
 * 图形验证码输入组件（登录页与注册页共用）。
 *
 * <h3>为什么抽成组件</h3>
 * 验证码的"取图 → 提交 → 失败后重新取图"是一条<b>有状态</b>的小流程，
 * 而它有两个使用方。复制一份的代价不是 30 行代码，而是<b>两处会漂移</b>：
 * 登录页改了刷新时机、注册页没改，于是两个入口的验证码行为不一致 ——
 * 这类"同一功能两种行为"的 bug 极难被发现（因为两边单独看都正常）。
 *
 * <h3>一次性语义对调用方的要求</h3>
 * 验证码提交即作废（无论校验对错），因此<b>任何登录/注册失败的 catch 分支
 * 都必须调用 {@link refresh}</b>。组件把它暴露出来而不是自己监听失败信号，
 * 是因为"什么叫失败"只有调用方知道（业务错误码、网络异常、用户取消……）。
 *
 * <h3>可用性降级</h3>
 * 若服务端关闭了验证码（或接口不可用），组件不渲染任何内容并把
 * {@code available} 置为 false —— 调用方据此决定"是否要求验证码必填"。
 * 这样"后端关了验证码"不会把前端登录整体卡死。
 */
const props = withDefaults(
  defineProps<{
    /** 输入框标签文案（登录/注册语境不同）。 */
    label?: string
  }>(),
  { label: '验证码' }
)

/** 用户输入（v-model:code）。 */
const code = defineModel<string>('code', { default: '' })

const emit = defineEmits<{
  /** 服务端是否提供验证码（false 时调用方不应要求必填）。 */
  'update:available': [value: boolean]
  /** 输入框回车：由调用方决定是登录还是注册。 */
  submit: []
}>()

const CAPTCHA_IMAGE_PREFIX = 'data:image/png;base64,'

const captchaId = ref('')
const image = ref('')
const loading = ref(false)
const available = ref(false)

function setAvailable(value: boolean): void {
  available.value = value
  emit('update:available', value)
}

/** 取一张新验证码（失败后必须调用；见组件注释）。 */
async function refresh(): Promise<void> {
  loading.value = true
  try {
    const issued = await getLoginCaptcha()
    if (!issued.captchaId || !issued.imageBase64) {
      setAvailable(false)
      return
    }
    captchaId.value = issued.captchaId
    image.value = `${CAPTCHA_IMAGE_PREFIX}${issued.imageBase64}`
    code.value = ''
    setAvailable(true)
  } catch {
    setAvailable(false)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void refresh()
})

defineExpose({ refresh, captchaId, available, loading })
</script>

<template>
  <div v-if="available" class="captcha-field">
    <label class="captcha-field__label">{{ props.label }}</label>
    <div class="captcha-field__row">
      <n-input
        v-model:value="code"
        placeholder="请输入验证码"
        :maxlength="8"
        @keyup.enter="emit('submit')"
      />
      <img
        class="captcha-field__img"
        :src="image"
        alt="点击刷新验证码"
        title="看不清？点击刷新"
        @click="refresh"
      >
    </div>
  </div>
</template>

<style scoped>
.captcha-field {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-xs, 4px);
  margin-bottom: var(--wa-spacing-lg, 16px);
}

.captcha-field__label {
  font-size: var(--wa-font-size-md, 14px);
  color: var(--wa-text-primary, #1f2329);
}

.captcha-field__row {
  display: flex;
  align-items: center;
  gap: var(--wa-spacing-sm, 8px);
}

.captcha-field__img {
  flex: none;
  width: 120px;
  height: 40px;
  border-radius: var(--wa-radius-sm, 4px);
  border: 1px solid var(--wa-border, #e5e6eb);
  cursor: pointer;
  user-select: none;
}

.captcha-field__img:hover {
  border-color: var(--wa-color-primary, #2563eb);
}
</style>
