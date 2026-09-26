import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

/**
 * 流式大屏的「容器比例换算」——本方案的核心工具。
 *
 * <h3>为什么是流式布局，而不是整体缩放</h3>
 * 常见做法是把 1920×1080 的设计稿整体 `transform: scale()` 塞进视口。它的问题是
 * 结构性的，不是调参能解决的：
 * <ul>
 *   <li><b>字体被位图缩放</b>：放大后发虚、缩小后笔画糊在一起</li>
 *   <li><b>鼠标坐标失真</b>：命中区域与视觉位置有偏差，图表 tooltip / hover 点位不准</li>
 *   <li><b>比例被强行锁定</b>：超宽屏两侧必须留白，竖屏直接没法用</li>
 * </ul>
 * 流式布局让元素<b>真的跟着容器重排</b>：宽度用 fr/%/flex，字体用 clamp()，
 * 只有"无法用 CSS 表达的东西"才走 {@link ScreenScale.sc} 换算。
 *
 * <h3>为什么监听容器而不是 window</h3>
 * 内容区的可用宽度变化**不等于**窗口变化。最典型的反例：用户折叠侧边栏 ——
 * window 尺寸一点没变，但图表容器宽了几百像素；反过来页签栏出现/消失也一样。
 * 只听 window 会导致"侧边栏收起后图表被拉伸变形"。ResizeObserver 直接盯容器，
 * 这类情况全部覆盖。
 *
 * <h3>为什么用 rAF 合并回调</h3>
 * ResizeObserver 在一次布局变更中可能连续触发多次（尤其是拖拽窗口时），
 * 每次回调都重算 option + setOption 会造成明显掉帧。合并到一帧最多一次，
 * 观感无差别，渲染次数降一个数量级。
 *
 * <h3>换算函数只服务于 ECharts 内部配置</h3>
 * {@link ScreenScale.sc} 的适用场景是**CSS 管不到的地方**：ECharts 的
 * fontSize / lineWidth / symbolSize / padding / itemGap / 地图 label 等。
 * 布局尺寸（容器宽高、栅格、间距）一律交给 CSS 流式单位 ——
 * 反过来做（全用 sc 算像素再写死）就等于把整体缩放搬到 JS 里，同样会退化。
 */

/** 换算结果与工具。 */
export interface ScreenScale {
  /** 当前容器宽度 / 设计稿宽度。图表 option 用它作为 computed 依赖以自动重建。 */
  scale: Ref<number>
  /**
   * px（设计稿像素）→ 当前容器下的真实像素。
   *
   * <p>给 ECharts 内部配置用。返回整数，避免出现 12.3456789px 这类无意义精度。
   */
  sc: (px: number) => number
  /**
   * 带上下限的字号换算：小屏不至于小到看不清，超宽屏不至于大到滑稽。
   *
   * <p>这是流式布局必备的一道闸门 —— 纯比例换算在 3840 宽的屏幕上会把
   * 12px 的正文字号推到 24px，整个界面会散掉。
   */
  scClamp: (px: number, min: number, max: number) => number
  /** 当前容器宽度（px），用于需要按断点切换形态的地方（如窄屏改单列）。 */
  width: Ref<number>
}

export function useScreenScale(
  containerEl: Ref<HTMLElement | null>,
  designWidth = 1920
): ScreenScale {
  const scale = ref(1)
  const width = ref(0)
  let observer: ResizeObserver | null = null
  let rafId = 0

  function measure(): void {
    const element = containerEl.value
    if (!element) {
      return
    }
    const current = element.clientWidth
    if (current <= 0) {
      return
    }
    width.value = current
    scale.value = current / designWidth
  }

  /** 一帧内最多测一次：ResizeObserver 会连发，直接测量会重复重建 option。 */
  function schedule(): void {
    if (rafId !== 0) {
      return
    }
    rafId = requestAnimationFrame(() => {
      rafId = 0
      measure()
    })
  }

  function sc(px: number): number {
    return Math.round(px * scale.value)
  }

  function scClamp(px: number, min: number, max: number): number {
    return Math.min(max, Math.max(min, Math.round(px * scale.value)))
  }

  onMounted(() => {
    measure()
    observer = new ResizeObserver(schedule)
    if (containerEl.value) {
      observer.observe(containerEl.value)
    }
  })

  onBeforeUnmount(() => {
    if (rafId !== 0) {
      cancelAnimationFrame(rafId)
      rafId = 0
    }
    observer?.disconnect()
    observer = null
  })

  return { scale, sc, scClamp, width }
}
