<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ProLayoutMenu } from '../../types'
import ProIcon from './ProIcon.vue'

/**
 * 侧边栏菜单（递归渲染）。
 *
 * <pre>{@code
 * <ProMenu :menus="menus" :active-key="route.path" @select="router.push" />
 * }</pre>
 *
 * <h3>为什么它是「递归组件」而不是一层 v-for</h3>
 * 菜单的层级由后端数据决定（目录可以嵌套目录），
 * <b>层级不是模板能枚举的</b>。递归让"每一层的渲染规则"只有一份 ——
 * 缩进、激活态、展开箭头在任意深度表现一致，
 * 而"写死两层 + 第三层不管"的实现迟早会在真实数据下露馅。
 *
 * <h3>为什么不用 n-menu（偏离 Naive 内核的说明）</h3>
 * n-menu 本身就是递归的（吃 options 树）。自建这一层的理由是<b>约定而非能力</b>：
 * <ol>
 *   <li>布局壳（ProLayout）需要与菜单的 DOM 结构/类名强配合
 *       （折叠宽度、缩进节奏、激活条），用自家结构比穿透 n-menu 的内部类名可靠；</li>
 *   <li>后端菜单的图标目前是字符串占位，n-menu 要求渲染函数包装，
 *       中间还得维护一份「业务菜单 → MenuOption」的映射 —— 自建结构直接消费
 *       {@link ProLayoutMenu}，映射这一层就不需要存在。</li>
 * </ol>
 * 它只做<b>结构与交互</b>（缩进 / 展开 / 激活 / 折叠），不实现任何重能力，
 * 属于 {@code ui-component-policy} 允许的「布局编排」范畴；
 * 配色全部走 Token，主题切换不需要改这里。
 *
 * <h3>折叠态的取舍</h3>
 * 折叠后宽度只有 64px，放不下文字，因此只渲染第一层：
 * <ul>
 *   <li>叶子 → 显示图标（无图标时取标题首字），点击直接导航；</li>
 *   <li>目录 → <b>hover 弹出子菜单浮层</b>（NPopover，右侧展开整组叶子清单，
 *       点叶子直达，激活项在浮层内高亮）。点击图标本体仍替用户选中
 *       其第一个叶子（与顶栏 mix 模式同款策略）。</li>
 * </ul>
 */

const props = withDefaults(
  defineProps<{
    /** 菜单树（当前层要渲染的兄弟节点）。 */
    menus?: ProLayoutMenu[]
    /** 当前激活项（通常是路由完整路径）。 */
    activeKey?: string
    /** 折叠态。<b>只在最外层生效</b>——折叠后子层级不存在。 */
    collapsed?: boolean
    /** 递归深度。组件内部自增，调用方不需要传。 */
    level?: number
  }>(),
  {
    menus: () => [],
    activeKey: '',
    collapsed: false,
    level: 0
  }
)

const emit = defineEmits<{
  /** 用户选择了某个叶子项（或折叠态下某个分组的第一个叶子）。 */
  (e: 'select', key: string): void
}>()

// ---------------------------------------------------------------------
// 展开 / 收起
// ---------------------------------------------------------------------

/** 本层各分组的展开集合。递归实例各自持有 —— 每层只管自己这层的分组。 */
const expandedKeys = ref(new Set<string>())

function toggleExpand(key: string): void {
  const next = new Set(expandedKeys.value)
  if (next.has(key)) {
    next.delete(key)
  } else {
    next.add(key)
  }
  expandedKeys.value = next
}

/** 子树中是否存在指定 key（递归查找）。 */
function subtreeHas(nodes: ProLayoutMenu[], key: string): boolean {
  for (const node of nodes) {
    if (node.key === key) {
      return true
    }
    if (node.children?.length && subtreeHas(node.children, key)) {
      return true
    }
  }
  return false
}

/**
 * 激活项变化 → 自动展开其祖先分组。
 *
 * <p>只做「并入」，不清空用户手动展开的其它分支 ——
 * 直接覆盖会让"展开了一个分支去看，一导航就被收起来"。
 */
watch(
  [() => props.menus, () => props.activeKey],
  () => {
    const toAdd = props.menus
      .filter(
        (node) =>
          node.children?.length &&
          !expandedKeys.value.has(node.key) &&
          subtreeHas(node.children, props.activeKey)
      )
      .map((node) => node.key)
    if (toAdd.length > 0) {
      expandedKeys.value = new Set([...expandedKeys.value, ...toAdd])
    }
  },
  { immediate: true }
)

// ---------------------------------------------------------------------
// 选择
// ---------------------------------------------------------------------

function handleSelect(key: string): void {
  emit('select', key)
}

/**
 * 分组节点的第一个叶子。
 *
 * <p>折叠态点目录时用：目录自身不可导航，原样抛 key 会让调用方
 * 跳到一个没有页面的路径。
 */
function firstLeafKey(node: ProLayoutMenu): string {
  let current = node
  while (current.children?.length) {
    current = current.children[0]!
  }
  return current.key
}

/**
 * 折叠浮层用的叶子清单：把一组菜单递归展平为可导航叶子。
 * 浮层是平铺列表（浮层里再嵌展开/收起会超出 64px 侧栏的信息密度预算），
 * 深层结构用层级缩进表达。
 */
function flattenLeaves(
  node: ProLayoutMenu
): Array<ProLayoutMenu & { depth: number }> {
  const out: Array<ProLayoutMenu & { depth: number }> = []
  const walk = (n: ProLayoutMenu, depth: number): void => {
    if (n.children?.length) {
      n.children.forEach((child) => walk(child, depth + 1))
      return
    }
    out.push({ ...n, depth })
  }
  walk(node, 0)
  return out
}

/** 折叠项是否应高亮：自身激活，或激活项在它的子树里。 */
function isCollapsedActive(node: ProLayoutMenu): boolean {
  return node.key === props.activeKey || subtreeHas(node.children ?? [], props.activeKey)
}

// ---------------------------------------------------------------------
// 样式辅助
// ---------------------------------------------------------------------

/** 递归缩进：基础内边距 + 每层固定步长。 */
const indentStyle = computed(() => ({
  paddingLeft: `calc(var(--wa-spacing-md, 12px) + ${props.level * 18}px)`
}))
</script>

<template>
  <!-- 折叠态（仅最外层）：只渲染第一层，无文字 -->
  <div v-if="collapsed && level === 0" class="pro-menu pro-menu--collapsed" role="menu">
    <template v-for="node in menus" :key="node.key">
      <!-- 目录：hover 弹出子菜单浮层（右侧展开整组叶子清单） -->
      <n-popover
        v-if="node.children?.length"
        trigger="hover"
        placement="right-start"
        :show-arrow="false"
      >
        <template #trigger>
          <button
            type="button"
            class="pro-menu__item pro-menu__item--collapsed"
            :class="{ 'pro-menu__item--active': isCollapsedActive(node) }"
            :aria-label="node.label"
            @click="handleSelect(firstLeafKey(node))"
          >
            <ProIcon v-if="node.icon" :name="node.icon" :size="18" />
            <span v-else class="pro-menu__glyph">{{ node.label.slice(0, 1) }}</span>
          </button>
        </template>
        <div class="pro-menu__flyout" role="menu">
          <div class="pro-menu__flyout-title">{{ node.label }}</div>
          <button
            v-for="leaf in flattenLeaves(node)"
            :key="leaf.key"
            type="button"
            class="pro-menu__flyout-item"
            :class="{ 'pro-menu__flyout-item--active': leaf.key === activeKey }"
            :style="{ paddingLeft: `${12 + leaf.depth * 14}px` }"
            role="menuitem"
            @click="handleSelect(leaf.key)"
          >
            {{ leaf.label }}
          </button>
        </div>
      </n-popover>
      <!-- 叶子：点击直达 -->
      <button
        v-else
        type="button"
        class="pro-menu__item pro-menu__item--collapsed"
        :class="{ 'pro-menu__item--active': isCollapsedActive(node) }"
        :title="node.label"
        :aria-label="node.label"
        @click="handleSelect(node.key)"
      >
        <ProIcon v-if="node.icon" :name="node.icon" :size="18" />
        <span v-else class="pro-menu__glyph">{{ node.label.slice(0, 1) }}</span>
      </button>
    </template>
  </div>

  <!-- 常规态：递归渲染 -->
  <div v-else class="pro-menu" role="menu">
    <template v-for="node in menus" :key="node.key">
      <!-- 分组：自身不可导航，点击只做展开/收起 -->
      <div v-if="node.children?.length" class="pro-menu__group">
        <button
          type="button"
          class="pro-menu__item pro-menu__item--parent"
          :class="{ 'pro-menu__item--trail': subtreeHas(node.children, activeKey) }"
          :style="indentStyle"
          :aria-expanded="expandedKeys.has(node.key)"
          @click="toggleExpand(node.key)"
        >
          <ProIcon v-if="node.icon" :name="node.icon" :size="16" class="pro-menu__icon" />
          <span class="pro-menu__label">{{ node.label }}</span>
          <svg
            class="pro-menu__arrow"
            :class="{ 'pro-menu__arrow--open': expandedKeys.has(node.key) }"
            viewBox="0 0 24 24"
            width="14"
            height="14"
            aria-hidden="true"
          >
            <path fill="currentColor" d="M7 10l5 5 5-5z" />
          </svg>
        </button>

        <div v-show="expandedKeys.has(node.key)" class="pro-menu__sub">
          <ProMenu
            :menus="node.children"
            :active-key="activeKey"
            :level="level + 1"
            @select="handleSelect"
          />
        </div>
      </div>

      <!-- 叶子：导航项 -->
      <button
        v-else
        type="button"
        class="pro-menu__item"
        :class="{ 'pro-menu__item--active': activeKey === node.key }"
        :style="indentStyle"
        :title="node.label"
        role="menuitem"
        @click="handleSelect(node.key)"
      >
        <ProIcon v-if="node.icon" :name="node.icon" :size="16" class="pro-menu__icon" />
        <span class="pro-menu__label">{{ node.label }}</span>
      </button>
    </template>

    <div v-if="menus.length === 0" class="pro-menu__empty">暂无菜单</div>
  </div>
</template>

<style scoped>
/* 结构与交互态走 Token；不定义任何主题色（见类注释） */
.pro-menu {
  display: flex;
  flex-direction: column;
  padding: var(--wa-spacing-sm, 8px) 0;
  gap: 2px;
}

.pro-menu__item {
  display: flex;
  align-items: center;
  gap: var(--wa-spacing-sm, 8px);
  width: 100%;
  padding-top: 8px;
  padding-bottom: 8px;
  padding-right: var(--wa-spacing-md, 12px);
  border: none;
  border-radius: var(--wa-radius-sm, 4px);
  background: transparent;
  color: var(--wa-text-primary, #1f2329);
  font-size: var(--wa-font-size-md, 14px);
  text-align: left;
  cursor: pointer;
  white-space: nowrap;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.pro-menu__item:hover {
  background: var(--wa-bg-hover, #f5f7fa);
}

.pro-menu__item--parent {
  font-weight: 500;
}

/* 激活项在子树里时，分组标题给出弱提示（不强抢激活态） */
.pro-menu__item--trail {
  color: var(--wa-color-primary, #2563eb);
}

.pro-menu__item--active {
  background: var(--wa-color-primary-bg, #eef4ff);
  color: var(--wa-color-primary, #2563eb);
  font-weight: 500;
}

.pro-menu__label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.pro-menu__arrow {
  flex: none;
  color: var(--wa-text-tertiary, #8a939f);
  transition: transform 0.15s ease;
}

.pro-menu__arrow--open {
  transform: rotate(180deg);
}

.pro-menu__empty {
  padding: var(--wa-spacing-md, 12px);
  color: var(--wa-text-tertiary, #8a939f);
  font-size: var(--wa-font-size-sm, 12px);
  text-align: center;
}

/* ---- 折叠态 ---- */
.pro-menu--collapsed {
  align-items: center;
}

.pro-menu__item--collapsed {
  width: 40px;
  height: 40px;
  padding: 0;
  justify-content: center;
}

.pro-menu__glyph {
  font-size: var(--wa-font-size-md, 14px);
  font-weight: 600;
}

/* ---- 折叠态弹出浮层 ----
   NPopover 会把内容 teleport 到 body，但元素仍带本组件的 scoped 标记，
   因此这里的样式可以正常命中。配色走 Token，随主题切换。 */
.pro-menu__flyout {
  display: flex;
  flex-direction: column;
  min-width: 176px;
}

.pro-menu__flyout-title {
  padding: 6px 12px 4px;
  font-size: 12px;
  font-weight: 600;
  color: var(--wa-text-secondary, #5c6570);
}

.pro-menu__flyout-item {
  padding: 7px 12px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}

.pro-menu__flyout-item:hover {
  background: var(--wa-bg-hover, #f0f3f7);
}

.pro-menu__flyout-item--active {
  color: var(--wa-color-primary, #2563eb);
  font-weight: 600;
}
</style>
