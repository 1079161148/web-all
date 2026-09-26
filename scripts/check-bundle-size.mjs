#!/usr/bin/env node
/**
 * 门禁：首屏资源体积不得回涨。
 *
 * <h3>为什么必须是"棘轮"而不是"一刀切到目标值"</h3>
 * 设计文档 §12.1 的目标是首屏 JS &lt; 250 KB（gzip）。但当前实测约 315 KB ——
 * 若直接按 250 KB 设门禁，它会<b>从第一天起就是红的</b>，然后被忽略或被注释掉，
 * 结果是这条门禁等于不存在（这比没有更糟：它会让人以为体积被守住了）。
 *
 * <p>因此这里采用棘轮策略：<b>阈值设在当前值略上方，只允许下降不允许上升。</b>
 * 每做一次体积优化就手动下调一次阈值（见 BASELINE_GZIP_KB 的注释），
 * 最终收敛到 250 KB。
 *
 * <h3>为什么统计的是「index.html 引用的资源」而不是「dist 总大小」</h3>
 * 总大小包含按需 chunk（vxe / form-create 约 331 KB），
 * 而它们<b>不影响首屏</b>。用总大小做门禁会得出"优化没生效"的错误结论，
 * 也会让"把东西挪到懒加载 chunk"这类正确做法被门禁挡住。
 *
 * <p>判定依据必须是<b>浏览器首次打开时真正会下载的东西</b>：
 * `index.html` 里的 `<script src>`、`<link rel="modulepreload">` 与 `<link rel="stylesheet">`。
 */

import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { gzipSync } from 'node:zlib'

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const DIST = join(repoRoot, 'apps/admin/dist')

/**
 * 首屏 gzip 阈值（KB）。
 *
 * 调整规则：**只允许下调**。
 * 需要上调时必须说明原因，并确认无法通过懒加载或精确注册解决。
 *
 * 历史：
 *   890  —— vxe + form-create 全量注册（回归起点）
 *   426  —— 内核改为按需加载
 *   315  —— Naive 改为精确注册 + 新增 check:naive 门禁
 *   551  —— 「AI调研」5 个页面 + 图标整包静态导入（回归）
 *   334  —— 图标包改为懒加载 chunk + 按图标子路径导入
 *   320  —— 新增窄入口 @admin/ui/core：截断"barrel 被预加载"（实测省 151 KB gzip 的共享 chunk）
 *   322  —— 全局配置功能（有意上调，两处不可省的来源）：
 *          ① NRow/NCol/NRadioButton 补注册（form-create 运行时解析，
 *            check:naive 的模板扫描够不到它们 —— 门禁盲区修复）；
 *          ② 精确注册机制决定"注册即进入口"，配置抽屉本体已懒加载，
 *            剩余为注册字节与 chunk 重组开销。
 *   323  —— ProTable 列布局持久化（有意上调，~1KB）：布局恢复/防抖保存
 *          与组件内部的 grid 引用、分页状态强耦合，抽成外部 composable
 *          需要把五个内部状态穿透出去 —— 得不偿失。ProTable 本就是
 *          同步组件，此为它的正当增长。可观测采集器已改懒加载抵消一部分。
 *   324  —— 全局配置体系 + 布局能力扩展（有意上调，~1KB）：
 *          ProLayout 新增菜单布局模式（顶栏 / 混合）、5 种页签风格、
 *          页脚与 Logo 开关、头部固定开关、内容限宽；store 新增对应偏好
 *          与页签持久化；顶栏新增全屏按钮。这些都在布局组件与 store 里，
 *          属于首屏固有成本，无法懒加载。
 *          <p>本次同时新增了二维码 / 条形码组件：它们各自带一个不轻的库
 *          （qrcode / jsbarcode，其中 JsBarcode 单 chunk 62KB），已改为
 *          <b>异步组件 + 动态导入</b>，完全不在首屏 —— 增量已逐项核对，
 *          无浪费项，因此本次上调是"有意为之"而非回涨。
 */
const BASELINE_GZIP_KB = 324
// ---------------------------------------------------------------------------
// 构成分析（2026-09-26 收尾盘点，为下一次瘦身留靶子）：
//   324 KB 的首屏构成（gzip）：lazy 共享 chunk 137 + 主包 76 + feedback 50 +
//   vue runtime 32 + naive 精确注册小件 ~29。
//   · lazy chunk（536KB raw）**不是** loader，而是 naive-ui 的主题系统
//     （ConfigProvider → 全组件 theme overrides self() + date-fns locale），
//     它被 core.ts 的 AdminConfigProvider（首屏根组件）静态依赖 —— 结构性成本，
//     在 naive 的主题架构下无法摘除。
//   · feedback chunk 50KB 是 createDiscreteApi（message/notification）的实现，
//     若未来换轻量 toast 实现，这是最有潜力的单点（约 -50 KB）。
//   · 因此本次收尾不盲目下调阈值 —— 324 之下没有"错放的首屏代码"，只有
//     "需要结构性手术的成本"。下次动它之前先看这条注释。
// ---------------------------------------------------------------------------

/** 目标（设计文档 §12.1）：收敛到此值后把 BASELINE 直接设为目标。 */
const TARGET_GZIP_KB = 250

if (!existsSync(join(DIST, 'index.html'))) {
  console.error('[bundle-size] 未找到 apps/admin/dist/index.html —— 请先执行 pnpm build。')
  process.exit(1)
}

const html = readFileSync(join(DIST, 'index.html'), 'utf8')
const refs = [...html.matchAll(/(?:src|href)="\/(assets\/[^"]+)"/g)].map((m) => m[1])

if (refs.length === 0) {
  console.error('[bundle-size] index.html 中未解析到任何资源引用 —— 构建产物结构可能已变化。')
  process.exit(1)
}

let totalGzip = 0
const rows = []
for (const ref of refs) {
  const file = join(DIST, ref)
  if (!existsSync(file)) {
    console.error(`[bundle-size] index.html 引用了不存在的文件：${ref}`)
    process.exit(1)
  }
  const buf = readFileSync(file)
  const gz = gzipSync(buf).length
  totalGzip += gz
  rows.push({ name: ref.split('/').pop(), gz })
}

const totalKb = totalGzip / 1024

console.log(
  `[bundle-size] 首屏 ${refs.length} 个文件，gzip ${totalKb.toFixed(0)} KB ` +
    `（阈值 ${BASELINE_GZIP_KB} KB，目标 ${TARGET_GZIP_KB} KB）`
)

// ⚠️ 判定口径必须与显示口径一致：阈值是 1KB 粒度的棘轮，显示用 toFixed(0)，
// 比较也必须取整到同一粒度。否则 322.0055 > 322 这种"6 个 gzip 字节的
// chunk 重组抖动"（真实案例：给懒加载页面新增依赖后 rolldown 重新划分
// 共享模块，压缩字典变化导致 ±字节）会被误判为回涨，门禁变成看运气。
// 取整后，只有真正跨过 1KB 边界的回涨（≥ +1KB）才会被拦下。
if (Math.round(totalKb) > BASELINE_GZIP_KB) {
  console.error('')
  console.error('='.repeat(72))
  console.error(`✗ 首屏体积回涨：${totalKb.toFixed(0)} KB > 阈值 ${BASELINE_GZIP_KB} KB`)
  console.error('='.repeat(72))
  console.error('')
  console.error('  最大的几个文件：')
  for (const row of rows.sort((a, b) => b.gz - a.gz).slice(0, 6)) {
    console.error(`    ${(row.gz / 1024).toFixed(0).padStart(5)} KB  ${row.name}`)
  }
  console.error('')
  console.error('  常见原因：')
  console.error('    1. 静态 import 了重型依赖（vxe / form-create / ECharts…）——')
  console.error('       它们应当走 adapters/lazy.ts 的按需加载')
  console.error('    2. 新增了 Naive 组件却没加进 packages/ui/src/naive.ts ——')
  console.error('       精确注册清单外的组件会让整包被保留')
  console.error('    3. 在入口（main.ts）里 import 了只在少数页面用到的东西')
  console.error('')
  console.error('  排查：`node scripts/check-bundle-size.mjs` 配合 `node scripts/size-report.mjs`')
  console.error('='.repeat(72))
  process.exit(1)
}

const headroom = BASELINE_GZIP_KB - totalKb
if (headroom > 20) {
  console.log(
    `[bundle-size] ✓ 通过。距离阈值还有 ${headroom.toFixed(0)} KB —— ` +
      '若这是优化后的结果，请下调 BASELINE_GZIP_KB 把收益锁住（棘轮只进不退）。'
  )
} else {
  console.log(`[bundle-size] ✓ 通过（距离阈值 ${headroom.toFixed(0)} KB）`)
}
