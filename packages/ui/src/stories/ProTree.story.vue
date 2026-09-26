<script setup lang="ts">
import { ref } from 'vue'
import ProTree from '../components/pro/ProTree.vue'
import type { FlatTreeRow, ProTreeDropInfo } from '../index'

/**
 * ProTree 的 story。
 *
 * <p>这里刻意用<b>扁平数据 + 预设</b>来演示，而不是手写嵌套结构 ——
 * 因为"后端列表接口返回扁平数据"才是真实形态，
 * 手写嵌套会把最容易出问题的那一步（建树）从 story 里绕过去。
 */

/** 部门：扁平结构，字段名与后端 DeptDTO 一致。 */
const deptRows: FlatTreeRow[] = [
  { id: 1, parentId: 0, deptName: '总部', sort: 1, status: 'ACTIVE' },
  { id: 2, parentId: 1, deptName: '技术中心', sort: 1, status: 'ACTIVE' },
  { id: 3, parentId: 1, deptName: '市场部', sort: 2, status: 'ACTIVE' },
  { id: 4, parentId: 2, deptName: '平台组', sort: 1, status: 'ACTIVE' },
  { id: 5, parentId: 2, deptName: '应用组', sort: 2, status: 'ACTIVE' },
  { id: 6, parentId: 3, deptName: '华东区', sort: 1, status: 'ACTIVE' },
  // 已停用：应当显示但不可选（预设里的 disabledOf 负责）
  { id: 7, parentId: 3, deptName: '华南区（已撤销）', sort: 2, status: 'DISABLED' },
  // 无父节点可寻：验证"从根不可达会被丢弃"而不是把页面卡死
  { id: 8, parentId: 999, deptName: '孤儿节点', sort: 9, status: 'ACTIVE' }
]

/** 菜单：验证 menu 预设给按钮加后缀（否则「用户管理」与「用户新增」看不出层级）。 */
const menuRows: FlatTreeRow[] = [
  { id: 10, parentId: 0, menuName: '系统管理', menuType: 'DIR', sort: 1, status: 'ACTIVE' },
  { id: 11, parentId: 10, menuName: '用户管理', menuType: 'MENU', sort: 1, status: 'ACTIVE' },
  { id: 12, parentId: 11, menuName: '用户新增', menuType: 'BUTTON', sort: 1, status: 'ACTIVE' },
  { id: 13, parentId: 11, menuName: '用户删除', menuType: 'BUTTON', sort: 2, status: 'ACTIVE' }
]

const selected = ref<string | number | null>(3)
const checked = ref<Array<string | number>>([4])
const menuChecked = ref<Array<string | number>>([12])
const customSelected = ref<string | number | null>(null)
const lazySelected = ref<string | number | null>(null)
const dropLog = ref('')

/**
 * 懒加载：只给根节点，子级在展开时才取。
 *
 * <p>刻意带 300ms 延迟 —— 没有延迟就看不出加载态，
 * 而"展开后一片空白到底是没数据还是还没回来"正是懒加载最容易误判的地方。
 */
async function loadChildren(node: { key: string | number }) {
  await new Promise((resolve) => setTimeout(resolve, 300))
  if (node.key === 'L1') {
    return [
      { label: '一级子节点 A', key: 'L1-A', isLeaf: true },
      { label: '一级子节点 B', key: 'L1-B', isLeaf: true }
    ]
  }
  return []
}

/**
 * 落点校验：不允许把节点移到<b>自己的子孙</b>下（会形成环）。
 *
 * <p>这条规则需要同时知道"被拖的是谁"与"落到哪"。内核的 allowDrop
 * 只给落点节点，所以这段逻辑在裸用 n-tree 时写不出来 ——
 * 这正是 ProTree 在 dragstart 记录源节点的原因。
 */
function allowDrop(info: ProTreeDropInfo): boolean {
  const dragKey = info.dragNode?.key
  if (dragKey === undefined) {
    return true
  }
  // 从落点沿 parentId 向上回推，若能遇到被拖节点，说明落点在它的子树里
  const parentOf = new Map<number | string, number | string>()
  for (const row of deptRows) {
    if (row.id !== undefined) {
      parentOf.set(row.id, (row.parentId as number) ?? 0)
    }
  }

  let cursor: number | string | undefined = info.dropNode.key
  // 向上回推必须防环（脏数据会让它永不终止），下行遍历才不需要
  const guard = new Set<number | string>()
  while (cursor !== undefined && cursor !== 0 && !guard.has(cursor)) {
    if (cursor === dragKey) {
      return false
    }
    guard.add(cursor)
    cursor = parentOf.get(cursor)
  }
  return true
}

function handleDrop(info: ProTreeDropInfo): void {
  // 组件不实现移动：这里只记录，真实项目里应调接口并在成功后 reload()
  dropLog.value =
    `把「${info.dragNode?.label ?? '?'}」拖到「${info.dropNode.label}」的 ` +
    `${info.position}`
}

/**
 * 非部门/菜单的树：自定义结构。
 *
 * <p>这里定义一个自己的行类型（而不是硬套 FlatTreeRow）——
 * 泛型会从 rows 推断出它，于是 labelOf 里能直接写 {@code row.name}。
 * 这正是不给 FlatTreeRow 加索引签名换来的好处：
 * 自定义结构走泛型，内置预设走显式字段，两条路都不需要断言。
 */
interface CategoryRow extends FlatTreeRow {
  name?: string
}

const customRows: CategoryRow[] = [
  { id: 'a', parentId: '', name: '一级分类', sort: 1 },
  { id: 'b', parentId: 'a', name: '二级分类', sort: 1 }
]
</script>

<template>
  <Story title="Pro 组件/ProTree" :layout="{ type: 'grid', width: '420px' }">
    <Variant
      title="部门预设：扁平数据直接建树"
      doc="只给 rows + preset='dept'。建树、按 sort 排序、停用项置灰都由组件负责。注意「孤儿节点」被丢弃而不是卡死 —— 自根向下遍历天然防环。"
    >
      <ProTree v-model:value="selected" :rows="deptRows" preset="dept" default-expand-all />
      <p class="story-value">当前选中：{{ selected }}</p>
    </Variant>

    <Variant
      title="菜单预设：按钮自动加后缀"
      doc="菜单与部门只差标题字段，因此共用同一份建树逻辑；菜单预设额外给 BUTTON 加「（按钮）」后缀。"
    >
      <ProTree
        v-model:value="menuChecked"
        :rows="menuRows"
        preset="menu"
        checkable
        default-expand-all
      />
      <p class="story-value">已勾选：{{ menuChecked }}</p>
    </Variant>

    <Variant
      title="搜索"
      doc="n-tree 本身没有输入框（只有 pattern），搜索框由本组件提供。匹配规则交给内核，它会保留命中节点的祖先链 —— 否则深层节点命中了也显示不出来。"
    >
      <ProTree :rows="deptRows" preset="dept" default-expand-all />
    </Variant>

    <Variant
      title="拖拽 + 落点校验（防环）"
      doc="⚠️ 把「总部」拖到它自己的子部门下会被拒绝。这条校验需要同时知道被拖节点与落点，而内核的 allowDrop 只给落点 —— 源节点由 ProTree 在 dragstart 补齐。"
    >
      <ProTree
        :rows="deptRows"
        preset="dept"
        draggable
        default-expand-all
        :allow-drop="allowDrop"
        @drop="handleDrop"
      />
      <p class="story-value">{{ dropLog || '试着把「总部」拖到「平台组」上 —— 会被拒绝' }}</p>
    </Variant>

    <Variant
      title="勾选模式（权限分配场景）"
      doc="checkable 时 value 落在勾选键上；checkStrategy 决定父子联动策略 —— 'child' 常用于权限树：「勾了子菜单，父目录自动算作拥有」。"
    >
      <ProTree
        v-model:value="checked"
        :rows="deptRows"
        preset="dept"
        checkable
        check-strategy="all"
        default-expand-all
      />
      <p class="story-value">已勾选：{{ checked }}</p>
    </Variant>

    <Variant
      title="懒加载"
      doc="⚠️ 展开才取子级。注意与上面的搜索配合时的问题：未展开的分支没有数据，也就搜不到 —— 数据量大时应监听 search 事件把搜索交给后端。"
    >
      <ProTree
        v-model:value="lazySelected"
        :options="[{ label: '根节点', key: 'L1' }]"
        :load-children="loadChildren"
      />
    </Variant>

    <Variant
      title="自定义 labelOf + rootValue（非部门/菜单）"
      doc="不依赖预设：任意扁平结构都能建树，只需告诉组件用哪个字段当标题。这棵树的根 parentId 是空串而非 0 —— 根值猜错的表现是整棵树为空，所以它必须可配。"
    >
      <ProTree
        v-model:value="customSelected"
        :rows="customRows"
        :label-of="(row) => String(row.name)"
        root-value=""
        default-expand-all
      />
    </Variant>

    <Variant title="空数据" doc="空态与加载态互斥 —— 加载中显示「暂无数据」会闪一下，看起来像出错了。">
      <ProTree :rows="[]" preset="dept" />
    </Variant>
  </Story>
</template>

<style scoped>
.story-value {
  margin: var(--wa-spacing-md) 0 0;
  color: var(--wa-text-secondary);
  font-size: var(--wa-font-size-sm);
}
</style>
