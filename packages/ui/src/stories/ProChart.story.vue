<script setup lang="ts">
import { computed, ref, shallowRef } from 'vue'
import type { EChartsOption } from 'echarts'
import ProChart from '../components/pro/ProChart.vue'

/**
 * ProChart 的 story。
 *
 * <p>配置项刻意写成"业务看到的样子"（真实维度、真实单位），
 * 而不是 `{ x: [1,2,3] }` 这类占位数据 —— 后者看不出图表在真实数据下
 * 是否可读（长标签会不会被截断、图例会不会挤成一团）。
 */

const trendOption: EChartsOption = {
  tooltip: { trigger: 'axis' },
  legend: { data: ['新增用户', '活跃用户'] },
  grid: { left: 48, right: 16, top: 40, bottom: 32 },
  xAxis: {
    type: 'category',
    data: ['1月', '2月', '3月', '4月', '5月', '6月']
  },
  yAxis: { type: 'value' },
  series: [
    { name: '新增用户', type: 'line', smooth: true, data: [120, 186, 152, 268, 310, 402] },
    { name: '活跃用户', type: 'line', smooth: true, data: [820, 932, 901, 1290, 1330, 1450] }
  ]
}

const pieOption: EChartsOption = {
  tooltip: { trigger: 'item' },
  legend: { orient: 'vertical', left: 8, top: 'center' },
  series: [
    {
      name: '部门人数',
      type: 'pie',
      radius: ['45%', '70%'],
      center: ['62%', '50%'],
      label: { show: true },
      data: [
        { value: 42, name: '技术中心' },
        { value: 18, name: '市场部' },
        { value: 12, name: '财务部' },
        { value: 9, name: '人力资源' }
      ]
    }
  ]
}

/** 刻意用长标签，验证真实业务数据下的可读性。 */
const barOption: EChartsOption = {
  tooltip: { trigger: 'axis' },
  grid: { left: 140, right: 24, top: 24, bottom: 32 },
  xAxis: { type: 'value' },
  yAxis: {
    type: 'category',
    data: ['平台管理-系统监控-接口日志', '系统管理-角色管理', '组织管理-部门管理', '系统管理-用户管理']
  },
  series: [{ type: 'bar', data: [86, 210, 320, 540] }]
}

/**
 * ⚠️ 这里必须用 `shallowRef` 而不是 `ref`（实测踩过）。
 *
 * <p>`EChartsOption` 是一个极其庞大的联合类型，而 `ref<T>()` 会对 `T` 做
 * <b>深度响应式解包</b>（`UnwrapRef`）。解包后的类型与原来的
 * `EChartsOption` <b>不再互相兼容</b>，于是把它传给 prop 会报
 * "类型不可赋值"，且报错信息里是一坨展开的对象结构，看不出真因。
 *
 * <p>`shallowRef` 不做深度解包，类型原样保留。
 * 图表的配置项本来就是整体替换（不是改某个字段），
 * 因此也不需要深度响应式 —— 这是"类型对了，语义也更对"的一处。
 */
const emptyOption = shallowRef<EChartsOption | null>(null)

const delayedOption = shallowRef<EChartsOption | null>(null)
const loading = ref(false)

/** 模拟"数据还没回来"：2 秒后才给 option，验证加载态与内核按需加载。 */
function simulateFetch(): void {
  delayedOption.value = null
  loading.value = true
  window.setTimeout(() => {
    delayedOption.value = trendOption
    loading.value = false
  }, 1200)
}

const darkTheme = ref(false)
const themedOption = computed(() => trendOption)
</script>

<template>
  <Story title="Pro 组件/ProChart" :layout="{ type: 'grid', width: '100%' }">
    <Variant
      title="基础用法：option 原样透传"
      doc="HTML 里不重新定义任何 ECharts 配置。要什么画什么，完全由 ECharts 的能力决定。"
    >
      <ProChart :option="trendOption" :height="300" />
    </Variant>

    <Variant title="饼图" doc="同一个组件，换一张类图只是换 option。">
      <ProChart :option="pieOption" :height="300" />
    </Variant>

    <Variant
      title="长标签（真实业务数据）"
      doc="标签很长时必须给 grid.left 留足宽度，否则会被截断 —— 这类问题只有用真实维度才看得出来。"
    >
      <ProChart :option="barOption" :height="300" />
    </Variant>

    <Variant
      title="⚠️ 无数据时不加载内核"
      doc="没有 option 时显示空态，并且**完全不加载 ECharts**（150 KB）。看板上多数卡片在数据回来前不该产生任何网络开销。"
    >
      <ProChart :option="emptyOption" :height="300" empty-text="该时间段没有数据" />
    </Variant>

    <Variant
      title="加载态 → 有数据"
      doc="点下面的按钮：先显示居中 spinner，数据到了才加载内核并绘制。刻意不用骨架屏 —— 图表没法预演形状，画一个表格状骨架反而误导。"
    >
      <button type="button" @click="simulateFetch">模拟一次数据请求</button>
      <ProChart :option="delayedOption" :height="300" :loading="loading" />
    </Variant>

    <Variant
      title="深色主题"
      doc="theme 透传给内核。ECharts 内置 'dark'，自定义主题由应用注册。"
    >
      <ProChart :option="themedOption" :height="300" :theme="darkTheme ? 'dark' : undefined" />
      <button type="button" @click="darkTheme = !darkTheme">
        切换为{{ darkTheme ? '浅色' : '深色' }}主题
      </button>
    </Variant>
  </Story>
</template>

<style scoped>
button {
  margin-bottom: var(--wa-spacing-md);
  padding: var(--wa-spacing-sm) var(--wa-spacing-md);
  border: 1px solid var(--wa-border);
  border-radius: var(--wa-radius-sm);
  background: var(--wa-bg-hover);
  cursor: pointer;
}
</style>
