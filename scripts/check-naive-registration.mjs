#!/usr/bin/env node
/**
 * 门禁：模板里用到的 Naive 组件必须都已在 `naive.ts` 中登记。
 *
 * <h3>它防的是什么</h3>
 * {@code @admin/ui} 只注册<b>显式清单</b>里的 Naive 组件（而不是 `app.use(naive)` 全量注册），
 * 这样业务页面用不到的组件（Calendar / Carousel / ColorPicker / Watermark / Transfer…）
 * 不会进入首屏 bundle。
 *
 * <p>代价是：<b>清单漏一个组件，页面上那一处会静默消失。</b>
 * Vue 对未注册的标签不会报错，只会渲染成一个空的自定义元素 ——
 * 表现是"按钮不见了"或"弹窗打不开"，而控制台里可能只有一条容易被忽略的警告。
 *
 * <p>因此这条门禁：<b>把"漏注册"从运行时静默故障变成构建期失败</b>。
 * 这与 vxe 侧的 {@code assertRuntimeComponentsRegistered}（启动自检）是同一个思路 ——
 * 精确注册只有在配上这类检查之后才是安全的。
 *
 * <h3>判定方式</h3>
 * <ol>
 *   <li>从 {@code packages/ui/src/naive.ts} 解析出已注册的组件名清单</li>
 *   <li>扫描所有 {@code .vue} 里的 {@code <n-xxx>} 标签，转成组件名（{@code n-button} → {@code NButton}）</li>
 *   <li>求差集：有标签但没登记 → 失败</li>
 * </ol>
 *
 * <h3>为什么把"所有用到标签"都要求注册</h3>
 * 同一个组件可能在某处是显式 import、在另一处依赖全局注册 ——
 * 逐一区分两类的成本高于收益，而多注册一个已用到的组件只是极小的体积开销。
 * <b>宁可多注册一个，也不要漏掉一个。</b>
 */

import { readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

const NAIVE_TS = join(repoRoot, 'packages/ui/src/naive.ts')
const SCAN_DIRS = [join(repoRoot, 'apps'), join(repoRoot, 'packages')]
const IGNORE = /node_modules|[/\\]dist[/\\]|\.turbo|[/\\]target[/\\]/

// ---------------------------------------------------------------------
// 1. 已注册的组件名
// ---------------------------------------------------------------------

const naiveSource = readFileSync(NAIVE_TS, 'utf8')

/**
 * 解析 `export { NButton, NInput, ... } from 'naive-ui'` 里的组件名。
 *
 * 只取 `N` 开头的标识符：该 export 块里还会混有 `darkTheme` / `zhCN` 这类
 * 非组件导出（它们是主题与语言包），它们没有对应的标签，参与比对会误报。
 */
const registered = new Set(
  [...naiveSource.matchAll(/\bN[A-Z][A-Za-z0-9]*\b/g)].map((m) => m[0])
)

if (registered.size === 0) {
  console.error('[naive-check] 未能从 naive.ts 解析出任何组件名 —— 请检查该文件的导出格式。')
  process.exit(1)
}

// ---------------------------------------------------------------------
// 2. 模板中使用的标签
// ---------------------------------------------------------------------

const usedTags = new Map() // ComponentName -> 首次出现的文件

function walk(dir) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    if (IGNORE.test(full)) continue
    const stat = statSync(full)
    if (stat.isDirectory()) {
      walk(full)
    } else if (entry.endsWith('.vue')) {
      collect(full)
    }
  }
}

function collect(file) {
  const source = readFileSync(file, 'utf8')
  for (const match of source.matchAll(/<(n-[a-z0-9]+(?:-[a-z0-9]+)*)[\s/>]/g)) {
    const name = match[1]
      .split('-')
      .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
      .join('')
    if (!usedTags.has(name)) {
      usedTags.set(name, file.replace(repoRoot + '\\', '').replace(repoRoot + '/', ''))
    }
  }
}

for (const dir of SCAN_DIRS) {
  try {
    walk(dir)
  } catch {
    // 目录不存在（如尚未创建 apps/xxx）时跳过
  }
}

// ---------------------------------------------------------------------
// 3. 比对
// ---------------------------------------------------------------------

/**
 * 白名单：标签名不来自 Naive 组件库的情况。
 *
 * <p>新增条目必须说明理由 —— 往这里加等于放宽门禁。
 */
const IGNORED_TAGS = new Set([
  // Naive 没有这个组件；它来自 vxe（<vxe-*> 才是它的标签），
  // 此处仅用于防御性地忽略将来可能出现的第三方 n- 前缀标签
])

const missing = [...usedTags.entries()].filter(
  ([name]) => !registered.has(name) && !IGNORED_TAGS.has(name)
)

if (missing.length > 0) {
  console.error('')
  console.error('='.repeat(72))
  console.error('✗ 模板中使用了未注册的 Naive 组件')
  console.error('='.repeat(72))
  console.error('')
  for (const [name, file] of missing) {
    console.error(`  ${name}`)
    console.error(`      首次出现在：${file}`)
  }
  console.error('')
  console.error('  未注册的组件不会报错，只会渲染成空白 —— 表现为"按钮不见了"。')
  console.error('')
  console.error('  修复：把它们加进 packages/ui/src/naive.ts 的导出清单，')
  console.error('        并在 packages/ui/src/plugins 中注册（见该文件说明）。')
  console.error('')
  console.error(`  当前已登记 ${registered.size} 个；模板用到 ${usedTags.size} 个。`)
  console.error('='.repeat(72))
  process.exit(1)
}

console.log(
  `[naive-check] ✓ 模板用到的 ${usedTags.size} 个组件全部已登记` +
    `（登记清单共 ${registered.size} 个）`
)
