<script setup lang="ts">
import { computed, defineAsyncComponent, h, nextTick, onMounted, ref, watch } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { NButton, NDropdown, NSpace, ProCommand, ProIcon, ProLayout, feedback } from '@admin/ui'
import type { ProLayoutMenu, ProLayoutTabAction } from '@admin/ui'
import { useCommands } from '@/composables/useCommands'
import { useAppStore } from '@/stores/app'
import logoSrc from '@/assets/images/logo.jpg'
import { useAuthStore } from '@/stores/auth'
import { usePermissionStore, type MenuNode } from '@/stores/permission'
import { ensureIconsRegistered } from '@/icons'
import { useTabsStore } from '@/stores/tabs'
import SessionDialog from './SessionDialog.vue'
import MessagePanel from './MessagePanel.vue'
import { useNotifications } from '@/composables/useNotifications'
import { useFullscreen } from '@/composables/useFullscreen'

/**
 * 配置抽屉懒加载：它只在用户点"配置"时才需要，
 * 静态引入会把整个面板（及其样式）塞进布局 chunk —— 首屏多背 1KB+。
 * 设置的"生效逻辑"在 store 里（启动即生效），这里的只是编辑界面。
 */
const SettingDrawer = defineAsyncComponent(() => import('./SettingDrawer.vue'))

// 在线会话弹窗：当前用户查看并注销自己的登录设备（见 SessionDialog）
const sessionsVisible = ref(false)

// 全局配置抽屉（主题模式 / 主题色 / 水印 / 灰色与色弱模式）
const settingsVisible = ref(false)

/**
 * 全屏切换。
 *
 * <p>{@code isFullscreen} 由浏览器事件驱动（见 {@link useFullscreen}）：
 * 用户按 Esc 退出全屏时图标会同步变回"进入全屏"。只在自己的 toggle 里
 * 翻转标志位会出现"图标说能退出、实际已不在全屏"的状态不一致 ——
 * 这是全屏按钮最常见的 bug。
 */
const { isFullscreen, toggle: toggleFullscreen } = useFullscreen()

async function onToggleFullscreen(): Promise<void> {
  const ok = await toggleFullscreen()
  if (!ok) {
    feedback.error('浏览器拒绝了全屏请求（可能受权限策略或非用户手势限制）')
  }
}

/** 全屏水印背景：SVG data URL（无第三方水印库 —— 一个旋转文本瓦片就够了）。
 *  文本取自 appStore（如工号/部门），防截图泄密的最后一道软防线。 */
const watermarkStyle = computed(() => {
  if (!appStore.watermarkEnabled) {
    return undefined
  }
  const text = appStore.watermarkText || '中台管理系统'
  const escaped = text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const svg =
    `<svg xmlns='http://www.w3.org/2000/svg' width='260' height='180'>` +
    `<text x='28' y='96' font-size='14' fill='rgba(128,128,128,0.12)' ` +
    `transform='rotate(-18 28 96)'>${escaped}</text></svg>`
  return { backgroundImage: `url("data:image/svg+xml,${encodeURIComponent(svg)}")` }
})

// 消息中心：未读角标 + SSE 实时推送（连接随布局建立，退出登录时由 useAuthStore 清理）
const messagePanelVisible = ref(false)
const { unread: unreadCount, connect: connectNotifications, disconnect: disconnectNotifications } =
  useNotifications()

onMounted(() => {
  void connectNotifications()
})

/**
 * 应用主布局（基于 @admin/ui 的 ProLayout）。
 *
 * <h3>本轮的变化：从"自建布局"改为"组合组件库的 ProLayout"</h3>
 * 之前这里自己用 Naive 的布局原子搭了一份，只覆盖侧边模式，
 * 并在注释里列明了未实现项（标签栏、面包屑、顶栏/混合模式）。
 * 现在这些东西由 {@code ProLayout} 提供，本文件只剩下<b>接线</b>：
 * <ul>
 *   <li>菜单：{@code permissionStore.menuTree}（后端驱动）→ ProLayoutMenu</li>
 *   <li>当前项：{@code route.path}（菜单 key 就是完整路径）</li>
 *   <li>导航：{@code @select} → {@code router.push}</li>
 *   <li>页签：{@code tabsStore}（访问过哪些页面）</li>
 *   <li>布局偏好：{@code appStore}（已持久化，刷新不丢）</li>
 * </ul>
 * <b>布局组件不认识路由、store、权限 —— 它只收发数据。</b>
 * 这正是它放在组件库而不是 app 里的前提。
 *
 * <h3>页签与缓存严格同步（keep-alive 已落地）</h3>
 * 历史上这里是"已知未做"项 —— 视图文件名全是 index.vue，隐式组件名
 * 全部相同，无法用 include 精确缓存。该障碍已由
 * {@code router/dynamic.ts} 的 {@code cacheNameOf}（按组件路径生成唯一名）
 * + {@code namedLoader}（loader 到达时写名）解决。
 * 现在的同步关系见下方 {@code cachedNames}：页签开着且路由声明 keepAlive
 * 才进 include，关页签即驱逐；「刷新」经 evictedNames 临时逐出。
 *
 * <h3>为什么不顺带接 ProCommand</h3>
 * 命令面板需要一份"可执行命令"的列表。菜单可以充当来源，
 * 但还需要把「命令 → 动作」这层映射定义出来（含权限过滤、快捷键约定），
 * 那是独立的一轮工作，混在这里做会让本轮无法验证。
 */

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const authStore = useAuthStore()
const permissionStore = usePermissionStore()
const tabsStore = useTabsStore()

// 登出后停止 SSE 重连并清零角标。
// ⚠️ 这个 watch 必须位于 authStore 声明之后：watch 的 getter 在注册时
// 会<b>立即求值一次</b>（建立初始值与依赖追踪），而不是等到回调执行 ——
// 之前它被放在 authStore 之前并"用函数包裹"，结果注册瞬间就触发
// TDZ（Cannot access 'authStore' before initialization，控制台实测）。
// "推迟读取"的正确手段是把 getter 写成 () => authStore.xxx，
// 但位置仍须在声明之后 —— 函数包裹解决的是回调时机，不是求值时机。
watch(
  () => authStore.isAuthenticated,
  (authenticated) => {
    if (!authenticated) {
      disconnectNotifications()
    }
  }
)

/** 后端菜单树 → 布局菜单。key 直接用完整路径：它同时是标识与导航目标。 */
function toLayoutMenus(nodes: MenuNode[]): ProLayoutMenu[] {
  return nodes.map((node) => ({
    key: node.path,
    label: node.label,
    // 后端存的图标名（字符串）：由 ProIcon 经注册表解析；
    // 应用未注册图标集时降级为首字形，不会静默空白
    icon: node.icon ?? undefined,
    children: node.children.length > 0 ? toLayoutMenus(node.children) : undefined
  }))
}

const menus = computed<ProLayoutMenu[]>(() => toLayoutMenus(permissionStore.menuTree))

/*
  图标兜底：菜单的 icon 来自数据库，可能是首屏常用集之外的图标。
  这里只在"真的出现了未注册的名字"时才去载入整包图标 ——
  既保证侧栏不会长期停在占位字形，又不必让首屏为整包图标买单
  （设计 §12.1 的首屏体积预算，见 @/icons 的说明）。

  注册表是响应式的：整包到达后，已渲染的图标会自动补上，无需刷新页面。
*/
watch(
  () => permissionStore.menus,
  (menusFromApi) => {
    void ensureIconsRegistered((menusFromApi ?? []).map((menu) => menu.icon))
  },
  { immediate: true }
)

/**
 * 当前高亮项。
 *
 * <p>用 {@code route.path} 而不是 {@code route.name}：菜单 key 就是完整路径，
 * 而动态路由的 name 是 {@code menu-{id}}（注册时才知道）。
 * 用路径匹配就不必在菜单与路由之间再维护一层 ID 映射。
 */
const activeKey = computed(() => route.path)

// 路由变化 → 记录页签。登录页与公开页不入标签栏
watch(
  () => route.path,
  () => {
    if (route.meta.public === true) {
      return
    }
    tabsStore.openTab({
      key: route.path,
      label: (route.meta.title as string | undefined) ?? route.path
    })
  },
  { immediate: true }
)

/**
 * 需要缓存的组件名（keep-alive 的 include）。
 *
 * <h3>这是「标签栏与缓存严格同步」的落点</h3>
 * 取的是「页签仍开着 <b>且</b> 该路由声明了 {@code keepAlive}」的组件名。
 * 于是<b>关掉页签就等于把它从 include 里移除</b>，
 * 而 keep-alive 会监听 include 变化并主动销毁对应实例。
 *
 * <p>关键点：我们<b>没有</b>在关页签的代码里去手动清缓存 ——
 * 那种"两处状态各改一次"的写法迟早会漏（比如以后加了"关闭其它页签"、
 * "退出登录清空页签"等入口）。这里让<b>两者读同一份状态</b>，
 * 同步关系由结构保证，而不是靠每一处都记得改。
 *
 * <p>名字来自路由的 {@code meta.cacheName}，由 {@code router/dynamic.ts} 在注册时生成
 * （视图文件名全是 {@code index.vue}，隐式组件名会全部相同，必须显式命名）。
 */
/**
 * 被「刷新」动作临时逐出缓存的组件名。
 *
 * <p>keep-alive 缓存的是组件实例：只做 v-if 重挂，拿回来的仍是<b>缓存的旧实例</b>，
 * 页面数据一点都不会变 —— 对缓存页来说"刷新"就白点了。
 * 正确顺序是：先把名字移出 include（keep-alive 会主动销毁对应实例），
 * 再重挂，最后恢复 include 让新实例重新进入缓存。
 */
const evictedNames = ref(new Set<string>())

/** 内容区的挂载开关，「刷新当前页」用。 */
const showView = ref(true)

const cachedNames = computed(() => {
  const names: string[] = []
  for (const tab of tabsStore.tabs) {
    const meta = router.resolve(tab.key).meta as { keepAlive?: boolean; cacheName?: string }
    if (meta.keepAlive === true && meta.cacheName && !evictedNames.value.has(meta.cacheName)) {
      names.push(meta.cacheName)
    }
  }
  return names
})

/**
 * 刷新一个页签对应的页面。
 *
 * <p>刷新<b>非当前页</b>时只需逐出缓存（下次打开自然重建），不必跳动画面；
 * 刷新<b>当前页</b>时才做一次卸载-重挂。
 */
async function refreshTab(key: string): Promise<void> {
  const meta = router.resolve(key).meta as { cacheName?: string }
  if (meta.cacheName) {
    evictedNames.value.add(meta.cacheName)
  }
  if (key === route.path) {
    showView.value = false
    await nextTick()
    showView.value = true
  }
  if (meta.cacheName) {
    // 等新实例挂载完成后再恢复 include，让它重新进入缓存。
    // 恢复必须晚于重挂一拍：同一拍内移出又移回等于没移，
    // 旧实例不会被销毁，「刷新缓存页无效」会原样复现
    await nextTick()
    evictedNames.value.delete(meta.cacheName)
  }
}

/**
 * 页签右键菜单的执行端。
 *
 * <p>ProLayout 只负责抛出「对哪个页签、做了什么动作」，语义之外的
 * 路由跳转与缓存管理都在这里 —— 布局组件不认识路由（设计约束）。
 */
function handleTabAction(key: string, action: ProLayoutTabAction): void {
  switch (action) {
    case 'refresh':
      void refreshTab(key)
      return
    case 'close':
      handleTabClose(key)
      return
    case 'close-others':
      tabsStore.closeOthers(key)
      // 当前页若被关掉，必须跳走，否则停在"页签没了但地址还在"的孤儿态
      if (activeKey.value !== key) {
        void router.push(key)
      }
      return
    case 'close-left':
      tabsStore.closeLeft(key)
      break
    case 'close-right':
      tabsStore.closeRight(key)
      break
    case 'close-all':
      tabsStore.closeAll()
      break
  }

  // 关左/关右/全部关闭都可能关掉当前页 —— 统一兜底：
  // 跳到右键的那个页签（close-all 时它也是保留项之一或已不存在，取最后一个）
  if (!tabsStore.tabs.some((tab) => tab.key === activeKey.value)) {
    const fallback =
      tabsStore.tabs.find((tab) => tab.key === key) ??
      tabsStore.tabs[tabsStore.tabs.length - 1]
    if (fallback) {
      void router.push(fallback.key)
    }
  }
}

function handleSelect(key: string): void {
  void router.push(key)
}

function handleTabSelect(key: string): void {
  void router.push(key)
}

/**
 * 关闭页签。
 *
 * <p>关掉的若正是当前页，必须<b>主动跳到另一个仍存在的页签</b> ——
 * 否则会停在一个"页签已经没了、但地址没变"的状态上，
 * 用户再点回来时标签栏里找不到它，看起来像标签栏坏了。
 */
function handleTabClose(key: string): void {
  const wasActive = key === activeKey.value
  tabsStore.closeTab(key)
  if (!wasActive) {
    return
  }
  const fallback = tabsStore.tabs[tabsStore.tabs.length - 1]
  if (fallback) {
    void router.push(fallback.key)
  }
}

/**
 * 退出登录与命令面板。
 *
 * <p>⚠️ {@code logout} 现在来自 {@code useCommands}，本文件不再自己实现。
 * 它需要同时重置 auth / permission / tabs 三个 store，
 * 而顶栏按钮与命令面板都要用它 —— <b>这段清理逻辑只应存在一份</b>，
 * 否则迟早只改一处（那是"换个账号就露出来、刷新一下又好了"这类问题的标准成因）。
 */
const { commands, recentKeys, runCommand, logout } = useCommands()

/** 命令面板显隐。⌘K 由 ProCommand 自己监听，这个 ref 只给按钮用。 */
const commandOpen = ref(false)

const userName = computed(() => authStore.user?.nickname ?? authStore.user?.username ?? '未登录')

// ---------------------------------------------------------------------
// 顶栏工具区：主题下拉 / 用户下拉（图标形态，见模板）
// ---------------------------------------------------------------------

/** 主题按钮图标随生效模式变化；auto 用"对比"图标表达"跟随系统"。 */
const themeIcon = computed(() =>
  appStore.themeMode === 'auto'
    ? 'ContrastOutline'
    : appStore.resolvedTheme === 'dark'
      ? 'Moon'
      : 'Sunny'
)

const themeOptions = [
  {
    key: 'auto',
    label: '跟随系统',
    icon: () => h(ProIcon, { name: 'ContrastOutline', size: 15 })
  },
  {
    key: 'light',
    label: '浅色',
    icon: () => h(ProIcon, { name: 'Sunny', size: 15 })
  },
  {
    key: 'dark',
    label: '深色',
    icon: () => h(ProIcon, { name: 'Moon', size: 15 })
  }
]

function onThemeSelect(key: string): void {
  appStore.setTheme(key as 'light' | 'dark' | 'auto')
}

const userOptions = [
  {
    key: 'profile',
    label: '个人中心',
    icon: () => h(ProIcon, { name: 'Person', size: 15 })
  },
  {
    key: 'sessions',
    label: '在线会话',
    icon: () => h(ProIcon, { name: 'ChatbubbleEllipsesOutline', size: 15 })
  },
  { type: 'divider' as const, key: 'd1' },
  {
    key: 'logout',
    label: '退出登录',
    icon: () => h(ProIcon, { name: 'LogOutOutline', size: 15 })
  }
]

function onUserSelect(key: string): void {
  if (key === 'profile') {
    void router.push('/profile')
  } else if (key === 'sessions') {
    sessionsVisible.value = true
  } else if (key === 'logout') {
    logout()
  }
}
</script>

<template>
  <!--
    布局偏好全部来自 appStore（见 stores/app.ts）：
    组件只负责渲染，改配置不需要动这个文件；偏好由 store 统一持久化。
    maxContentWidth：固定模式用主题约定的 1600px，自定义模式用用户设的宽度。
  -->
  <ProLayout
    :menus="menus"
    :active-key="activeKey"
    v-model:collapsed="appStore.sidebarCollapsed"
    :tabs="tabsStore.tabs"
    :show-tabs="appStore.showTagsView"
    :layout-mode="appStore.layoutMode"
    :tab-style="appStore.tabStyle"
    :show-logo="appStore.showLogo"
    :show-footer="!appStore.hideFooter"
    footer-text="中台管理系统 · 企业级多租户 SaaS 中台"
    :fixed-header="appStore.fixedHeader"
    :max-content-width="appStore.pageWidthMode === 'custom' ? appStore.pageWidth : ''"
    title="中台管理系统"
    @select="handleSelect"
    @tab-select="handleTabSelect"
    @tab-close="handleTabClose"
    @tab-action="handleTabAction"
  >
    <!-- 侧栏品牌区：logo + 系统标题（折叠时只保留图标） -->
    <template #logo="{ collapsed }">
      <div
        class="basic-layout__brand"
        :class="{ 'basic-layout__brand--collapsed': collapsed }"
      >
        <img class="basic-layout__logo" :src="logoSrc" alt="中台管理系统" />
        <span v-if="!collapsed" class="basic-layout__title">中台管理系统</span>
      </div>
    </template>

    <template #actions>
      <!-- 命令面板入口。给触屏用户 / 不知道快捷键的人 -->
      <n-button quaternary size="small" @click="commandOpen = true">
        搜索
        <span class="basic-layout__kbd">⌘K</span>
      </n-button>

      <n-space :size="6" align="center">
        <!-- 主题：图标按钮 + 下拉三选（浅色/深色/跟随系统），当前模式即按钮图标 -->
        <NDropdown
          :options="themeOptions"
          trigger="click"
          placement="bottom"
          @select="onThemeSelect"
        >
          <n-button quaternary size="small" class="basic-layout__icon-btn" title="主题模式">
            <ProIcon :name="themeIcon" :size="17" />
          </n-button>
        </NDropdown>

        <!-- 消息：铃铛 + 未读角标（角标绝对定位在图标右上） -->
        <n-button
          quaternary
          size="small"
          class="basic-layout__icon-btn"
          title="消息"
          @click="messagePanelVisible = true"
        >
          <ProIcon name="NotificationsOutline" :size="17" />
          <span
            v-if="unreadCount > 0"
            class="basic-layout__badge"
          >{{ unreadCount > 99 ? '99+' : unreadCount }}</span>
        </n-button>

        <!-- 用户：头像 + 昵称作下拉触发器（在线会话 / 退出登录收进菜单） -->
        <NDropdown
          :options="userOptions"
          trigger="click"
          placement="bottom"
          @select="onUserSelect"
        >
          <div class="basic-layout__user-trigger" title="账号">
            <span class="basic-layout__avatar">{{ userName.charAt(0) }}</span>
            <span class="basic-layout__user">{{ userName }}</span>
          </div>
        </NDropdown>

        <!-- 全屏切换：图标随真实全屏状态变化（点图标即进出全屏） -->
        <n-button
          quaternary
          size="small"
          class="basic-layout__icon-btn"
          :title="isFullscreen ? '退出全屏' : '全屏'"
          @click="onToggleFullscreen"
        >
          <ProIcon :name="isFullscreen ? 'ContractOutline' : 'ExpandOutline'" :size="17" />
        </n-button>

        <!-- 配置：齿轮图标，位于最右侧（与设计稿一致） -->
        <n-button
          quaternary
          size="small"
          class="basic-layout__icon-btn"
          title="系统配置"
          @click="settingsVisible = true"
        >
          <ProIcon name="Settings" :size="17" />
        </n-button>

        <SessionDialog v-model:visible="sessionsVisible" />
        <MessagePanel v-model:visible="messagePanelVisible" />
      </n-space>
    </template>

    <!--
      include 只放「页签仍开着 + 路由声明 keepAlive」的组件名。
      未声明 keepAlive 的页面因此完全不缓存（尊重后端的菜单配置）；
      关掉页签则名字离开 include，实例随之被销毁。

      ⚠️ keep-alive 里只能是那个 component，连注释都不能有 ——
      Vue 会把注释也当作 child，编译直接报
      "<KeepAlive> expects exactly one child component"（实测踩过）。
      v-if="showView" 用于页签右键「刷新」：卸载重挂当前页，
      配合 evictedNames 逐出缓存，保证缓存页也能真正重建实例。
    -->
    <!--
      include 只放「页签仍开着 + 路由声明 keepAlive」的组件名；
      关掉页签则名字离开 include，实例随之被销毁。

      ⚠️ keep-alive 里必须只有一个 component（连注释都不能有）。
      ⚠️ 必须显式加 :key="route.path"（实测踩过：不加时 keep-alive 在异步组件
      场景下会复用错乱的缓存实例，表现是 URL/页签/面包屑都对，
      但内容区渲染成了另一个已打开页面的组件）。
      用路由路径作 key 保证每个路由有独立的缓存槽。
    -->
    <router-view v-slot="{ Component }">
      <keep-alive :include="cachedNames">
        <component :is="Component" :key="route.path" v-if="showView" />
      </keep-alive>
    </router-view>

    <!--
      ⌘K 命令面板。快捷键由组件自己监听（挂在 window 上）；
      顶栏的按钮是给触屏用户的入口 —— 快捷键对没有键盘的人不存在。
    -->
    <ProCommand
      v-model:open="commandOpen"
      :commands="commands"
      :recent-keys="recentKeys"
      @select="runCommand"
    />

    <!--
      全屏水印：pointer-events none —— 只是视觉层，绝不能挡交互；
      z-index 高于常规内容、低于 Naive 弹层（2000+），弹窗保持最上层。
    -->
    <div
      v-if="watermarkStyle"
      class="basic-layout__watermark"
      :style="watermarkStyle"
      aria-hidden="true"
    />

    <SettingDrawer v-model:visible="settingsVisible" />
  </ProLayout>
</template>

<style scoped>
/* 图标按钮：触发器定位基准（角标挂在按钮上） */
.basic-layout__icon-btn {
  position: relative;
}

/* 未读角标：图标右上角小红点式计数 */
.basic-layout__badge {
  position: absolute;
  top: 0;
  right: 0;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  box-sizing: border-box;
  border-radius: 8px;
  background: var(--wa-color-error, #d03050);
  color: #fff;
  font-size: 10px;
  line-height: 16px;
  text-align: center;
  pointer-events: none;
}

/* 头像圆形：取昵称首字（无独立头像图 —— 不为顶栏引入图片资源） */
.basic-layout__avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--wa-color-primary, #2563eb);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  flex: none;
}

/* 用户下拉触发器：头像 + 昵称一行 */
.basic-layout__user-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  cursor: pointer;
  border-radius: var(--wa-radius-md, 4px);
}

.basic-layout__user-trigger:hover {
  background: var(--wa-bg-hover, #f0f2f5);
}

/* 全屏水印：整面平铺，pointer-events 放行所有交互 */
.basic-layout__watermark {
  position: fixed;
  inset: 0;
  z-index: 1500;
  pointer-events: none;
  background-repeat: repeat;
}

.basic-layout__user {
  font-size: var(--wa-font-size-md, 14px);
  color: var(--wa-text-secondary, #5c6570);
}

/* 品牌栏：与 ProLayout 的 .pro-layout__logo 容器同高，接管内部布局 */
.basic-layout__brand {
  display: flex;
  align-items: center;
  gap: var(--wa-spacing-sm);
  width: 100%;
  height: 100%;
  padding: 0 var(--wa-spacing-lg);
  box-sizing: border-box;
}

/* 折叠态：只显示 logo，居中，去掉水平内边距 */
.basic-layout__brand--collapsed {
  justify-content: center;
  padding: 0;
}

.basic-layout__logo {
  flex: none;
  height: 32px;
  width: auto;
  max-width: 100%;
  border-radius: var(--wa-radius-sm);
  object-fit: contain;
}

.basic-layout__title {
  flex: 1;
  min-width: 0;
  font-size: var(--wa-font-size-lg);
  font-weight: 600;
  line-height: 1.25;
  color: inherit;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 让 ProLayout 的 logo 容器在展开时左对齐，并加一条与侧栏风格一致的分割线 */
:deep(.pro-layout__logo) {
  justify-content: flex-start;
  border-bottom: 1px solid var(--wa-divider);
}

/* 折叠态仍保持居中 */
:deep(.pro-layout__logo.pro-layout__logo--collapsed) {
  justify-content: center;
}
</style>
