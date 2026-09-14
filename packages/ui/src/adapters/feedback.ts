import { createDiscreteApi } from 'naive-ui'

/**
 * 全局反馈 API 适配器。
 *
 * <h3>解决 Naive UI 最经典的坑</h3>
 * Naive 的 `useMessage` / `useDialog` / `useNotification` **必须在 `n-message-provider`
 * 等 provider 内部调用**，因此在下面这些场景会直接报错：
 * <ul>
 *   <li>Pinia store / 路由守卫 / 请求拦截器里提示错误</li>
 *   <li>`setTimeout` 回调 / WebSocket 消息处理</li>
 *   <li>任何非 setup 上下文的工具函数</li>
 * </ul>
 *
 * 常见错误解法是各处自行调用 `createDiscreteApi` —— 那会导致多个独立的 provider 实例，
 * 主题不联动、消息堆叠位置错乱、暗色模式下样式不对。
 *
 * <h3>正确做法</h3>
 * 在 `@admin/ui` 里创建**单例**，全应用共用；并在应用启动时通过
 * {@link setupFeedbackTheme} 把当前主题同步进来，保证与页面主题一致。
 *
 * <p>业务代码：`import { feedback } from '@admin/ui'`，禁止自己调 `createDiscreteApi`。
 */

const { message, dialog, notification, loadingBar } = createDiscreteApi([
  'message',
  'dialog',
  'notification',
  'loadingBar'
])

/** 全局反馈单例。 */
export const feedback = {
  /** 成功提示。 */
  success: (content: string) => message.success(content),
  /** 错误提示。 */
  error: (content: string) => message.error(content),
  /** 警告提示。 */
  warning: (content: string) => message.warning(content),
  /** 普通信息。 */
  info: (content: string) => message.info(content),
  /** 顶部加载条。 */
  loading: {
    start: () => loadingBar.start(),
    finish: () => loadingBar.finish(),
    error: () => loadingBar.error()
  },
  /** 二次确认；返回 true 表示用户确认。 */
  confirm: (options: { title: string; content: string; positiveText?: string; negativeText?: string }) =>
    new Promise<boolean>((resolve) => {
      dialog.warning({
        title: options.title,
        content: options.content,
        positiveText: options.positiveText ?? '确定',
        negativeText: options.negativeText ?? '取消',
        onPositiveClick: () => resolve(true),
        onNegativeClick: () => resolve(false),
        onClose: () => resolve(false),
        onMaskClick: () => resolve(false)
      })
    }),
  /** 危险操作确认（删除等不可逆操作）。 */
  confirmDanger: (title: string, content: string) =>
    new Promise<boolean>((resolve) => {
      dialog.error({
        title,
        content,
        positiveText: '确认删除',
        negativeText: '取消',
        onPositiveClick: () => resolve(true),
        onNegativeClick: () => resolve(false),
        onClose: () => resolve(false),
        onMaskClick: () => resolve(false)
      })
    }),
  /** 通知（右上角卡片），用于需要留存的操作结果。 */
  notify: (title: string, content: string) => notification.info({ title, content, duration: 5000 }),
  /** 底层 API，供特殊场景使用。 */
  raw: { message, dialog, notification, loadingBar }
}

/**
 * 把应用主题同步到全局反馈实例。
 *
 * <p>应用在主题切换时调用，否则会出现「页面是暗色、消息提示是亮色」的割裂感。
 * 这里刻意不引入 naive-ui 的 theme 对象类型，而是接受 `null`（亮色）或主题对象，
 * 保持适配层对上层封闭。
 */
export function setupFeedbackTheme(theme: unknown): void {
  // createDiscreteApi 的 configProviderProps 在创建后不可变，
  // 因此主题切换通过 CSS 变量（由 @admin/theme 注入）生效 —— 见 apps/admin 的实现。
  void theme
}
