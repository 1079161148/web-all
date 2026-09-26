<script setup lang="ts">
import { ref } from 'vue'
import { NTag } from 'naive-ui'
import ProCommand from '../components/pro/ProCommand.vue'
import type { ProCommandItem, ProCommandSelectPayload } from '../index'

/**
 * ProCommand 的 story。
 *
 * <p>在 story 里也能按 ⌘K / Ctrl+K 打开（快捷键是挂在 window 上的），
 * 但 Histoire 可能把这个 iframe 用于多个变体 —— 因此每个变体都提供了显式按钮，
 * <b>不依赖快捷键也能触发</b>。这不是"为了 story 而 story"：
 * 真实页面里也应当有可点击的入口（比如顶栏的搜索图标），
 * 因为快捷键对触屏用户不存在。
 */

const commands: ProCommandItem[] = [
  { key: 'user:create', label: '新建用户', group: '系统管理', hint: '⌘⇧U' },
  { key: 'user:list', label: '用户管理', group: '系统管理' },
  { key: 'role:create', label: '新建角色', group: '系统管理' },
  { key: 'dept:create', label: '新建部门', group: '组织管理' },
  { key: 'post:create', label: '新建岗位', group: '组织管理' },
  { key: 'dict:list', label: '字典管理', group: '平台管理', keywords: ['dictionary', 'code'] },
  { key: 'config:list', label: '参数配置', group: '平台管理' },
  {
    key: 'tenant:create',
    label: '新建租户',
    group: '平台管理',
    hint: '需超管权限',
    disabled: true,
    keywords: ['tenant', 'zuLin']
  }
]

const open = ref(false)
const picked = ref<ProCommandSelectPayload | null>(null)
const shortcutOff = ref(false)
const noShortcutOpen = ref(false)
const emptyOpen = ref(false)

function handleSelect(payload: ProCommandSelectPayload): void {
  picked.value = payload
}
</script>

<template>
  <Story title="Pro 组件/ProCommand" :layout="{ type: 'grid', width: '100%' }">
    <Variant
      title="基础用法（⌘K / Ctrl+K）"
      doc="按快捷键或点按钮打开。输入框自动获得焦点 —— 还要再按一次 Tab 的都不叫命令面板。试试点开后用 ↑↓ 移动（高亮会跟随滚动）、回车执行、Esc 关闭。"
    >
      <n-space vertical :size="12">
        <n-button size="small" type="primary" @click="open = true">
          打开命令面板（或按 ⌘/Ctrl + K）
        </n-button>
        <n-tag v-if="picked" size="small" type="success">
          选中的命令：{{ picked.key }}（分组：{{ picked.item.group }}）
        </n-tag>
        <n-tag v-else size="small" type="default">还没有选择任何命令</n-tag>

        <ProCommand v-model:open="open" :commands="commands" @select="handleSelect" />
      </n-space>
    </Variant>

    <Variant
      title="⚠️ 禁用项：照常显示但不参与导航"
      doc="「新建租户」是禁用的。它会显示，但 ↑↓ 会跳过它、回车也选不中。若直接过滤掉，用户会认为"这个功能不存在"，而真实原因是"当前不可用" —— 排查方向完全不同。"
    >
      <n-space vertical :size="12">
        <n-button size="small" @click="open = true">打开命令面板</n-button>
        <ProCommand v-model:open="open" :commands="commands" @select="handleSelect" />
      </n-space>
    </Variant>

    <Variant
      title="模糊检索：打错字母也能搜到"
      doc="试试输入 usr、dept、或者字典的别名 dictionary / code。label 与 keywords 分开是有意的：用户看到的文案与会输入的词往往不一致（显示「新建用户」但会打 add）。"
    >
      <n-space vertical :size="12">
        <n-button size="small" @click="open = true">打开命令面板</n-button>
        <ProCommand v-model:open="open" :commands="commands" @select="handleSelect" />
      </n-space>
    </Variant>

    <Variant
      title="关闭全局快捷键"
      doc="shortcutEnabled=false 时不再监听 ⌘K，只能用 v-model 控制显隐 —— 适合把入口放在自己的工具栏里的场景。"
    >
      <n-space vertical :size="12">
        <n-button size="small" @click="noShortcutOpen = true">打开（此时 ⌘K 无效）</n-button>
        <ProCommand
          v-model:open="noShortcutOpen"
          :commands="commands"
          :shortcut-enabled="shortcutOff"
          @select="handleSelect"
        />
      </n-space>
    </Variant>

    <Variant title="无匹配结果" doc="空态要说清"没有匹配的命令"，而不是留一片空白。">
      <n-space vertical :size="12">
        <n-button size="small" @click="emptyOpen = true">打开空的面板</n-button>
        <ProCommand
          v-model:open="emptyOpen"
          :commands="[]"
          empty-text="没有任何可用命令"
          @select="handleSelect"
        />
      </n-space>
    </Variant>
  </Story>
</template>
