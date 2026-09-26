<script setup lang="ts">
import { NButton, NCard, NSpace } from 'naive-ui'

/**
 * 页面容器：统一标题、返回、卡片间距。
 *
 * <h3>为什么它值得单独存在</h3>
 * 7 个管理页各写一遍 <code>&lt;h2&gt;标题&lt;/h2&gt; + 卡片包裹 + 间距</code>，
 * 结果是每个页面的标题字号、上下留白、卡片圆角都略有不同 ——
 * 单看每一页都"没问题"，连起来翻就明显不整齐。
 * <b>这类"每一处都差不多"的不一致，靠 review 是发现不了的，只能靠收敛组件。</b>
 *
 * <h3>间距从布局 Token 来，不从页面来</h3>
 * 页面的上下留白属于设计系统决策，不该由每个页面自行决定。
 * 本组件把留白写死在 <b>Token 引用</b>上，页面只负责提供内容。
 */
const props = withDefaults(
  defineProps<{
    /** 页面标题。 */
    title?: string
    /** 标题下方的说明文字。 */
    description?: string
    /**
     * 是否显示返回按钮。
     *
     * <p>返回行为用 {@code window.history.back()} 而不是 {@code router.back()}：
     * <ul>
     *   <li><b>不引入 vue-router 依赖</b> —— 组件库一旦 import 路由，
     *       就无法脱离本项目的路由方案复用了（这与"UI 库可替换"是同一类问题）</li>
     *   <li>语义完全等价：vue-router 的 history 模式底层就是 History API，
     *       {@code history.back()} 同样会触发路由更新</li>
     * </ul>
     * 需要"返回某个固定路由"的场景（很少见）请用 {@code onBack} 覆盖。
     */
    showBack?: boolean
    /** 自定义返回行为。不传则使用 window.history.back()。 */
    onBack?: () => void
    /** 是否用卡片包裹内容。详情页/表单页通常需要，列表页不需要（表格自带边框）。 */
    card?: boolean
    /**
     * 内容区最大宽度。默认不限宽 —— 管理页的表格应当铺满内容区，
     * 限宽会让宽屏下出现"表格只占半屏 + 横向滚动"的怪相。
     * 需要限宽的页面（文档/详情类）用此 prop 显式声明。
     */
    maxWidth?: string
  }>(),
  {
    showBack: false,
    card: true,
    maxWidth: '100%'
  }
)

function handleBack(): void {
  if (props.onBack) {
    props.onBack()
    return
  }
  window.history.back()
}
</script>

<template>
  <div class="page-container" :style="{ maxWidth }">
    <header v-if="title || $slots.extra || showBack" class="page-container__header">
      <div class="page-container__title-area">
        <n-button v-if="showBack" text size="small" class="page-container__back" @click="handleBack">
          ← 返回
        </n-button>
        <h2 v-if="title" class="page-container__title">{{ title }}</h2>
        <p v-if="description" class="page-container__description">{{ description }}</p>
      </div>

      <n-space v-if="$slots.extra" :size="8" :wrap="false" class="page-container__extra">
        <slot name="extra" />
      </n-space>
    </header>

    <n-card v-if="card" :bordered="false" class="page-container__card">
      <slot />
    </n-card>
    <slot v-else />
  </div>
</template>

<style scoped>
.page-container {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-lg);
  /* 居中并限宽：超宽屏下让内容保持可读宽度，而不是铺满整个屏幕 */
  margin: 0 auto;
  width: 100%;
}

.page-container__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--wa-spacing-lg);
}

.page-container__title-area {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-xs);
  min-width: 0;
}

.page-container__back {
  align-self: flex-start;
  margin-bottom: var(--wa-spacing-xs);
}

.page-container__title {
  margin: 0;
  font-size: var(--wa-font-size-xl);
  font-weight: 600;
  line-height: var(--wa-line-height-tight, 1.25);
  color: var(--wa-text-primary);
}

.page-container__description {
  margin: 0;
  font-size: var(--wa-font-size-sm);
  color: var(--wa-text-secondary);
}

.page-container__extra {
  flex: none;
}

.page-container__card {
  border-radius: var(--wa-radius-lg);
}
</style>
