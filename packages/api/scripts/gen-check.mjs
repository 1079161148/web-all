#!/usr/bin/env node
/**
 * 契约 → 客户端生成（可选校验）。
 *
 * <pre>
 *   node scripts/gen-check.mjs           # 只生成
 *   node scripts/gen-check.mjs --check   # 生成并校验产物与已提交内容一致（CI 用）
 * </pre>
 *
 * <h3>为什么不能只写 `orval && git diff --exit-code src/generated`</h3>
 * 这条命令有一个<b>静默的盲区：`git diff` 看不见未跟踪文件。</b>
 *
 * <p>真实场景：有人加了一个接口，orval 生成了<b>新文件</b>
 * （比如某个 DTO 拆出了 `model/newThing.ts`），他忘了 `git add`。
 * 此时：
 * <ul>
 *   <li>`git diff --exit-code src/generated` → <b>通过</b>（新文件不在 diff 里）</li>
 *   <li>CI 绿了，但因为文件没入库，其他人拉下来后<b>前端直接编译不过</b></li>
 * </ul>
 * 也就是说，这条本该最严格的门禁，恰好在"忘了提交新产物"这个最常见的失误上失效了。
 * 而这正是它存在的主要理由。
 *
 * <p>因此本脚本做两件事，缺一不可：
 * <ol>
 *   <li><b>未跟踪检查</b>（{@code git ls-files --others}）—— 新增产物必须入库</li>
 *   <li><b>内容一致性检查</b>（{@code git diff}）—— 已入库的产物必须与刚生成的一致</li>
 * </ol>
 *
 * <h3>为什么不自己实现"两份内容比对"</h3>
 * 用 git 而不是把生成结果复制到临时目录再逐字节比较，是因为 git 的 diff
 * 能直接告诉人"哪一行变了" —— 而门禁失败时，<b>可读的差异比一个"不一致"的结论有用得多</b>。
 */

import { spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const apiRoot = resolve(here, '..')
const repoRoot = resolve(apiRoot, '../..')

const CHECK = process.argv.includes('--check')
const GENERATED = 'packages/api/src/generated'

// ---------------------------------------------------------------------

function run(command, { cwd = apiRoot, capture = false } = {}) {
  const result = spawnSync(command, {
    cwd,
    shell: true,
    encoding: 'utf8',
    stdio: capture ? 'pipe' : 'inherit',
    env: { ...process.env, FORCE_COLOR: capture ? '0' : '1' }
  })
  if (result.status !== 0) {
    const detail = capture ? `\n${result.stdout ?? ''}\n${result.stderr ?? ''}` : ''
    throw new Error(`命令失败（exit ${result.status}）: ${command}${detail}`)
  }
  return (result.stdout ?? '').trim()
}

function fail(title, body) {
  console.error('')
  console.error('='.repeat(72))
  console.error(`✗ ${title}`)
  console.error('='.repeat(72))
  console.error(body)
  console.error('='.repeat(72))
  process.exit(1)
}

// ---------------------------------------------------------------------
// 1. 生成
// ---------------------------------------------------------------------

console.log('[gen] 预处理 spec（剥离统一响应体外壳 R<T>）')
run('node scripts/prepare-spec.mjs')

console.log('[gen] orval 生成类型与请求函数')
run('pnpm exec orval --config orval.config.ts')

if (!CHECK) {
  console.log(`[gen] 完成 → ${GENERATED}`)
  process.exit(0)
}

// ---------------------------------------------------------------------
// 2. 校验（仅 --check）
// ---------------------------------------------------------------------

// git 不可用时（例如下载的 zip 包、或在非 git 环境构建）不能装作通过 ——
// 那正是"门禁静默失效"的形态。但也确实无法执行检查，因此明确降级并说明。
const gitAvailable = spawnSync('git rev-parse --is-inside-work-tree', {
  cwd: repoRoot,
  shell: true,
  encoding: 'utf8',
  stdio: 'pipe'
}).status === 0

if (!gitAvailable) {
  console.warn('')
  console.warn('[gen:check] ⚠ 当前环境不是 git 仓库，无法校验产物是否已提交 —— 本次校验被跳过。')
  console.warn('           这不是通过，只是无法检查。CI 上必须处于 git 仓库中。')
  process.exit(0)
}

const generatedPath = resolve(repoRoot, GENERATED)
if (!existsSync(generatedPath)) {
  fail(
    '生成产物目录不存在',
    `  期望路径：${generatedPath}\n  说明 orval 的输出路径被改过，或生成失败了。`
  )
}

// ---- 2a. 未跟踪产物 ----
const untracked = run('git ls-files --others --exclude-standard -- ' + GENERATED, {
  cwd: repoRoot,
  capture: true
})

if (untracked) {
  fail(
    '存在未提交的生成产物（git diff 检查不到这类问题）',
    [
      '  以下文件是 orval 生成的，但没有被 git 跟踪：',
      '',
      ...untracked.split('\n').map((line) => `    ${line}`),
      '',
      '  原因通常是新增了接口或 DTO，orval 生成了新文件但忘了 git add。',
      '  只跑 `git diff --exit-code` 是发现不了的（diff 不含未跟踪文件），',
      '  而它们没入库会导致其他人拉下代码后前端编译不过。',
      '',
      '  修复：',
      '    git add packages/api/src/generated',
      ''
    ].join('\n')
  )
}

// ---- 2b. 已跟踪产物内容一致性 ----
const diff = run(`git diff -- ${GENERATED}`, { cwd: repoRoot, capture: true })

if (diff) {
  fail(
    '生成产物与已提交内容不一致',
    [
      '  说明契约（openapi.json）改过，但没有重新生成前端客户端。',
      '  也就是说：调用方拿到的类型定义<b>落后于</b>后端实际接口。',
      '',
      `  差异（${GENERATED}）：`,
      '',
      ...diff.split('\n').slice(0, 80).map((line) => `    ${line}`),
      diff.split('\n').length > 80 ? `    ...（还有 ${diff.split('\n').length - 80} 行）` : '',
      '',
      '  修复：',
      '    pnpm gen:api',
      '    然后提交 packages/api/src/generated 的改动',
      ''
    ]
      .filter((line) => line !== '')
      .join('\n')
  )
}

console.log('')
console.log(`[gen:check] ✓ 生成产物与已提交内容一致（且无未跟踪文件）→ ${GENERATED}`)
