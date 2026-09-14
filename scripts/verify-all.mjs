#!/usr/bin/env node
/**
 * 本地一键门禁：跑与 CI 完全相同的三组检查。
 *
 * <h3>为什么需要这个脚本</h3>
 * 门禁最危险的状态不是"太严"，而是「<b>本地没有东西可跑，于是所有检查都推迟到 CI</b>」。
 * 那种情况下开发者的反馈周期是一整个 CI 往返，久了就会形成
 * "先推上去看 CI 报什么"的习惯 —— 门禁从此只起到"拦住合并"的作用，
 * 而失去了"让人提前发现"的价值。
 *
 * <p>因此这个脚本的目标很具体：<b>让本地能一条命令跑到与 CI 逐字相同的检查</b>。
 * 命令写死在 {@link STEPS} 里，与 {@code .github/workflows/ci.yml} 的
 * {@code run:} 行一一对应 —— 两边必须同时修改，这是刻意的耦合，
 * 因为"CI 跑了但本地跑不了"正是我们要消除的问题。
 *
 * <h3>失败即停</h3>
 * 第一个失败就中断，不做"全部跑完再汇总"。理由：后端门禁挂了之后，
 * 前端门禁的结论没有意义（契约可能已经不对了），继续跑只是浪费几分钟
 * 并输出一堆会被忽略的日志。
 *
 * <p>用法：
 * <pre>
 *   node scripts/verify-all.mjs            # 全部
 *   node scripts/verify-all.mjs 前端        # 只跑名称/说明里含"前端"的步骤
 *   node scripts/verify-all.mjs --list     # 列出步骤
 * </pre>
 */

import { spawnSync } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

/**
 * 门禁步骤。
 *
 * ⚠️ 这里的 command 必须与 .github/workflows/ci.yml 中对应 job 的 run 保持一致。
 */
const STEPS = [
  {
    name: '后端门禁',
    detail: '领域单测 · 架构红线(ArchUnit) · 集成测试(Testcontainers) · 契约新鲜度',
    command: 'mvn -B verify',
    cwd: 'server',
    // 这两个前置都是实测踩过的坑，写在这里而不是让人自己猜：
    //   · Docker —— Testcontainers 缺失时的报错来自 Testcontainers 内部，
    //     不指向"你没装 Docker"
    //   · JAVA_HOME —— 缺失时 Maven 会用 PATH 上的另一个 JDK，
    //     报错是"class file version 69.0 ... 65.0"，一个不提 JDK 版本的说法。
    //     现在有 maven-enforcer-plugin 兜底，会给出明确提示。
    requires: 'JAVA_HOME 指向 JDK 25（见 server/pom.xml 的 java.version）+ Docker 守护进程'
  },
  {
    name: '前端门禁',
    detail: 'lint(oxlint) · 类型检查(vue-tsc) · 构建(vite)',
    command: 'pnpm verify',
    cwd: '.'
  },
  {
    name: '契约一致性',
    detail: 'orval 重新生成前端客户端，与已提交的 src/generated 比对',
    command: 'pnpm gen:check',
    cwd: '.',
    requires: '已提交的 packages/api/openapi.json 与 src/generated'
  }
]

// ---------------------------------------------------------------------
// 参数处理
// ---------------------------------------------------------------------

const args = process.argv.slice(2)

if (args.includes('--list')) {
  console.log('可用步骤：')
  STEPS.forEach((step, index) => {
    console.log(`  ${index + 1}. ${step.name} — ${step.detail}`)
  })
  process.exit(0)
}

const filter = args.find((arg) => !arg.startsWith('-'))
const selected = filter
  ? STEPS.filter(
      (step) => step.name.includes(filter) || step.detail.includes(filter)
    )
  : STEPS

if (selected.length === 0) {
  console.error(`没有匹配「${filter}」的步骤。可用步骤：`)
  STEPS.forEach((step) => console.error(`  - ${step.name}`))
  process.exit(2)
}

// ---------------------------------------------------------------------
// 执行
// ---------------------------------------------------------------------

const t0 = Date.now()
const results = []

console.log('='.repeat(72))
console.log(`本地门禁：共 ${selected.length} 步（与 CI 执行的命令一致）`)
console.log('='.repeat(72))

for (const [index, step] of selected.entries()) {
  console.log('')
  console.log(`[${index + 1}/${selected.length}] ${step.name} — ${step.detail}`)
  console.log(`          $ ${step.command}   (cwd: ${step.cwd})`)
  if (step.requires) {
    console.log(`          前置：${step.requires}`)
  }
  console.log('-'.repeat(72))

  const started = Date.now()
  const result = spawnSync(step.command, {
    cwd: resolve(repoRoot, step.cwd),
    // shell: true 让 Windows 上的 mvn.cmd / pnpm.cmd 能被自动解析，
    // 否则 spawnSync 会去直接执行一个不存在的 "mvn" 文件（ENOENT）。
    shell: true,
    stdio: 'inherit',
    env: { ...process.env, FORCE_COLOR: '1' }
  })
  const seconds = ((Date.now() - started) / 1000).toFixed(1)

  if (result.status !== 0) {
    results.push({ name: step.name, seconds, ok: false })
    console.log('')
    console.log('='.repeat(72))
    console.log(`✗ ${step.name} 失败（${seconds}s）`)
    console.log('')
    console.log('  上面的输出就是原因。若是契约不一致，日志里会给出：')
    console.log('    ① 实时契约文件的位置（可直接 diff）')
    console.log('    ② 更新契约的命令（-Dcontract.update=true）')
    console.log('')
    console.log('  后续步骤已跳过 —— 前面的门禁没过时，后面的结论没有意义。')
    console.log('='.repeat(72))
    process.exit(1)
  }

  results.push({ name: step.name, seconds, ok: true })
  console.log('-'.repeat(72))
  console.log(`✓ ${step.name} 通过（${seconds}s）`)
}

const total = ((Date.now() - t0) / 1000).toFixed(1)

console.log('')
console.log('='.repeat(72))
console.log(`全部门禁通过（合计 ${total}s）`)
results.forEach((r) => console.log(`  ✓ ${r.name}  ${r.seconds}s`))
console.log('='.repeat(72))
