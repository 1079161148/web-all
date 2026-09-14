<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { NButton, NLayout, NLayoutContent, NLayoutHeader, NLayoutSider, NMenu, NSpace } from '@admin/ui'
import type { MenuOption } from '@admin/ui'
import { getTokens } from '@admin/theme'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { usePermissionStore, type MenuNode } from '@/stores/permission'

/**
 * 基础布局。
 *
 * <h3>本轮的关键变化：菜单改为后端驱动</h3>
 * 之前这里硬编码了一份菜单（违反设计文档 §11.15 第 5 条红线）。
 * 现在菜单来自 `permissionStore.menuTree`，而后者由后端 `/auth/menus` 构建 ——
 * <b>后台改了菜单或权限，前端不需要改代码、不需要重新构建。</b>
 *
 * <h3>仍未实现的部分（诚实说明）</h3>
 * 设计文档 §11.4 把 `ProLayout` 列为"唯一必须自研的组件"并规划了四种布局模式。
 * 当前只有侧边模式的最小可用版本，以下均未实现：
 * <ul>
 *   <li>标签页（TagsView）+ `<keep-alive>` 页面缓存</li>
 *   <li>面包屑、全屏、命令面板（Cmd+K）、快捷键体系</li>
 *   <li>顶栏 / 混合 / 分栏三种布局模式</li>
 * </ul>
 * 这些属于 P2（Pro 组件补齐），不阻塞当前权限闭环。
 */
const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const authStore = useAuthStore()
const permissionStore = usePermissionStore()

const tokens = computed(() => getTokens(appStore.themeMode))

/** Naive 菜单是扁平 value + 嵌套 options，这里把后端的菜单树递归转换。 */
function toMenuOptions(nodes: MenuNode[]): MenuOption[] {
  return nodes.map((node) => ({
    label: node.label,
    key: node.path,
    children: node.children.length > 0 ? toMenuOptions(node.children) : undefined
  }))
}

const menuOptions = computed<MenuOption[]>(() => toMenuOptions(permissionStore.menuTree))

/**
 * 当前高亮项。
 *
 * <p>用 `route.path` 而不是 `route.name`：菜单 key 就是完整路径，
 * 而动态路由的 name 是 `menu-{id}`（注册时才知道）。
 * 用路径匹配不需要在菜单与路由之间再建立一层 ID 映射。
 *
 * <p>⚠️ 已知不足：子路由（如 `/system/user/42`）不会高亮父菜单。
 * 需要在标签页/面包屑一起做时统一处理（按最长前缀匹配），
 * 现在不做半套，避免出现"有时高亮有时不高亮"的更难理解的行为。
 */
const activeKey = computed(() => route.path)

function handleMenuSelect(key: string): void {
  void router.push(key)
}

/**
 * 退出登录。
 *
 * <p>除了清本地会话，还<b>必须重置权限 store</b> ——
 * 否则下一个登录的用户在路由守卫里会看到 `loaded === true`，
 * 于是跳过加载、直接使用上一个用户的菜单与权限。
 * 后果是"换账号登录后菜单没变、按钮权限错乱"，
 * 而且刷新一次就好了，极具迷惑性。
 */
function logout(): void {
  authStore.logout()
  permissionStore.reset()
  void router.push('/login')
}

const userName = computed(() => authStore.user?.nickname ?? authStore.user?.username ?? '未登录')
</script>

<template>
  <n-layout has-sider class="basic-layout">
    <n-layout-sider
      bordered
      collapse-mode="width"
      :collapsed-width="Number.parseInt(tokens.layout.sidebarCollapsedWidth, 10)"
      :width="Number.parseInt(tokens.layout.sidebarWidth, 10)"
      :collapsed="appStore.sidebarCollapsed"
      show-trigger
      @collapse="appStore.sidebarCollapsed = true"
      @expand="appStore.sidebarCollapsed = false"
    >
      <div class="basic-layout__logo">{{ appStore.sidebarCollapsed ? '中' : '中台管理系统' }}</div>
      <n-menu
        :value="activeKey"
        :options="menuOptions"
        :collapsed="appStore.sidebarCollapsed"
        :collapsed-width="Number.parseInt(tokens.layout.sidebarCollapsedWidth, 10)"
        :collapsed-icon-size="18"
        :indent="18"
        @update:value="handleMenuSelect"
      />
    </n-layout-sider>

    <n-layout>
      <n-layout-header bordered class="basic-layout__header">
        <span class="basic-layout__title">
          {{ (route.meta.title as string | undefined) ?? '' }}
        </span>
        <n-space :size="12" align="center">
          <!-- 用户身份来自 /auth/me，而非前端解析令牌 —— 令牌格式不外泄到前端 -->
          <span class="basic-layout__user">{{ userName }}</span>
          <n-button quaternary size="small" @click="appStore.toggleTheme">
            {{ appStore.themeMode === 'light' ? '暗色' : '亮色' }}
          </n-button>
          <n-button quaternary size="small" @click="logout">退出登录</n-button>
        </n-space>
      </n-layout-header>

      <n-layout-content class="basic-layout__content" content-style="padding: 16px;">
        <!-- 用 RouterView 的插槽形式而非直接 <router-view />：
             便于将来在此处按 route.meta.keepAlive 包 keep-alive，
             现在先不引入，避免"缓存了但没生效"的半成品状态 -->
        <router-view v-slot="{ Component }">
          <component :is="Component" />
        </router-view>
      </n-layout-content>
    </n-layout>
  </n-layout>
</template>

<style scoped>
.basic-layout {
  height: 100vh;
}

.basic-layout__logo {
  display: flex;
  align-items: center;
  justify-content: center;
  height: var(--wa-layout-header-height, 56px);
  font-size: var(--wa-font-size-lg, 16px);
  font-weight: 600;
  color: var(--wa-color-primary, #2563eb);
  white-space: nowrap;
  overflow: hidden;
}

.basic-layout__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--wa-layout-header-height, 56px);
  padding: 0 var(--wa-spacing-lg, 16px);
}

.basic-layout__title {
  font-size: var(--wa-font-size-lg, 16px);
  font-weight: 500;
  color: var(--wa-text-primary, #1f2329);
}

.basic-layout__user {
  font-size: var(--wa-font-size-md, 14px);
  color: var(--wa-text-secondary, #5c6570);
}

.basic-layout__content {
  height: calc(100vh - var(--wa-layout-header-height, 56px));
  background: var(--wa-bg, #f5f7fa);
}
</style>
