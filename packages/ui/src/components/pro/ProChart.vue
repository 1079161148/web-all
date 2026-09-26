<script setup lang="ts">
import { computed, ref, shallowRef, watch, type Component } from 'vue'
import { NEmpty, NSpin } from 'naive-ui'
import type { EChartsOption } from 'echarts'
import { ensureEcharts } from '../../adapters/lazy'

/**
 * 图表（ECharts 6 + vue-echarts 内核）。
 *
 * <pre>{@code
 * <ProChart :option="option" :height="320" :theme="isDark ? 'dark' : undefined" />
 * }</pre>
 *
 * <h3>只做四件事，`option` 原样透传</h3>
 * <ol>
 *   <li><b>按需加载内核</b>（ECharts 约 150 KB gzip，不能进首屏）</li>
 *   <li><b>自适应尺寸</b>（容器变化时重绘）</li>
 *   <li><b>状态矩阵</b>：加载中 / 无数据 / 内核加载失败</li>
 *   <li><b>主题</b>（跟随亮暗切换）</li>
 * </ol>
 * {@code option} <b>不重新定义、不做二次映射</b> —— ECharts 的配置项本身就是
 * 一份成熟且庞大的 DSL，在中间再造一层只会让人既不懂 ECharts 也不懂我们
 * （{@code ui-component-policy} §4 明确要求：ECharts 的 option 原样透传）。
 *
 * <h3>⚠️ 没有 option 时连内核都不加载</h3>
 * 若只是"渲染一个空图表"，没必要为它下载 150 KB。
 * 因此内核加载由 {@code option} 是否出现来触发 —— 看板上多数卡片
 * 在数据回来之前不该产生任何网络开销。
 *
 * <h3>⚠️ 加载态用骨架屏不合适</h3>
 * 本包有 {@code AppSkeleton}，但图表<b>没法预演形状</b>：
 * 数据回来前不知道它是折线、饼图还是雷达图，画一个表格状的骨架反而误导。
 * 因此这里用一个居中 spinner，让"正在加载"这件事保持中性。
 *
 * <h3>已知边界（刻意不做，而非遗漏）</h3>
 * 未暴露 ECharts 实例与事件绑定。原因是它需要 vue-echarts 的实例暴露形状，
 * 而本组件尚未在真实运行环境中验证过那一层 —— <b>不发布没验证过的 API</b>。
 * 需要深度集成（点击图例联动、导出图片）时，请先确认接口形状再补开放口。
 */

const props = withDefaults(
  defineProps<{
    /** ECharts 配置项（原样透传）。为空时显示空态且不加载内核。 */
    option?: EChartsOption | null
    /** 高度。数字按 px 处理。 */
    height?: number | string
    /** 外部加载态（数据请求中）。 */
    loading?: boolean
    /** 主题。内置 {@code 'dark'} 或已注册的自定义主题名/对象。 */
    theme?: string | Record<string, unknown>
    /** 无数据时的提示。 */
    emptyText?: string
    /** 容器尺寸变化时自动重绘。 */
    autoresize?: boolean
  }>(),
  {
    option: null,
    height: 320,
    loading: false,
    theme: undefined,
    emptyText: '暂无数据',
    autoresize: true
  }
)

const emit = defineEmits<{
  /** 内核加载失败。组件不自行提示，由应用决定怎么呈现。 */
  (e: 'error', error: unknown): void
}>()

/**
 * 内核组件。
 *
 * <p>用 {@code shallowRef} 而不是 {@code ref}：整个组件对象不需要被深度追踪，
 * 用 ref 会让 Vue 递归遍历它（组件对象内部结构复杂且庞大），
 * 是一个典型但容易被忽略的性能浪费。
 */
const chartComponent = shallowRef<Component | null>(null)

const kernelLoading = ref(false)
const kernelError = ref<unknown>(null)

const resolvedHeight = computed(() =>
  typeof props.height === 'number' ? `${props.height}px` : props.height
)

/** 无数据：显示空态，并且不触发内核加载。 */
const isEmpty = computed(() => !props.option)

async function loadKernel(): Promise<void> {
  kernelLoading.value = true
  try {
    const mod = await ensureEcharts()
    chartComponent.value = mod.VChart as Component
  } catch (error) {
    kernelError.value = error
    emit('error', error)
  } finally {
    kernelLoading.value = false
  }
}

watch(
  () => props.option,
  (option) => {
    // 有数据才加载内核；已加载/加载中则跳过（并发挂载多个图表时不会重复加载 ——
    // ensureEcharts 自身有模块缓存，这里的判断只是省掉多余的 await）
    if (option && !chartComponent.value && !kernelLoading.value && !kernelError.value) {
      void loadKernel()
    }
  },
  { immediate: true }
)
</script>

<template>
  <div class="pro-chart" :style="{ height: resolvedHeight }">
    <!-- 内核加载失败：给出可见原因，而不是永远转圈 -->
    <n-empty
      v-if="kernelError"
      class="pro-chart__center"
      description="图表内核加载失败，请刷新重试"
    />

    <n-empty v-else-if="isEmpty" class="pro-chart__center" :description="emptyText" />

    <div v-else-if="loading || kernelLoading || !chartComponent" class="pro-chart__center">
      <n-spin />
    </div>

    <component
      :is="chartComponent"
      v-else
      class="pro-chart__canvas"
      :option="option"
      :theme="theme"
      :autoresize="autoresize"
    />
  </div>
</template>

<style scoped>
/* 只做结构与尺寸，容器高度由调用方给定（图表必须有一个确定的高度才能绘制） */
.pro-chart {
  position: relative;
  width: 100%;
}

.pro-chart__center {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}

.pro-chart__canvas {
  width: 100%;
  height: 100%;
}
</style>
