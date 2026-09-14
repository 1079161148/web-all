import type { RouteRecordRaw } from 'vue-router'
import type { MenuDTO } from '@admin/api'

/**
 * 由后端菜单构建前端路由。
 *
 * <h3>核心约束：菜单不硬编码（设计文档 §11.15 第 5 条红线）</h3>
 * 后端 `iam_menu.component` 存的是**字符串路径**（如 `iam/user/index`），
 * 前端用 {@link import.meta.glob} 建立"路径 → 组件加载器"的映射表来查表。
 *
 * <p>这样做的收益是双向的：
 * <ul>
 *   <li>后端新增一个菜单，前端不需要改任何代码、不需要重新构建</li>
 *   <li>后端的菜单数据<b>无法注入可执行内容</b> —— 它只能命中映射表里已存在的组件，
 *       任意字符串最多导致"页面未实现"，不可能变成代码执行</li>
 * </ul>
 * 对比 RuoYi 常见的"前端写死一份菜单、后端再配一份"的做法：两边必然漂移，
 * 而且每次加页面都要改两个地方。
 *
 * <h3>为什么用 glob 而不是动态 import 字符串</h3>
 * `import(变量)` 在 Vite 里无法被静态分析，会导致所有可能的目标都被打进产物或直接失败。
 * glob 是<b>构建期</b>展开的，既保留了按需加载（每个页面独立 chunk），
 * 又让打包器能准确知道有哪些候选模块。
 */

/**
 * 视图组件映射表。
 *
 * <p>键是相对 `src/views` 的路径（不含扩展名），与后端 `component` 字段的写法一致。
 */
const viewModules = import.meta.glob('../views/**/*.vue')

/** 把后端给的 component 字符串规范化为映射表的键。 */
function normalizeComponentKey(component: string): string {
  let key = component.trim()
  // 去掉可能的前导斜杠与 `@/views/` 前缀 —— 不同团队录数据习惯不同，
  // 容错比要求所有人都严格按一种写法更实际
  key = key.replace(/^@\/views\//, '').replace(/^\/+/, '')
  key = key.replace(/\.vue$/, '')
  return `../views/${key}.vue`
}

/**
 * 解析组件加载器；找不到时返回占位组件，并把缺失路径记入 {@code missing}。
 *
 * <h3>为什么找不到不报错、而是给占位页</h3>
 * 常见情形：后端已经配好了菜单（或刚加了菜单），前端页面还没开发完。
 * 此时若让路由指向 undefined，页面会白屏且控制台只有一句找不到组件 ——
 * 使用者看到的是"系统坏了"。
 *
 * <p>给一个明确说明"该页面尚未实现，component = xxx"的占位页，
 * 就把一个"故障"变成了一个"已知状态"，<b>这是状态矩阵思路在路由层的延伸</b>。
 *
 * <h3>为什么不在这里逐条 console.warn</h3>
 * 初版是每缺一个组件就 warn 一次。实际跑起来的结果是：后端配了 8 个菜单、
 * 前端只实现了 1 个，于是<b>每次刷新页面都刷出 7 条警告</b>。
 *
 * <p>这有两个反效果：
 * <ol>
 *   <li><b>噪声会训练人忽略控制台。</b>当 7 条警告是"正常的过渡状态"时，
 *       真正要紧的第 8 条（某个 component 路径拼错了）也会被一并划过去 ——
 *       而它正是这个警告唯一要防的东西</li>
 *   <li>分散的警告看不出<b>全貌</b>。开发者想知道的是"还差哪几个页面"，
 *       而不是在滚动的控制台里逐条拼凑</li>
 * </ol>
 * 因此改为：把缺失路径收集起来，由 {@link buildRoutes} 在最后<b>汇总成一条</b>。
 * <b>可操作的提示必须是聚合的、低噪声的。</b>
 */
function resolveComponent(component: string | undefined, missing: string[]) {
  if (!component || !component.trim()) {
    return () => import('../views/PlaceholderView.vue')
  }
  const loader = viewModules[normalizeComponentKey(component)]
  if (!loader) {
    missing.push(component)
    return () => import('../views/PlaceholderView.vue')
  }
  return loader
}

/** 动态路由的元信息（供布局、标签页、面包屑消费）。 */
export interface DynamicRouteMeta {
  title: string
  icon?: string
  keepAlive: boolean
  alwaysShow: boolean
  /** 菜单 ID，便于与后端菜单对齐。 */
  menuId: number
  /**
   * 后端菜单声明的组件路径（如 {@code org/post/index}）。
   *
   * <p>把它带进 meta，是为了让占位页能直接告诉开发者<b>该创建哪个文件</b>。
   * 否则开发者只看到"这个菜单没页面"，还得回去翻后端菜单表才知道要建什么 ——
   * <b>提示的价值在于把下一步动作说清楚，而不只是陈述现状。</b>
   */
  expectedComponent?: string
}

/**
 * 业务路由类型。
 *
 * <p>刻意保持为 {@link RouteRecordRaw} 的别名，而<b>不</b>写成
 * `RouteRecordRaw & { meta: DynamicRouteMeta }`。
 * 后者看起来更精确，但 vue-router 的 `RouteRecordRaw` 是一个联合类型
 * （带 children 的 / 不带 children 的 / 重定向的…），交叉一个额外的 meta
 * 会让 TS 无法确定该用哪个分支，于是要求写出 `children` 字段 ——
 * 而我们的路由确实没有 children。**过度的类型精确反而制造了不存在的约束。**
 *
 * <p>需要 meta 类型安全时，用 {@link readRouteMeta} 读取，它做一次窄化。
 */
export type DynamicRoute = RouteRecordRaw

/** 从路由 meta 中安全读取业务元信息。 */
export function readRouteMeta(meta: unknown): Partial<DynamicRouteMeta> {
  return (meta ?? {}) as Partial<DynamicRouteMeta>
}

/**
 * 把扁平菜单转换成 vue-router 的路由记录。
 *
 * <p>返回的是<b>扁平数组</b>（每个可导航菜单一条顶层记录，路径为完整路径），
 * 而不是嵌套结构。原因：后端菜单的目录/菜单两层结构与 vue-router 的
 * "父路由 + children" 语义并不总是一一对应（目录可能没有组件），
 * 而扁平注册配合完整路径能让 `router.addRoute` 的行为完全可预测 ——
 * <b>路径匹配的正确性比嵌套结构的"优雅"重要得多。</b>
 */
export function buildRoutes(menus: MenuDTO[]): DynamicRoute[] {
  const sorted = menus
    .filter((menu) => menu.menuType === 'MENU')
    .slice()
    .sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0))

  // ⚠️ 只有 id 确定存在的菜单才能进映射表与生成路由。
  //    生成类型里所有字段都是可选的（OpenAPI 未标注 required），
  //    但"菜单必须有 id"是后端的硬约束（主键）。
  //    在这里一次性收敛，比让 undefined 扩散到每个使用点更可靠 ——
  //    否则会出现 `menu-undefined` 这样的路由名，多个菜单互相覆盖。
  const byId = new Map<number, MenuDTO>()
  for (const menu of menus) {
    if (menu.id !== undefined) {
      byId.set(menu.id, menu)
    }
  }

  /** 收集本次构建中"组件文件不存在"的菜单，最后汇总成一条警告。 */
  const missingComponents: string[] = []

  const routes = sorted
    .filter((menu): menu is MenuDTO & { id: number } => menu.id !== undefined)
    .map((menu) => {
      const fullPath = resolveFullPath(menu, byId)
      const route: DynamicRoute = {
        path: fullPath,
        name: `menu-${menu.id}`,
        component: resolveComponent(menu.component ?? undefined, missingComponents),
        meta: {
          title: menu.menuName ?? '未命名菜单',
          icon: menu.icon ?? undefined,
          keepAlive: menu.keepAlive ?? false,
          alwaysShow: menu.alwaysShow ?? false,
          menuId: menu.id,
          expectedComponent: menu.component ?? undefined
        }
      }
      return route
    })

  warnMissingComponents(missingComponents)
  return routes
}

/**
 * 汇总报告缺失的组件文件。
 *
 * <p>只在开发期提示。生产环境静默回退到占位页 —— 生产不该因为
 * "某个页面还没开发"而在用户控制台里输出开发提示。
 */
function warnMissingComponents(missing: string[]): void {
  if (missing.length === 0 || !import.meta.env.DEV) {
    return
  }
  const detail = missing.map((component) => `    · ${component}  →  待创建：src/views/${component}.vue`)
  console.warn(
    [
      `[dynamic-route] 有 ${missing.length} 个菜单的组件文件尚未创建，已回退到占位页：`,
      ...detail,
      '  说明：这是预期的过渡状态 —— 后端菜单可以先配好，前端页面逐个开发。',
      '  若某个菜单本应已有页面，请检查上面的路径是否与后端 iam_menu.component 一致（多是拼写不一致）。'
    ].join('\n')
  )
}

/**
 * 自底向上拼接完整路径。
 *
 * <p>后端的目录与菜单各自的 `path` 是**相对片段**（`system` / `user`），
 * 而 vue-router 注册时需要完整路径（`/system/user`）。这里沿 parentId 向上回推，
 * 并带一个访问集合防环 —— 数据库里若出现父子互指（脏数据或人工改库），
 * 没有防环就会无限循环、把浏览器卡死。
 */
function resolveFullPath(menu: MenuDTO & { id: number }, byId: Map<number, MenuDTO>): string {
  const segments: string[] = []
  const visited = new Set<number>()
  let current: MenuDTO | undefined = menu

  while (current) {
    const currentId = current.id
    if (currentId === undefined || visited.has(currentId)) {
      // id 缺失（理论上不会，已在上游过滤）或检测到环 → 停止向上追溯。
      // 防环是必需的：数据库里若出现父子互指（人工改库或脏数据），
      // 没有这个判断就会无限循环并把浏览器卡死。
      break
    }
    visited.add(currentId)

    if (current.path) {
      // 绝对路径直接终止（该菜单自带完整路径，不再拼父级）
      if (current.path.startsWith('/')) {
        return current.path
      }
      segments.unshift(current.path)
    }

    // 显式标注类型：`current` 会被重新赋值，而 `current.parentId` 的类型
    // 依赖 `current` 自身的类型 —— 不标注会让 TS 判定为"循环推断"而报 TS7022。
    const parentId: number | undefined = current.parentId
    current = parentId !== undefined && parentId !== 0 ? byId.get(parentId) : undefined
  }

  return `/${segments.join('/')}`
}
