import type { ProTreeNode, ProTreePreset } from '../../types'

/**
 * 扁平列表 → 树。
 *
 * <h3>为什么这一步必须由组件库提供（而不是各页面各写一遍）</h3>
 * 本项目的后端<b>列表接口一律返回扁平结构</b>（带 {@code parentId}），
 * 这是刻意的设计：同一份数据要服务多种视图 ——
 * 管理页要树表格、权限分配要"可勾选的树"、面包屑要"根到当前节点的路径"、
 * 下拉要"可搜索的树选择"，而它们的建树规则并不相同。
 *
 * <p>但内核（n-tree）需要的是<b>嵌套</b>结构。这个"扁平 → 嵌套"的转换
 * 内核提供不了，于是每个用到树的页面都要写一遍。实测已经重复了 5 处：
 * 用户页的 {@code buildDeptTree}、角色页的 {@code buildTree}、
 * 权限 store 的 {@code build}、部门页与菜单页各自的 {@code parentOptions} ——
 * 五份实现的逻辑完全相同（按 parentId 分组 → 按 sort 排序 → 递归）。
 *
 * <p><b>重复的代价不只是行数</b>：五份里只要有一份忘了按 {@code sort} 排序，
 * 那一个页面的树序就会与其它页面不一致 —— 而"顺序不对"通常被当成后端问题。
 */

/**
 * 扁平树行的最小契约。
 *
 * <p>刻意用结构化类型（而不是要求调用方实现某个接口）：
 * 部门、菜单、分类的 DTO 各不相同，但它们都有 {@code id / parentId / sort}。
 * 让本函数接受"长得像树行的东西"，比要求它们都实现同一个接口更实际。
 *
 * <h3>⚠️ 这里绝对不能加 {@code [key: string]: unknown}</h3>
 * 加了之后<b>全部页面都编译不过</b>，而且报错指向调用处、
 * 与"索引签名"看起来毫无关系：
 * <pre>
 *   类型 "DeptDTO[]" 的参数不能赋给类型 "FlatTreeRow[]" 的参数。
 *     索引签名 "string" 在类型 "DeptDTO" 中缺失。
 * </pre>
 * 原因是 <b>TypeScript 的 interface 不生成隐式索引签名</b> ——
 * 而后端生成的 {@code DeptDTO} / {@code MenuDTO} / {@code RoleResponse}
 * 全是 interface，它们<b>永远不满足</b> {@code Record<string, unknown>}。
 *
 * <p>这个坑本仓库已经踩过一次（见 {@code ProCell.ts} 里关于
 * "为什么单元格渲染器不接收 {@code T extends Record<string, unknown>}" 的记录），
 * 这里是它的第二次现形。<b>凡是"要接收后端 DTO"的类型，都不要带索引签名。</b>
 *
 * <p>代价是：内置预设会读取的字段（{@code deptName} / {@code menuName} /
 * {@code menuType} / {@code status}）必须<b>显式列出</b>。
 * 这不亏 —— 列出它们的同时也就把"预设依赖哪些字段"变成了可检索的事实。
 * 自定义结构则由调用方用泛型参数传入（见 {@link flatToTree} 的泛型）。
 */
export interface FlatTreeRow {
  id?: number | string
  parentId?: number | string | null
  sort?: number
  /** 部门名（dept 预设读取）。 */
  deptName?: string
  /** 菜单名（menu 预设读取）。 */
  menuName?: string
  /** 菜单类型，用于给按钮加后缀（menu 预设读取）。 */
  menuType?: string
  /** 状态，用于判断是否禁用（两个预设都读取）。 */
  status?: string
}

/** 建树选项。 */
export interface FlatToTreeOptions<T extends FlatTreeRow> {
  /** 节点文案。唯一必须提供的东西 —— 因为"用哪个字段当标题"无法推断。 */
  labelOf: (row: T) => string
  /**
   * 根节点的 {@code parentId} 取值。
   *
   * <p>默认 {@code 0}（本项目的后端约定）。做成可配置是因为
   * {@code null} / {@code 0} / 空串在不同表里都出现过。
   */
  rootValue?: number | string
  /** 是否禁用某行（如已停用的部门不可选）。 */
  disabledOf?: (row: T) => boolean
  /**
   * 过滤：返回 {@code false} 的行**及其整棵子树**一并丢弃。
   *
   * <p>刻意不做"只丢自己、把子节点上提一级"的处理：
   * 那样会让树的层级与真实父子关系脱节，而使用者是照着树做选择的
   * （例如"把菜单挂到这个目录下"），层级失真会直接导致选错。
   */
  filter?: (row: T) => boolean
}

/**
 * 把扁平行列表构建成树。
 *
 * <h3>⚠️ 关于环（脏数据）：这里"丢弃"而不是"卡死"，是刻意的</h3>
 * 本函数<b>自根向下</b>遍历，因此父子互指的节点会因为"从根不可达"而
 * 被自动排除 —— 不需要显式防环。
 *
 * <p>对比：{@code apps/admin} 里"沿 {@code parentId} 向上回推完整路径"的实现
 * <b>必须</b>带访问集合防环（见 {@code router/dynamic.ts}），
 * 因为向上遍历遇到环会永不终止、直接把页面卡死。
 * <b>下行遍历天然安全，上行必须防环</b> —— 这是两件容易混淆的事。
 *
 * <p>代价是脏数据会"静默消失"。这是有意的取舍：树里少几个节点，
 * 比浏览器标签页卡死要好得多，而且数据修复后会自动恢复。
 */
export function flatToTree<T extends FlatTreeRow>(
  rows: T[],
  options: FlatToTreeOptions<T>
): ProTreeNode[] {
  return buildTreeNodes(rows, options, (row, children) => ({
    label: options.labelOf(row),
    key: row.id as string | number,
    // 挂上原始行：调用方常需要拿到 code / type / perms 等字段。
    // 组件不解释它们，只负责透传
    row,
    ...(options.disabledOf ? { disabled: options.disabledOf(row) } : {}),
    // 有子级才挂 children：挂一个空数组会让 n-tree 渲染出
    // 一个可展开但展开后什么都没有的箭头
    ...(children.length > 0 ? { children } : {})
  }))
}

/**
 * 表格树行：原始行字段 + 嵌套的 children。
 *
 * <p>vxe 的树形表格（{@code treeConfig}）要求的就是这种形状 ——
 * 行本身携带业务字段（列直接取），父子关系由 {@code children} 表达。
 * 与 {@link ProTreeNode}（为 n-tree / n-select 设计的 label + key + row 包装）
 * 是两种不同的消费形态，因此分开两个出口而不是硬凑一个。
 */
export type TableTreeRow<T> = T & { children?: TableTreeRow<T>[] }

/**
 * 扁平行 → 表格树行（vxe treeConfig 直接可用）。
 *
 * <p>与 {@link flatToTree} 共享同一份建树内核（分组 / 排序 / 过滤 / 防环），
 * 差别只在节点形状：这里把原始行字段<b>摊开</b>在节点上。
 * 叶子不挂 children —— 空数组会让 vxe 渲染出展开后什么都没有的箭头。
 */
export function flatToTableTree<T extends FlatTreeRow>(
  rows: T[],
  options: Omit<FlatToTreeOptions<T>, 'disabledOf'>
): Array<TableTreeRow<T>> {
  return buildTreeNodes(rows, options, (row, children) => ({
    ...row,
    ...(children.length > 0 ? { children } : {})
  }))
}

/**
 * 共用建树内核。分组、排序、过滤、防环规则只在这里定义一次；
 * {@link flatToTree} 与 {@link flatToTableTree} 只是两种节点形状的出口。
 */
function buildTreeNodes<T extends FlatTreeRow, N>(
  rows: T[],
  options: FlatToTreeOptions<T>,
  nodeOf: (row: T, children: N[]) => N
): N[] {
  const rootValue = options.rootValue ?? 0

  // 一级索引：parentId → 子行。把"找某节点的所有子节点"从 O(n) 降到 O(1)，
  // 整体从 O(n²)（每个节点都扫一遍全表）降到 O(n)
  const byParent = new Map<string, T[]>()
  for (const row of rows) {
    if (row.id === undefined || row.id === null) {
      // 没有主键的行无法成为父节点，也无法被选择。
      // 静默跳过而不是抛错：列表里偶尔混入一条残缺数据，
      // 不应该让整棵树渲染失败
      continue
    }
    if (options.filter && !options.filter(row)) {
      continue
    }
    const parentKey = String(row.parentId ?? rootValue)
    const siblings = byParent.get(parentKey)
    if (siblings) {
      siblings.push(row)
    } else {
      byParent.set(parentKey, [row])
    }
  }

  const build = (parentId: string, depth: number): N[] => {
    const siblings = (byParent.get(parentId) ?? [])
      .slice()
      // 按 sort 排序：依赖接口返回顺序是不可靠的 ——
      // 列表接口的 ORDER BY 未必与页面期望的展示顺序一致，
      // 而"树序不对"很容易被误判成后端问题
      .sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0))

    const nodes: N[] = []
    for (const row of siblings) {
      // 深度上限：即便真出现环（父子互指且恰好构成可下行环，如 A→B→A 且
      // 其中一方的 parentId 也指向根），也只是被截断而不是无限递归。
      // 这是一道"不可能触发但绝不能没有"的保险
      if (depth > MAX_TREE_DEPTH) {
        continue
      }
      const children = build(String(row.id), depth + 1)
      nodes.push(nodeOf(row, children))
    }
    return nodes
  }

  return build(String(rootValue), 0)
}

/**
 * 扁平下拉选项。
 *
 * <h3>⚠️ 必须是 `type` 而不是 `interface`（实测踩过）</h3>
 * 写成 {@code interface} 时，把它传给 {@code n-select} 的 {@code :options}
 * 会报：
 * <pre>
 *   类型 "FlatOption&lt;number&gt;[]" 不能赋给类型 "SelectMixedOption[]"
 * </pre>
 * 而两边的形状明明一致。原因是 Naive 的 {@code SelectBaseOption} 带
 * {@code [k: string]: unknown} 索引签名，而 <b>TypeScript 只为"对象字面量类型"
 * 推断隐式索引签名，interface 不推断</b> —— 于是 interface 版本不满足它。
 *
 * <p>同样的形状写成 {@code type} 别名就能满足。
 * 这是本仓库第三次遇到这条规则（前两次见 {@code ProCell.ts} 与
 * {@code tree.ts} 的 {@code FlatTreeRow}）：<b>凡是数据要流进第三方组件的
 * props 类型，先用 type 别名。</b>
 */
export type FlatOption<V extends string | number = string | number> = {
  label: string
  value: V
}

/**
 * 树 → 带层级缩进的扁平下拉选项。
 *
 * <h3>它替代的是每个管理页都要手写一遍的「算深度」</h3>
 * 部门页与菜单页的"选择上级"下拉都是这个形态：<b>扁平选项 + 用全角空格缩进表示层级</b>。
 * 而层级不是数据里现成的字段，得自己算 —— 于是两页各写了一份：
 * <ul>
 *   <li>部门页用 {@code depthOf(dept)}：每次调用都沿 parentId 向上回推一遍</li>
 *   <li>菜单页用 {@code depthById}：预先把所有节点的深度缓存成 Map，
 *       注释里写着理由是"避免每行都重新走一遍链（行数 × 深度 的重复计算）"</li>
 * </ul>
 *
 * <p><b>先建树再遍历，层级天然可得</b> ——
 * 上面那两种写法都是在"没有树"的前提下各自绕路。
 * 菜单页那份 Map 连同它的<b>防环 guard</b> 因此可以整段消失：
 * 树结构本身就是无环的（见 {@link flatToTree} 关于环的说明）。
 *
 * <h3>为什么 value 用 valueOf 而不是直接取 id</h3>
 * 返回值要直接喂给 {@code n-select} 并绑定到表单的 {@code parentId}（数字）。
 * 让调用方用 {@code valueOf} 声明确切类型，就不必在赋值处写
 * {@code as number} 断言 —— 类型从源头就是对的。
 */
export function flatOptionsWithDepth<T extends FlatTreeRow, V extends string | number>(
  rows: T[],
  options: {
    /** 节点文案。 */
    labelOf: (row: T) => string
    /** 选项值。 */
    valueOf: (row: T) => V
    /** 过滤（同 flatToTree，作用于整棵子树）。 */
    filter?: (row: T) => boolean
    /** 根节点的 parentId 取值。默认 0。 */
    rootValue?: number | string
    /** 每层缩进串。默认全角空格（等宽、不会被 HTML 折叠）。 */
    indent?: string
  }
): Array<FlatOption<V>> {
  const indent = options.indent ?? '　'

  const tree = flatToTree(rows, {
    labelOf: options.labelOf,
    filter: options.filter,
    rootValue: options.rootValue
  })

  const result: Array<FlatOption<V>> = []

  const walk = (nodes: ProTreeNode[], depth: number): void => {
    for (const node of nodes) {
      result.push({
        label: `${indent.repeat(depth)}${node.label}`,
        // 断言说明：flatToTree 把原始行挂在 node.row 上（用于透传业务字段），
        // 但 ProTreeNode 为了通用性把它声明成了 unknown。
        // 这里还原成 T —— 运行期就是当初传进去的那个对象
        value: options.valueOf(node.row as T)
      })
      if (node.children?.length) {
        walk(node.children, depth + 1)
      }
    }
  }

  walk(tree, 0)
  return result
}

/**
 * 树深上限。
 *
 * <p>取 64：真实业务树（部门 / 菜单）通常不超过 10 层，
 * 64 已经远超合理范围 —— 它是一道针对"数据异常"的保险，
 * 不是业务约束。
 */
const MAX_TREE_DEPTH = 64

/** 预设的转换规则。 */
export interface TreePresetConfig {
  /** 节点文案。 */
  labelOf: (row: FlatTreeRow) => string
  /** 是否禁用。 */
  disabledOf?: (row: FlatTreeRow) => boolean
}

/**
 * 部门 / 菜单两个预设。
 *
 * <h3>预设解决的是"字段名不一致"</h3>
 * 部门的标题字段是 {@code deptName}，菜单是 {@code menuName}，
 * 而树的其它部分（{@code id} / {@code parentId} / {@code sort} / {@code status}）
 * 完全一致。因此预设只做一件事：<b>把"用哪个字段当标题"这件事登记下来</b>，
 * 顺带带上各自的禁用规则。
 *
 * <p>之所以值得做成预设而不是让调用方传 {@code labelOf}：
 * 同一棵部门树在多个页面出现（用户页选部门、角色页配数据范围、部门管理页），
 * 每处的 {@code labelOf} 都该一样 —— 现在"一样"由这里保证，而不是靠自觉。
 */
export const TREE_PRESETS: Record<ProTreePreset, TreePresetConfig> = {
  dept: {
    labelOf: (row) => String(row.deptName ?? '未命名部门'),
    // 停用的部门仍然显示（历史数据里可能有引用），但不可选
    disabledOf: (row) => row.status !== undefined && row.status !== null && row.status !== 'ACTIVE'
  },
  menu: {
    labelOf: (row) => {
      const name = String(row.menuName ?? '未命名节点')
      // 按钮加后缀：菜单树里「用户管理」与「用户新增」是父子关系，
      // 但视觉上只差两个字，看不出层级。加上后缀才能在树上区分开
      return row.menuType === 'BUTTON' ? `${name}（按钮）` : name
    },
    disabledOf: (row) => row.status !== undefined && row.status !== null && row.status !== 'ACTIVE'
  }
}

/**
 * 在「扁平树」上按名称做模糊过滤，命中节点<b>连同其祖先</b>一并保留。
 *
 * <h3>为什么必须保留祖先</h3>
 * 树形列表的行是按父子关系缩进/成树渲染的（部门靠 {@code ancestors} 算缩进，
 * 菜单靠 {@code flatToTableTree} 还原层级）。只留命中节点会让子节点变成孤立的根节点、
 * 缩进全错 —— 用户看到的是"搜出来的结果层级乱了"，而不是"筛选生效了"。
 *
 * <p>过滤发生在<b>前端</b>：部门与菜单是两个全量返回的树（不分页），
 * 后端不提供筛选参数。这与其余列表"筛选下推到 SQL"的取舍不同 ——
 * 全量数据已在内存里，再发一次请求没有意义。
 *
 * <p>顺序保持输入顺序（后端已按 parentId + sort 排好），因此过滤后层级与排序不变。
 */
export function filterFlatTreeByLabel<T extends FlatTreeRow>(
  rows: T[],
  keyword: string,
  options: { labelOf: (row: T) => string }
): T[] {
  const needle = keyword.trim().toLowerCase()
  if (needle === '') {
    return rows
  }

  const byId = new Map<number | string, T>()
  for (const row of rows) {
    if (row.id !== undefined && row.id !== null) {
      byId.set(row.id, row)
    }
  }

  const kept = new Set<number | string>()
  for (const row of rows) {
    if (!options.labelOf(row).toLowerCase().includes(needle)) {
      continue
    }
    if (row.id !== undefined && row.id !== null) {
      kept.add(row.id)
    }
    // 沿 parentId 向上回溯保留祖先。visited 防御数据异常造成的环 ——
    // 没有它时一个环形 parentId 会让这里死循环（后端有校验，但前端不该赌）
    let parentId = row.parentId ?? null
    const visited = new Set<number | string>()
    while (parentId !== null && parentId !== undefined && parentId !== 0 && !visited.has(parentId)) {
      visited.add(parentId)
      kept.add(parentId)
      const parent = byId.get(parentId)
      if (!parent) {
        break
      }
      parentId = parent.parentId ?? null
    }
  }

  return rows.filter((row) => row.id !== undefined && row.id !== null && kept.has(row.id))
}
