<script setup lang="ts">
import { AdminConfigProvider } from '@admin/ui'
import { useAppStore } from '@/stores/app'

/**
 * 根组件。
 *
 * <p>只做一件事：把当前主题模式交给 `AdminConfigProvider`。
 * 主题到 Naive `themeOverrides` 的映射逻辑封装在 `@admin/ui` 内 ——
 * 应用层不需要、也不允许接触第三方 UI 库的主题类型。
 */
const appStore = useAppStore()
</script>

<template>
  <AdminConfigProvider :mode="appStore.themeMode">
    <router-view />
  </AdminConfigProvider>
</template>
