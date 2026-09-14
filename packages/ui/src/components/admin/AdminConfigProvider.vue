<script setup lang="ts">
import { computed } from 'vue'
import { NConfigProvider, NGlobalStyle, darkTheme, dateZhCN, zhCN } from 'naive-ui'
import type { GlobalThemeOverrides } from 'naive-ui'
import { getTokens, type ThemeMode } from '@admin/theme'

/**
 * 全局配置容器：把 Design Token 映射为 Naive UI 主题。
 *
 * <h3>为什么放在 @admin/ui 而不是应用里</h3>
 * 主题映射需要接触 `naive-ui` 的 `themeOverrides` 类型，属于 UI 库实现细节。
 * 放在应用里会让 `apps/**` 依赖 naive-ui（破坏隔离）；放在这里则应用只需
 * 传递一个 `mode` 字符串。
 *
 * <p>映射所用的值<b>全部来自 `@admin/theme` 的 Token</b>，不允许出现颜色字面量 ——
 * 这样改品牌色只需改 `tokens.ts` 一处，亮/暗两套主题自动跟随（设计文档 §11.8）。
 *
 * <p>这套映射同时是未来「vxe-table 主题桥接」的参照物：同一组 Token 值
 * 需要再映射一遍到 vxe 的 CSS 变量（设计文档 §12.3 P0 验证项 2）。
 */
const props = withDefaults(
  defineProps<{
    /** 主题模式。 */
    mode?: ThemeMode
  }>(),
  {
    mode: 'light'
  }
)

const theme = computed(() => (props.mode === 'dark' ? darkTheme : null))

const themeOverrides = computed<GlobalThemeOverrides>(() => {
  const { colors, neutrals, radius, fontSize } = getTokens(props.mode)

  return {
    common: {
      primaryColor: colors.primary,
      primaryColorHover: colors.primaryHover,
      primaryColorPressed: colors.primaryPressed,
      primaryColorSuppl: colors.primary,

      successColor: colors.success,
      warningColor: colors.warning,
      errorColor: colors.error,
      infoColor: colors.info,

      textColorBase: neutrals.textPrimary,
      textColor1: neutrals.textPrimary,
      textColor2: neutrals.textSecondary,
      textColor3: neutrals.textDisabled,

      bodyColor: neutrals.bg,
      cardColor: neutrals.bgElevated,
      modalColor: neutrals.bgElevated,
      popoverColor: neutrals.bgElevated,
      tableColor: neutrals.bgElevated,
      inputColor: neutrals.bgElevated,
      hoverColor: neutrals.bgHover,

      borderColor: neutrals.border,
      dividerColor: neutrals.divider,

      borderRadius: radius.md,
      borderRadiusSmall: radius.sm,

      fontSize: fontSize.md,
      fontSizeSmall: fontSize.sm,
      fontSizeMedium: fontSize.md,
      fontSizeLarge: fontSize.lg,
      fontSizeHuge: fontSize.xl
    }
  }
})
</script>

<template>
  <n-config-provider
    :theme="theme"
    :theme-overrides="themeOverrides"
    :locale="zhCN"
    :date-locale="dateZhCN"
  >
    <!-- 注入 Naive 的全局样式（body 背景、滚动条等），随主题变量联动 -->
    <n-global-style />
    <slot />
  </n-config-provider>
</template>
