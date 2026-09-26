import type { Component } from 'vue'
/**
 * ⚠️ 逐个图标走<b>子路径</b>导入，而不是从包根具名导入。
 *
 * <p>原因不是风格：从包根导入会让入口与整包 barrel（{@code es/index.js}）
 * 建立静态依赖，构建器就会把整包图标抽成一个 chunk <b>并写进 index.html 的
 * modulepreload</b> —— 首屏照样下载 200+ KB，等于没优化。
 * 子路径指向单个图标模块，只有这十几个进首屏（见下方 CURATED）。
 */
import Analytics from '@vicons/ionicons5/es/Analytics'
import Book from '@vicons/ionicons5/es/Book'
import Briefcase from '@vicons/ionicons5/es/Briefcase'
import Business from '@vicons/ionicons5/es/Business'
import ChatbubbleEllipsesOutline from '@vicons/ionicons5/es/ChatbubbleEllipsesOutline'
import ContrastOutline from '@vicons/ionicons5/es/ContrastOutline'
import Construct from '@vicons/ionicons5/es/Construct'
import Create from '@vicons/ionicons5/es/Create'
import GitNetwork from '@vicons/ionicons5/es/GitNetwork'
import Home from '@vicons/ionicons5/es/Home'
import List from '@vicons/ionicons5/es/List'
import LogOutOutline from '@vicons/ionicons5/es/LogOutOutline'
import Menu from '@vicons/ionicons5/es/Menu'
import Moon from '@vicons/ionicons5/es/Moon'
import NotificationsOutline from '@vicons/ionicons5/es/NotificationsOutline'
import Options from '@vicons/ionicons5/es/Options'
import Person from '@vicons/ionicons5/es/Person'
import Settings from '@vicons/ionicons5/es/Settings'
import ShieldCheckmark from '@vicons/ionicons5/es/ShieldCheckmark'
import Sunny from '@vicons/ionicons5/es/Sunny'
import { hasIcon, registerIcons, type ProIconOption } from '@admin/ui/core'

/**
 * 应用图标集（@vicons/ionicons5）。
 *
 * <h3>名字契约</h3>
 * 后端 {@code iam_menu.icon} 存的就是这套图标的<b>组件名</b>
 * （见 {@code V1.0.4/V1.0.5/V1.0.7} 的种子数据），前端按名字解析。
 * 名字唯一对应、精确匹配，不做大小写或前缀归一 —— 模糊容错会让"图标名拼错"
 * 静默降级成占位字形，反而更难发现。
 *
 * <h3>为什么分「常用集（静态）」+「整包（懒加载）」两批注册</h3>
 * 早期实现是 {@code import * as IonIcons} 整体导入，好处是不写死任何清单，
 * 代价是<b>整包图标都进首屏</b>——首屏 gzip 因此超出设计 §12.1 的预算门禁
 * （{@code pnpm check:size}），而图标对首屏而言并不是关键资源。
 *
 * <p>现在的分工：
 * <ol>
 *   <li>{@link setupIcons} 只注册下面这份<b>常用集</b>：首屏就可见的顶层图标
 *       （侧栏一级目录 + 首页）。叶子菜单的图标不在这里 ——
 *       {@link ensureIconsRegistered} 会发现它们未注册并载入整包，
 *       代价是"展开父级后一瞬间显示占位字形，随后自动补上"，
 *       换来的是首屏不必为所有叶子图标买单</li>
 *   <li>{@link loadMenuIcons} 按需载入<b>整包</b>：菜单编辑器要给出全量候选，
 *       以及数据里配置了常用集之外的图标时兜底</li>
 * </ol>
 * 注册表是响应式的（见 {@code registry/icon.ts} 的 version），
 * 因此"后到的图标"会自动补进已渲染的位置，不会一直停在占位字形。
 *
 * <p>⚠️ 新增菜单若用了常用集之外的图标，<b>无需改这里</b>：
 * {@link ensureIconsRegistered} 会在发现未注册的名字时把整包载进来。
 * 但如果某个图标是首屏就要显示的（侧栏顶层），建议加进常用集以免先看到占位字形。
 */
const CURATED: ProIconOption[] = [
  { name: 'Analytics', component: Analytics },
  { name: 'Book', component: Book },
  { name: 'Briefcase', component: Briefcase },
  { name: 'Business', component: Business },
  // ---- 顶栏工具区（首屏即需要，见 BasicLayout） ----
  { name: 'ChatbubbleEllipsesOutline', component: ChatbubbleEllipsesOutline },
  { name: 'ContrastOutline', component: ContrastOutline },
  { name: 'LogOutOutline', component: LogOutOutline },
  { name: 'Moon', component: Moon },
  { name: 'NotificationsOutline', component: NotificationsOutline },
  { name: 'Sunny', component: Sunny },
  // ---- 菜单常用 ----
  { name: 'Construct', component: Construct },
  { name: 'Create', component: Create },
  { name: 'GitNetwork', component: GitNetwork },
  { name: 'Home', component: Home },
  { name: 'List', component: List },
  { name: 'Menu', component: Menu },
  { name: 'Options', component: Options },
  { name: 'Person', component: Person },
  { name: 'Settings', component: Settings },
  { name: 'ShieldCheckmark', component: ShieldCheckmark }
]

let registered = false

/**
 * 应用启动时调用一次：注册常用图标集（幂等）。
 *
 * <p>不在模块顶层直接注册，是为了让调用时机可控（与其它 resolver 同批在 main.ts）。
 */
export function setupIcons(): void {
  if (registered) {
    return
  }
  registerIcons(CURATED)
  registered = true
}

/** 整包图标的载入 Promise（同一会话只载一次）。 */
let fullIcons: Promise<ProIconOption[]> | null = null

/**
 * 载入并注册整包图标（懒加载，幂等）。
 *
 * <p>动态 {@code import()} 会让图标包成为<b>独立 chunk</b>，
 * 不进 {@code index.html} 的预加载列表 —— 这正是首屏体积门禁所度量的东西。
 */
export function loadMenuIcons(): Promise<ProIconOption[]> {
  if (!fullIcons) {
    fullIcons = import('@vicons/ionicons5').then((mod) => {
      const options = toOptions(mod as Record<string, unknown>)
      registerIcons(options)
      return options
    })
  }
  return fullIcons
}

/**
 * 若给定名字中存在<b>未注册</b>的图标，则载入整包。
 *
 * <p>用于"数据里配置的图标"这一场景：菜单的图标名来自数据库，
 * 可能不在常用集里。只有真的出现缺口时才付整包下载的代价，
 * 而不是每次启动都默认全量载入。
 *
 * @param names 可能为空的图标名集合（如菜单树的 icon 字段）
 */
export async function ensureIconsRegistered(names: Array<string | undefined | null>): Promise<void> {
  const missing = names.some((name) => typeof name === 'string' && name !== '' && !hasIcon(name))
  if (missing) {
    await loadMenuIcons()
  }
}

/** 命名空间模块 → 注册项：导出名即图标名（与后端存的值一致）。 */
function toOptions(mod: Record<string, unknown>): ProIconOption[] {
  return Object.entries(mod)
    .filter(([, comp]) => comp !== null && typeof comp === 'object')
    .map(([name, component]) => ({ name, component: component as Component }))
}
