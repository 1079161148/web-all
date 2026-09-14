/**
 * Design Token —— 全站视觉唯一来源（设计文档 §11.8）。
 *
 * <h3>为什么必须集中</h3>
 * 禁止在组件里硬编码颜色 / 间距 / 字号。一旦硬编码，主题切换、暗色模式、
 * 品牌色定制全部会失效，而且散落的硬编码无法被搜索出来（`#1890ff` 可能写成
 * `#1890FF`、`rgb(24,144,255)` 等无数种形式）。
 * 集中之后，改品牌色只需要改这一处的 5 个色阶。
 *
 * <h3>输出两个消费者</h3>
 * <ul>
 *   <li>{@link toCssVariables} —— 供 UnoCSS 与自研组件使用</li>
 *   <li>{@code @admin/ui} 的 themeOverrides 映射 —— 供 Naive UI 使用</li>
 * </ul>
 * 两者同源，因此「用 Tailwind 写的颜色」与「用 Naive 组件渲染的颜色」不会打架。
 */

/** 主题模式。 */
export type ThemeMode = 'light' | 'dark'

/** 语义色（不随主题变化的基础色板）。 */
export interface ColorPalette {
  /** 品牌主色（默认取蓝色系）。 */
  primary: string
  primaryHover: string
  primaryPressed: string
  success: string
  warning: string
  error: string
  info: string
}

/** 中性色：按主题切换的一套灰度。 */
export interface NeutralPalette {
  /** 页面背景。 */
  bg: string
  /** 容器/卡片背景。 */
  bgElevated: string
  /** 悬浮态背景。 */
  bgHover: string
  /** 边框。 */
  border: string
  /** 分割线（更浅）。 */
  divider: string
  /** 主要文本。 */
  textPrimary: string
  /** 次要文本。 */
  textSecondary: string
  /** 占位/禁用文本。 */
  textDisabled: string
}

/** 间距栅格：4 的倍数，避免出现 13px / 17px 这类随意值。 */
export const spacing = {
  xxs: '2px',
  xs: '4px',
  sm: '8px',
  md: '12px',
  lg: '16px',
  xl: '24px',
  xxl: '32px',
  xxxl: '48px'
} as const

/** 圆角。 */
export const radius = {
  sm: '2px',
  md: '4px',
  lg: '8px',
  full: '9999px'
} as const

/** 字号字阶。 */
export const fontSize = {
  xs: '12px',
  sm: '13px',
  md: '14px',
  lg: '16px',
  xl: '18px',
  xxl: '22px',
  display: '28px'
} as const

/** 行高。 */
export const lineHeight = {
  tight: '1.25',
  normal: '1.5',
  relaxed: '1.75'
} as const

/** 阴影层级。 */
export const shadow = {
  sm: '0 1px 2px rgba(0, 0, 0, 0.06)',
  md: '0 2px 8px rgba(0, 0, 0, 0.08)',
  lg: '0 6px 20px rgba(0, 0, 0, 0.12)'
} as const

/** 动效曲线与时长。 */
export const motion = {
  durationFast: '0.1s',
  durationNormal: '0.2s',
  durationSlow: '0.3s',
  easeOut: 'cubic-bezier(0.215, 0.61, 0.355, 1)',
  easeInOut: 'cubic-bezier(0.645, 0.045, 0.355, 1)'
} as const

/** 布局尺寸。 */
export const layout = {
  sidebarWidth: '220px',
  sidebarCollapsedWidth: '64px',
  headerHeight: '56px',
  tagsViewHeight: '40px',
  /** 内容区最大宽度，超宽屏下避免表格被拉伸得难以阅读。 */
  contentMaxWidth: '1600px'
} as const

const lightColors: ColorPalette = {
  primary: '#2563eb',
  primaryHover: '#3b82f6',
  primaryPressed: '#1d4ed8',
  success: '#16a34a',
  warning: '#d97706',
  error: '#dc2626',
  info: '#0891b2'
}

const darkColors: ColorPalette = {
  primary: '#3b82f6',
  primaryHover: '#60a5fa',
  primaryPressed: '#2563eb',
  success: '#22c55e',
  warning: '#f59e0b',
  error: '#ef4444',
  info: '#06b6d4'
}

const lightNeutrals: NeutralPalette = {
  bg: '#f5f7fa',
  bgElevated: '#ffffff',
  bgHover: '#f0f2f5',
  border: '#e4e7ed',
  divider: '#ebeef5',
  textPrimary: '#1f2329',
  textSecondary: '#5c6570',
  textDisabled: '#a8b0ba'
}

const darkNeutrals: NeutralPalette = {
  bg: '#101014',
  bgElevated: '#18181c',
  bgHover: '#26262c',
  border: '#2d2d34',
  divider: '#242429',
  textPrimary: '#e5e7eb',
  textSecondary: '#9ca3af',
  textDisabled: '#6b7280'
}

/** 完整主题令牌。 */
export interface ThemeTokens {
  mode: ThemeMode
  colors: ColorPalette
  neutrals: NeutralPalette
  spacing: typeof spacing
  radius: typeof radius
  fontSize: typeof fontSize
  lineHeight: typeof lineHeight
  shadow: typeof shadow
  motion: typeof motion
  layout: typeof layout
}

/** 取得指定模式下的完整令牌集。 */
export function getTokens(mode: ThemeMode): ThemeTokens {
  return {
    mode,
    colors: mode === 'dark' ? darkColors : lightColors,
    neutrals: mode === 'dark' ? darkNeutrals : lightNeutrals,
    spacing,
    radius,
    fontSize,
    lineHeight,
    shadow,
    motion,
    layout
  }
}

/** CSS 变量前缀。 */
const CSS_VAR_PREFIX = '--wa'

/**
 * 令牌 → CSS 变量，注入到 `:root` 或主题容器上。
 *
 * <p>这样 UnoCSS、自研组件、第三方库（vxe-table 主题桥接）都能消费同一套变量，
 * 实现「改一处、全站生效」。
 */
export function toCssVariables(mode: ThemeMode): Record<string, string> {
  const tokens = getTokens(mode)
  const vars: Record<string, string> = {}

  Object.entries(tokens.colors).forEach(([key, value]) => {
    vars[`${CSS_VAR_PREFIX}-color-${kebab(key)}`] = value
  })
  Object.entries(tokens.neutrals).forEach(([key, value]) => {
    vars[`${CSS_VAR_PREFIX}-${kebab(key)}`] = value
  })
  Object.entries(tokens.spacing).forEach(([key, value]) => {
    vars[`${CSS_VAR_PREFIX}-spacing-${key}`] = value
  })
  Object.entries(tokens.radius).forEach(([key, value]) => {
    vars[`${CSS_VAR_PREFIX}-radius-${key}`] = value
  })
  Object.entries(tokens.fontSize).forEach(([key, value]) => {
    vars[`${CSS_VAR_PREFIX}-font-size-${key}`] = value
  })
  Object.entries(tokens.layout).forEach(([key, value]) => {
    vars[`${CSS_VAR_PREFIX}-layout-${kebab(key)}`] = value
  })

  return vars
}

/** camelCase → kebab-case。 */
function kebab(input: string): string {
  return input.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase()
}

/** 把令牌写入 DOM（供应用启动时调用）。 */
export function applyTokensToDom(mode: ThemeMode, target?: HTMLElement): void {
  const element = target ?? document.documentElement
  const vars = toCssVariables(mode)
  Object.entries(vars).forEach(([key, value]) => {
    element.style.setProperty(key, value)
  })
  element.dataset.theme = mode
}
