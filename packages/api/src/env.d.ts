/**
 * `import.meta.env` 的类型声明。
 *
 * <h3>为什么本包要自己声明，而不是引入 `vite/client` 类型</h3>
 * {@code @admin/api} 是一个<b>被应用消费的库</b>，它的构建目标不止 Vite：
 * 它也需要能被 Node 脚本直接加载（例如契约流水线的校验脚本、
 * 或像本次排查那样用 `node --experimental-strip-types` 直接跑源码验证行为）。
 *
 * <p>若通过 `"types": ["vite/client"]` 引入 Vite 的类型，本包就被绑上了
 * 一个只在 Vite 构建期存在的类型依赖 —— 在其他环境里类型检查会失败，
 * 而它实际上只用到 `import.meta.env` 上的一个可选字符串。
 * <b>用 5 行声明换掉一个不必要的构建工具耦合，是划算的。</b>
 *
 * <p>注意所有字段都是可选的：本包必须能在"没有 Vite 注入 env"的环境下工作，
 * 因此 `client.ts` 里读它是用可选链 + 兜底值，而不是假定一定存在。
 */
interface ImportMetaEnv {
  /** API 基地址。开发期留空，走 Vite 代理；生产由同源反代提供。 */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env?: ImportMetaEnv
}
