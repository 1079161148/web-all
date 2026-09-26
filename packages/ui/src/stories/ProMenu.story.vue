<script setup lang="ts">
import { ref } from 'vue'
import ProMenu from '../components/pro/ProMenu.vue'
import type { ProLayoutMenu } from '../index'

/**
 * ProMenu 的 story。
 *
 * <p>预演数据与 ProLayout 的 story 同源（三层深），因为递归组件的价值
 * 只在多层树下可见：一层菜单时它和普通列表没有区别。
 */

const menus: ProLayoutMenu[] = [
  { key: '/dashboard', label: '首页' },
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
  },
  { key: '/org/dept', label: '部门管理' }
]

const activeKey = ref('/platform/monitor/api')
const selected = ref('')

function handleSelect(key: string): void {
  activeKey.value = key
  selected.value = key
}
</script>

<template>
  <Story title="Pro 组件/ProMenu" :layout="{ type: 'grid', width: 400 }">
    <Variant
      title="递归树（三层深）"
      doc="层级由数据决定，渲染规则只有一份：任意深度的缩进、激活态、展开箭头一致。点「平台管理 → 系统监控」验证第三层。"
    >
      <div class="demo-frame">
        <ProMenu :menus="menus" :active-key="activeKey" @select="handleSelect" />
      </div>
      <p class="demo-hint">最近选择：{{ selected || '（无）' }}</p>
    </Variant>

    <Variant
      title="激活项自动展开祖先"
      doc="把 activeKey 初始值设为深层叶子（/platform/monitor/api），首帧就会展开它的父链 —— 导航直达深链路时菜单不该是全收起的。"
    >
      <div class="demo-frame">
        <ProMenu :menus="menus" :active-key="activeKey" @select="handleSelect" />
      </div>
    </Variant>

    <Variant
      title="折叠态"
      doc="只渲染第一层：叶子显示首字（无图标时），目录点击替用户选中第一个叶子 —— 折叠宽度下放不下子级列表，与其弹出半成品不如给出明确行为。"
    >
      <div class="demo-frame demo-frame--collapsed">
        <ProMenu :menus="menus" :active-key="activeKey" collapsed @select="handleSelect" />
      </div>
    </Variant>

    <Variant title="空菜单" doc="菜单未加载 / 用户无权限时的形态 —— 给出提示而不是空白。">
      <div class="demo-frame">
        <ProMenu :menus="[]" :active-key="''" @select="handleSelect" />
      </div>
    </Variant>
  </Story>
</template>

<style scoped>
.demo-frame {
  width: 220px;
  border: 1px solid var(--wa-border);
  border-radius: var(--wa-radius-sm);
  overflow: hidden;
}

.demo-frame--collapsed {
  width: 64px;
}

.demo-hint {
  margin-top: var(--wa-spacing-sm);
  color: var(--wa-text-secondary);
  font-size: var(--wa-font-size-sm);
}
</style>
