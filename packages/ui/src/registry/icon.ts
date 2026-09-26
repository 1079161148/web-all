import { ref, type Component } from 'vue'

/**
 * 图标注册表：图标名 → 组件的解析来源。
 *
 * <h3>为什么是一个「注册表」而不是组件库内置一套图标</h3>
 * 与字典 / 权限注册表（见 {@code registry/dict.ts}）是同一个依赖方向问题：
 * <ul>
 *   <li>{@code @vicons} 是<b>图标集的集合</b>（ionicons / material / tabler …
 *       各自独立发布），内置哪一套是产品决策，组件库不该替使用者拍板；</li>
 *   <li>整套图标动辄几百 KB，内置进组件库会让所有页面承担这份体积。</li>
 * </ul>
 * 因此方向反过来：<b>应用启动时注册自己选定的图标集</b>，组件库只负责"拿名字 → 渲染"。
 *
 * <pre>{@code
 * // apps/admin/src/main.ts（应用侧接线示例）
 * const modules = import.meta.glob('@vicons/ionicons5/*', { eager: true })
 * registerIcons(
 *   Object.entries(modules).map(([path, mod]) => ({
 *     name: path.split('/').pop()!.replace(/\.js$/, ''),
 *     component: (mod as { default: Component }).default
 *   }))
 * )
 * }</pre>
 *
 * <h3>名字契约</h3>
 * 注册名必须与 {@code IconPicker} 提交给后端的名字<b>完全一致</b>（精确匹配，
 * 不做大小写 / 前缀归一）—— 两端共用同一份图标清单是应用的责任，
 * 组件库在中间做"模糊容错"只会让"图标名拼错了"这类问题被静默掩盖
 * （表现为fallback字形，反而更难发现）。
 */

/** 注册项。与 IconPicker 的 {@code ProIconOption} 结构一致（结构化类型，可直接传入）。 */
export interface IconRegistration {
  /** 图标名。与 IconPicker 提交给后端的存储值是同一个名字。 */
  name: string
  /** 图标组件（来自 @vicons 或任意实现了 SVG 渲染的组件）。 */
  component: Component
}

const byName = new Map<string, Component>()

/**
 * 注册版本号。**它存在的唯一目的是让"后到的图标"能被重新解析。**
 *
 * <h3>为什么必须有它（否则会永久显示占位符）</h3>
 * 应用可能分两批注册图标：首屏只注册常用集（控制入口体积，见
 * {@code apps/admin/src/icons.ts} 的体积取舍），整包在空闲/需要时异步载入。
 * 而 Vue 的渲染只跟踪<b>响应式依赖</b>——若 {@link resolveIcon} 是纯查表，
 * 那么"渲染时还没注册"的图标会一直停留在降级字形，<b>直到下一次因别的原因重渲染</b>，
 * 表现为"图标时有时无、切个页面又好了"，是最难复现的一类问题。
 *
 * <p>做法很轻：{@link resolveIcon} 在查表前读一下版本号，
 * 于是每个用到图标的渲染函数都订阅了它——注册时自增即可让它们全部重新解析。
 */
const version = ref(0)

/** 读一次版本号以建立依赖（见 {@link version} 的说明）。 */
function trackVersion(): void {
  // eslint-disable-next-line @typescript-eslint/no-unused-expressions
  void version.value
}

/**
 * 批量注册图标。应用启动时调用一次；重复注册同名图标以最后一次为准
 * （热更新场景下模块重复执行时不会累积脏条目）。
 */
export function registerIcons(icons: IconRegistration[]): void {
  for (const icon of icons) {
    byName.set(icon.name, icon.component)
  }
  // 触发已渲染图标重新解析（见 version 的说明）
  version.value += 1
}

/**
 * 图标名是否已注册。
 *
 * <p>供应用判断"是否需要把整包图标载进来"——只有出现未注册的名字时才付这份代价，
 * 而不是默认全量载入。
 */
export function hasIcon(name: string): boolean {
  return byName.has(name)
}

/** 是否已注册过图标（供 story / 测试断言用）。 */
export function hasIconRegistry(): boolean {
  return byName.size > 0
}

/**
 * 把「图标名或组件」解析成组件。
 *
 * <p>入参刻意允许直接传 {@code Component}：纯前端场景（story、写死的页面装饰）
 * 没有必要为了渲染一个图标先去注册表绕一圈。
 * 字符串查不到时返回 undefined —— 由组件决定降级形态，注册表不猜测。
 */
export function resolveIcon(name?: string | Component | null): Component | undefined {
  if (!name) {
    return undefined
  }
  if (typeof name === 'string') {
    // 订阅注册版本：图标后续才注册时，本次渲染会被自动重跑（见 version 的说明）
    trackVersion()
    return byName.get(name)
  }
  return name
}
