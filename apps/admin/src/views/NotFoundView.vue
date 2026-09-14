<script setup lang="ts">
import { useRouter } from 'vue-router'

/**
 * 404 页面。
 *
 * <p>按状态矩阵规范（设计文档 §11.9），任何异常状态都必须给出**可操作的出口**，
 * 而不是只显示一句「页面不存在」让用户自己想办法。
 */
const router = useRouter()
</script>

<template>
  <div class="not-found">
    <p class="not-found__code">404</p>
    <p class="not-found__title">页面不存在</p>
    <p class="not-found__description">你访问的页面可能已被移动或删除</p>
    <div class="not-found__actions">
      <button type="button" class="not-found__btn not-found__btn--primary" @click="router.push('/')">
        返回首页
      </button>
      <button type="button" class="not-found__btn" @click="router.back()">返回上一页</button>
    </div>
  </div>
</template>

<style scoped>
.not-found {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
  text-align: center;
}

.not-found__code {
  margin: 0;
  font-size: 72px;
  font-weight: 700;
  color: var(--wa-text-disabled, #a8b0ba);
}

.not-found__title {
  margin: var(--wa-spacing-md, 12px) 0 var(--wa-spacing-xs, 4px);
  font-size: var(--wa-font-size-xl, 18px);
  color: var(--wa-text-primary, #1f2329);
}

.not-found__description {
  margin: 0 0 var(--wa-spacing-xl, 24px);
  font-size: var(--wa-font-size-md, 14px);
  color: var(--wa-text-secondary, #5c6570);
}

.not-found__actions {
  display: flex;
  gap: var(--wa-spacing-sm, 8px);
}

.not-found__btn {
  padding: var(--wa-spacing-xs, 4px) var(--wa-spacing-lg, 16px);
  border: 1px solid var(--wa-border, #e4e7ed);
  border-radius: var(--wa-radius-md, 4px);
  background: transparent;
  color: var(--wa-text-primary, #1f2329);
  cursor: pointer;
}

.not-found__btn--primary {
  border-color: var(--wa-color-primary, #2563eb);
  background: var(--wa-color-primary, #2563eb);
  color: #fff;
}
</style>
