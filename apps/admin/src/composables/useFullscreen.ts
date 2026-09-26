import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

/**
 * 全屏切换（Fullscreen API）。
 *
 * <h3>为什么必须监听 fullscreenchange，而不是自己维护布尔值</h3>
 * 用户按 Esc、或浏览器自己退出全屏时，**不会**通知调用方。如果只在
 * 自己的 toggle 里翻转标志位，会出现"图标说还能退出全屏、但其实已经不在全屏"
 * 的状态不一致 —— 这是全屏按钮最常见的 bug。正确做法是让浏览器事件成为
 * 唯一事实来源：{@code document.fullscreenElement} 是判断依据。
 *
 * <h3>兼容性处理</h3>
 * 标准 API（Firefox / Chrome / Edge）与 WebKit 前缀（旧 Safari）都做兼容，
 * 且 Safari 在非用户手势中调用会 reject —— 因此 {@link useFullscreen.toggle}
 * 内部吞掉异常并返回布尔值，让调用方可以给出提示而不是抛到控制台。
 */

/** 扩展的 HTMLElement / Document 类型（WebKit 前缀），避免声明 any。 */
interface WebkitFullscreenElement extends HTMLElement {
  webkitRequestFullscreen?: () => Promise<void> | void
}

interface WebkitFullscreenDocument extends Document {
  webkitFullscreenElement?: Element | null
  webkitExitFullscreen?: () => Promise<void> | void
}

export interface UseFullscreenReturn {
  /** 是否处于全屏（由浏览器事件同步，不手动维护） */
  isFullscreen: Ref<boolean>
  /** 当前浏览器是否支持全屏 API */
  supported: Ref<boolean>
  /** 切换全屏；返回是否成功（失败通常是被浏览器策略拒绝） */
  toggle: (target?: HTMLElement) => Promise<boolean>
}

export function useFullscreen(fallbackTarget?: () => HTMLElement | null): UseFullscreenReturn {
  const isFullscreen = ref(false)
  const supported = ref(true)

  function currentElement(): Element | null {
    const doc = document as WebkitFullscreenDocument
    return doc.fullscreenElement ?? doc.webkitFullscreenElement ?? null
  }

  function sync(): void {
    isFullscreen.value = currentElement() !== null
  }

  async function toggle(target?: HTMLElement): Promise<boolean> {
    const doc = document as WebkitFullscreenDocument
    try {
      if (currentElement()) {
        if (doc.exitFullscreen) {
          await doc.exitFullscreen()
        } else if (doc.webkitExitFullscreen) {
          await doc.webkitExitFullscreen()
        }
        return true
      }

      // 默认整页全屏：大屏/报表页想只全屏某块内容时，把元素传进来即可
      const element =
        target ?? fallbackTarget?.() ?? document.documentElement
      const requestable = element as WebkitFullscreenElement
      if (requestable.requestFullscreen) {
        await requestable.requestFullscreen()
      } else if (requestable.webkitRequestFullscreen) {
        await requestable.webkitRequestFullscreen()
      } else {
        supported.value = false
        return false
      }
      return true
    } catch {
      // Safari 在非手势上下文会 reject；策略拒绝也走这里。
      // 返回 false 让调用方决定要不要提示，而不是把异常抛进控制台。
      return false
    }
  }

  onMounted(() => {
    supported.value =
      typeof document.documentElement.requestFullscreen === 'function' ||
      typeof (document.documentElement as WebkitFullscreenElement).webkitRequestFullscreen ===
        'function'
    sync()
    document.addEventListener('fullscreenchange', sync)
    document.addEventListener('webkitfullscreenchange', sync)
  })

  onBeforeUnmount(() => {
    document.removeEventListener('fullscreenchange', sync)
    document.removeEventListener('webkitfullscreenchange', sync)
  })

  return { isFullscreen, supported, toggle }
}
