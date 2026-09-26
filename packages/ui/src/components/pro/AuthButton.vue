<script setup lang="ts">
import { computed } from 'vue'
import { NButton, NTooltip } from 'naive-ui'
import { hasAnyPermission } from '../../registry/permission'

/**
 * 权限按钮。
 *
 * <h3>与 `v-permission` 指令的分工</h3>
 * <table>
 *   <tr><th></th><th>适用</th></tr>
 *   <tr><td>{@code v-permission}</td><td>已有元素上的"顺手加个权限"，
 *       如 {@code <n-button v-permission="'x:y:add'">}</td></tr>
 *   <tr><td>{@code <AuthButton>}</td><td><b>需要区分"隐藏"与"禁用"</b>的场景。
 *       指令只能隐藏，无法表达"看得见但点不动 + 鼠标悬停告诉你为什么"</td></tr>
 * </table>
 *
 * <h3>为什么需要"禁用"这种形态</h3>
 * 直接隐藏按钮，用户看到的是"这个功能不存在"；而禁用 + 提示看到的是
 * "这个功能存在，但我没有权限"。后者在多数后台里是更好的体验 ——
 * 使用者会去申请权限，而不是以为系统没有这个功能，或者反复刷新页面。
 *
 * <p>因此本组件把 {@code mode} 做成必选项而不是默认隐藏：
 * <b>这是一个需要调用方显式决定的取舍</b>（隐藏=干净，禁用=可发现），
 * 没有普适的默认值。
 *
 * <h3>⚠️ 再强调：这不是安全机制</h3>
 * 隐藏或禁用只影响体验。真正的边界在后端 {@code @PreAuthorize} ——
 * 任何人都可以直接调接口。
 */
const props = withDefaults(
  defineProps<{
    /** 权限码。数组表示"任一命中即可"。 */
    permission?: string | string[]
    /**
     * 无权限时的处理方式。
     *
     * <ul>
     *   <li>{@code hide} —— 不渲染（默认）</li>
     *   <li>{@code disable} —— 渲染为禁用态，并用 tooltip 说明原因</li>
     * </ul>
     */
    mode?: 'hide' | 'disable'
    /** 无权限时的提示文案。 */
    deniedText?: string
    /** 按钮类型，透传给 NButton。 */
    type?: 'default' | 'primary' | 'info' | 'success' | 'warning' | 'error'
    size?: 'tiny' | 'small' | 'medium' | 'large'
    quaternary?: boolean
    secondary?: boolean
    tertiary?: boolean
    text?: boolean
    ghost?: boolean
    round?: boolean
    circle?: boolean
    loading?: boolean
    /** 业务自身原因的禁用（与权限无关）。 */
    disabled?: boolean
  }>(),
  {
    mode: 'hide',
    deniedText: '没有权限执行该操作',
    type: 'default'
  }
)

/** 权限码集合。单个码也走 hasAnyPermission，统一一条路径。 */
const codes = computed(() => {
  if (!props.permission) {
    return []
  }
  return Array.isArray(props.permission) ? props.permission : [props.permission]
})

const allowed = computed(() =>
  codes.value.length === 0 ? true : hasAnyPermission(codes.value)
)

const denied = computed(() => !allowed.value)
</script>

<template>
  <span v-if="denied && mode === 'hide'" class="auth-button__hidden" />

  <n-tooltip v-else-if="denied && mode === 'disable'" trigger="hover">
    <template #trigger>
      <!--
        用一个 span 包住：禁用状态的按钮不触发鼠标事件，
        直接放在 tooltip 的 trigger 上会导致提示出不来
      -->
      <span class="auth-button__wrapper">
        <n-button
          :type="type"
          :size="size"
          :quaternary="quaternary"
          :secondary="secondary"
          :tertiary="tertiary"
          :text="text"
          :ghost="ghost"
          :round="round"
          :circle="circle"
          :loading="loading"
          disabled
        >
          <slot />
        </n-button>
      </span>
    </template>
    {{ deniedText }}
  </n-tooltip>

  <n-button
    v-else
    :type="type"
    :size="size"
    :quaternary="quaternary"
    :secondary="secondary"
    :tertiary="tertiary"
    :text="text"
    :ghost="ghost"
    :round="round"
    :circle="circle"
    :loading="loading"
    :disabled="disabled"
  >
    <slot />
  </n-button>
</template>

<style scoped>
/* hide 模式下渲染空 span 而不是 null：
   保留一个挂载点，避免父元素的 v-if 链因为子节点消失而整体重排 */
.auth-button__hidden {
  display: none;
}

.auth-button__wrapper {
  display: inline-flex;
}
</style>
