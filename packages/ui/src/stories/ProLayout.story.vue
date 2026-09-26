<script setup lang="ts">
import { ref } from 'vue'
import { NButton, NTag } from 'naive-ui'
import ProLayout from '../components/pro/ProLayout.vue'
import type { ProLayoutMenu, ProLayoutTab } from '../index'

/**
 * ProLayout 的 story。
 *
 * <p>布局只有一种形态：菜单全在左侧栏（多级展开也在侧栏内），
 * 顶栏只放面包屑与操作区。预演数据刻意做成<b>三层深</b>
 * （平台管理 → 系统监控 → 接口日志），
 * 因为多级展开、面包屑链路只在深菜单下才看得出来。
 */

const menus: ProLayoutMenu[] = [
  {
    key: '/dashboard',
    label: '首页'
  },
  {
    key: '/system',
    label: '系统管理',
    children: [
      { key: '/system/user', label: '用户管理' },
      { key: '/system/role', label: '角色管理' },
      { key: '/system/menu', label: '菜单管理' }
    ]
  },
  {
    key: '/org',
    label: '组织管理',
    children: [
      { key: '/org/dept', label: '部门管理' },
      { key: '/org/post', label: '岗位管理' }
    ]
  },
  {
    key: '/platform',
    label: '平台管理',
    children: [
      { key: '/platform/dict', label: '字典管理' },
      { key: '/platform/config', label: '参数配置' },
      {
        key: '/platform/monitor',
        label: '系统监控',
        children: [
          { key: '/platform/monitor/api', label: '接口日志' },
          { key: '/platform/monitor/login', label: '登录日志' }
        ]
      }
    ]
  }
]

const activeKey = ref('/platform/monitor/api')
const collapsed = ref(false)
const tabs = ref<ProLayoutTab[]>([
  { key: '/dashboard', label: '首页', affix: true },
  { key: '/system/user', label: '用户管理' },
  { key: '/system/role', label: '角色管理' }
])

/** 演示用：点击页签/菜单时把激活项切过去（真实项目里由路由完成）。 */
function handleSelect(key: string): void {
  activeKey.value = key
  if (!tabs.value.some((tab) => tab.key === key)) {
    const label = findLabel(menus, key)
    tabs.value = [...tabs.value, { key, label: label ?? key }]
  }
}

function handleTabClose(key: string): void {
  tabs.value = tabs.value.filter((tab) => tab.key !== key)
  // 关掉当前页签后，激活项落到仍存在的最后一个 —— 否则会残留一个
  // "高亮着但页签已经没了"的状态
  if (activeKey.value === key) {
    activeKey.value = tabs.value[tabs.value.length - 1]?.key ?? ''
  }
}

function findLabel(nodes: ProLayoutMenu[], key: string): string | undefined {
  for (const node of nodes) {
    if (node.key === key) {
      return node.label
    }
    if (node.children) {
      const found = findLabel(node.children, key)
      if (found) {
        return found
      }
    }
  }
  return undefined
}
</script>

<template>
  <Story title="Pro 组件/ProLayout" :layout="{ type: 'grid', width: '100%' }">
    <Variant
      title="标准形态：多级菜单在左侧，面包屑在顶栏"
      doc="菜单全部渲染在左侧栏（递归组件 ProMenu 承载多级展开），顶栏只有面包屑与操作区。激活项是深层叶子（/platform/monitor/api），首帧即展开其父链。"
    >
      <ProLayout
        v-model:collapsed="collapsed"
        :menus="menus"
        :active-key="activeKey"
        :tabs="tabs"
        title="中台管理"
        height="520px"
        @select="handleSelect"
        @tab-select="handleSelect"
        @tab-close="handleTabClose"
      >
        <template #actions>
          <n-tag size="small" type="info">{{ activeKey }}</n-tag>
          <n-button size="small" quaternary @click="collapsed = !collapsed">折叠</n-button>
        </template>
        <div class="layout-demo">
          <p>当前页面：<b>{{ activeKey }}</b></p>
          <p>内容区由默认插槽承载（真实项目里是 router-view）。</p>
        </div>
      </ProLayout>
    </Variant>

    <Variant title="折叠侧栏" doc="折叠后只渲染第一层：叶子显示首字，目录替用户选中第一个叶子（宽度 64px 放不下子级列表）。">
      <ProLayout
        v-model:collapsed="collapsed"
        :menus="menus"
        :active-key="activeKey"
        :tabs="tabs"
        title="中台管理"
        height="420px"
        @select="handleSelect"
        @tab-select="handleSelect"
        @tab-close="handleTabClose"
      />
    </Variant>

    <Variant
      title="移动端（≤768px 自动改用抽屉）"
      doc="把断点提到 2000px 来强制进入移动端形态 —— 免去真的去缩窗口。此时侧栏消失，菜单移入抽屉。"
    >
      <ProLayout
        :menus="menus"
        :active-key="activeKey"
        :tabs="tabs"
        title="中台管理"
        height="420px"
        :mobile-breakpoint="2000"
        @select="handleSelect"
        @tab-select="handleSelect"
        @tab-close="handleTabClose"
      />
    </Variant>

    <Variant title="极简（无面包屑、无标签栏）" doc="适合嵌入到别的容器里，或不需要多页签的系统。">
      <ProLayout
        :menus="menus"
        :active-key="activeKey"
        title="中台管理"
        height="360px"
        :show-breadcrumb="false"
        :show-tabs="false"
        @select="handleSelect"
      >
        <div class="layout-demo">
          <p>只有菜单与内容区。</p>
        </div>
      </ProLayout>
    </Variant>

    <Variant title="空菜单" doc="菜单未加载或用户无任何可见菜单时的形态 —— 不该出现空白侧栏。">
      <ProLayout :menus="[]" :active-key="''" title="中台管理" height="260px">
        <div class="layout-demo">
          <p>没有可用菜单。</p>
        </div>
      </ProLayout>
    </Variant>
  </Story>
</template>

<style scoped>
.layout-demo {
  color: var(--wa-text-secondary);
  font-size: var(--wa-font-size-sm);
}

.layout-demo p {
  margin: 0 0 var(--wa-spacing-sm);
}
</style>
