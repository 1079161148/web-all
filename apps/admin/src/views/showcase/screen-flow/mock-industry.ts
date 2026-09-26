/**
 * 大屏的工业模拟数据层。
 *
 * <h3>为什么用"确定性伪随机"而不是 Math.random()</h3>
 * 大屏演示最容易犯的错是所有数字每次刷新都乱跳 —— 观感上像"坏了"，
 * 而且无法复现、无法比对两个版本的差异。这里用 seed 驱动的伪随机：
 * <ul>
 *   <li>同一 seed 必然产生同一份数据（可复现、可截图对比）</li>
 *   <li>实时刷新时用 tick 作为扰动源，数字是"小幅波动"而不是"重新抽签"，
 *       看起来像真实产线在变化</li>
 * </ul>
 *
 * <h3>为什么地图数据不硬编码省份名</h3>
 * 地图数据必须与 GeoJSON 里的 region name 严格一致，否则整张图无数据。
 * 而 GeoJSON 的命名（"内蒙古自治区" 还是 "内蒙古"、带不带"省"）随数据源变化，
 * 硬编码一份名单就等于埋了一个"换地图源就崩"的雷。
 * 因此 {@link regionMetric} 由**调用方传入 GeoJSON 里的原始 name** 来生成数值，
 * 名称始终对齐，换数据源也不会失配。
 */

/** mulberry32：32 位确定性伪随机，够用且代码量极小。 */
function createRng(seed: number): () => number {
  let state = seed >>> 0
  return () => {
    state = (state + 0x6d2b79f5) >>> 0
    let t = state
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/** 字符串 → 稳定整数（FNV-1a 变体），用于把 region name 变成种子。 */
export function hashText(text: string): number {
  let hash = 2166136261
  for (let i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i)
    hash = Math.imul(hash, 16777619)
  }
  return hash >>> 0
}

/**
 * 某个区域的指标值（例如在网设备数）。
 *
 * @param regionName GeoJSON 里的原始 region name
 * @param base       基准量级
 * @param tick       刷新计数，用于制造小幅波动（同一个 tick 结果恒定）
 * @param amplitude  波动幅度（占总量的比例）
 */
export function regionMetric(
  regionName: string,
  base = 1200,
  tick = 0,
  amplitude = 0.18
): number {
  const rng = createRng(hashText(regionName))
  // 先给每个区域一个稳定的"性格系数"（0.35~1.35），再按 tick 做小幅摆动
  const character = 0.35 + rng() * 1
  const wave = Math.sin((hashText(regionName) % 360 + tick * 7) * (Math.PI / 180))
  const value = base * character * (1 + wave * amplitude)
  return Math.round(value)
}

/** 顶部关键指标。 */
export interface KpiItem {
  label: string
  value: number
  unit: string
  /** 同比/环比变化百分比，正数为好（用于着色） */
  delta: number
}

export function buildKpis(tick: number): KpiItem[] {
  const rng = createRng(20260926 + tick)
  const wave = (offset: number): number => Number(((rng() - 0.4) * 2 + offset).toFixed(1))
  return [
    { label: '在网设备', value: 8642 + Math.round(wave(0) * 12), unit: '台', delta: wave(0.6) },
    { label: '设备在线率', value: Number((97.4 + wave(0.2) / 10).toFixed(1)), unit: '%', delta: wave(0.3) },
    { label: '今日产量', value: 128460 + Math.round(wave(1) * 320), unit: '件', delta: wave(0.8) },
    { label: '综合能耗', value: Number((2846 + wave(2) * 8).toFixed(0)), unit: 'kWh', delta: wave(-0.5) },
    { label: '未处理报警', value: Math.max(0, 7 + Math.round(wave(2))), unit: '条', delta: wave(-1.2) }
  ]
}

/** 设备状态分布（环形图）。 */
export function buildDeviceStatus(tick: number): { name: string; value: number }[] {
  const jitter = (seed: number): number => Math.round((createRng(seed + tick)() - 0.5) * 40)
  return [
    { name: '运行', value: 6218 + jitter(11) },
    { name: '待机', value: 1104 + jitter(22) },
    { name: '维护', value: 312 + jitter(33) },
    { name: '故障', value: 108 + jitter(44) }
  ]
}

/** 产线产能对比（柱状图，单位：件/小时）。 */
export function buildLineOutput(tick: number): { name: string; plan: number; actual: number }[] {
  const lines = ['A1 冲压', 'A2 焊装', 'B1 涂装', 'B2 总装', 'C1 检测', 'C2 包装']
  return lines.map((name, index) => {
    const rng = createRng(hashText(name) + tick)
    const plan = 800 + index * 40
    return {
      name,
      plan,
      actual: Math.round(plan * (0.78 + rng() * 0.3))
    }
  })
}

/** 24 小时产量与良品数（面积折线）。 */
export function buildHourlyOutput(tick: number): { hours: string[]; output: number[]; good: number[] } {
  const hours: string[] = []
  const output: number[] = []
  const good: number[] = []
  for (let hour = 0; hour < 24; hour += 1) {
    hours.push(`${String(hour).padStart(2, '0')}:00`)
    const rng = createRng(hour * 977 + tick)
    // 白天产能高、夜班低，并叠加噪声：贴近真实班次曲线
    const shiftFactor = hour >= 8 && hour < 20 ? 1 : 0.62
    const base = (4200 + Math.sin((hour / 24) * Math.PI * 2) * 900) * shiftFactor
    const value = Math.round(base * (0.92 + rng() * 0.16))
    output.push(value)
    good.push(Math.round(value * (0.955 + rng() * 0.03)))
  }
  return { hours, output, good }
}

/** 能耗构成（玫瑰/环形图）。 */
export function buildEnergyMix(tick: number): { name: string; value: number }[] {
  const jitter = (seed: number): number => Math.round((createRng(seed + tick)() - 0.5) * 30)
  return [
    { name: '电力', value: 1860 + jitter(5) },
    { name: '天然气', value: 520 + jitter(6) },
    { name: '蒸汽', value: 318 + jitter(7) },
    { name: '压缩空气', value: 148 + jitter(8) }
  ]
}

/** 车间 OEE 排行（横向柱状图，%）。 */
export function buildWorkshopOee(tick: number): { name: string; value: number }[] {
  const workshops = ['总装车间', '焊装车间', '涂装车间', '冲压车间', '注塑车间', '检测中心', '包装车间', '动力站房']
  return workshops
    .map((name) => ({
      name,
      value: Number((68 + createRng(hashText(name) + tick)() * 26).toFixed(1))
    }))
    .sort((left, right) => left.value - right.value)
}

/** 实时报警条目。 */
export interface AlarmItem {
  time: string
  device: string
  level: '严重' | '警告' | '提示'
  message: string
}

const ALARM_TEMPLATES: { device: string; level: AlarmItem['level']; message: string }[] = [
  { device: 'A1-冲压机-03', level: '严重', message: '液压油温超上限（92℃）' },
  { device: 'B2-总装线-07', level: '警告', message: '扭矩枪校准偏差 3.2%' },
  { device: 'C1-视觉检测-02', level: '警告', message: '相机标定过期 12 小时' },
  { device: 'A2-焊装机器人-11', level: '提示', message: '焊丝余量低于 15%' },
  { device: 'B1-喷涂房-05', level: '严重', message: 'VOC 浓度超阈值' },
  { device: 'D-空压机-01', level: '提示', message: '运行时长达到保养周期' },
  { device: 'C2-打包机-04', level: '警告', message: '连续 3 次称重异常' },
  { device: 'A1-送料机械手-02', level: '提示', message: '待机超过 30 分钟' }
]

export function buildAlarms(tick: number): AlarmItem[] {
  const rng = createRng(777 + tick)
  const count = 6
  const items: AlarmItem[] = []
  for (let index = 0; index < count; index += 1) {
    const template = ALARM_TEMPLATES[Math.floor(rng() * ALARM_TEMPLATES.length)]
    const minutesAgo = Math.floor(rng() * 58) + 1
    const hour = 9 + Math.floor(minutesAgo / 60)
    const minute = 60 - (minutesAgo % 60 || 60)
    items.push({
      ...template,
      time: `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`
    })
  }
  return items
}
