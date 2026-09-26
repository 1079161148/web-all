<script setup lang="ts">
import { NSkeleton } from 'naive-ui'

/**
 * 骨架屏。
 *
 * <h3>为什么用骨架屏而不是转圈</h3>
 * 转圈只告诉用户"在加载"，骨架屏还额外告诉他"<b>将要出现什么形状的内容</b>"。
 * 对表格/详情这类结构固定的页面，后者的感知等待时间明显更短 ——
 * 内容到位时视觉不发生跳变，不会出现"转圈 → 突然一整屏"的闪烁。
 *
 * <h3>形状必须贴近真实内容</h3>
 * 骨架屏最常见的误用是"随便放几个灰条"。若真实内容是
 * 「标题 + 4 个字段的两列布局」，而骨架是 3 条线，
 * 那么内容加载完会有一次明显的重排 —— 反而比转圈更糟。
 *
 * <p>因此本组件提供 {@code variant} 预设而不是让调用方自由拼灰条：
 * 预设的形状与项目里真实页面的结构一致。
 */
withDefaults(
  defineProps<{
    /**
     * 骨架形状。
     *
     * <ul>
     *   <li>{@code table} —— 表头 + N 行 × M 列，用于列表页</li>
     *   <li>{@code form} —— 若干"标签 + 输入框"两列，用于表单页</li>
     *   <li>{@code detail} —— 标题 + 字段网格，用于详情页</li>
     *   <li>{@code text} —— 若干行文字，用于正文/说明</li>
     * </ul>
     */
    variant?: 'table' | 'form' | 'detail' | 'text'
    /** 行数/字段数。 */
    rows?: number
    /** 列数（table / form 用）。 */
    cols?: number
  }>(),
  {
    variant: 'table',
    rows: 6,
    cols: 5
  }
)
</script>

<template>
  <div class="app-skeleton" :class="`app-skeleton--${variant}`" aria-busy="true" aria-live="polite">
    <!-- 表格：表头 + 数据行 -->
    <template v-if="variant === 'table'">
      <div class="app-skeleton__row app-skeleton__row--head">
        <n-skeleton v-for="c in cols" :key="`h${c}`" text style="width: 100%" />
      </div>
      <div v-for="r in rows" :key="`r${r}`" class="app-skeleton__row">
        <n-skeleton v-for="c in cols" :key="`r${r}c${c}`" text style="width: 100%" />
      </div>
    </template>

    <!-- 表单：标签 + 控件 -->
    <template v-else-if="variant === 'form'">
      <div v-for="r in rows" :key="`f${r}`" class="app-skeleton__field">
        <n-skeleton text style="width: 80px" />
        <n-skeleton text style="width: 100%; height: 32px" />
      </div>
    </template>

    <!-- 详情：标题 + 字段网格 -->
    <template v-else-if="variant === 'detail'">
      <n-skeleton text style="width: 30%; height: 24px" />
      <div class="app-skeleton__grid">
        <div v-for="r in rows" :key="`d${r}`" class="app-skeleton__field">
          <n-skeleton text style="width: 80px" />
          <n-skeleton text style="width: 100%" />
        </div>
      </div>
    </template>

    <!-- 正文 -->
    <template v-else>
      <n-skeleton v-for="r in rows" :key="`t${r}`" text :style="{ width: r === rows ? '60%' : '100%' }" />
    </template>
  </div>
</template>

<style scoped>
.app-skeleton {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-md);
  width: 100%;
}

.app-skeleton__row {
  display: grid;
  grid-template-columns: repeat(v-bind(cols), minmax(0, 1fr));
  gap: var(--wa-spacing-lg);
  padding: var(--wa-spacing-sm) 0;
}

.app-skeleton__row--head {
  border-bottom: 1px solid var(--wa-border);
  padding-bottom: var(--wa-spacing-md);
}

.app-skeleton__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--wa-spacing-lg);
  margin-top: var(--wa-spacing-lg);
}

.app-skeleton__field {
  display: flex;
  align-items: center;
  gap: var(--wa-spacing-md);
}
</style>
