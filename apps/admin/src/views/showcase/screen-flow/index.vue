<template>
  <div ref="rootEl" class="screen" :style="rootStyle">
    <!-- ============ 顶栏：标题 + 关键指标 + 控制 ============ -->
    <header class="screen__head">
      <div class="screen__brand">
        <span class="screen__brand-main">智能制造运行监控中心</span>
        <span class="screen__brand-sub">华东制造基地 · 实时数据</span>
      </div>

      <div class="screen__kpis">
        <div v-for="item in kpis" :key="item.label" class="screen__kpi">
          <span class="screen__kpi-label">{{ item.label }}</span>
          <span class="screen__kpi-value">{{ item.value }}</span>
          <span class="screen__kpi-unit">{{ item.unit }}</span>
          <span
            class="screen__kpi-delta"
            :class="item.delta >= 0 ? 'screen__kpi-delta--up' : 'screen__kpi-delta--down'"
          >
            {{ item.delta >= 0 ? '▲' : '▼' }}{{ Math.abs(item.delta) }}%
          </span>
        </div>
      </div>

      <div class="screen__ctrl">
        <span class="screen__clock">{{ clock }}</span>
        <NSwitch v-model:value="live" size="small" />
        <span class="screen__ctrl-label">实时</span>
        <NButton size="tiny" quaternary @click="refresh">刷新</NButton>
      </div>
    </header>

    <!-- ============ 主体：三列流式栅格 ============ -->
    <main class="screen__grid">
      <section class="screen__col">
        <div class="panel">
          <h3 class="panel__title">设备状态分布</h3>
          <div class="panel__body">
            <ProChart :option="statusOption" height="100%" />
          </div>
        </div>
        <div class="panel">
          <h3 class="panel__title">产线产能达成</h3>
          <div class="panel__body">
            <ProChart :option="lineOption" height="100%" />
          </div>
        </div>
      </section>

      <section class="screen__col screen__col--main">
        <div class="panel panel--map">
          <h3 class="panel__title">
            全国在网设备分布
            <span class="panel__hint">视觉映射：区域设备数 · 悬停查看明细</span>
          </h3>
          <div class="panel__body">
            <ProChart v-if="mapReady" :option="mapOption" height="100%" />
            <div v-else class="panel__loading">
              {{ mapError ? `地图数据加载失败：${mapError}` : '地图数据加载中…' }}
            </div>
          </div>
        </div>
        <div class="panel">
          <h3 class="panel__title">24 小时产量与良品趋势</h3>
          <div class="panel__body">
            <ProChart :option="hourlyOption" height="100%" />
          </div>
        </div>
      </section>

      <section class="screen__col">
        <div class="panel">
          <h3 class="panel__title">能耗构成</h3>
          <div class="panel__body">
            <ProChart :option="energyOption" height="100%" />
          </div>
        </div>
        <div class="panel">
          <h3 class="panel__title">车间 OEE 排行</h3>
          <div class="panel__body">
            <ProChart :option="oeeOption" height="100%" />
          </div>
        </div>
      </section>

      <div class="panel panel--alarms">
        <h3 class="panel__title">
          实时报警
          <span class="panel__hint">{{ alarms.length }} 条未处理 · 按时间倒序</span>
        </h3>
        <div class="alarms">
          <!-- 每条报警可点：弹出该设备的二维码（巡检扫码）+ 条码（打印贴标） -->
          <button
            v-for="(alarm, index) in alarms"
            :key="`${alarm.time}-${index}`"
            type="button"
            class="alarms__item"
            :title="`查看 ${alarm.device} 的标签`"
            @click="openDeviceLabel(alarm.device)"
          >
            <span class="alarms__time">{{ alarm.time }}</span>
            <span class="alarms__level" :class="levelClass(alarm.level)">{{ alarm.level }}</span>
            <span class="alarms__device">{{ alarm.device }}</span>
            <span class="alarms__message">{{ alarm.message }}</span>
          </button>
        </div>
      </div>
    </main>

    <!--
      设备标签弹窗：二维码（扫码进详情）+ 条码（打印贴标）。
      ⚠️ NModal 默认 teleport 到 body —— 大屏容器有 overflow: hidden，
      不 teleport 弹窗会被裁在容器里（这也是为什么样式要用 :global 命中）。
    -->
    <NModal v-model:show="labelVisible" preset="card" class="screen-label-modal" title="设备标签">
      <div v-if="labelDevice" class="screen-label">
        <div class="screen-label__meta">
          <div class="screen-label__row"><span>设备名称</span><b>{{ labelDevice.name }}</b></div>
          <div class="screen-label__row"><span>设备编号</span><b>{{ labelDevice.code }}</b></div>
          <div class="screen-label__row"><span>所属产线</span><b>{{ labelDevice.line }}</b></div>
          <p class="screen-label__tip">
            二维码用于移动端巡检扫码直达设备详情；条码用于打印贴标，扫码枪可直接读取编号。
            两者都是矢量/高清导出，可交给标签打印机使用。
          </p>
        </div>
        <div class="screen-label__codes">
          <div class="screen-label__code">
            <ProQrcode
              :value="labelDevice.url"
              :size="132"
              downloadable
              download-name="device-qrcode"
            />
            <span class="screen-label__caption">扫码查看详情</span>
          </div>
          <div class="screen-label__code">
            <ProBarcode
              :value="labelDevice.code"
              format="CODE128"
              :height="52"
              downloadable
              download-name="device-barcode"
            />
            <span class="screen-label__caption">打印贴标（SVG 矢量）</span>
          </div>
        </div>
      </div>
    </NModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import {
  NButton,
  NModal,
  NSwitch,
  ProBarcode,
  ProChart,
  ProQrcode,
  feedback,
  type EChartsOption
} from '@admin/ui'
import { MapChart } from 'echarts/charts'
import { GeoComponent, VisualMapComponent } from 'echarts/components'
import { registerMap, use as useEcharts } from 'echarts/core'
import {
  buildAlarms,
  buildDeviceStatus,
  buildEnergyMix,
  buildHourlyOutput,
  buildKpis,
  buildLineOutput,
  buildWorkshopOee,
  hashText,
  regionMetric
} from './mock-industry'
import { useScreenScale } from './use-screen-scale'

/**
 * 流式布局工业大屏（生产级方案演示）。
 *
 * <h3>为什么选流式布局，而不是"整体缩放"</h3>
 * 缩放方案（把 1920×1080 的稿子 transform: scale 塞进视口）看起来最省事，
 * 但有三个解决不掉的问题：字体被位图缩放后发虚、鼠标坐标失真（tooltip 与
 * hover 点位偏移）、非标比例（超宽屏、竖屏）必然留白。
 *
 * 本页的做法是让元素<b>真的跟着容器重排</b>：
 * <ul>
 *   <li><b>布局</b>：grid 的 fr 比例 + clamp() 间距/字号 —— 尺寸完全由容器决定</li>
 *   <li><b>图表容器</b>：flex + min-height:0 撑满网格单元，不写死 px 高度</li>
 *   <li><b>ECharts 内部配置</b>（fontSize / lineWidth / symbolSize / padding /
 *       itemGap / labelLine 长度）：这些 CSS 管不到，用 {@link useScreenScale}
 *       的 sc()/scClamp() 按容器比例换算</li>
 *   <li><b>尺寸变化</b>：ResizeObserver 盯<b>容器</b>（不是 window）——
 *       侧边栏折叠、页签增减都会改变容器宽度而 window 毫无变化</li>
 * </ul>
 *
 * <h3>地图数据为什么放在 public 而不是打进 bundle</h3>
 * 中国地图 GeoJSON 有 569KB。打进 bundle 会直接顶穿体积门禁，而且它只在
 * 这一个页面用到。放到 `public/map/china.json` 后按需 fetch，加载结果与
 * registerMap 结果都做模块级缓存 —— 页面来回切换不会重复下载与注册。
 *
 * <h3>数据为什么是"确定性伪随机"</h3>
 * 见 mock-industry.ts 的说明：大屏演示最忌讳所有数字每次刷新都重抽。
 * 这里用 tick 驱动小幅波动，看起来像真实产线在变化，且同一 tick 可复现。
 */

// ---------------------------------------------------------------------
// 容器尺寸：流式布局的"测量"部分
// ---------------------------------------------------------------------

const rootEl = ref<HTMLElement | null>(null)
const frameHeight = ref('100%')
// 注意：这里不需要取 scale 本体 —— sc()/scClamp() 内部已读取它，
// 只要在 computed 里调用换算函数，option 就自动依赖容器尺寸变化。
const { sc, scClamp, width: containerWidth } = useScreenScale(rootEl)

/**
 * 容器高度用实测而不是 calc(100vh - Npx)。
 *
 * <p>原因见 harness 页的同款处理：硬编码减法必须同时猜中顶栏、页签栏、
 * 内容区内边距，任何一处调整都会漏白边或溢出。这里量的是"容器上沿到视口底"。
 *
 * <p>窄屏（单列堆叠）下不能锁死高度 —— 内容会超出，此时改用自然高度让内容区自己滚。
 */
function measure(): void {
  const element = rootEl.value
  if (!element) {
    return
  }
  const top = element.getBoundingClientRect().top
  const narrow = window.innerWidth < 1280
  frameHeight.value = narrow
    ? 'auto'
    : `${Math.max(520, Math.round(window.innerHeight - top - 16))}px`
}

const rootStyle = computed(() => ({
  height: frameHeight.value,
  // 间距与字号随容器宽度平滑变化：小屏不挤、超宽屏不散
  '--screen-gap': `clamp(8px, 0.55vw, 14px)`,
  '--screen-pad': `clamp(8px, 0.6vw, 14px)`
}))

// ---------------------------------------------------------------------
// 实时数据
// ---------------------------------------------------------------------

const tick = ref(0)
const live = ref(true)
const clock = ref('')
const kpis = computed(() => buildKpis(tick.value))
const alarms = computed(() => buildAlarms(tick.value))

let dataTimer = 0
let clockTimer = 0

function refresh(): void {
  tick.value += 1
}

function levelClass(level: string): string {
  if (level === '严重') {
    return 'alarms__level--critical'
  }
  if (level === '警告') {
    return 'alarms__level--warn'
  }
  return 'alarms__level--info'
}

// ---------------------------------------------------------------------
// 设备标签（二维码 / 条码的真实用例）
// ---------------------------------------------------------------------

const labelVisible = ref(false)
const labelDevice = ref<{ name: string; code: string; line: string; url: string } | null>(null)

/**
 * 打开某个设备的标签。
 *
 * <p>二维码指向**设备详情地址**（移动端巡检扫码直达），条码编码**设备编号**
 * （扫码枪直接读，用于产线扫码核对）。
 *
 * <p>演示数据里没有独立编号字段，这里由设备名派生一个稳定编号 —— 真实系统中
 * 编号来自设备主数据，二维码 URL 也应是带签名/带权限校验的详情地址，
 * 而不是前端拼出来的裸链（裸链等于把设备详情公开给任何扫码的人）。
 */
function openDeviceLabel(deviceName: string): void {
  const derived = hashText(deviceName).toString(36).toUpperCase().padStart(6, '0')
  const code = `EQ-${derived.slice(0, 6)}`
  labelDevice.value = {
    name: deviceName,
    code,
    line: deviceName.split('-')[0] ?? '未分配',
    url: `${window.location.origin}/showcase/screen-flow?device=${encodeURIComponent(code)}`
  }
  labelVisible.value = true
}

/** 地图 + 各图表的共享样式片段：深色底上的统一文字与分割线颜色。 */
const AXIS_LABEL = '#9fb3c8'
const SPLIT_LINE = 'rgba(120, 160, 200, 0.16)'

/**
 * 深色大屏的统一 tooltip 外观。
 *
 * <p>ECharts 默认 tooltip 是白底黑字 —— 压在深蓝地图上非常刺眼（实测截图里
 * 一块白框直接盖住地图）。这类"同类配置"必须集中一处：六个图表各写一遍，
 * 迟早会有一个漏掉又变回白底。
 *
 * <p>fontSize 不写进常量：它必须按容器比例换算（见 use-screen-scale），
 * 各图表在自己的 tooltip 里覆盖 textStyle。
 */
const TOOLTIP_DARK = {
  backgroundColor: 'rgba(8, 22, 38, 0.94)',
  borderColor: 'rgba(56, 189, 248, 0.35)',
  borderWidth: 1,
  extraCssText: 'box-shadow: 0 6px 18px rgba(0,0,0,0.45); border-radius: 6px;'
}

// ---------------------------------------------------------------------
// 图表 option：全部依赖 scale，容器尺寸变化时字号自动跟随
// ---------------------------------------------------------------------

/** 设备状态：环形图。 */
const statusOption = computed<EChartsOption>(() => ({
  animationDuration: 400,
  tooltip: {
    trigger: 'item',
    ...TOOLTIP_DARK,
    textStyle: { color: '#dbeafe', fontSize: sc(12) }
  },
  legend: {
    bottom: 0,
    textStyle: { color: AXIS_LABEL, fontSize: scClamp(11, 10, 15) },
    itemWidth: sc(10),
    itemHeight: sc(10)
  },
  series: [
    {
      type: 'pie',
      radius: ['48%', '70%'],
      center: ['50%', '45%'],
      label: {
        show: true,
        color: '#dbeafe',
        fontSize: scClamp(11, 10, 14),
        formatter: '{b}\n{d}%'
      },
      labelLine: { length: sc(6), length2: sc(8) },
      itemStyle: { borderColor: '#0b1526', borderWidth: sc(2) },
      data: buildDeviceStatus(tick.value),
      color: ['#22d3ee', '#60a5fa', '#fbbf24', '#f87171']
    }
  ]
}))

/** 产线产能：计划 vs 实际对比柱。 */
const lineOption = computed<EChartsOption>(() => {
  const rows = buildLineOutput(tick.value)
  return {
    animationDuration: 400,
    grid: {
      left: sc(8),
      right: sc(12),
      top: sc(24),
      bottom: sc(4),
      containLabel: true
    },
    tooltip: {
    trigger: 'axis',
    ...TOOLTIP_DARK,
    textStyle: { color: '#dbeafe', fontSize: sc(12) }
  },
    legend: {
      right: 0,
      top: 0,
      textStyle: { color: AXIS_LABEL, fontSize: scClamp(10, 9, 13) },
      itemWidth: sc(10),
      itemHeight: sc(8)
    },
    xAxis: {
      type: 'category',
      data: rows.map((row) => row.name),
      axisLabel: {
        color: AXIS_LABEL,
        fontSize: scClamp(10, 9, 13),
        interval: 0,
        rotate: containerWidth.value < 1500 ? 30 : 0
      },
      axisLine: { lineStyle: { color: SPLIT_LINE } }
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: AXIS_LABEL, fontSize: scClamp(10, 9, 13) },
      splitLine: { lineStyle: { color: SPLIT_LINE } }
    },
    series: [
      {
        name: '计划',
        type: 'bar',
        barWidth: sc(9),
        itemStyle: { color: 'rgba(96, 165, 250, 0.45)', borderRadius: [sc(2), sc(2), 0, 0] },
        data: rows.map((row) => row.plan)
      },
      {
        name: '实际',
        type: 'bar',
        barWidth: sc(9),
        itemStyle: { color: '#22d3ee', borderRadius: [sc(2), sc(2), 0, 0] },
        data: rows.map((row) => row.actual)
      }
    ]
  }
})

/** 24 小时产量：面积折线。 */
const hourlyOption = computed<EChartsOption>(() => {
  const data = buildHourlyOutput(tick.value)
  return {
    animationDuration: 500,
    grid: { left: sc(8), right: sc(16), top: sc(26), bottom: sc(4), containLabel: true },
    tooltip: {
    trigger: 'axis',
    ...TOOLTIP_DARK,
    textStyle: { color: '#dbeafe', fontSize: sc(12) }
  },
    legend: {
      right: 0,
      top: 0,
      textStyle: { color: AXIS_LABEL, fontSize: scClamp(10, 9, 13) },
      itemWidth: sc(12),
      itemHeight: sc(8)
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: data.hours,
      axisLabel: {
        color: AXIS_LABEL,
        fontSize: scClamp(9, 8, 12),
        interval: Math.max(0, Math.floor(data.hours.length / (containerWidth.value / 90)) - 1)
      },
      axisLine: { lineStyle: { color: SPLIT_LINE } }
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: AXIS_LABEL, fontSize: scClamp(10, 9, 13) },
      splitLine: { lineStyle: { color: SPLIT_LINE } }
    },
    series: [
      {
        name: '产量',
        type: 'line',
        smooth: true,
        showSymbol: false,
        lineStyle: { width: sc(2), color: '#22d3ee' },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(34, 211, 238, 0.35)' },
              { offset: 1, color: 'rgba(34, 211, 238, 0.02)' }
            ]
          }
        },
        data: data.output
      },
      {
        name: '良品',
        type: 'line',
        smooth: true,
        showSymbol: false,
        lineStyle: { width: sc(2), color: '#4ade80' },
        data: data.good
      }
    ]
  }
})

/** 能耗构成：玫瑰图。 */
const energyOption = computed<EChartsOption>(() => ({
  animationDuration: 400,
  tooltip: {
    trigger: 'item',
    ...TOOLTIP_DARK,
    textStyle: { color: '#dbeafe', fontSize: sc(12) }
  },
  legend: {
    bottom: 0,
    textStyle: { color: AXIS_LABEL, fontSize: scClamp(10, 9, 14) },
    itemWidth: sc(10),
    itemHeight: sc(10)
  },
  series: [
    {
      type: 'pie',
      roseType: 'radius',
      radius: ['22%', '68%'],
      center: ['50%', '44%'],
      label: { show: false },
      itemStyle: { borderColor: '#0b1526', borderWidth: sc(2) },
      data: buildEnergyMix(tick.value),
      color: ['#facc15', '#fb923c', '#38bdf8', '#a78bfa']
    }
  ]
}))

/** 车间 OEE 排行：横向柱。 */
const oeeOption = computed<EChartsOption>(() => {
  const rows = buildWorkshopOee(tick.value)
  return {
    animationDuration: 400,
    grid: { left: sc(8), right: sc(30), top: sc(6), bottom: sc(4), containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      ...TOOLTIP_DARK,
      textStyle: { color: '#dbeafe', fontSize: sc(12) }
    },
    xAxis: {
      type: 'value',
      max: 100,
      axisLabel: { color: AXIS_LABEL, fontSize: scClamp(10, 9, 13) },
      splitLine: { lineStyle: { color: SPLIT_LINE } }
    },
    yAxis: {
      type: 'category',
      data: rows.map((row) => row.name),
      axisLabel: { color: AXIS_LABEL, fontSize: scClamp(10, 9, 13) },
      axisLine: { lineStyle: { color: SPLIT_LINE } }
    },
    series: [
      {
        type: 'bar',
        barWidth: sc(10),
        label: {
          show: true,
          position: 'right',
          color: '#dbeafe',
          fontSize: scClamp(10, 9, 13),
          formatter: '{c}%'
        },
        itemStyle: {
          borderRadius: [0, sc(3), sc(3), 0],
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 1,
            y2: 0,
            colorStops: [
              { offset: 0, color: 'rgba(34, 211, 238, 0.35)' },
              { offset: 1, color: '#22d3ee' }
            ]
          }
        },
        data: rows.map((row) => row.value)
      }
    ]
  }
})

// ---------------------------------------------------------------------
// 中国地图：GeoJSON 外置 + 模块级缓存 + registerMap
// ---------------------------------------------------------------------

/** 模块级缓存：同一会话内不重复下载与注册（页面来回切换会反复挂载）。 */
let mapPromise: Promise<string[]> | null = null

const mapReady = ref(false)
const mapError = ref('')
/**
 * 地图上的区域名（来自 GeoJSON 原始 name），保证与数据严格对齐。
 *
 * <p>只缓存"名字"，不缓存"值"：数值在 {@link mapOption} 的 computed 里按当前
 * tick 现算。否则会表现为"别的图在跳、地图定死在加载那一刻"——看起来像坏了。
 */
const mapRegionNames = shallowRef<string[]>([])

async function loadChinaMap(): Promise<string[]> {
  mapPromise ??= (async () => {
    const response = await fetch('/map/china.json')
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }
    const geoJson = (await response.json()) as {
      features: { properties: { name: string; adcode: string | number } }[]
    }
    // 地图类图表不在适配层的按需清单里（普通业务用不到），在此按需注册。
    // 与 big-chart 页同一模式：use() 是全局注册表，与 ProChart 共享同一内核。
    useEcharts([MapChart, GeoComponent, VisualMapComponent])
    registerMap('china', geoJson as never)
    // 只把「省级行政区」纳入业务数据。
    //
    // datav 的全国 GeoJSON 共 35 个区块：34 个省级 + 1 个 adcode 为 100000_JD、
    // name 为空的海域区块（界面上显示为「南海诸岛」）。它不是行政区，
    // 给它"在网设备"这种业务值没有意义；更关键的是它的 name 与数据对不上，
    // 会让该区块在 tooltip 里显示 NaN。
    //
    // 判据用 adcode 规则（6 位数字）而不是硬编码名称：名称写法会随数据源变化
    // （"内蒙古" 还是 "内蒙古自治区"），而"省级 adcode 是 6 位数字"是稳定规则 ——
    // 换地图源时这段逻辑不用改。
    return geoJson.features
      .filter(
        (feature) =>
          /^\d{6}$/.test(String(feature.properties.adcode)) &&
          feature.properties.name.length > 0
      )
      .map((feature) => feature.properties.name)
  })()
  return mapPromise
}

const mapOption = computed<EChartsOption>(() => {
  // 每次 tick 重算区域值：地图与其它图表保持同一刷新节奏
  const regions = mapRegionNames.value.map((name) => ({
    name,
    value: regionMetric(name, 1200, tick.value)
  }))
  const values = regions.map((region) => region.value)
  return {
    animationDuration: 500,
    tooltip: {
      trigger: 'item',
      ...TOOLTIP_DARK,
      textStyle: { color: '#dbeafe', fontSize: sc(12) },
      formatter: (params: unknown) => {
        const item = params as { name: string; value?: number }
        // ⚠️ 这里不能用 `item.value ?? 0`：当某个区块存在于 GeoJSON、却不在数据里时，
        // echarts 传进来的是 **NaN**（不是 undefined/null），`??` 完全挡不住 ——
        // 表现就是 tooltip 显示"NaN 台"（实测：南海诸岛区块）。
        // 用 Number.isFinite 判定，并区分两种情况：
        //   value 为 0     → "0 台"（确实一台设备都没有）
        //   value 非有限值 → "暂无数据"（该区块不参与业务统计）
        const regionName = item.name || '南海诸岛'
        const deviceText = Number.isFinite(item.value) ? `<b>${item.value}</b> 台` : '暂无数据'
        return `${regionName}<br/>在网设备：${deviceText}`
      }
    },
    visualMap: {
      min: 0,
      // 必须过滤非有限值：NaN 会传染 Math.max（Math.max(600, NaN) === NaN），
      // 一条脏数据就能让整条色阶失效、全图变成同一个颜色
      max: Math.max(600, ...values.filter((value) => Number.isFinite(value))),
      left: sc(10),
      bottom: sc(10),
      calculable: true,
      itemWidth: sc(12),
      itemHeight: sc(90),
      textStyle: { color: AXIS_LABEL, fontSize: scClamp(10, 9, 13) },
      inRange: { color: ['#0f2a44', '#14556f', '#1b8ba1', '#22d3ee', '#a5f3fc'] }
    },
    series: [
      {
        type: 'map',
        map: 'china',
        roam: false,
        zoom: 1.08,
        // 大屏不做缩放漫游（避免误操作带走上下文），需要时把 roam 打开即可
        label: { show: false },
        emphasis: {
          label: { show: true, color: '#04121f', fontSize: scClamp(10, 9, 14) },
          itemStyle: { areaColor: '#a5f3fc' }
        },
        select: { disabled: true },
        itemStyle: {
          areaColor: '#0f2a44',
          borderColor: 'rgba(120, 190, 220, 0.45)',
          borderWidth: sc(0.6)
        },
        data: regions
      }
    ]
  }
})

async function initMap(): Promise<void> {
  try {
    const names = await loadChinaMap()
    mapRegionNames.value = names
    mapReady.value = true
  } catch (error) {
    mapError.value = error instanceof Error ? error.message : String(error)
    feedback.error('地图数据加载失败，请确认 public/map/china.json 存在')
  }
}

// ---------------------------------------------------------------------
// 生命周期
// ---------------------------------------------------------------------

function syncClock(): void {
  const now = new Date()
  clock.value = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`
}

onMounted(() => {
  measure()
  window.addEventListener('resize', measure)
  syncClock()
  clockTimer = window.setInterval(syncClock, 1000)
  // 3 秒一次的数据刷新：大屏常态。option 重建成本主要在 ECharts 侧，
  // 7 个图表在这个频率下完全无压力（比这更激进才会需要增量 setOption）
  dataTimer = window.setInterval(() => {
    if (live.value) {
      refresh()
    }
  }, 3000)
  void initMap()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', measure)
  window.clearInterval(dataTimer)
  window.clearInterval(clockTimer)
})
</script>

<style scoped>
/*
 * 大屏整体走深色（工业监控的通用视觉语言），不跟随中台亮/暗主题：
 * 数据可视化的对比度与色板是硬需求，跟随主题会让同一份色板在亮色下失效。
 * 容器内部所有尺寸都用流式单位（clamp / fr / % / vh），不写死 px。
 */
.screen {
  display: flex;
  flex-direction: column;
  gap: var(--screen-gap, 10px);
  padding: var(--screen-pad, 12px);
  border-radius: 10px;
  overflow: hidden;
  background:
    radial-gradient(120% 80% at 50% 0%, rgba(34, 211, 238, 0.09), transparent 60%),
    #0b1526;
  color: #dbeafe;
}

.screen__head {
  flex: none;
  display: flex;
  align-items: center;
  gap: var(--screen-gap, 10px);
  flex-wrap: wrap;
}

.screen__brand {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.screen__brand-main {
  font-size: clamp(15px, 0.95vw, 22px);
  font-weight: 700;
  letter-spacing: 0.06em;
  background: linear-gradient(90deg, #a5f3fc, #22d3ee 45%, #60a5fa);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  white-space: nowrap;
}

.screen__brand-sub {
  font-size: clamp(10px, 0.6vw, 13px);
  color: #64748b;
}

.screen__kpis {
  flex: 1;
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--screen-gap, 10px);
  min-width: 0;
}

.screen__kpi {
  display: flex;
  align-items: baseline;
  gap: 4px;
  padding: clamp(4px, 0.5vw, 8px) clamp(6px, 0.6vw, 12px);
  border: 1px solid rgba(56, 189, 248, 0.18);
  border-radius: 8px;
  background: linear-gradient(180deg, rgba(34, 211, 238, 0.1), rgba(15, 42, 68, 0.25));
  flex-wrap: wrap;
}

.screen__kpi-label {
  width: 100%;
  font-size: clamp(10px, 0.62vw, 13px);
  color: #7dd3fc;
}

.screen__kpi-value {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: clamp(16px, 1.15vw, 28px);
  font-weight: 700;
  color: #e0f2fe;
  line-height: 1.1;
}

.screen__kpi-unit {
  font-size: clamp(10px, 0.6vw, 13px);
  color: #94a3b8;
}

.screen__kpi-delta {
  font-size: clamp(10px, 0.6vw, 13px);
}

.screen__kpi-delta--up {
  color: #4ade80;
}

.screen__kpi-delta--down {
  color: #f87171;
}

.screen__ctrl {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: clamp(10px, 0.62vw, 13px);
  color: #94a3b8;
}

.screen__clock {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: clamp(11px, 0.7vw, 15px);
  color: #a5f3fc;
}

.screen__ctrl-label {
  white-space: nowrap;
}

/* 主体栅格：左 22 / 中 56 / 右 22，行 1fr + 报警条 auto */
.screen__grid {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 22fr 56fr 22fr;
  grid-template-rows: minmax(0, 1fr) auto;
  gap: var(--screen-gap, 10px);
}

.screen__col {
  min-height: 0;
  display: grid;
  grid-template-rows: minmax(0, 1fr) minmax(0, 1fr);
  gap: var(--screen-gap, 10px);
}

.screen__col--main {
  grid-template-rows: minmax(0, 1.32fr) minmax(0, 1fr);
}

.panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  padding: clamp(6px, 0.5vw, 12px);
  border: 1px solid rgba(56, 189, 248, 0.16);
  border-radius: 8px;
  background: linear-gradient(180deg, rgba(15, 42, 68, 0.5), rgba(11, 21, 38, 0.35));
}

.panel__title {
  flex: none;
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin: 0 0 clamp(4px, 0.4vw, 8px);
  padding-left: clamp(6px, 0.5vw, 10px);
  border-left: 3px solid #22d3ee;
  font-size: clamp(11px, 0.72vw, 16px);
  font-weight: 600;
  color: #cbeafe;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.panel__hint {
  font-size: clamp(9px, 0.56vw, 12px);
  font-weight: 400;
  color: #64748b;
}

.panel__body {
  flex: 1;
  min-height: 0;
}

.panel__loading {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  font-size: clamp(11px, 0.65vw, 14px);
  color: #64748b;
}

.screen__col--main .panel--map {
  min-height: clamp(220px, 26vh, 460px);
}

/* 报警条：横向流式排列，窄屏自动换行 */
.panel--alarms {
  grid-column: 1 / -1;
}

.alarms {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(clamp(220px, 16vw, 320px), 1fr));
  gap: 4px;
}

.alarms__item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  border-radius: 6px;
  background: rgba(148, 163, 184, 0.08);
  font-size: clamp(10px, 0.62vw, 13px);
  overflow: hidden;
  white-space: nowrap;
}

.alarms__time {
  flex: none;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  color: #7dd3fc;
}

.alarms__level {
  flex: none;
  padding: 0 5px;
  border-radius: 3px;
  font-size: clamp(9px, 0.56vw, 12px);
}

.alarms__level--critical {
  background: rgba(248, 113, 113, 0.2);
  color: #fca5a5;
}

.alarms__level--warn {
  background: rgba(251, 191, 36, 0.18);
  color: #fcd34d;
}

.alarms__level--info {
  background: rgba(96, 165, 250, 0.18);
  color: #93c5fd;
}

.alarms__device {
  flex: none;
  color: #cbd5e1;
}

.alarms__message {
  flex: 1;
  min-width: 0;
  color: #94a3b8;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 报警项改成 button 后需清掉浏览器默认样式，保持原有观感并给出可点的反馈 */
.alarms__item {
  border: none;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.15s ease;
}

.alarms__item:hover {
  background: rgba(56, 189, 248, 0.18);
}

/* ---- 设备标签弹窗（teleport 到 body，因此用 :global 命中）---- */
:global(.screen-label-modal) {
  width: 600px;
  max-width: 92vw;
}

.screen-label {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
}

.screen-label__meta {
  flex: 1;
  min-width: 220px;
}

.screen-label__row {
  display: flex;
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px dashed rgba(128, 128, 128, 0.2);
  font-size: 13px;
}

.screen-label__row span {
  flex: none;
  width: 72px;
  color: var(--n-text-color-2, #666);
}

.screen-label__tip {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--n-text-color-2, #666);
}

.screen-label__codes {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
}

.screen-label__code {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.screen-label__caption {
  font-size: 12px;
  color: var(--n-text-color-2, #666);
}

/*
 * 窄屏与竖屏：降为单列，容器高度改自然高度（由 measure() 把 frameHeight 设为 auto）。
 * 这正是流式布局相对"整体缩放"的关键优势 —— 竖屏不是"被缩放变形"，而是重排。
 */
@media (max-width: 1280px) {
  .screen__grid {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: none;
  }

  .screen__col,
  .screen__col--main {
    grid-template-rows: none;
  }

  .panel {
    min-height: 260px;
  }

  .screen__kpis {
    grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  }
}
</style>
