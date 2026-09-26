import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { applyTokensToDom } from '@admin/theme'
import type { ProLayoutMode, ProLayoutTabStyle } from '@admin/ui'
import { syncVxeTheme } from '@admin/ui/core'

/**
 * 应用级**客户端状态**。
 *
 * <h3>边界（设计文档 §11.5）</h3>
 * 这里只放「客户端状态」：主题、布局偏好、侧边栏折叠。
 * <b>服务端数据（列表、详情、字典、菜单）一律不得放进 Pinia</b> ——
 * 那会导致每个页面手写缓存失效、并发去重、loading/error、重试逻辑。
 * 服务端数据统一由 TanStack Query 管理。
 */

/** 用户的主题偏好。`auto` = 跟随系统（prefers-color-scheme）。 */
export type ThemePreference = 'light' | 'dark' | 'auto'

/**
 * 菜单布局模式。
 *
 * <ul>
 *   <li>{@code vertical} —— 菜单在左侧栏（中台默认，窄屏友好）</li>
 *   <li>{@code horizontal} —— 菜单在顶栏，内容区获得全部宽度（宽屏数据页友好）</li>
 *   <li>{@code mix} —— 顶栏放一级菜单、侧栏放当前一级下的子菜单（菜单层级深时最省空间）</li>
 * </ul>
 */
export type LayoutMode = ProLayoutMode

/**
 * 页签风格。
 *
 * <p>前三种对齐主流中台（灵动 / 卡片 / 谷歌），后两种是本项目的增强风格，
 * 用于对外演示与数据大屏等需要视觉冲击的场景。
 */
export type TabStyle = ProLayoutTabStyle

/** 页宽策略：`fixed` 用主题里的内容最大宽度，`custom` 用 {@link useAppStore} 的 pageWidth。 */
export type PageWidthMode = 'fixed' | 'custom'

export const useAppStore = defineStore('app', () => {
  /** 主题偏好（注意：`auto` 不是一种视觉模式，而是"跟随系统"的策略）。 */
  const themeMode = ref<ThemePreference>('light')

  /**
   * 实际生效的主题。
   *
   * <h3>为什么要把"偏好"与"生效值"分开</h3>
   * 消费方（AdminConfigProvider / applyTokensToDom / vxe 同步）只认
   * light/dark 两态；`auto` 在这里就被解析掉。若把 auto 直接往下传，
   * 每个消费方都得自己问一遍系统 —— 三处实现必然漂移。
   */
  const systemDark = ref(
    typeof window !== 'undefined' &&
      window.matchMedia('(prefers-color-scheme: dark)').matches
  )
  if (typeof window !== 'undefined') {
    window
      .matchMedia('(prefers-color-scheme: dark)')
      .addEventListener('change', (event) => {
        systemDark.value = event.matches
      })
  }

  const resolvedTheme = computed<'light' | 'dark'>(() => {
    if (themeMode.value === 'auto') {
      return systemDark.value ? 'dark' : 'light'
    }
    return themeMode.value
  })

  /** 自定义品牌主色（十六进制）。空字符串 = 使用 Token 默认。 */
  const primaryColor = ref('')

  /** 灰色模式（哀悼日等场景的全站置灰）。 */
  const grayMode = ref(false)

  /** 色弱模式（提高对比的辅助滤镜）。 */
  const weakenMode = ref(false)

  /** 是否显示标签栏。 */
  const showTagsView = ref(true)

  /** 全屏水印。 */
  const watermarkEnabled = ref(false)
  const watermarkText = ref('中台管理系统')

  /** 侧边栏是否折叠。 */
  const sidebarCollapsed = ref(false)

  /** 菜单布局：左侧 / 顶栏 / 混合。 */
  const layoutMode = ref<LayoutMode>('vertical')

  /** 页签风格。默认保持"卡片"（与升级前表现一致，避免用户被动改观感）。 */
  const tabStyle = ref<TabStyle>('card')

  /** 页宽策略与自定义宽度（px）。 */
  const pageWidthMode = ref<PageWidthMode>('fixed')
  const pageWidth = ref(1600)

  /** 是否隐藏页脚。 */
  const hideFooter = ref(false)

  /**
   * 页签是否持久化（刷新/重新登录后恢复上次打开的一组标签）。
   *
   * <p>默认开启：刷新后标签全丢是用户最容易抱怨的体验损失之一。
   * 需要"每次都是干净工作区"的用户可以在配置里关掉。
   */
  const tabsPersistence = ref(true)

  /** 顶栏是否显示 Logo。 */
  const showLogo = ref(true)

  /**
   * 头部（含页签栏）是否固定在顶部、不随内容滚动。
   *
   * <p>默认固定：中台的顶栏承载搜索/通知/用户菜单，滚动后消失会迫使用户
   * 回到顶部才能操作。内容区很长时这条尤其明显。
   */
  const fixedHeader = ref(true)

  /**
   * 本地偏好持久化。
   *
   * <h3>为什么放在 store 而不是组件里</h3>
   * 「侧边栏折叠」「布局模式」是<b>用户对界面的选择</b>，属于客户端偏好。
   * 放在组件的 `onMounted` 里读写会遇到两个问题：
   * <ol>
   *   <li>同一份偏好被多个组件各读一次，写入时机分散</li>
   *   <li>刷新后丢失 —— 表现为"选了混合布局，F5 又变回左侧"，
   *       而这类"设置不生效"通常会被当成布局组件的问题去查</li>
   * </ol>
   *
   * <h3>⚠️ 解析失败必须吞掉</h3>
   * localStorage 里的内容可能被手改、可能残留旧版本的结构。
   * 若让异常冒出去，<b>整个应用会在启动时白屏</b> ——
   * 而代价本来只是"回到默认布局"。偏好恢复失败永远不该阻塞启动。
   */
  const LAYOUT_STORAGE_KEY = 'admin.layout'

  function restoreLayout(): void {
    try {
      const raw = localStorage.getItem(LAYOUT_STORAGE_KEY)
      if (!raw) {
        return
      }
      const saved = JSON.parse(raw) as {
        sidebarCollapsed?: boolean
        themeMode?: ThemePreference
        primaryColor?: string
        grayMode?: boolean
        weakenMode?: boolean
        showTagsView?: boolean
        watermarkEnabled?: boolean
        watermarkText?: string
        layoutMode?: LayoutMode
        tabStyle?: TabStyle
        pageWidthMode?: PageWidthMode
        pageWidth?: number
        hideFooter?: boolean
        tabsPersistence?: boolean
        showLogo?: boolean
        fixedHeader?: boolean
      }
      if (typeof saved.sidebarCollapsed === 'boolean') {
        sidebarCollapsed.value = saved.sidebarCollapsed
      }
      if (
        saved.themeMode === 'light' ||
        saved.themeMode === 'dark' ||
        saved.themeMode === 'auto'
      ) {
        themeMode.value = saved.themeMode
      }
      if (typeof saved.primaryColor === 'string') {
        primaryColor.value = saved.primaryColor
      }
      if (typeof saved.grayMode === 'boolean') {
        grayMode.value = saved.grayMode
      }
      if (typeof saved.weakenMode === 'boolean') {
        weakenMode.value = saved.weakenMode
      }
      if (typeof saved.showTagsView === 'boolean') {
        showTagsView.value = saved.showTagsView
      }
      if (typeof saved.watermarkEnabled === 'boolean') {
        watermarkEnabled.value = saved.watermarkEnabled
      }
      if (typeof saved.watermarkText === 'string') {
        watermarkText.value = saved.watermarkText
      }
      // ---- 布局相关 ----
      // 每一项都做"取值校验"而不是直接赋值：localStorage 可能被手改或残留旧结构，
      // 一个非法值（如 layoutMode: 'triangle'）会把布局组件带进未定义分支。
      // 枚举类逐值比对、数值类做范围收敛，是本文件一贯的恢复策略。
      if (
        saved.layoutMode === 'vertical' ||
        saved.layoutMode === 'horizontal' ||
        saved.layoutMode === 'mix'
      ) {
        layoutMode.value = saved.layoutMode
      }
      if (
        saved.tabStyle === 'smart' ||
        saved.tabStyle === 'card' ||
        saved.tabStyle === 'google' ||
        saved.tabStyle === 'neon' ||
        saved.tabStyle === 'pill'
      ) {
        tabStyle.value = saved.tabStyle
      }
      if (saved.pageWidthMode === 'fixed' || saved.pageWidthMode === 'custom') {
        pageWidthMode.value = saved.pageWidthMode
      }
      if (typeof saved.pageWidth === 'number' && Number.isFinite(saved.pageWidth)) {
        // 收敛到可读区间：小于 960 会让表格挤成竖条，大于 2560 在 4K 屏上也失去意义
        pageWidth.value = Math.min(2560, Math.max(960, Math.round(saved.pageWidth)))
      }
      if (typeof saved.hideFooter === 'boolean') {
        hideFooter.value = saved.hideFooter
      }
      if (typeof saved.tabsPersistence === 'boolean') {
        tabsPersistence.value = saved.tabsPersistence
      }
      if (typeof saved.showLogo === 'boolean') {
        showLogo.value = saved.showLogo
      }
      if (typeof saved.fixedHeader === 'boolean') {
        fixedHeader.value = saved.fixedHeader
      }
    } catch {
      // 见上方说明：偏好损坏只是回到默认值，不该影响启动
    }
  }

  watch(
    [
      sidebarCollapsed,
      themeMode,
      primaryColor,
      grayMode,
      weakenMode,
      showTagsView,
      watermarkEnabled,
      watermarkText,
      layoutMode,
      tabStyle,
      pageWidthMode,
      pageWidth,
      hideFooter,
      tabsPersistence,
      showLogo,
      fixedHeader
    ],
    () => {
      try {
        localStorage.setItem(
          LAYOUT_STORAGE_KEY,
          JSON.stringify({
            sidebarCollapsed: sidebarCollapsed.value,
            themeMode: themeMode.value,
            primaryColor: primaryColor.value,
            grayMode: grayMode.value,
            weakenMode: weakenMode.value,
            showTagsView: showTagsView.value,
            watermarkEnabled: watermarkEnabled.value,
            watermarkText: watermarkText.value,
            layoutMode: layoutMode.value,
            tabStyle: tabStyle.value,
            pageWidthMode: pageWidthMode.value,
            pageWidth: pageWidth.value,
            hideFooter: hideFooter.value,
            tabsPersistence: tabsPersistence.value,
            showLogo: showLogo.value,
            fixedHeader: fixedHeader.value
          })
        )
      } catch {
        // 隐私模式 / 配额满时写入会失败。此时只影响"下次是否记住"，
        // 当前这次使用完全正常，因此不打扰用户
      }
    }
  )

  restoreLayout()

  /** 切换主题（浅 ↔ 深，以实际生效值为基准；auto 偏好会被覆盖为明确选择）。 */
  function toggleTheme(): void {
    themeMode.value = resolvedTheme.value === 'light' ? 'dark' : 'light'
  }

  function setTheme(mode: ThemePreference): void {
    themeMode.value = mode
  }

  function setPrimaryColor(color: string): void {
    // 非 #RGB/#RRGGBB 一律视为清空（原生取色器可能给出别的格式）
    primaryColor.value = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.test(color) ? color : ''
  }

  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  // 主题变化 → 同步 CSS 变量与 document 标记，供样式层与第三方库消费
  watch(
    [resolvedTheme, primaryColor],
    ([mode, primary]) => {
      applyTokensToDom(mode, primary || undefined)
      document.documentElement.dataset.theme = mode
      // vxe 与 Naive 的主题系统互不感知，必须显式同步（否则页面暗了、表格没暗）
      void syncVxeTheme(mode === 'dark')
    },
    { immediate: true }
  )

  /** 灰色 / 色弱滤镜（挂在根元素上，全站一次性生效）。
   *
   *  ⚠️ 已知代价：CSS filter 会让后代元素里的 position:fixed 改为相对
   *  该祖先定位 —— 弹窗在极端滚动位置下可能有偏移。辅助功能开关
   *  默认关闭，用户主动开启即接受此权衡。 */
  function applyDisplayFilter(): void {
    const filters = [
      grayMode.value ? 'grayscale(100%)' : '',
      weakenMode.value ? 'invert(0.92) hue-rotate(180deg)' : ''
    ].filter(Boolean)
    if (filters.length > 0) {
      document.documentElement.style.filter = filters.join(' ')
    } else {
      document.documentElement.style.removeProperty('filter')
    }
  }

  watch([grayMode, weakenMode], applyDisplayFilter, { immediate: true })

  return {
    themeMode,
    resolvedTheme,
    primaryColor,
    grayMode,
    weakenMode,
    showTagsView,
    watermarkEnabled,
    watermarkText,
    sidebarCollapsed,
    layoutMode,
    tabStyle,
    pageWidthMode,
    pageWidth,
    hideFooter,
    tabsPersistence,
    showLogo,
    fixedHeader,
    toggleTheme,
    setTheme,
    setPrimaryColor,
    toggleSidebar
  }
})
