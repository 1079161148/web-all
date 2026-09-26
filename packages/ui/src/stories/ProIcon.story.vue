<script setup lang="ts">
import { defineComponent, h } from 'vue'
import ProIcon from '../components/pro/ProIcon.vue'
import { registerIcons } from '../registry/icon'

/**
 * ProIcon 的 story。
 *
 * <p>story 里不依赖 @vicons —— 用两个内联 SVG 组件模拟"应用注册过的图标"，
 * 验证的是解析与降级行为，不是某个图标集的形状。
 */

/** 模拟 @vicons 组件：一个实心圆 / 一个方框。 */
const DemoCircle = defineComponent({
  name: 'DemoCircle',
  render: () =>
    h('svg', { viewBox: '0 0 24 24', width: '1em', height: '1em' }, [
      h('circle', { cx: 12, cy: 12, r: 9, fill: 'currentColor' })
    ])
})

const DemoSquare = defineComponent({
  name: 'DemoSquare',
  render: () =>
    h('svg', { viewBox: '0 0 24 24', width: '1em', height: '1em' }, [
      h('rect', {
        x: 4,
        y: 4,
        width: 16,
        height: 16,
        rx: 2,
        fill: 'none',
        stroke: 'currentColor',
        'stroke-width': 2
      })
    ])
})

// 注册名刻意与"后端会存的图标名"同构（IconPicker 提交什么，这里就注册什么）
registerIcons([
  { name: 'DemoCircle', component: DemoCircle },
  { name: 'DemoSquare', component: DemoSquare }
])
</script>

<template>
  <Story title="Pro 组件/ProIcon" :layout="{ type: 'grid', width: 320 }">
    <Variant title="名字 → 注册表解析" doc="名字命中注册表时渲染图标组件；颜色跟随 currentColor。">
      <div class="row">
        <ProIcon name="DemoCircle" :size="20" />
        <ProIcon name="DemoSquare" :size="20" />
        <span class="row__label">DemoCircle / DemoSquare</span>
      </div>
    </Variant>

    <Variant
      title="未注册的名字 → 首字形降级"
      doc="名字查不到组件时渲染首字母（与 DictTag 显示原始码值同理）：静默空白会让「图标名拼错了」变成「菜单没图标」，排查成本极高。"
    >
      <div class="row">
        <ProIcon name="NotFound" :size="20" />
        <span class="row__label">NotFound →「N」</span>
      </div>
    </Variant>

    <Variant title="直接传组件" doc="纯前端场景（写死的页面装饰）不必绕注册表。">
      <div class="row">
        <ProIcon :name="DemoCircle" :size="20" />
        <span class="row__label">Component 直传</span>
      </div>
    </Variant>

    <Variant title="空值不渲染" doc="name 为空时不渲染任何元素 —— 空位交给布局的 gap，不留占位盒。">
      <div class="row">
        <span>前</span>
        <ProIcon :size="20" />
        <span>后</span>
      </div>
    </Variant>

    <Variant title="尺寸" doc="数值按 px；也接受任意 CSS 长度。">
      <div class="row">
        <ProIcon name="DemoSquare" :size="14" />
        <ProIcon name="DemoSquare" :size="20" />
        <ProIcon name="DemoSquare" size="28px" />
        <ProIcon name="DemoSquare" size="2em" />
      </div>
    </Variant>
  </Story>
</template>

<style scoped>
.row {
  display: flex;
  align-items: center;
  gap: var(--wa-spacing-md);
  color: var(--wa-text-primary);
}

.row__label {
  color: var(--wa-text-secondary);
  font-size: var(--wa-font-size-sm);
}
</style>
