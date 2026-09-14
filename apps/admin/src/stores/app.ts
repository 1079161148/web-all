import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { applyTokensToDom, type ThemeMode } from '@admin/theme'

/**
 * 应用级**客户端状态**。
 *
 * <h3>边界（设计文档 §11.5）</h3>
 * 这里只放「客户端状态」：主题、布局偏好、侧边栏折叠。
 * <b>服务端数据（列表、详情、字典、菜单）一律不得放进 Pinia</b> ——
 * 那会导致每个页面手写缓存失效、并发去重、loading/error、重试逻辑。
 * 服务端数据统一由 TanStack Query 管理。
 */
export const useAppStore = defineStore('app', () => {
  /** 主题模式。 */
  const themeMode = ref<ThemeMode>('light')

  /** 侧边栏是否折叠。 */
  const sidebarCollapsed = ref(false)

  /** 是否显示标签栏。 */
  const showTagsView = ref(true)

  /** 切换主题并把 Token 写入 DOM（UnoCSS / 自研组件 / vxe 桥接共用同一套变量）。 */
  function toggleTheme(): void {
    themeMode.value = themeMode.value === 'light' ? 'dark' : 'light'
  }

  function setTheme(mode: ThemeMode): void {
    themeMode.value = mode
  }

  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  // 主题变化 → 同步 CSS 变量与 document 标记，供样式层与第三方库消费
  watch(
    themeMode,
    (mode) => {
      applyTokensToDom(mode)
      document.documentElement.dataset.theme = mode
    },
    { immediate: true }
  )

  return {
    themeMode,
    sidebarCollapsed,
    showTagsView,
    toggleTheme,
    setTheme,
    toggleSidebar
  }
})
