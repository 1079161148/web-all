<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  NButton,
  NDrawer,
  NDrawerContent,
  NInput,
  NRadioButton,
  NRadioGroup,
  NSwitch,
  feedback
} from '@admin/ui'
import { useAppStore, type LayoutMode, type TabStyle } from '@/stores/app'

/**
 * 全局配置抽屉（系统设置面板）。
 *
 * <h3>为什么偏好状态在 store 而不在这里</h3>
 * 本组件只是设置的**编辑界面**；主题/水印/滤镜的真实生效逻辑全部
 * 收敛在 {@code stores/app.ts} 的 watch 里 —— 关掉抽屉、甚至删掉这个
 * 组件，所有设置依然生效且被持久化。"设置界面"与"设置生效"分离，
 * 是这类面板最容易做反的地方。
 *
 * <h3>为什么"菜单布局"和"页签风格"用自绘缩略图而不是下拉框</h3>
 * 这两项的取值是**视觉结果**，不是抽象名词。让用户在下拉框里读
 * "纵向 / 横向 / 混合"必须先在脑子里翻译一遍，选完还要等界面变化才知道对不对。
 * 缩略图 + 实时预览把这个循环缩短成"看图点击" —— 这也是这类配置面板
 * 的通用做法（截图里的 PureAdmin 同理）。代价是多写几十行 CSS，
 * 换来的是零解释成本。
 *
 * <h3>主题色：预设 + 调色板</h3>
 * <ul>
 *   <li>8 个预设色板：覆盖常见品牌色系，一键切换</li>
 *   <li>原生 {@code <input type="color">} 当调色板：零依赖、系统级取色器，
 *       代替再注册一个 Naive 的 NColorPicker（体积不值当）</li>
 *   <li>hover/pressed 色阶由主题包派生 —— 用户只选一个颜色</li>
 * </ul>
 */

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ (e: 'update:visible', value: boolean): void }>()

const appStore = useAppStore()

/** 预设品牌色板（按色相环分布，深浅兼顾）。 */
const PRESET_COLORS = [
  '#2563eb', // 蓝（默认）
  '#722ed1', // 紫
  '#eb2f96', // 品红
  '#d03050', // 红
  '#f0a020', // 橙
  '#13c2c2', // 青
  '#52c41a', // 绿
  '#6366f1' // 靛
]

const visible = computed({
  get: () => props.visible,
  set: (value: boolean) => emit('update:visible', value)
})

function close(): void {
  visible.value = false
}

// ---------------------------------------------------------------------
// 布局相关
// ---------------------------------------------------------------------

interface LayoutOption {
  value: LayoutMode
  label: string
}

const layoutOptions: LayoutOption[] = [
  { value: 'vertical', label: '左侧菜单' },
  { value: 'horizontal', label: '顶栏菜单' },
  { value: 'mix', label: '混合' }
]

function pickLayout(value: LayoutMode): void {
  appStore.layoutMode = value
  // 横向模式没有侧栏，折叠状态留着会让切回纵向时"莫名是收起的"
  if (value === 'horizontal') {
    appStore.sidebarCollapsed = false
  }
}

interface TabStyleOption {
  value: TabStyle
  label: string
}

const tabStyleOptions: TabStyleOption[] = [
  { value: 'smart', label: '灵动' },
  { value: 'card', label: '卡片' },
  { value: 'google', label: '谷歌' },
  { value: 'neon', label: '霓虹' },
  { value: 'pill', label: '胶囊' }
]

function pickTabStyle(value: TabStyle): void {
  appStore.tabStyle = value
}

/**
 * 页宽：自定义值收敛在 400~2560 之间（超出范围在宽屏上失去意义）。
 *
 * 输入交互刻意用"自由输入、失焦提交"：受控输入若在打字过程中即时回写
 * （输入 1200，打到 "1" 就被钳到 960 回显），数字根本打不进来 ——
 * 所以过程只编辑本地值，blur / 回车才提交并归一。
 */
const widthInput = ref(String(appStore.pageWidth))

watch(
  () => appStore.pageWidth,
  (value) => {
    widthInput.value = String(value)
  }
)

function commitPageWidth(): void {
  const parsed = Number(widthInput.value)
  if (Number.isFinite(parsed) && parsed > 0) {
    appStore.pageWidth = Math.min(2560, Math.max(400, Math.round(parsed)))
  } else {
    widthInput.value = String(appStore.pageWidth)
  }
}

// ---------------------------------------------------------------------
// 主题 / 主题色
// ---------------------------------------------------------------------

function pickPreset(color: string): void {
  appStore.setPrimaryColor(color)
}

function onCustomColor(event: Event): void {
  const value = (event.target as HTMLInputElement).value
  appStore.setPrimaryColor(value)
}

function resetPrimary(): void {
  appStore.setPrimaryColor('')
  feedback.success('已恢复默认主题色')
}

function resetAll(): void {
  appStore.layoutMode = 'vertical'
  appStore.tabStyle = 'card'
  appStore.pageWidthMode = 'fixed'
  appStore.pageWidth = 1600
  appStore.showTagsView = true
  appStore.hideFooter = false
  appStore.tabsPersistence = true
  appStore.showLogo = true
  appStore.fixedHeader = true
  appStore.grayMode = false
  appStore.weakenMode = false
  feedback.success('已恢复默认界面配置')
}

interface ThemeOption {
  value: 'light' | 'dark' | 'auto'
  label: string
}

const themeOptions: ThemeOption[] = [
  { value: 'light', label: '☀ 浅色' },
  { value: 'dark', label: '🌙 深色' },
  { value: 'auto', label: '🖥 跟随系统' }
]
</script>

<template>
  <NDrawer v-model:show="visible" :width="316" placement="right">
    <NDrawerContent title="系统配置" closable @close="close">
      <!-- ===== 菜单布局 ===== -->
      <section class="set-group">
        <h4 class="set-group__title">菜单布局</h4>
        <div class="layout-picker">
          <button
            v-for="option in layoutOptions"
            :key="option.value"
            type="button"
            class="layout-picker__item"
            :class="{ 'layout-picker__item--active': appStore.layoutMode === option.value }"
            @click="pickLayout(option.value)"
          >
            <span class="layout-picker__thumb" :class="`layout-picker__thumb--${option.value}`">
              <i class="layout-picker__bar" />
              <i class="layout-picker__pane" />
            </span>
            <span class="layout-picker__label">{{ option.label }}</span>
          </button>
        </div>
      </section>

      <!-- ===== 页宽 ===== -->
      <section class="set-group">
        <h4 class="set-group__title">页宽</h4>
        <div class="set-tabs">
          <button
            type="button"
            class="set-tabs__item"
            :class="{ 'set-tabs__item--active': appStore.pageWidthMode === 'fixed' }"
            @click="appStore.pageWidthMode = 'fixed'"
          >
            固定
          </button>
          <button
            type="button"
            class="set-tabs__item"
            :class="{ 'set-tabs__item--active': appStore.pageWidthMode === 'custom' }"
            @click="appStore.pageWidthMode = 'custom'"
          >
            自定义
          </button>
        </div>
        <div v-if="appStore.pageWidthMode === 'custom'" class="set-group__row">
          <NInput
            v-model:value="widthInput"
            size="small"
            placeholder="内容区最大宽度"
            @blur="commitPageWidth"
            @keyup.enter="commitPageWidth"
          >
            <template #suffix>px</template>
          </NInput>
        </div>
        <p v-else class="set-group__tip">占满可用宽度 —— 内容铺满，宽屏下不再被限宽。</p>
      </section>

      <!-- ===== 页签风格 ===== -->
      <section class="set-group">
        <h4 class="set-group__title">页签风格</h4>
        <div class="tab-picker">
          <button
            v-for="option in tabStyleOptions"
            :key="option.value"
            type="button"
            class="tab-picker__item"
            :class="{ 'tab-picker__item--active': appStore.tabStyle === option.value }"
            @click="pickTabStyle(option.value)"
          >
            <span class="tab-picker__preview" :class="`tab-picker__preview--${option.value}`">Aa</span>
            <span class="tab-picker__label">{{ option.label }}</span>
          </button>
        </div>
      </section>

      <!-- ===== 主题模式 ===== -->
      <section class="set-group">
        <h4 class="set-group__title">主题模式</h4>
        <NRadioGroup
          :value="appStore.themeMode"
          size="small"
          @update:value="(value: 'light' | 'dark' | 'auto') => appStore.setTheme(value)"
        >
          <NRadioButton
            v-for="option in themeOptions"
            :key="option.value"
            :value="option.value"
            :label="option.label"
          />
        </NRadioGroup>
        <p v-if="appStore.themeMode === 'auto'" class="set-group__tip">
          当前跟随系统：系统为{{ appStore.resolvedTheme === 'dark' ? '深色' : '浅色' }}模式。
        </p>
      </section>

      <!-- ===== 主题色 ===== -->
      <section class="set-group">
        <h4 class="set-group__title">主题色</h4>
        <div class="set-swatch-row">
          <button
            v-for="color in PRESET_COLORS"
            :key="color"
            type="button"
            class="set-swatch"
            :class="{ 'set-swatch--active': appStore.primaryColor === color }"
            :style="{ background: color }"
            :title="color"
            @click="pickPreset(color)"
          >
            <span v-if="appStore.primaryColor === color" class="set-swatch__check">✓</span>
          </button>
          <!-- 调色板：原生取色器（系统级），空值时预览默认蓝 -->
          <label class="set-swatch set-swatch--custom" title="自定义主题色">
            <input
              type="color"
              class="set-swatch__picker"
              :value="appStore.primaryColor || '#2563eb'"
              @input="onCustomColor"
            />
            <span class="set-swatch__plus">+</span>
          </label>
        </div>
        <div class="set-group__row">
          <NInput
            :value="appStore.primaryColor"
            size="small"
            placeholder="如 #722ed1"
            @update:value="(value: string) => appStore.setPrimaryColor(value)"
          />
          <NButton size="small" @click="resetPrimary">默认</NButton>
        </div>
      </section>

      <!-- ===== 界面显示 ===== -->
      <section class="set-group">
        <h4 class="set-group__title">界面显示</h4>
        <div class="set-group__row set-group__row--between">
          <span>显示标签页</span>
          <NSwitch v-model:value="appStore.showTagsView" size="small" />
        </div>
        <div class="set-group__row set-group__row--between">
          <span>页签持久化</span>
          <NSwitch v-model:value="appStore.tabsPersistence" size="small" />
        </div>
        <div class="set-group__row set-group__row--between">
          <span>头部固定</span>
          <NSwitch v-model:value="appStore.fixedHeader" size="small" />
        </div>
        <div class="set-group__row set-group__row--between">
          <span>显示页脚</span>
          <NSwitch
            :value="!appStore.hideFooter"
            size="small"
            @update:value="(value: boolean) => (appStore.hideFooter = !value)"
          />
        </div>
        <div class="set-group__row set-group__row--between">
          <span>显示 Logo</span>
          <NSwitch v-model:value="appStore.showLogo" size="small" />
        </div>
        <div class="set-group__row set-group__row--between">
          <span>灰色模式</span>
          <NSwitch v-model:value="appStore.grayMode" size="small" />
        </div>
        <div class="set-group__row set-group__row--between">
          <span>色弱模式</span>
          <NSwitch v-model:value="appStore.weakenMode" size="small" />
        </div>
        <p class="set-group__tip">页签持久化：刷新后恢复上次打开的一组标签。</p>
      </section>

      <!-- ===== 全屏水印 ===== -->
      <section class="set-group">
        <div class="set-group__row set-group__row--between">
          <h4 class="set-group__title">全屏水印</h4>
          <NSwitch v-model:value="appStore.watermarkEnabled" size="small" />
        </div>
        <div v-if="appStore.watermarkEnabled" class="set-group__row">
          <NInput
            v-model:value="appStore.watermarkText"
            size="small"
            placeholder="水印文本（如工号 / 部门）"
          />
        </div>
      </section>

      <!-- ===== 恢复默认 ===== -->
      <section class="set-group">
        <NButton block size="small" @click="resetAll">恢复默认界面配置</NButton>
      </section>
    </NDrawerContent>
  </NDrawer>
</template>

<style scoped>
.set-group {
  margin-bottom: 24px;
}

.set-group__title {
  margin: 0 0 10px;
  font-size: var(--wa-font-size-md, 14px);
  font-weight: 600;
  color: var(--wa-text-primary, #1f2329);
}

.set-group__tip {
  margin: 8px 0 0;
  font-size: var(--wa-font-size-xs, 12px);
  color: var(--wa-text-secondary, #5c6570);
}

.set-group__row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 10px;
}

.set-group__row--between {
  justify-content: space-between;
  margin-top: 0;
  padding: 8px 0;
}

/* ===== 菜单布局：缩略图选择器 ===== */
.layout-picker {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}

.layout-picker__item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 8px 4px;
  border: 1px solid var(--wa-border);
  border-radius: var(--wa-radius-md, 6px);
  background: var(--wa-bg-elevated, #fff);
  cursor: pointer;
  transition: transform var(--wa-motion-duration-fast, 0.1s), border-color 0.15s ease,
    box-shadow 0.15s ease;
}

.layout-picker__item:hover {
  transform: translateY(-2px);
  border-color: var(--wa-color-primary, #2563eb);
}

.layout-picker__item--active {
  border-color: var(--wa-color-primary, #2563eb);
  box-shadow: 0 0 0 2px var(--wa-color-primary-bg, rgba(37, 99, 235, 0.18));
}

/* 用两三个色块摆出布局形态：位置关系一眼可辨 */
.layout-picker__thumb {
  position: relative;
  display: block;
  width: 100%;
  height: 40px;
  border-radius: var(--wa-radius-sm, 4px);
  background: var(--wa-bg-hover, #f0f2f5);
  overflow: hidden;
}

.layout-picker__bar,
.layout-picker__pane {
  position: absolute;
  display: block;
  border-radius: 2px;
}

.layout-picker__bar {
  background: var(--wa-text-tertiary, #8a939f);
}

.layout-picker__pane {
  background: var(--wa-color-primary, #2563eb);
  opacity: 0.75;
}

.layout-picker__thumb--vertical .layout-picker__bar {
  left: 4px;
  top: 4px;
  bottom: 4px;
  width: 10px;
}

.layout-picker__thumb--vertical .layout-picker__pane {
  left: 18px;
  right: 4px;
  top: 4px;
  bottom: 4px;
}

.layout-picker__thumb--horizontal .layout-picker__bar {
  left: 4px;
  right: 4px;
  top: 4px;
  height: 9px;
}

.layout-picker__thumb--horizontal .layout-picker__pane {
  left: 4px;
  right: 4px;
  top: 17px;
  bottom: 4px;
}

.layout-picker__thumb--mix .layout-picker__bar {
  left: 4px;
  right: 4px;
  top: 4px;
  height: 9px;
}

/* 混合：顶栏 + 左栏 + 内容 三块，体现"一级在顶、二级在侧" */
.layout-picker__thumb--mix .layout-picker__pane {
  left: 4px;
  top: 17px;
  bottom: 4px;
  width: 12px;
}

.layout-picker__thumb--mix::after {
  content: '';
  position: absolute;
  left: 20px;
  right: 4px;
  top: 17px;
  bottom: 4px;
  border-radius: 2px;
  background: var(--wa-bg-elevated, #fff);
  opacity: 0.85;
}

.layout-picker__label {
  font-size: var(--wa-font-size-xs, 12px);
  color: var(--wa-text-secondary, #5c6570);
}

/* ===== 页宽 / 页签：分段按钮 ===== */
.set-tabs {
  display: flex;
  gap: 6px;
  padding: 3px;
  border-radius: var(--wa-radius-md, 6px);
  background: var(--wa-bg-hover, #f0f2f5);
}

.set-tabs__item {
  flex: 1;
  height: 26px;
  border: none;
  border-radius: var(--wa-radius-sm, 4px);
  background: transparent;
  color: var(--wa-text-secondary, #5c6570);
  font-size: var(--wa-font-size-sm, 12px);
  cursor: pointer;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.set-tabs__item--active {
  background: var(--wa-bg-elevated, #fff);
  color: var(--wa-color-primary, #2563eb);
  font-weight: 600;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}

/* ===== 页签风格：带迷你预览 ===== */
.tab-picker {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(64px, 1fr));
  gap: 8px;
}

.tab-picker__item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 6px 2px;
  border: 1px solid var(--wa-border);
  border-radius: var(--wa-radius-md, 6px);
  background: var(--wa-bg-elevated, #fff);
  cursor: pointer;
  transition: transform var(--wa-motion-duration-fast, 0.1s), border-color 0.15s ease,
    box-shadow 0.15s ease;
}

.tab-picker__item:hover {
  transform: translateY(-2px);
  border-color: var(--wa-color-primary, #2563eb);
}

.tab-picker__item--active {
  border-color: var(--wa-color-primary, #2563eb);
  box-shadow: 0 0 0 2px var(--wa-color-primary-bg, rgba(37, 99, 235, 0.18));
}

.tab-picker__label {
  font-size: var(--wa-font-size-xs, 12px);
  color: var(--wa-text-secondary, #5c6570);
}

/* 迷你标签：五种风格各自的形状/配色差异要在这一小块里看得出来 */
.tab-picker__preview {
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 40px;
  height: 22px;
  padding: 0 8px;
  font-size: 11px;
  line-height: 1;
}

.tab-picker__preview--smart {
  border-radius: 4px 4px 0 0;
  background: var(--wa-color-primary-bg, #eef4ff);
  color: var(--wa-color-primary, #2563eb);
  box-shadow: inset 0 -2px 0 var(--wa-color-primary, #2563eb);
}

.tab-picker__preview--card {
  border: 1px solid var(--wa-border);
  border-radius: 3px;
  background: var(--wa-bg-hover, #f0f2f5);
}

.tab-picker__preview--google {
  border-radius: 8px 8px 0 0;
  background: var(--wa-bg-elevated, #fff);
  box-shadow: 0 -1px 0 var(--wa-border), -1px 0 0 var(--wa-border), 1px 0 0 var(--wa-border);
}

.tab-picker__preview--neon {
  border-radius: 6px;
  border: 1px solid var(--wa-color-primary, #2563eb);
  color: var(--wa-color-primary, #2563eb);
  background: transparent;
  box-shadow: 0 0 10px var(--wa-color-primary-bg, rgba(37, 99, 235, 0.35));
}

.tab-picker__preview--pill {
  border-radius: 999px;
  background: linear-gradient(135deg, var(--wa-color-primary, #2563eb), rgba(34, 211, 238, 0.9));
  color: #fff;
}

/* ===== 色板 ===== */
.set-swatch-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.set-swatch {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  cursor: pointer;
  border: 2px solid transparent;
  border-radius: 50%;
  box-shadow: 0 0 0 1px rgba(0, 0, 0, 0.08);
  transition: transform var(--wa-motion-duration-fast, 0.1s);
}

.set-swatch:hover {
  transform: scale(1.12);
}

.set-swatch--active {
  border-color: var(--wa-bg-elevated, #fff);
  box-shadow: 0 0 0 2px var(--wa-color-primary, #2563eb);
}

.set-swatch__check {
  color: #fff;
  font-size: 12px;
  line-height: 1;
}

/* 调色板：原生取色器隐藏原生外观，+ 号提示可自定义 */
.set-swatch--custom {
  background: var(--wa-bg-hover, #f0f2f5);
  border-style: dashed;
  overflow: hidden;
}

.set-swatch__picker {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}

.set-swatch__plus {
  color: var(--wa-text-secondary, #5c6570);
  font-size: 15px;
  line-height: 1;
  pointer-events: none;
}
</style>
