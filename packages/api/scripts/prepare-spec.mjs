/**
 * 契约预处理：剥掉统一响应体 `R<T>` 外壳。
 *
 * <h3>为什么必须做这一步</h3>
 * 后端所有接口都返回统一响应体 `R<T> = { code, msg, data }`，这对前端是好事 ——
 * 但生成客户端时会产生一个**类型与运行时不一致**的严重问题：
 *
 * <pre>
 *   OpenAPI 里响应 schema 是 R<PageResult<TenantResponse>>
 *   orval 据此把函数返回类型标注为 R<PageResult<TenantResponse>>
 *   而我们的 mutator（packages/api/src/client.ts 的 request）已经把 R 剥成了 data
 *   ⇒ 声明的类型是 { code, msg, data }，实际拿到的是 data
 *   ⇒ 调用方按类型写 `res.data`，运行时却得到 undefined
 * </pre>
 *
 * 上述 bug <b>编译器不会报错</b>，只会在运行时表现为"数据是 undefined"，
 * 而且每个接口都要踩一次。这是"统一响应体"与"契约生成"两个正确的决策
 * 叠加后产生的副作用 —— 必须由流水线上的一道工序来消解。
 *
 * 解法：在把 spec 交给 orval 之前，把响应里对 `R*` schema 的 `$ref`
 * 替换成它内部 `data` 字段的 schema。这样 orval 生成的返回类型
 * 恰好等于 mutator 实际返回的内容。**运行时与类型完全对齐。**
 *
 * <h3>为什么用脚本而不是手工改 openapi.json</h3>
 * openapi.json 是后端构建产物，每次都会重新生成。任何手工修改都会被下次覆盖。
 * 预处理必须是流水线中可重复执行的一步。
 */

import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const apiRoot = resolve(here, '..')

const INPUT = resolve(apiRoot, 'openapi.json')
const OUTPUT = resolve(apiRoot, 'openapi.stripped.json')

/** 统一响应体外壳的 schema 名前缀（后端 R<T> 生成的名字形如 RPageResultTenantResponse）。 */
const ENVELOPE_PREFIX = 'R'

/**
 * 判断一个 schema 是否是统一响应体外壳。
 *
 * 用"必须含 code / msg / data 三个属性"来识别，而不是只看名字前缀 ——
 * 名字前缀会误伤（某个业务 DTO 恰好以 R 开头），而这三个属性是我们 `R` 记录的真实形状。
 *
 * <p>⚠️ 刻意<b>不</b>要求"恰好三个属性"。因为 springdoc 会把 record 上的
 * 无参访问方法也暴露成属性 —— `R` 的 `isSuccess()` 会额外生成一个 `success` 字段。
 * 若按"属性数恰好为 3"判断，会一个外壳都识别不出来，
 * 而脚本只会打印一句警告就继续跑，最终产出类型错位的客户端。
 * <b>识别条件应当基于"必须有什么"，而不是"恰好有多少"。</b>
 */
function isEnvelopeSchema(schema) {
  if (!schema || typeof schema !== 'object' || !schema.properties) {
    return false
  }
  const keys = Object.keys(schema.properties)
  return keys.includes('code') && keys.includes('msg') && keys.includes('data')
}

/** 取外壳里 data 字段的 schema；缺失时回退为"无内容"。 */
function unwrapEnvelope(envelope) {
  const data = envelope.properties?.data
  if (!data || Object.keys(data).length === 0) {
    // data 为空对象（即 R<Void>）：语义上是"没有返回值"。
    // 返回 null 表示"删除整个 schema"（见下方 resetContent），
    // 让生成函数返回 void，而不是一个无意义的空对象类型。
    return null
  }
  return data
}

const spec = JSON.parse(readFileSync(INPUT, 'utf8'))
const schemas = spec.components?.schemas ?? {}

// ---- 1. 收集需要剥离的外壳 schema 名 ----
const envelopeNames = new Set()
for (const [name, schema] of Object.entries(schemas)) {
  if (name.startsWith(ENVELOPE_PREFIX) && isEnvelopeSchema(schema)) {
    envelopeNames.add(name)
  }
}

if (envelopeNames.size === 0) {
  console.warn(
    '[prepare-spec] 未识别到任何统一响应体外壳 schema。' +
      '请确认后端接口仍返回 R<T>，或该方法已被上游改动。'
  )
}

// ---- 2. 替换所有响应中的 $ref ----
let replaced = 0
let resetToEmpty = 0

for (const pathItem of Object.values(spec.paths ?? {})) {
  for (const operation of Object.values(pathItem)) {
    const content = operation?.responses?.['200']?.content
    if (!content) {
      continue
    }
    for (const media of Object.values(content)) {
      const ref = media?.schema?.$ref
      if (!ref) {
        continue
      }
      const refName = ref.split('/').pop()
      if (!envelopeNames.has(refName)) {
        continue
      }
      const inner = unwrapEnvelope(schemas[refName])
      if (inner === null) {
        // R<Void>：删除 schema，让生成器产出 Promise<void>
        delete media.schema
        resetToEmpty++
      } else {
        // 把 $ref 换成内联 schema。
        // 内联是安全的：同样的 schema 可能被多个接口引用，
        // 而 OpenAPI 允许内联重复定义，不会产生冲突。
        media.schema = inner
        replaced++
      }
    }
  }
}

// ---- 3. 删除外壳 schema 定义 ----
for (const name of envelopeNames) {
  delete schemas[name]
}

writeFileSync(OUTPUT, JSON.stringify(spec, null, 2), 'utf8')

console.log(
  `[prepare-spec] 识别外壳 schema ${envelopeNames.size} 个；` +
    `替换响应 ${replaced} 处，置空 ${resetToEmpty} 处 → ${OUTPUT}`
)
