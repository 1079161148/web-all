<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  NBreadcrumb,
  NBreadcrumbItem,
  NButton,
  NDrawer,
  NDrawerContent,
  NDropdown,
  NIcon,
  NLayout,
  NLayoutContent,
  NLayoutHeader,
  NLayoutSider,
  NScrollbar,
  NTab,
  NTabs
} from 'naive-ui'
import type { DropdownOption } from 'naive-ui'
import type { ProLayoutMenu, ProLayoutTab, ProLayoutTabAction } from '../../types'
import ProMenu from './ProMenu.vue'

/**
 * 后台布局骨架（左侧菜单 + 顶部面包屑）。
 *
 * <pre>{@code
 * <ProLayout
 *   :menus="menus" :active-key="route.path" :tabs="tabs"
 *   @select="router.push" @tab-close="closeTab"
 * >
 *   <router-view />
 * </ProLayout>
 * }</pre>
 *
 * <h3>唯一的布局形态：菜单全在左侧栏，顶栏只放面包屑与操作区</h3>
 * 菜单的多级展开全部由侧栏内的递归组件 ProMenu 承载，顶栏<b>不渲染菜单</b>。
 * 曾经支持过 vertical / horizontal / mix 三种模式，实测业务后砍掉了：
 * 多一套模式就多一份"菜单取哪一段"的分支逻辑（mix 要拆一级/二级、
 * horizontal 要下拉映射），而项目实际只用左侧形态 ——
 * <b>按真实需求裁剪，而不是保留一个"看起来更通用"的 prop。</b>
 *
 * <h3>⚠️ 为什么不做成"从 store 读菜单"</h3>
 * 本包禁止依赖业务状态（见 {@code registry/*.ts} 的说明）。
 * 布局需要菜单、当前路径、导航动作，这三者都由<b>调用方通过 props 与事件</b>提供：
 * <ul>
 *   <li>{@code menus} —— 菜单树（从哪来由应用决定）</li>
 *   <li>{@code activeKey} —— 当前激活项（通常就是路由 path）</li>
 *   <li>{@code @select} —— 用户选择了哪一项（<b>导航由应用执行</b>）</li>
 * </ul>
 * 好处是布局可以在没有路由的环境里工作（story、单页嵌入），
 * 且换成 hash 路由 / 多标签不同导航策略时都不需要改组件。
 *
 * <h3>⚠️ 颜色不在这里定义</h3>
 * 深色侧边栏、品牌色顶栏这些属于<b>主题</b>，由 {@code AdminConfigProvider}
 * 与 Token 决定。本组件只负责结构与尺寸，样式全部引用 Token
 * （{@code ui-component-policy} 强行约束第 5 条）。因此浅色/深色皮肤
 * 不需要改这个文件。
 */

/**
 * 菜单布局模式。
 *
 * <ul>
 *   <li>{@code vertical} —— 菜单在左侧栏（默认；层级深、窄屏友好）</li>
 *   <li>{@code horizontal} —— 菜单全在顶栏（横向一级 + 悬停下拉子级），内容区获得全部宽度</li>
 *   <li>{@code mix} —— 顶栏放一级菜单、侧栏跟着显示当前一级的子菜单（层级深且要省垂直空间时最好用）</li>
 * </ul>
 *
 * <p>⚠️ 早前版本砍掉过多布局模式，理由是"多一套模式就多一份菜单取哪一段的分支"。
 * 现在重新引入，是因为需求确实出现了（对外演示要在三种形态间切换），
 * 且这次把分支收敛在 {@link sideMenus} 一个 computed 里 —— 依然是"按真实需求裁剪"，
 * 只是需求变了。
 */
export type ProLayoutMode = 'vertical' | 'horizontal' | 'mix'

/**
 * 页签风格。
 *
 * <p>前三种对齐主流中台；后两种是视觉增强风格，用于演示/大屏等需要冲击力的场景：
 * <ul>
 *   <li>{@code smart} 灵动：无边框，激活项浅底高亮 + 底部指示条</li>
 *   <li>{@code card} 卡片：经典卡片标签（与升级前表现一致）</li>
 *   <li>{@code google} 谷歌：顶圆角、粘连式，类似浏览器标签</li>
 *   <li>{@code neon} 霓虹：渐变描边 + 外发光（暗色主题下最出彩）</li>
 *   <li>{@code pill} 胶囊：圆角胶囊 + 渐变填充</li>
 * </ul>
 */
export type ProLayoutTabStyle = 'smart' | 'card' | 'google' | 'neon' | 'pill'

const props = withDefaults(
  defineProps<{
    /** 菜单树。 */
    menus?: ProLayoutMenu[]
    /** 当前激活项的 key（通常是路由完整路径）。 */
    activeKey?: string
    /** 折叠状态（v-model:collapsed）。 */
    collapsed?: boolean
    /** 标题（无 logo 插槽时显示在 logo 位）。 */
    title?: string
    /** 是否显示面包屑。 */
    showBreadcrumb?: boolean
    /** 是否显示标签栏。 */
    showTabs?: boolean
    /** 页签数据（由应用维护）。 */
    tabs?: ProLayoutTab[]
    /** 侧边栏宽度。 */
    siderWidth?: number
    /** 折叠后的宽度。 */
    collapsedWidth?: number
    /** 移动端断点（≤ 该宽度时改用抽屉承载菜单）。 */
    mobileBreakpoint?: number
    /** 整块布局的高度。默认占满视口。 */
    height?: string
    /** 内容区内边距。 */
    contentPadding?: string
    /** 菜单布局模式。 */
    layoutMode?: ProLayoutMode
    /** 页签风格。 */
    tabStyle?: ProLayoutTabStyle
    /** 是否显示 Logo 区。 */
    showLogo?: boolean
    /** 是否显示页脚。 */
    showFooter?: boolean
    /** 页脚文字（无 footer 插槽时使用）。 */
    footerText?: string
    /**
     * 头部与页签栏是否固定在顶部、不随内容滚动。
     *
     * <p>默认固定。关闭后头部会跟着内容一起滚走 —— 适用于内容很长的
     * 报告/大屏类页面（用户希望"一屏到底"而不是被两行固定条占住）。
     */
    fixedHeader?: boolean
    /**
     * 内容区最大宽度（数字按 px）。
     *
     * <p>给出后内容区居中并限宽。宽屏上这是可读性的关键：
     * 一行 200 个字符的表格/正文，眼睛很难跟行。传值即生效，不传则不限宽。
     */
    maxContentWidth?: number | string
  }>(),
  {
    menus: () => [],
    activeKey: '',
    collapsed: false,
    title: '',
    showBreadcrumb: true,
    showTabs: true,
    tabs: () => [],
    siderWidth: 220,
    collapsedWidth: 64,
    mobileBreakpoint: 768,
    height: '100vh',
    contentPadding: '16px',
    layoutMode: 'vertical',
    tabStyle: 'card',
    showLogo: true,
    showFooter: false,
    footerText: '',
    fixedHeader: true,
    maxContentWidth: ''
  }
)

const emit = defineEmits<{
  (e: 'update:collapsed', collapsed: boolean): void
  /** 用户选择了某个菜单项。<b>导航由应用执行</b>，组件不碰路由。 */
  (e: 'select', key: string): void
  /** 用户点击了某个页签。 */
  (e: 'tab-select', key: string): void
  /** 用户关闭了某个页签。affix 页签不会触发。 */
  (e: 'tab-close', key: string): void
  /**
   * 用户对某个页签执行了右键动作（刷新 / 关闭 / 关闭其它…）。
   * 动作的语义由这里定义，<b>执行在应用侧</b> —— 刷新与导航都碰路由，内核不碰。
   */
  (e: 'tab-action', key: string, action: ProLayoutTabAction): void
}>()

// ---------------------------------------------------------------------
// 菜单树查询
// ---------------------------------------------------------------------

/**
 * 从根到目标节点的完整链路。
 *
 * <p>面包屑靠它渲染"根 → 当前页"的祖先链。
 * 找不到时返回 null（而不是空数组）—— 区分"没有这条路径"与"路径为空"，
 * 前者通常意味着 activeKey 与菜单对不上（例如详情页不在菜单里），
 * 此时不该显示一个空面包屑。
 */
function findPath(
  nodes: ProLayoutMenu[],
  key: string,
  trail: ProLayoutMenu[] = []
): ProLayoutMenu[] | null {
  for (const node of nodes) {
    const next = [...trail, node]
    if (node.key === key) {
      return next
    }
    if (node.children?.length) {
      const found = findPath(node.children, key, next)
      if (found) {
        return found
      }
    }
  }
  return null
}

const activePath = computed(() => findPath(props.menus, props.activeKey) ?? [])

/** 面包屑：当前节点的祖先链 + 自身。 */
const breadcrumb = computed(() => activePath.value)

// ---------------------------------------------------------------------
// 选择
// ---------------------------------------------------------------------

function handleSelect(key: string): void {
  emit('select', key)
  if (isMobile.value) {
    drawerVisible.value = false
  }
}

// ---------------------------------------------------------------------
// 页签右键菜单
// ---------------------------------------------------------------------

/**
 * 右键菜单状态。
 *
 * <p>用 manual 触发的 n-dropdown 并记录鼠标坐标 —— 内核的下拉
 * 只能挂在触发元素上，而页签在滚动容器里，挂载定位会跟着滚动跑偏；
 * 跟随鼠标坐标是标签栏右键菜单的标准形态（pure-admin 同款）。
 */
const tabMenu = reactive({ visible: false, x: 0, y: 0, key: '' })

function openTabMenu(tab: ProLayoutTab, event: MouseEvent): void {
  tabMenu.visible = true
  tabMenu.x = event.clientX
  tabMenu.y = event.clientY
  tabMenu.key = tab.key
}

function closeTabMenu(): void {
  tabMenu.visible = false
}

/**
 * 菜单项的禁用状态按当前页签位置计算。
 * 禁用而非隐藏：用户需要知道"这里有这个能力，只是当前不可用"，
 * 与 ProCommand 对禁用命令的处理是同一个原则。
 */
const tabMenuOptions = computed<DropdownOption[]>(() => {
  const index = props.tabs.findIndex((tab) => tab.key === tabMenu.key)
  const target = props.tabs[index]
  return [
    { key: 'refresh', label: '刷新页面' },
    { key: 'close', label: '关闭', disabled: !target || target.affix },
    { key: 'close-others', label: '关闭其他' },
    { key: 'close-left', label: '关闭左侧', disabled: index <= 0 },
    {
      key: 'close-right',
      label: '关闭右侧',
      disabled: index < 0 || index >= props.tabs.length - 1
    },
    { type: 'divider', key: 'divider' },
    { key: 'close-all', label: '全部关闭' }
  ]
})

function handleTabMenuSelect(action: string): void {
  closeTabMenu()
  emit('tab-action', tabMenu.key, action as ProLayoutTabAction)
}

// ---------------------------------------------------------------------
// 移动端
// ---------------------------------------------------------------------

const isMobile = ref(false)
const drawerVisible = ref(false)

let mediaQuery: MediaQueryList | null = null

function handleMediaChange(event: MediaQueryList | MediaQueryListEvent): void {
  isMobile.value = event.matches
  if (!event.matches) {
    drawerVisible.value = false
  }
}

onMounted(() => {
  if (typeof window === 'undefined' || !window.matchMedia) {
    return
  }
  mediaQuery = window.matchMedia(`(max-width: ${props.mobileBreakpoint}px)`)
  handleMediaChange(mediaQuery)
  mediaQuery.addEventListener('change', handleMediaChange)
})

onBeforeUnmount(() => {
  // 不解绑会在组件销毁后继续写 ref（HMR 下反复挂载/卸载时尤其明显：
  // 监听器越积越多，表现为"改一次窗口大小，回调被执行很多次"）
  mediaQuery?.removeEventListener('change', handleMediaChange)
})

/** 侧边栏是否显示为常驻栏（移动端一律改用抽屉）。 */
const showSider = computed(() => !isMobile.value)

// ---------------------------------------------------------------------
// 布局模式（vertical / horizontal / mix）
// ---------------------------------------------------------------------

/** 顶栏是否承载菜单（horizontal 与 mix 两种情况）。 */
const isTopMenu = computed(() => props.layoutMode !== 'vertical')

/**
 * 侧栏要渲染的菜单。
 *
 * <p>这是三种布局模式**唯一**的分支点（其余模板都是同一份）：
 * <ul>
 *   <li>vertical / horizontal：原样渲染整棵树（horizontal 时侧栏本来就不渲染）</li>
 *   <li>mix：只渲染"当前一级菜单"下的子级；若该一级没有子级，则回退渲染整棵树
 *       ——否则会出现"点了一级，侧栏空了"的惊悚画面</li>
 * </ul>
 */
/** mix 模式下当前选中的一级菜单 key（先于 sideMenus 声明，后者依赖它）。 */
const activeTopKey = ref('')

const sideMenus = computed<ProLayoutMenu[]>(() => {
  if (props.layoutMode !== 'mix') {
    return props.menus
  }
  const top = props.menus.find((node) => node.key === activeTopKey.value)
  return top?.children?.length ? top.children : props.menus
})

/**
 * 激活项变化 → 同步顶栏一级高亮。
 *
 * <p>用"从根到当前页的祖先链"推导而不是比对 key 前缀：菜单 key 是路径，
 * 前缀相同不代表同一分支（如 /ai 与 /ai-chat 前缀相近但不同支），
 * 而 {@link activePath} 是真实的树结构链路，推导结果一定正确。
 */
watch(
  [() => props.activeKey, () => props.menus],
  () => {
    if (!isTopMenu.value) {
      return
    }
    const trail = activePath.value
    if (trail.length > 0) {
      activeTopKey.value = trail[0]!.key
    }
  },
  { immediate: true }
)

/** 顶栏一级项被点击：有子级则只切换（mix 靠侧栏跟随），叶子直接导航。 */
function handleTopSelect(node: ProLayoutMenu): void {
  if (node.children?.length) {
    activeTopKey.value = node.key
    return
  }
  handleSelect(node.key)
}

/** 顶栏一级项是否处于激活/所在分支。 */
function isTopActive(node: ProLayoutMenu): boolean {
  return (
    node.key === activeTopKey.value ||
    (node.children?.length ? subtreeHasInMenu(node.children, props.activeKey) : false)
  )
}

/**
 * 子树包含判断（顶栏用）。
 *
 * <p>与 ProMenu 内部的 {@code subtreeHas} 是同一算法，但那个函数是 ProMenu 的私有实现。
 * 这里刻意不去抽公共函数：它只有 8 行，而跨组件抽公共工具会让两个组件的
 * 演进互相牵扯（顶栏要处理的是"一级"，侧栏要处理的是"任意层"）。
 */
function subtreeHasInMenu(nodes: ProLayoutMenu[], key: string): boolean {
  for (const node of nodes) {
    if (node.key === key) {
      return true
    }
    if (node.children?.length && subtreeHasInMenu(node.children, key)) {
      return true
    }
  }
  return false
}

/** 子级 → 下拉项（horizontal 模式悬停展开用）。 */
function toDropdownOptions(nodes: ProLayoutMenu[]): DropdownOption[] {
  return nodes.map((node) => ({
    key: node.key,
    label: node.label,
    children: node.children?.length ? toDropdownOptions(node.children) : undefined
  }))
}

// ---------------------------------------------------------------------
// 页签风格 / 页宽 / 页脚
// ---------------------------------------------------------------------

/**
 * 页签风格 → Naive 的基础类型。
 *
 * <p>只用 line / card 两种底座，再用 CSS 做出五种差异：
 * 换一套 DOM 结构做风格等于把 Naive 的交互（滚动、关闭按钮、键盘导航）重写一遍，
 * 而那才是标签栏真正难的部分。
 */
const tabType = computed<'line' | 'card'>(() => {
  return props.tabStyle === 'smart' || props.tabStyle === 'pill' ? 'line' : 'card'
})

/** 内容区样式：内边距 + 可选限宽居中。 */
const contentStyle = computed<Record<string, string>>(() => {
  const style: Record<string, string> = { padding: props.contentPadding }
  if (props.maxContentWidth !== '' && props.maxContentWidth !== undefined) {
    const width =
      typeof props.maxContentWidth === 'number'
        ? `${props.maxContentWidth}px`
        : props.maxContentWidth
    style.maxWidth = width
    style.width = '100%'
    style.marginLeft = 'auto'
    style.marginRight = 'auto'
  }
  return style
})

function handleCollapseUpdate(value: boolean): void {
  emit('update:collapsed', value)
}
</script>

<template>
  <n-layout class="pro-layout" :style="{ height }">
    <n-layout has-sider class="pro-layout__body">
      <!-- ============ 左侧栏（菜单，含多级） ============ -->
      <!-- 横向模式没有侧栏：菜单全在顶栏 -->
      <n-layout-sider
        v-if="showSider && layoutMode !== 'horizontal'"
        bordered
        collapse-mode="width"
        :width="siderWidth"
        :collapsed-width="collapsedWidth"
        :collapsed="collapsed"
        :native-scrollbar="false"
        @update:collapsed="handleCollapseUpdate"
      >
        <div
          v-if="showLogo"
          class="pro-layout__logo"
          :class="{ 'pro-layout__logo--collapsed': collapsed }"
        >
          <slot name="logo" :collapsed="collapsed">
            <span v-if="!collapsed" class="pro-layout__title">{{ title }}</span>
          </slot>
        </div>

        <!--
          隐藏 Logo 后菜单要顶上：sider-scroll 的高度是按"减去 logo 区"算的，
          不减掉会顶部空出一条（实测表现为"关了 Logo 菜单不贴顶"）。
        -->
        <n-scrollbar
          class="pro-layout__sider-scroll"
          :class="{ 'pro-layout__sider-scroll--nologo': !showLogo }"
        >
          <!-- 侧栏菜单由递归组件 ProMenu 渲染；mix 模式下这里只渲染当前一级的子级（见 sideMenus） -->
          <ProMenu
            :menus="sideMenus"
            :active-key="activeKey"
            :collapsed="collapsed"
            @select="handleSelect"
          />
        </n-scrollbar>
      </n-layout-sider>

      <!-- ============ 主区 ============ -->
      <!--
        fixedHeader = false 时整块主区变成一个滚动容器，头部与页签跟着内容滚走。
        这是刻意用"同一个 DOM、两套布局"而不是复制一份模板：
        复制会让 header/tabs 的每个后续改动都要记得改两处。
      -->
      <n-layout
        class="pro-layout__main"
        :class="{ 'pro-layout__main--flow': !fixedHeader }"
      >
        <n-layout-header bordered class="pro-layout__header">
          <!-- 顶栏模式（horizontal / mix）：Logo 移到顶栏左侧 -->
          <div v-if="isTopMenu && showLogo" class="pro-layout__logo pro-layout__logo--top">
            <slot name="logo" :collapsed="false">
              <span class="pro-layout__title">{{ title }}</span>
            </slot>
          </div>

          <!-- 折叠按钮：有常驻侧栏时出现（横向模式没有侧栏，折叠无意义） -->
          <n-button
            v-if="showSider && layoutMode !== 'horizontal'"
            quaternary
            size="small"
            class="pro-layout__collapse"
            @click="emit('update:collapsed', !collapsed)"
          >
            <template #icon>
              <n-icon>
                <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
                  <path
                    fill="currentColor"
                    d="M3 5h18v2H3V5zm0 6h18v2H3v-2zm0 6h18v2H3v-2z"
                  />
                </svg>
              </n-icon>
            </template>
          </n-button>

          <!-- 移动端：用抽屉承载菜单 -->
          <n-button
            v-if="isMobile"
            quaternary
            size="small"
            class="pro-layout__collapse"
            @click="drawerVisible = true"
          >
            <template #icon>
              <n-icon>
                <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
                  <path
                    fill="currentColor"
                    d="M3 5h18v2H3V5zm0 6h18v2H3v-2zm0 6h18v2H3v-2z"
                  />
                </svg>
              </n-icon>
            </template>
          </n-button>

          <!--
            顶栏菜单（horizontal / mix 模式）。
            ⚠️ 这里刻意不复用 ProMenu：那是纵向结构（缩进 / 展开箭头 / 任意层级），
            横向需要另一套视觉与交互。横向只处理"一级"，子级交给下拉（horizontal）
            或侧栏（mix），因此不需要递归 —— 少一层递归就少一类边界。
          -->
          <nav v-if="isTopMenu" class="pro-layout__topmenu">
            <template v-for="node in menus" :key="node.key">
              <!-- 有子级：悬停下拉（只横向模式；mix 的子级在侧栏） -->
              <n-dropdown
                v-if="node.children?.length && layoutMode === 'horizontal'"
                trigger="hover"
                placement="bottom-start"
                :options="toDropdownOptions(node.children)"
                @select="handleSelect"
              >
                <button
                  type="button"
                  class="pro-layout__topmenu-item"
                  :class="{ 'pro-layout__topmenu-item--active': isTopActive(node) }"
                >
                  {{ node.label }}
                </button>
              </n-dropdown>

              <!-- 叶子项 / mix 的一级项：点击 -->
              <button
                v-else
                type="button"
                class="pro-layout__topmenu-item"
                :class="{ 'pro-layout__topmenu-item--active': isTopActive(node) }"
                @click="handleTopSelect(node)"
              >
                {{ node.label }}
              </button>
            </template>
          </nav>

          <!-- 侧栏模式下的面包屑（顶栏被菜单占用时不与菜单抢位置） -->
          <n-breadcrumb
            v-else-if="showBreadcrumb && breadcrumb.length"
            class="pro-layout__breadcrumb"
          >
            <n-breadcrumb-item v-for="node in breadcrumb" :key="node.key">
              {{ node.label }}
            </n-breadcrumb-item>
          </n-breadcrumb>

          <!-- 右侧操作区 -->
          <div class="pro-layout__actions">
            <slot name="actions" />
          </div>
        </n-layout-header>

        <!--
          标签栏。

          ⚠️ 必须用 `n-tab` 而不是 `n-tab-pane`（实测踩过，浏览器 DOM 取证）：
          n-tab-pane 是「内容面板」组件 —— Naive 把它转成标签时优先取它的
          #tab <b>插槽</b>；在「自闭合、没有任何插槽」的写法下，
          一个空的默认插槽会盖掉 prop 传入的 :tab，
          实测渲染结果是 `n-tabs-tab__label` 里只有一个注释节点 ——
          标签栏表现为一排只有关闭按钮的空壳。
          n-tab 是纯导航型标签（没有内容面板），label 正常渲染，
          且不会渲染多余的 pane 容器，本就是标签栏-only 场景的正确组件。
        -->
        <div
          v-if="showTabs && tabs.length"
          class="pro-layout__tabs"
          :class="`pro-layout__tabs--${tabStyle}`"
        >
          <n-tabs
            :value="activeKey"
            :type="tabType"
            size="small"
            :tabs-padding="0"
            @update:value="(key: string) => emit('tab-select', key)"
            @close="(key: string) => emit('tab-close', key)"
          >
            <n-tab
              v-for="tab in tabs"
              :key="tab.key"
              :name="tab.key"
              :tab="tab.label"
              :closable="!tab.affix"
              @contextmenu.prevent="openTabMenu(tab, $event)"
            />
          </n-tabs>

          <!-- 右键菜单：manual + 跟随鼠标坐标，见 tabMenu 的说明 -->
          <n-dropdown
            trigger="manual"
            placement="bottom-start"
            :show="tabMenu.visible"
            :x="tabMenu.x"
            :y="tabMenu.y"
            :options="tabMenuOptions"
            @select="handleTabMenuSelect"
            @clickoutside="closeTabMenu"
          />
        </div>

        <n-layout-content
          class="pro-layout__content"
          :native-scrollbar="!fixedHeader"
        >
          <!--
            限宽/内边距包在自有的一层上，而不是用 n-layout-content 的
            content-style：固定头部模式下 naive 会把 content-style 挂到
            .n-scrollbar-content（它自带内联 min-width:100%，而 min-width
            优先级高于 max-width），导致 maxContentWidth 限宽静默失效
            （实测：fixed 模式内容 1700px 全宽 vs flow 模式 1600px）——
            这正是"头部固定配置影响页面宽度"的根因。挂到自己的 div 上，
            两种滚动模式的表现就完全一致了。
          -->
          <div class="pro-layout__content-inner" :style="contentStyle">
            <slot />
          </div>
        </n-layout-content>

        <!-- 页脚：默认不渲染（中台通常不需要），开启时优先用插槽 -->
        <footer v-if="showFooter" class="pro-layout__footer">
          <slot name="footer">{{ footerText }}</slot>
        </footer>
      </n-layout>
    </n-layout>

    <!-- ============ 移动端抽屉 ============ -->
    <n-drawer v-model:show="drawerVisible" :width="siderWidth" placement="left">
      <n-drawer-content :title="title" closable body-content-style="padding: 0">
        <ProMenu :menus="menus" :active-key="activeKey" @select="handleSelect" />
      </n-drawer-content>
    </n-drawer>
  </n-layout>
</template>

<style scoped>
/*
  只做结构与尺寸，不定义配色（配色属于主题，见类注释）。
  所有尺寸/颜色引用 Token，无一处硬编码（ui-component-policy 强行约束第 5 条）。
*/
.pro-layout {
  --pro-layout-header-height: 56px;
}

.pro-layout__body {
  height: 100%;
}

.pro-layout__main {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

/*
  ⚠️ 固定头部的关键（浏览器 DOM 取证后修复）：

  naive 的 n-layout 会把所有子元素包进一层 .n-layout-scroll-container
  （实测样式：display:block + overflow-y:auto）。我们写在 .pro-layout__main
  上的 flex 布局只作用于这一个包装层，header/tabs/content 全在它里面
  自然堆叠 —— 长内容把它撑到几千像素，滚动发生在它身上，
  头部与页签随之滚走，这就是"固定头部"失效的根因
  （上一版只测了根元素 scrollHeight，没测到这一层，被自己骗了）。

  修复：把 flex 列布局**下沉**到 scroll-container，并禁止它自己滚动 ——
  这样 content 的 flex:1 + min-height:0 才真正生效，滚动回到内容区内部。
*/
.pro-layout__main:not(.pro-layout__main--flow) > :deep(.n-layout-scroll-container) {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.pro-layout__header {
  display: flex;
  align-items: center;
  gap: var(--wa-spacing-md);
  height: var(--pro-layout-header-height);
  flex: none;
  padding: 0 var(--wa-spacing-lg);
}

.pro-layout__collapse {
  flex: none;
}

.pro-layout__logo {
  display: flex;
  align-items: center;
  justify-content: center;
  height: var(--pro-layout-header-height);
  flex: none;
  overflow: hidden;
}

.pro-layout__title {
  font-size: var(--wa-font-size-md);
  font-weight: 600;
  white-space: nowrap;
}

.pro-layout__breadcrumb {
  flex: none;
}

.pro-layout__actions {
  margin-left: auto;
  flex: none;
  display: flex;
  align-items: center;
  gap: var(--wa-spacing-sm);
}

.pro-layout__sider-scroll {
  height: calc(100% - var(--pro-layout-header-height));
}

.pro-layout__tabs {
  flex: none;
  padding: var(--wa-spacing-sm) var(--wa-spacing-lg) 0;
  border-bottom: 1px solid var(--wa-border);
}

.pro-layout__content {
  flex: 1;
  min-height: 0;
}

/* 限宽/内边距的自有层：不经过 naive 的滚动内容节点，
   保证 fixedHeader 两种模式下宽度行为一致（见模板内注释）。
   ⚠️ 必须 border-box：否则 width:100% + 左右 padding 会在容器之外
   再多出两个 padding 的宽度（实测溢出 32px），横向滚动一发生，
   右侧 padding 被卷出滚动区 —— 表现为"左边有间距、右边没有"。 */
.pro-layout__content-inner {
  width: 100%;
  box-sizing: border-box;
}

/* ---- 顶栏模式：Logo 在顶栏左侧 ---- */
.pro-layout__logo--top {
  justify-content: flex-start;
  width: auto;
  padding-right: var(--wa-spacing-md, 12px);
}

.pro-layout__topmenu {
  display: flex;
  align-items: center;
  gap: var(--wa-spacing-xs, 4px);
  min-width: 0;
  overflow: hidden;
}

.pro-layout__topmenu-item {
  position: relative;
  height: 32px;
  padding: 0 var(--wa-spacing-md, 12px);
  border: none;
  border-radius: var(--wa-radius-sm, 4px);
  background: transparent;
  color: var(--wa-text-primary, #1f2329);
  font-size: var(--wa-font-size-md, 14px);
  white-space: nowrap;
  cursor: pointer;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.pro-layout__topmenu-item:hover {
  background: var(--wa-bg-hover, #f5f7fa);
}

.pro-layout__topmenu-item--active {
  color: var(--wa-color-primary, #2563eb);
  font-weight: 500;
}

/* 激活指示条：横向模式没有侧栏，需要一条明确的"我在哪" */
.pro-layout__topmenu-item--active::after {
  content: '';
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: 2px;
  height: 2px;
  border-radius: 2px;
  background: var(--wa-color-primary, #2563eb);
}

/* =====================================================================
   页签风格
   =====================================================================
   五种风格共用 line / card 两种底座，差异全部由 CSS 完成（见 tabType 的说明）。
   全部走 Token 取色，深色主题下自动适配。
*/

/* 灵动：无边框 + 浅底高亮 + 底部指示条 */
.pro-layout__tabs--smart {
  padding-top: 0;
}

.pro-layout__tabs--smart :deep(.n-tabs-nav) {
  padding: 0 var(--wa-spacing-lg, 16px);
}

.pro-layout__tabs--smart :deep(.n-tabs-tab) {
  padding: var(--wa-spacing-sm, 8px) var(--wa-spacing-md, 12px);
  border-radius: var(--wa-radius-sm, 4px) var(--wa-radius-sm, 4px) 0 0;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.pro-layout__tabs--smart :deep(.n-tabs-tab:hover) {
  background: var(--wa-bg-hover, #f5f7fa);
}

.pro-layout__tabs--smart :deep(.n-tabs-tab.n-tabs-tab--active) {
  background: var(--wa-color-primary-bg, #eef4ff);
  color: var(--wa-color-primary, #2563eb);
  font-weight: 500;
}

.pro-layout__tabs--smart :deep(.n-tabs-bar) {
  height: 2px;
}

/*
  谷歌：浏览器标签式 —— 顶圆角 + 激活项浅底高亮 + 底部与内容区连通（去掉底边）+ 相邻粘连。

  ⚠️ 形状与配色优先通过 **naive 自己的 CSS 变量**定制（--n-tab-border-radius /
  --n-tab-color 等），而不是硬覆盖 border-radius / background。
  原因：naive 在内部多处引用这些变量（含 label、close 按钮的对齐），
  硬覆盖只能改到我们看得见的那一层，实测会出现"外框圆了、内部高亮块还是方角"
  这类半吊子效果。
*/
/*
  谷歌风格（对齐 pure-admin）：页签栏浅灰底，激活项白色"凸起"。
  凸起感来自与栏底色的对比 —— 栏底若是白色，白色激活标签就看不见了，
  所以栏底必须比激活标签深一档。
*/
.pro-layout__tabs--google {
  padding-top: var(--wa-spacing-sm, 8px);
  background: var(--wa-bg-hover, #f5f7fa);
}

.pro-layout__tabs--google :deep(.n-tabs) {
  --n-tab-border-radius: 10px 10px 0 0;
  --n-tab-padding: var(--wa-spacing-sm, 8px) var(--wa-spacing-md, 12px);
  --n-tab-color: transparent;
  --n-tab-text-color: var(--wa-text-secondary, #5c6570);
  --n-tab-text-color-active: var(--wa-color-primary, #2563eb);
  --n-tab-border-color: var(--wa-border);
}

/* 相邻标签贴合（浏览器标签是连着的，中间留缝就不像了） */
.pro-layout__tabs--google :deep(.n-tabs-tab) {
  position: relative;
  z-index: 0;
  margin: 0;
  border-bottom: none;
}

/*
  形状层 —— 逐字对齐 pure-admin 官方 TagChrome.vue 的实现：

  官方用一条 SVG path 画 Chrome 标签形状（顶角 8px 圆角 + 底部两侧
  向外翻的下凹弧），fill="currentColor"，激活时填浅主色、hover 填
  #dee1e6。我们把同一条 path 编码成 data-uri，用 CSS mask 上色 ——
  不增加 DOM，形状与官方完全一致。

  path（viewBox 0 0 214 36）：顶部两角 8px 圆弧 → 侧壁垂直 →
  底部从侧壁向外翻 9px 高的下凹曲线 → 底边 y=34（留 2px 给弧脚）。
*/
.pro-layout__tabs--google :deep(.n-tabs-tab:hover)::before,
.pro-layout__tabs--google :deep(.n-tabs-tab.n-tabs-tab--active)::before {
  content: '';
  position: absolute;
  inset: 0;
  z-index: -1;
  pointer-events: none;
  background: #dee1e6;
  -webkit-mask: url("data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 214 36' preserveAspectRatio='none'%3E%3Cpath d='M17,0H197a8,8,0,0,1,8,8V26c0,4.5,4,8,9,8H0c4.5,0,9-3.5,9-8V8a8,8,0,0,1,8-8Z'/%3E%3C/svg%3E")
    center / 100% 100% no-repeat;
  mask: url("data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 214 36' preserveAspectRatio='none'%3E%3Cpath d='M17,0H197a8,8,0,0,1,8,8V26c0,4.5,4,8,9,8H0c4.5,0,9-3.5,9-8V8a8,8,0,0,1,8-8Z'/%3E%3C/svg%3E")
    center / 100% 100% no-repeat;
}

.pro-layout__tabs--google :deep(.n-tabs-tab:hover) {
  background: transparent;
  color: #1f1f1f;
}

.pro-layout__tabs--google :deep(.n-tabs-tab.n-tabs-tab--active) {
  position: relative;
  z-index: 1;
  /* 下探 1px 压住栏底边线：否则 border-bottom 会横穿底部弧角 */
  margin-bottom: -1px;
  background: transparent;
  color: var(--wa-color-primary, #2563eb);
  border-color: transparent;
  box-shadow: 0 0 0.7px #888;
  font-weight: 500;
}

.pro-layout__tabs--google :deep(.n-tabs-tab.n-tabs-tab--active)::before {
  /*
    官方激活态用 primary-light-9（约 10% 主色），但实机观感上几乎
    融进栏底，用户感知是"选中没有样式" —— 形状不变，把浓度提到
    ~14%，让选中态与 hover（灰色形状）一眼可辨且同样可感知。
  */
  background: color-mix(in srgb, var(--wa-color-primary, #2563eb) 14%, #fff);
}

/*
  霓虹：激活项描边 + 外发光，非激活保持朴素（全都发光等于都不发光）。
  暗色主题下用主色做一层内发光，观感更接近霓虹灯管。
*/
.pro-layout__tabs--neon :deep(.n-tabs) {
  --n-tab-border-radius: 8px;
  --n-tab-padding: var(--wa-spacing-xs, 4px) var(--wa-spacing-md, 12px);
  --n-tab-color: transparent;
  --n-tab-text-color: var(--wa-text-secondary, #5c6570);
  --n-tab-text-color-active: var(--wa-color-primary, #2563eb);
  --n-tab-border-color: transparent;
}

.pro-layout__tabs--neon :deep(.n-tabs-tab) {
  margin-right: var(--wa-spacing-xs, 4px);
  border: 1px solid transparent;
  background: transparent;
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    color 0.18s ease,
    background-color 0.18s ease;
}

.pro-layout__tabs--neon :deep(.n-tabs-tab:hover) {
  border-color: var(--wa-border);
}

.pro-layout__tabs--neon :deep(.n-tabs-tab.n-tabs-tab--active) {
  border: 1px solid var(--wa-color-primary, #2563eb);
  background: var(--wa-bg-elevated, #fff);
  color: var(--wa-color-primary, #2563eb);
  font-weight: 600;
  box-shadow:
    0 0 0 1px var(--wa-color-primary, #2563eb),
    0 0 14px var(--wa-color-primary-bg, rgba(37, 99, 235, 0.35));
}

/* 暗色主题：发光要更实，浅色主题下过强的光晕会显脏 */
:global(html[data-theme='dark']) .pro-layout__tabs--neon :deep(.n-tabs-tab.n-tabs-tab--active) {
  background: rgba(37, 99, 235, 0.14);
  box-shadow:
    0 0 0 1px var(--wa-color-primary, #2563eb),
    0 0 18px rgba(37, 99, 235, 0.5);
}

/* 胶囊：圆角 + 渐变填充 */
.pro-layout__tabs--pill :deep(.n-tabs-nav) {
  padding: 0 var(--wa-spacing-lg, 16px);
}

.pro-layout__tabs--pill :deep(.n-tabs-tab) {
  margin: var(--wa-spacing-sm, 8px) var(--wa-spacing-xs, 4px) var(--wa-spacing-sm, 8px) 0;
  padding: var(--wa-spacing-xs, 4px) var(--wa-spacing-md, 12px);
  border-radius: 999px;
  transition: background 0.18s ease, color 0.18s ease, box-shadow 0.18s ease;
}

.pro-layout__tabs--pill :deep(.n-tabs-tab:hover) {
  background: var(--wa-bg-hover, #f5f7fa);
}

.pro-layout__tabs--pill :deep(.n-tabs-tab.n-tabs-tab--active) {
  /* 第二个色位刻意用固定青蓝：品牌色可被用户改成任意颜色，
     渐变若两端都取品牌色就会"看起来是纯色"，失去胶囊的层次感 */
  background: linear-gradient(135deg, var(--wa-color-primary, #2563eb), rgba(34, 211, 238, 0.9));
  color: #fff;
  font-weight: 500;
  box-shadow: 0 2px 10px rgba(37, 99, 235, 0.35);
}

.pro-layout__tabs--pill :deep(.n-tabs-tab.n-tabs-tab--active .n-tabs-tab__close) {
  color: #fff;
}

/* ---- 页脚 ---- */
.pro-layout__footer {
  flex: none;
  padding: var(--wa-spacing-md, 12px) var(--wa-spacing-lg, 16px);
  border-top: 1px solid var(--wa-border);
  color: var(--wa-text-tertiary, #8a939f);
  font-size: var(--wa-font-size-sm, 12px);
  text-align: center;
}

/*
  不固定头部：主区整体成为滚动容器，头部与页签随内容一起滚走。

  ⚠️ 关键是 display: block（实测踩过）。
  只在 flex 列容器上加 overflow-y: auto 是**不会滚动的** ——
  因为 flex 子项（content）带着 flex: 1 会被强行撑到容器高度，
  内容再长也只会在 content 内部滚动，外层永远没有可滚动的溢出，
  表现就是"设置了不固定头部、但没生效"。
  改成 block 后头部/页签/内容回到自然流，超出才由主区滚动。
*/
.pro-layout__main--flow {
  display: block;
  height: 100%;
  overflow-y: auto;
}

.pro-layout__main--flow .pro-layout__header,
.pro-layout__main--flow .pro-layout__tabs,
.pro-layout__main--flow .pro-layout__footer {
  flex: none;
}

.pro-layout__main--flow .pro-layout__content,
.pro-layout__main--flow :deep(.n-layout-scroll-container) {
  height: auto !important;
  min-height: 0 !important;
  flex: none !important;
  overflow: visible !important;
}

/* 隐藏 Logo 时侧栏菜单要贴顶（sider-scroll 默认减去了 logo 高度） */
.pro-layout__sider-scroll--nologo {
  height: 100%;
}
</style>
