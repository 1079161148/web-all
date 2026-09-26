<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { NEmpty, NInput, NModal, type InputInst } from 'naive-ui'
import { ensureFuse } from '../../adapters/lazy'
import type { ProCommandItem, ProCommandSelectPayload } from '../../types'

/**
 * 命令面板（Cmd/Ctrl + K）。
 *
 * <pre>{@code
 * <ProCommand v-model:open="paletteOpen" :commands="commands" @select="runCommand" />
 * }</pre>
 *
 * <h3>它是「键盘优先」而不是「有个搜索框的弹窗」</h3>
 * 命令面板的价值全在于<b>手不离键盘</b>：⌘K 打开 → 输入 → ↑↓ 选 → 回车执行。
 * 少任何一环，用户就会改用鼠标去点菜单，于是这个组件就白做了。
 * 因此下面几件事不是"增强功能"，而是它的定义：
 * <ul>
 *   <li>打开后<b>输入框自动获得焦点</b>（还要按一次 Tab 的都不是命令面板）</li>
 *   <li>↑↓ 在结果间移动，<b>滚动跟随</b>（高亮跑出可视区等于没有高亮）</li>
 *   <li>回车执行当前项，Esc 关闭</li>
 * </ul>
 *
 * <h3>⚠️ 全局快捷键必须解绑</h3>
 * 与 ProLayout 的 `matchMedia` 同理：不 `removeEventListener`，
 * 组件销毁后监听器仍在，HMR 下反复挂载会越积越多 ——
 * 表现为"按一次 ⌘K 弹出好几个面板"。
 *
 * <h3>⚠️ 禁用项与"不存在"必须区分</h3>
 * 禁用的命令<b>照常显示、但不参与导航与回车选中</b>。
 * 若直接把它过滤掉，用户会认为"这个功能没有"，
 * 而真实原因是"当前不可用" —— 这两者的排查方向完全不同。
 *
 * <h3>内核按需加载，且加载前可用</h3>
 * fuse.js 只有约 12 KB，但仍在首次打开时才加载（判据是"不是每个页面都要"）。
 * 加载完成之前退化为子串匹配 —— <b>输入了却什么都不显示</b> 比"匹配得糙一点"糟糕得多。
 */
const props = withDefaults(
  defineProps<{
    /** 命令列表。 */
    commands?: ProCommandItem[]
    /** 显隐（v-model:open）。 */
    open?: boolean
    /** 是否启用全局快捷键（⌘/Ctrl + K）。 */
    shortcutEnabled?: boolean
    placeholder?: string
    emptyText?: string
    /** 最多展示多少条结果。 */
    maxResults?: number
    /**
     * 最近使用的命令 key（由调用方持久化，组件不碰存储）。
     * <b>只在空查询时生效</b>：把最近用过的排到前面 ——
     * 有关键词时用户在"找"，频次信息反而会干扰模糊评分。
     */
    recentKeys?: string[]
  }>(),
  {
    commands: () => [],
    open: false,
    shortcutEnabled: true,
    placeholder: '输入命令或搜索…',
    emptyText: '没有匹配的命令',
    maxResults: 50,
    recentKeys: () => []
  }
)

const emit = defineEmits<{
  (e: 'update:open', open: boolean): void
  (e: 'select', payload: ProCommandSelectPayload): void
  (e: 'error', error: unknown): void
}>()

const query = ref('')
const activeIndex = ref(0)

const inputRef = ref<InputInst | null>(null)
const listRef = ref<HTMLElement | null>(null)

const fuseModule = shallowRef<typeof import('fuse.js') | null>(null)

async function loadFuse(): Promise<void> {
  if (fuseModule.value) {
    return
  }
  try {
    fuseModule.value = await ensureFuse()
  } catch (error) {
    // 检索内核加载失败不影响使用：下面的 results 会走子串匹配兜底
    emit('error', error)
  }
}

/**
 * 检索索引。
 *
 * <p>按 {@code commands} 建立一次并缓存（computed 会随它变化重建）——
 * 若放在每次输入时新建，列表大一点就会在每次按键上重算一遍索引。
 */
const fuseIndex = computed(() => {
  const mod = fuseModule.value
  if (!mod) {
    return null
  }
  return new mod.default(props.commands, {
    keys: ['label', 'keywords', 'hint'],
    // 0.4 是"允许打错一两个字母"的量级；再松会把无关项也搜出来，
    // 而命令面板里出现不相关的项比"少搜到一条"更让人困惑
    threshold: 0.4
  })
})

const results = computed<ProCommandItem[]>(() => {
  const keyword = query.value.trim()
  if (!keyword) {
    // 空查询：最近使用优先（保持其余原序）。倒序遍历 recentKeys
    // 让"最近一次使用"排最前；用 Set 保证重复记录与越界 key 不产生副作用
    const recent = props.recentKeys
    if (recent.length > 0) {
      const rank = new Map<string, number>()
      for (let i = recent.length - 1; i >= 0; i -= 1) {
        rank.set(recent[i], i)
      }
      const sorted = [...props.commands].sort((a, b) => {
        const ra = rank.get(a.key) ?? Number.MAX_SAFE_INTEGER
        const rb = rank.get(b.key) ?? Number.MAX_SAFE_INTEGER
        return ra - rb
      })
      return sorted.slice(0, props.maxResults)
    }
    return props.commands.slice(0, props.maxResults)
  }
  const index = fuseIndex.value
  if (!index) {
    // 内核未就绪时的兜底：子串匹配。宁可糙，也不要"输入了没反应"
    const lower = keyword.toLowerCase()
    return props.commands
      .filter((item) => item.label.toLowerCase().includes(lower))
      .slice(0, props.maxResults)
  }
  return index
    .search(keyword)
    .slice(0, props.maxResults)
    .map((entry) => entry.item)
})

/** 结果按 group 连续分组，同时保留"扁平索引"用于键盘导航。 */
const groups = computed(() => {
  const out: Array<{ title: string; entries: Array<{ item: ProCommandItem; index: number }> }> = []
  results.value.forEach((item, index) => {
    const title = item.group ?? ''
    const last = out[out.length - 1]
    if (last && last.title === title) {
      last.entries.push({ item, index })
    } else {
      out.push({ title, entries: [{ item, index }] })
    }
  })
  return out
})

function isNavigable(index: number): boolean {
  const item = results.value[index]
  return Boolean(item) && item.disabled !== true
}

/** 从 from 出发按 direction 找下一个可用项；找不到返回 -1。 */
function step(from: number, direction: 1 | -1): number {
  const total = results.value.length
  if (total === 0) {
    return -1
  }
  let cursor = from
  for (let i = 0; i < total; i += 1) {
    cursor = (cursor + direction + total) % total
    if (isNavigable(cursor)) {
      return cursor
    }
  }
  return -1
}

// 结果变化后重选第一项：不重置会留下一个指向已消失项的索引
watch(results, () => {
  activeIndex.value = step(-1, 1)
})

// 高亮跟随滚动。用 nextTick 是因为高亮项此刻可能才刚被渲染出来
watch(activeIndex, async () => {
  await nextTick()
  listRef.value
    ?.querySelector<HTMLElement>(`[data-index="${activeIndex.value}"]`)
    ?.scrollIntoView({ block: 'nearest' })
})

function close(): void {
  emit('update:open', false)
}

function choose(index: number): void {
  const item = results.value[index]
  if (!item || item.disabled) {
    return
  }
  emit('select', { key: item.key, item })
  close()
}

function focusIndex(index: number): void {
  if (isNavigable(index)) {
    activeIndex.value = index
  }
}

function handleKeydown(event: KeyboardEvent): void {
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    activeIndex.value = step(activeIndex.value, 1)
    return
  }
  if (event.key === 'ArrowUp') {
    event.preventDefault()
    activeIndex.value = step(activeIndex.value, -1)
    return
  }
  if (event.key === 'Enter') {
    event.preventDefault()
    choose(activeIndex.value)
    return
  }
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
  }
}

function handleGlobalKeydown(event: KeyboardEvent): void {
  if (!props.shortcutEnabled) {
    return
  }
  if (!(event.metaKey || event.ctrlKey) || event.key.toLowerCase() !== 'k') {
    return
  }
  // 必须阻止默认：Chrome 里 ⌘K 是"聚焦地址栏"，
  // 不拦下来会先跳到地址栏再弹出面板
  event.preventDefault()
  emit('update:open', !props.open)
}

watch(
  () => props.open,
  async (isOpen) => {
    if (!isOpen) {
      return
    }
    query.value = ''
    void loadFuse()
    await nextTick()
    inputRef.value?.focus()
  }
)

onMounted(() => {
  window.addEventListener('keydown', handleGlobalKeydown)
})

onBeforeUnmount(() => {
  // 不解绑会在 HMR 下越积越多，表现为"按一次 ⌘K 弹出好几个面板"
  window.removeEventListener('keydown', handleGlobalKeydown)
})
</script>

<template>
  <n-modal
    :show="open"
    :mask-closable="true"
    class="pro-command-modal"
    @update:show="(value: boolean) => emit('update:open', value)"
  >
    <div class="pro-command">
      <n-input
        ref="inputRef"
        v-model:value="query"
        size="large"
        clearable
        :placeholder="placeholder"
        class="pro-command__input"
        @keydown="handleKeydown"
      />

      <div ref="listRef" class="pro-command__list">
        <n-empty
          v-if="results.length === 0"
          :description="emptyText"
          class="pro-command__empty"
        />

        <template v-for="group in groups" :key="group.title || '__ungrouped'">
          <div v-if="group.title" class="pro-command__group">{{ group.title }}</div>
          <button
            v-for="entry in group.entries"
            :key="entry.item.key"
            type="button"
            class="pro-command__item"
            :class="{
              'pro-command__item--active': entry.index === activeIndex,
              'pro-command__item--disabled': entry.item.disabled
            }"
            :data-index="entry.index"
            :disabled="entry.item.disabled"
            @click="choose(entry.index)"
            @mouseenter="focusIndex(entry.index)"
          >
            <span class="pro-command__label">{{ entry.item.label }}</span>
            <span v-if="entry.item.hint" class="pro-command__hint">{{ entry.item.hint }}</span>
          </button>
        </template>
      </div>
    </div>
  </n-modal>
</template>

<style scoped>
/*
  结构与尺寸，样式引用 Token（ui-component-policy 强行约束第 5 条）。
  用普通按钮而不是 n-list：列表项需要"高亮跟随键盘"与"滚动跟随"，
  用内核的选中模型要绕过它自己的交互，反而更绕；
  而这里的每一项只有 label 与 hint，是纯结构。
*/
.pro-command-modal {
  width: 560px;
  max-width: 90vw;
  margin-top: 10vh;
}

.pro-command {
  display: flex;
  flex-direction: column;
  max-height: 60vh;
  border-radius: var(--wa-radius-md);
  overflow: hidden;

  /*
    ⚠️ 背景必须显式声明（实测踩过）：n-modal 不带 preset 时渲染的是裸内容，
    没有任何卡片样式 —— 之前这里漏了背景，面板下面的页面内容会整个透出来，
    命令列表与表格文字混在一起，看起来像「表格样式坏了」。
    颜色与阴影取 Token（--wa-shadow-* 由 applyTokensToDom 注入，见 theme 包）。
  */
  background: var(--wa-bg-elevated, #fff);
  color: var(--wa-text-primary, #1f2329);
  box-shadow: var(--wa-shadow-lg);
}

.pro-command__input {
  flex: none;
}

.pro-command__list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--wa-spacing-sm) 0;
}

.pro-command__empty {
  padding: var(--wa-spacing-xl) 0;
}

.pro-command__group {
  padding: var(--wa-spacing-sm) var(--wa-spacing-lg);
  color: var(--wa-text-secondary);
  font-size: var(--wa-font-size-xs);
}

.pro-command__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--wa-spacing-md);
  width: 100%;
  padding: var(--wa-spacing-sm) var(--wa-spacing-lg);
  border: none;
  background: transparent;
  color: inherit;
  font-size: var(--wa-font-size-sm);
  text-align: left;
  cursor: pointer;
}

.pro-command__item--active {
  background: var(--wa-bg-hover);
}

.pro-command__item--disabled {
  color: var(--wa-text-disabled);
  cursor: not-allowed;
}

.pro-command__hint {
  flex: none;
  color: var(--wa-text-secondary);
  font-size: var(--wa-font-size-xs);
}
</style>
