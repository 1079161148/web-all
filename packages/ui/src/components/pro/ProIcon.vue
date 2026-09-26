<script setup lang="ts">
import { computed, type Component } from 'vue'
import { NIcon } from 'naive-ui'
import { resolveIcon } from '../../registry/icon'

/**
 * 图标展示（名字或组件 → 图标）。
 *
 * <pre>{@code
 * <ProIcon name="HomeOutlined" :size="18" />   <!-- 名字：经注册表解析 -->
 * <ProIcon :name="SomeComponent" :size="18" /> <!-- 组件：直接渲染 -->
 * }</pre>
 *
 * <h3>它补的是「存储值 → 图标」这一段</h3>
 * {@code IconPicker} 负责"选图标、把名字提交给后端"；后端菜单表存的是
 * <b>字符串</b>。之前从头到尾没有任何组件能把这个名字解析回图标
 * （布局里曾用 {@code h(node.icon)} 直接渲染字符串 —— 那会渲染出一个
 * 不存在的 HTML 标签，静默失败）。本组件与 {@code registry/icon.ts} 一起
 * 补上这一段：名字 → 注册表 → 组件。
 *
 * <h3>降级形态：首字形，而不是空白</h3>
 * 名字查不到组件时渲染<b>名字首字母</b>。理由与 DictTag 显示原始码值相同：
 * 静默空白会让"图标名拼错了 / 应用忘了注册图标集"变成"菜单没有图标"，
 * 排查成本极高；一个可见的首字形既不崩溃，又明确暴露了"这里本该有图标"。
 * 纯前端直接传组件时不存在降级问题。
 *
 * <h3>为什么不透传颜色</h3>
 * 颜色跟随文字色（{@code currentColor}），由使用处的 CSS 决定 ——
 * 菜单激活态、按钮态各有自己的颜色规则，图标组件管颜色只会制造第二套来源。
 */
const props = withDefaults(
  defineProps<{
    /** 图标名（经注册表解析）或图标组件（直接渲染）。空值不渲染任何东西。 */
    name?: string | Component | null
    /** 尺寸（px 数值或任意 CSS 长度）。 */
    size?: number | string
  }>(),
  {
    name: undefined,
    size: 16
  }
)

const resolved = computed(() => resolveIcon(props.name))

/**
 * 降级字形：取名字首字符（大写）。
 * 只对"字符串名字且未注册到组件"的情况生效；组件入参永远能渲染，无需降级。
 */
const glyph = computed(() =>
  typeof props.name === 'string' && props.name ? props.name.charAt(0).toUpperCase() : ''
)

const resolvedSize = computed(() => (typeof props.size === 'number' ? `${props.size}px` : props.size))
</script>

<template>
  <n-icon v-if="resolved" :size="size">
    <component :is="resolved" />
  </n-icon>
  <span v-else-if="glyph" class="pro-icon__glyph" :style="{ fontSize: resolvedSize }">
    {{ glyph }}
  </span>
  <!-- name 为空：不渲染。空位留给布局的 gap，不渲染占位元素 -->
</template>

<style scoped>
/* 与 n-icon 的行内对齐行为保持一致；颜色继承 currentColor（见类注释） */
.pro-icon__glyph {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1em;
  height: 1em;
  font-weight: 600;
  line-height: 1;
  user-select: none;
}
</style>
