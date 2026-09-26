<script setup lang="ts">
import { computed } from 'vue'
import { NCard, NGrid, NGridItem, NTag } from '@admin/ui'
import { ProChart } from '@admin/ui'
import type { EChartsOption } from '@admin/ui'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'

/**
 * 首页工作台。
 *
 * <h3>⚠️ 当前数据全部是前端 Mock</h3>
 * 图表与统计卡的数据是<b>静态占位</b>，用于把首页形态先立起来。
 * 真实数据接入必须走契约优先：后端补 Dashboard 统计接口（springdoc 注解完备）
 * → 产出 openapi.json → `pnpm gen:api` 生成请求函数与类型 → 本页改用
 * TanStack Query 消费（红线 3 / 红线 6）。<b>不要</b>直接在这里手写 axios 请求。
 *
 * <h3>为什么图表用 ProChart 而不是直接用 vue-echarts</h3>
 * ECharts 约 150 KB gzip，ProChart 做了按需加载（有 option 才拉内核）、
 * 尺寸自适应与暗色主题联动 —— 这些都是每个图表页都要重写一遍的样板。
 */
const appStore = useAppStore()
const authStore = useAuthStore()

const userName = computed(() => authStore.user?.nickname ?? authStore.user?.username ?? '')

/** 图表主题跟随应用的亮暗模式（ProChart 只透传 theme，不做二次映射）。 */
const chartTheme = computed(() => (appStore.themeMode === 'dark' ? 'dark' : undefined))

// ---------------------------------------------------------------------
// 统计卡（Mock，待后端统计接口替换）
// ---------------------------------------------------------------------

const stats = [
  { label: '今日活跃用户', value: '1,286', trend: '+12.4%', up: true },
  { label: '租户总数', value: '328', trend: '+6', up: true },
  { label: '今日接口调用', value: '86,421', trend: '-3.1%', up: false },
  { label: '待办审批', value: '17', trend: '+5', up: true }
]

// ---------------------------------------------------------------------
// 图表配置（Mock，option 原样透传给 ECharts）
// ---------------------------------------------------------------------

const trendOption: EChartsOption = {
  tooltip: { trigger: 'axis' },
  legend: { data: ['活跃用户', '新增租户'] },
  grid: { left: 48, right: 16, top: 40, bottom: 32 },
  xAxis: { type: 'category', data: ['周一', '周二', '周三', '周四', '周五', '周六', '周日'] },
  yAxis: { type: 'value' },
  series: [
    {
      name: '活跃用户',
      type: 'line',
      smooth: true,
      data: [820, 932, 901, 1290, 1330, 780, 640]
    },
    {
      name: '新增租户',
      type: 'line',
      smooth: true,
      data: [12, 18, 9, 24, 31, 14, 8]
    }
  ]
}

const tenantTopOption: EChartsOption = {
  tooltip: { trigger: 'axis' },
  grid: { left: 96, right: 24, top: 24, bottom: 32 },
  xAxis: { type: 'value' },
  yAxis: {
    type: 'category',
    data: ['示例租户D', '示例租户C', '示例租户B', '示例租户A']
  },
  series: [{ type: 'bar', data: [420, 680, 1240, 2130] }]
}

const resourceOption: EChartsOption = {
  tooltip: { trigger: 'item' },
  legend: { orient: 'vertical', left: 8, top: 'center' },
  series: [
    {
      name: '资源占用',
      type: 'pie',
      radius: ['45%', '70%'],
      center: ['62%', '50%'],
      data: [
        { value: 46, name: '文件存储' },
        { value: 26, name: '数据库' },
        { value: 18, name: '缓存' },
        { value: 10, name: '其它' }
      ]
    }
  ]
}

const radarOption: EChartsOption = {
  tooltip: {},
  radar: {
    indicator: [
      { name: '接口稳定性', max: 100 },
      { name: '响应速度', max: 100 },
      { name: '任务成功率', max: 100 },
      { name: '存储余量', max: 100 },
      { name: '安全基线', max: 100 }
    ]
  },
  series: [
    {
      type: 'radar',
      data: [{ value: [96, 82, 99, 64, 88], name: '本周' }]
    }
  ]
}

const healthOption: EChartsOption = {
  series: [
    {
      type: 'gauge',
      startAngle: 210,
      endAngle: -30,
      min: 0,
      max: 100,
      progress: { show: true },
      axisLine: { lineStyle: { width: 12 } },
      detail: { formatter: '{value}分', fontSize: 22 },
      data: [{ value: 92, name: '系统健康度' }]
    }
  ]
}
</script>

<template>
  <div class="dashboard">
    <!-- 问候语：身份来自 /auth/me，而非前端解析令牌 -->
    <div class="dashboard__header">
      <h2 class="dashboard__title">{{ userName ? `${userName}，欢迎回来` : '欢迎回来' }}</h2>
      <n-tag size="small" type="info">数据为演示占位，统计接口接入后自动替换</n-tag>
    </div>

    <!-- 统计卡 -->
    <n-grid :cols="24" :x-gap="12" :y-gap="12">
      <n-grid-item v-for="stat in stats" :key="stat.label" :span="24" :s="12" :m="12" :l="6">
        <n-card size="small" :bordered="true">
          <div class="dashboard__stat">
            <span class="dashboard__stat-label">{{ stat.label }}</span>
            <div class="dashboard__stat-row">
              <span class="dashboard__stat-value">{{ stat.value }}</span>
              <span
                class="dashboard__stat-trend"
                :class="{ 'dashboard__stat-trend--down': !stat.up }"
              >
                {{ stat.trend }}
              </span>
            </div>
          </div>
        </n-card>
      </n-grid-item>
    </n-grid>

    <!-- 图表区：趋势占整行，其余按两列排布 -->
    <n-grid :cols="24" :x-gap="12" :y-gap="12" class="dashboard__charts">
      <n-grid-item :span="24">
        <n-card title="近 7 日活跃趋势" size="small">
          <ProChart :option="trendOption" :height="300" :theme="chartTheme" />
        </n-card>
      </n-grid-item>

      <n-grid-item :span="24" :m="24" :l="12">
        <n-card title="租户活跃 TOP 4" size="small">
          <ProChart :option="tenantTopOption" :height="280" :theme="chartTheme" />
        </n-card>
      </n-grid-item>

      <n-grid-item :span="24" :m="24" :l="12">
        <n-card title="平台资源分布" size="small">
          <ProChart :option="resourceOption" :height="280" :theme="chartTheme" />
        </n-card>
      </n-grid-item>

      <n-grid-item :span="24" :m="24" :l="12">
        <n-card title="服务质量雷达" size="small">
          <ProChart :option="radarOption" :height="280" :theme="chartTheme" />
        </n-card>
      </n-grid-item>

      <n-grid-item :span="24" :m="24" :l="12">
        <n-card title="系统健康度" size="small">
          <ProChart :option="healthOption" :height="280" :theme="chartTheme" />
        </n-card>
      </n-grid-item>
    </n-grid>
  </div>
</template>

<style scoped>
.dashboard__header {
  display: flex;
  align-items: center;
  gap: var(--wa-spacing-md, 12px);
  margin-bottom: var(--wa-spacing-md, 12px);
}

.dashboard__title {
  margin: 0;
  font-size: var(--wa-font-size-lg, 18px);
  font-weight: 600;
}

.dashboard__stat {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-sm, 8px);
}

.dashboard__stat-label {
  color: var(--wa-text-secondary, #5c6570);
  font-size: var(--wa-font-size-sm, 12px);
}

.dashboard__stat-row {
  display: flex;
  align-items: baseline;
  gap: var(--wa-spacing-sm, 8px);
}

.dashboard__stat-value {
  font-size: 22px;
  font-weight: 600;
}

.dashboard__stat-trend {
  color: var(--wa-color-success, #18a058);
  font-size: var(--wa-font-size-sm, 12px);
}

.dashboard__stat-trend--down {
  color: var(--wa-color-danger, #d03050);
}

.dashboard__charts {
  margin-top: var(--wa-spacing-md, 12px);
}
</style>
