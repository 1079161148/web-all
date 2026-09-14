<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { readRouteMeta } from '@/router/dynamic'

/**
 * 页面占位视图。
 *
 * <h3>它解决的是什么问题</h3>
 * 后端菜单可以先行配置（甚至已经配好了一整套系统管理菜单），
 * 而前端页面是逐个开发的。两者必然存在时间差。
 *
 * <p>如果没有这个页面，那个时间差的表现是：<b>点菜单 → 白屏 + 控制台一行
 * "Failed to resolve component"</b>。用户看到的是"系统坏了"，
 * 开发者需要打开控制台才能知道"只是页面还没做"。
 *
 * <p>有了它，时间差变成一个<b>明确、可读的状态</b>。
 * 这正是设计文档 §11.9 状态矩阵的思路 ——
 * <b>把"故障"变成"已知状态"，是"好用"的重要来源。</b>
 *
 * <h3>它必须回答"下一步做什么"</h3>
 * 只说"尚未实现"是不够的：开发者还得回去翻后端菜单表，才知道要建什么文件。
 * 因此这里直接给出<b>应创建的文件路径</b>与<b>菜单声明的 component 值</b> ——
 * 提示的价值在于把下一步动作说清楚，而不只是陈述现状。
 * 这也是排查"component 拼错"这类问题的入口：两个值并排显示，一眼就能比对。
 */
const route = useRoute()

const meta = computed(() => readRouteMeta(route.meta))
const title = computed(() => meta.value.title ?? '未知页面')
const expectedComponent = computed(() => meta.value.expectedComponent)

/** 由后端 component 值推导出应创建的文件路径。 */
const expectedFile = computed(() =>
  expectedComponent.value ? `src/views/${expectedComponent.value}.vue` : undefined
)
</script>

<template>
  <div class="placeholder">
    <div class="placeholder__card">
      <h2 class="placeholder__title">{{ title }}</h2>
      <p class="placeholder__text">该页面尚未实现，已回退到占位页。</p>

      <dl class="placeholder__facts">
        <dt>菜单 ID</dt>
        <dd>{{ meta.menuId ?? '-' }}</dd>
        <dt>当前路径</dt>
        <dd>{{ route.path }}</dd>
        <dt v-if="expectedComponent">菜单声明的组件</dt>
        <dd v-if="expectedComponent"><code>{{ expectedComponent }}</code></dd>
      </dl>

      <p v-if="expectedFile" class="placeholder__hint">
        创建文件即可启用本页面：
        <code>{{ expectedFile }}</code>
      </p>
      <p v-else class="placeholder__hint">
        该菜单未配置组件路径，请检查后端 <code>iam_menu.component</code>。
      </p>

      <p class="placeholder__note">
        这是预期的过渡状态：后端菜单可以先配好，前端页面逐个开发。
        若该菜单本应已有页面，请核对上面的路径是否拼写一致。
      </p>
    </div>
  </div>
</template>

<style scoped>
.placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 320px;
}

.placeholder__card {
  max-width: 560px;
  padding: var(--wa-spacing-xxl, 32px);
  border: 1px dashed var(--wa-border, #e4e7ed);
  border-radius: var(--wa-radius-lg, 8px);
  background: var(--wa-bg-elevated, #fff);
}

.placeholder__title {
  margin: 0 0 var(--wa-spacing-sm, 8px);
  font-size: var(--wa-font-size-lg, 16px);
  color: var(--wa-text-primary, #1f2329);
  text-align: center;
}

.placeholder__text {
  margin: 0 0 var(--wa-spacing-lg, 16px);
  color: var(--wa-text-secondary, #5c6570);
  text-align: center;
}

.placeholder__facts {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: var(--wa-spacing-xs, 4px) var(--wa-spacing-md, 12px);
  margin: 0 0 var(--wa-spacing-lg, 16px);
  font-size: var(--wa-font-size-xs, 12px);
}

.placeholder__facts dt {
  color: var(--wa-text-disabled, #a8b0ba);
  white-space: nowrap;
}

.placeholder__facts dd {
  margin: 0;
  color: var(--wa-text-primary, #1f2329);
  word-break: break-all;
}

.placeholder__hint {
  margin: 0 0 var(--wa-spacing-sm, 8px);
  font-size: var(--wa-font-size-md, 14px);
  color: var(--wa-text-primary, #1f2329);
}

.placeholder__note {
  margin: var(--wa-spacing-lg, 16px) 0 0;
  padding-top: var(--wa-spacing-md, 12px);
  border-top: 1px solid var(--wa-divider, #ebeef5);
  font-size: var(--wa-font-size-xs, 12px);
  line-height: 1.6;
  color: var(--wa-text-disabled, #a8b0ba);
}

code {
  padding: 1px 5px;
  border-radius: 3px;
  background: var(--wa-bg-hover, #f0f2f5);
  font-size: 0.95em;
  word-break: break-all;
}
</style>
