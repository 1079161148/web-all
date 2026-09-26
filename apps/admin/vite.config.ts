import { fileURLToPath, URL } from 'node:url'
import fs from 'node:fs'
import http from 'node:http'
import { defineConfig, type Connect } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * DeepSeek Harness（dsh）本地服务地址与启动日志位置。
 *
 * <p>dsh Web UI 默认监听 127.0.0.1:3080（`npx @deepseek-ai/dsh web --no-open`，
 * 由根目录 `pnpm harness` 脚本拉起，输出重定向到 .dsh/runtime.log）。
 * 首次访问需要日志里打印的一次性 token，`dev-harness-proxy` 插件会读取它
 * 自动完成握手 —— 见下方插件注释。
 */
const DSH_HOST = '127.0.0.1'
const DSH_PORT = 3080
const DSH_LOG = fileURLToPath(new URL('../../.dsh/runtime.log', import.meta.url))

/** 从 dsh 启动日志里取最近一次打印的入口 URL（含一次性 token）。 */
function readDshEntryUrl(): string | null {
  try {
    const log = readFileUtf8(DSH_LOG)
    const matches = log.match(/http:\/\/127\.0\.0\.1:3080\/\?token=[A-Za-z0-9_-]+/g)
    return matches && matches.length > 0 ? matches[matches.length - 1] : null
  } catch {
    // 日志不存在（dsh 从未启动）/ 被占用：按"未启动"处理
    return null
  }
}

function readFileUtf8(file: string): string {
  return fs.readFileSync(file, 'utf8')
}

/**
 * Vite 8 配置（Rolldown 统一引擎）。
 *
 * <h3>为什么显式用 alias 指向 workspace 包的源码</h3>
 * pnpm 通过符号链接把 workspace 包挂到 node_modules 下，
 * 直接引用会让部分插件把包内文件当作「外部依赖」跳过转换（尤其是 .vue 文件）。
 * 别名指到源码可以保证 SFC 编译与 HMR 都正常工作，也让改动即时生效、无需先构建包。
 *
 * <h3>分包策略（设计文档 §12.5）</h3>
 * 大依赖各自独立 chunk，避免单个 vendor 文件过大导致首屏阻塞。
 */
export default defineConfig({

  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      // ⚠️ 顺序有意义：alias 是**前缀匹配**，'@admin/ui/core' 必须以更具体的条目
      // 排在 '@admin/ui' 之前，否则会被后者吞掉（表现为"窄入口没生效，首屏依旧 151 KB"）。
      '@admin/ui/core': fileURLToPath(new URL('../../packages/ui/src/core.ts', import.meta.url)),
      '@admin/ui': fileURLToPath(new URL('../../packages/ui/src/index.ts', import.meta.url)),
      '@admin/api': fileURLToPath(new URL('../../packages/api/src/index.ts', import.meta.url)),
      '@admin/theme': fileURLToPath(new URL('../../packages/theme/src/index.ts', import.meta.url))
    }
  },

  server: {
    port: 5173,
    // 开发期通过代理访问后端，避免 CORS 配置与 Cookie 跨站问题
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      // WebSocket（监控大屏）：ws:true 让 Vite 代理升级连接
      '/ws': {
        target: 'http://localhost:8080',
        ws: true,
        changeOrigin: true
      }
    }
  },

  // ⚠️ SSE 专用转发中间件（dev-only）：AI 流式对话走 /sse-proxy 前缀。
  // 为什么不用上面的 proxy：http-proxy 对 text/event-stream 的分块转发
  // 存在缓冲问题（实测直连后端 1s 完成、经代理整体挂起到超时），
  // 且浏览器直连后端端口会撞上 CORS/私有网络访问限制。
  // 这里用 Node 原生 http 手工 pipe —— 一个字节都不缓冲，行为与
  // 生产 nginx 的 `proxy_buffering off` 等价。生产走 nginx 同源，无此中间件。
  // （必须是插件对象的 configureServer —— 顶层字段会被 Vite 静默忽略，实测踩过）
  plugins: [
    vue(),
    {
      name: 'dev-sse-proxy',
      configureServer(server) {
        server.middlewares.use('/sse-proxy', (req, res) => {
          const target = 'http://localhost:8080' + (req.url ?? '')

          const proxyReq = http.request(
            target,
            { method: req.method, headers: { ...req.headers, host: 'localhost:8080' } },
            (proxyRes) => {
              res.writeHead(proxyRes.statusCode ?? 502, proxyRes.headers)
              proxyRes.pipe(res)
            }
          )
          proxyReq.on('error', () => {
            res.statusCode = 502
            res.end('upstream error')
          })
          req.pipe(proxyReq)
        })
      }
    },
    {
      // ------------------------------------------------------------------
      // DeepSeek Harness 同源反代（dev-only）
      // ------------------------------------------------------------------
      // 目标：让中台 /ai/harness 页面用 iframe 原样嵌入 dsh 的 Web UI，
      // 且浏览器视角下"一切都在中台同源"。
      //
      // <h3>为什么必须同源代理，不能让 iframe 直连 127.0.0.1:3080</h3>
      // dsh 的会话 cookie（dsh-auth-*）是 host-only 且无 SameSite 属性 ——
      // 浏览器按 Lax 处理，**跨站 iframe 的请求不会携带它**，页面永远 401。
      // 同源代理后：cookie 种在中台域名下；请求头 Host 改写为
      // 127.0.0.1:3080，与 cookie 内嵌的 authority 一致 ——
      // dsh 的 authority 校验才通过（实测：不改写 Host 直接 401）。
      //
      // <h3>三条路径规则（实测得出的最小集合）</h3>
      //   /harness/**  → 去掉前缀转发（页面 iframe 的入口）
      //   /plugins/**  → 原样转发（index.html 里 esbuild 的插件拼接 URL 是
      //                  绝对路径；代理还额外把 HTML 里的 /plugins 前缀
      //                  改写为 /harness/plugins，双保险）
      //   /api/**      → 原样转发，但**排除 /api/v1/**（那是我们自己的后端）。
      //                  dsh 的实时流走 /api/remote.mux（HTTP 流，无 WebSocket，
      //                  因此不需要 ws 升级转发）。
      //
      // <h3>认证握手（本插件的关键职责）</h3>
      // dsh 首次访问要求 `/?token=xxx`（一次性 token，打印在启动日志里）。
      // 代理在需要时读取 .dsh/runtime.log 取 token，内部完成
      // "带 token 请求 → 拿 Set-Cookie"，之后所有转发请求都带上该 cookie；
      // 收到 401 时强制重新握手一次（dsh 重启后 token 轮换即可自愈）
      // —— 用户全程无感，页面无需知道 token 的存在。
      //
      // ⚠️ 中间件在 configureServer 中直接 use 属于 pre 阶段，先于
      // Vite 内置的 server.proxy（否则 /api 会被内置规则全部劫持到 8080）。
      name: 'dev-harness-proxy',
      configureServer(server) {
        let dshCookie: string | null = null

        /** 用一次性 token 换取会话 cookie（返回 'dsh-auth-xxx=...' 或 null）。 */
        function handshake(): Promise<string | null> {
          const entry = readDshEntryUrl()
          const token = entry?.match(/token=([A-Za-z0-9_-]+)/)?.[1]
          if (!token) {
            return Promise.resolve(null)
          }
          return new Promise((resolve) => {
            const req = http.request(
              {
                host: DSH_HOST,
                port: DSH_PORT,
                path: `/?token=${token}`,
                method: 'GET',
                headers: { host: `${DSH_HOST}:${DSH_PORT}` }
              },
              (res) => {
                const cookies = res.headers['set-cookie'] ?? []
                res.resume()
                const found = cookies
                  .map(String)
                  .find((item) => item.startsWith('dsh-auth-'))
                resolve(found ? found.split(';')[0] : null)
              }
            )
            req.on('error', () => resolve(null))
            req.end()
          })
        }

        async function ensureCookie(force: boolean): Promise<string | null> {
          if (dshCookie === null || force) {
            dshCookie = await handshake()
          }
          return dshCookie
        }

        /** 转发单个请求；401 时重新握手并重试一次（最多一次，避免死循环）。 */
        function forward(
          req: Connect.IncomingMessage,
          res: import('node:http').ServerResponse,
          targetPath: string,
          retry: boolean
        ): void {
          void (async () => {
            const cookie = await ensureCookie(false)
            const headers: Record<string, string | string[] | undefined> = {
              ...req.headers,
              // Host 必须改写成 dsh 自己的 authority，否则 401（见插件注释）
              host: `${DSH_HOST}:${DSH_PORT}`
            }
            // Origin 同理：dsh 对 /api/** 做来源校验（防跨站调用），
            // 浏览器带的是中台 origin → 403（实测：credentials/describe、
            // session/modelCatalog 等接口全部 403）。改写成 dsh 自己的
            // origin 才能通过 —— 与 Host 改写是同一条"让上游以为请求
            // 就来自它自己"的原则。
            if (headers.origin) {
              headers.origin = `http://${DSH_HOST}:${DSH_PORT}`
            }
            // 不声明压缩：dsh 的 gzip 会让 HTML 改写拿不到明文
            delete headers['accept-encoding']
            if (cookie) {
              headers.cookie = cookie
            } else {
              delete headers.cookie
            }

            const proxyReq = http.request(
              { host: DSH_HOST, port: DSH_PORT, path: targetPath, method: req.method, headers },
              (proxyRes) => {
                const status = proxyRes.statusCode ?? 502
                if (status === 401 && retry) {
                  proxyRes.resume()
                  void ensureCookie(true).then(() => forward(req, res, targetPath, false))
                  return
                }
                const contentType = String(proxyRes.headers['content-type'] ?? '')
                if (contentType.includes('text/html')) {
                  // 两处改写，缺一不可（都靠实测暴露）：
                  //  1. <base href="/"> → <base href="/harness/">：dsh 的
                  //     index.html 带 base 标签，所有**相对**资源（./assets/**、
                  //     ./manifest.webmanifest）都按根解析 —— 不改写就会请求
                  //     中台的 /assets/**（404，iframe 一片空白）。
                  //  2. /plugins 是绝对路径（esbuild 的插件拼接 URL），
                  //     不受 base 影响 → 改写为 /harness/plugins。
                  //     代理同时保留原生 /plugins 转发兜底（JS 内动态加载）。
                  let body = ''
                  proxyRes.setEncoding('utf8')
                  proxyRes.on('data', (chunk: string) => {
                    body += chunk
                  })
                  proxyRes.on('end', () => {
                    const rewritten = body
                      .replace(/<base\s+href="\/"\s*\/?>/i, '<base href="/harness/">')
                      .replaceAll('="/plugins', '="/harness/plugins')
                      .replaceAll("='/plugins", "='/harness/plugins")
                    const buffer = Buffer.from(rewritten, 'utf8')
                    const outHeaders: Record<string, string | string[] | undefined> = {
                      ...proxyRes.headers,
                      'content-length': String(buffer.length)
                    }
                    delete outHeaders['content-encoding']
                    delete outHeaders['transfer-encoding']
                    res.writeHead(status, outHeaders)
                    res.end(buffer)
                  })
                  return
                }
                res.writeHead(status, proxyRes.headers)
                proxyRes.pipe(res)
              }
            )
            proxyReq.on('error', () => {
              // dsh 未启动：给页面一个明确可判定的状态（页面据此显示启动引导）
              res.statusCode = 502
              res.setHeader('content-type', 'text/plain; charset=utf-8')
              res.end('DSH_OFFLINE')
            })
            req.pipe(proxyReq)
          })()
        }

        /**
         * dsh 的顶层路径白名单（**实测得出，新增顶层路径时在此补充**）：
         *   /plugins/**      插件的 esbuild 拼接产物（client.js 等）
         *   /open-in-app/**  open-in-app 插件的本地应用探测（编辑器集成）
         *   /api/**          Host API 与 /api/remote.mux 实时流
         *                    ⚠️ 必须排除 /api/v1/**（中台自己的后端）
         * 其余 /harness/** 是 iframe 入口本身（去前缀转发）。
         */
        const DSH_PATH_PREFIXES = ['/plugins/', '/open-in-app/']

        function isDshApiPath(pathOnly: string): boolean {
          return pathOnly.startsWith('/api/') && !pathOnly.startsWith('/api/v1/')
        }

        server.middlewares.use((req, res, next) => {
          const url = req.url ?? ''
          const queryIndex = url.indexOf('?')
          const pathOnly = queryIndex >= 0 ? url.slice(0, queryIndex) : url
          // ⚠️ 必须从**第一个** ? 起整段保留 query：dsh 的插件请求形如
          // /plugins/??a/client.js,b/client.js&rev=x（两个问号），
          // 用 url.split('?')[1] 会把它截空 → 上游 404（实测踩过）
          const query = queryIndex >= 0 ? url.slice(queryIndex) : ''

          if (pathOnly === '/harness' || pathOnly.startsWith('/harness/')) {
            const rest = pathOnly.slice('/harness'.length)
            forward(req, res, (rest === '' ? '/' : rest) + query, true)
            return
          }
          if (DSH_PATH_PREFIXES.some((prefix) => pathOnly.startsWith(prefix))) {
            forward(req, res, url, true)
            return
          }
          if (isDshApiPath(pathOnly)) {
            forward(req, res, url, true)
            return
          }
          next()
        })

        // ------------------------------------------------------------------
        // WebSocket 升级转发
        // ------------------------------------------------------------------
        // dsh 的实时通道是 WS：`/api/remote.mux`（客户端连接插件用它做会话
        // 多路复用）。Connect 中间件只处理普通请求，协议升级必须挂到
        // httpServer 的 upgrade 事件上。
        // Vite 自己的 HMR 也走 upgrade（路径为 / 且协议 vite-hmr），
        // 因此这里只接管我们负责的前缀，其余原样放行。
        server.httpServer?.on(
          'upgrade',
          (
            req: Connect.IncomingMessage,
            socket: import('node:stream').Duplex,
            head: Buffer
          ) => {
            const url = req.url ?? ''
            const queryIndex = url.indexOf('?')
            const pathOnly = queryIndex >= 0 ? url.slice(0, queryIndex) : url
            const isMine = isDshApiPath(pathOnly) || pathOnly.startsWith('/harness/')
            if (!isMine) {
              return
            }
            void (async () => {
              const cookie = await ensureCookie(false)
              const headers: Record<string, string | string[] | undefined> = {
                ...req.headers,
                host: `${DSH_HOST}:${DSH_PORT}`
              }
              if (headers.origin) {
                headers.origin = `http://${DSH_HOST}:${DSH_PORT}`
              }
              if (cookie) {
                headers.cookie = cookie
              } else {
                delete headers.cookie
              }
              delete headers['accept-encoding']

              const proxyReq = http.request({
                host: DSH_HOST,
                port: DSH_PORT,
                path: url,
                method: 'GET',
                headers
              })
              proxyReq.on('upgrade', (proxyRes, proxySocket, proxyHead) => {
                const lines = Object.entries(proxyRes.headers).map(
                  ([key, value]) =>
                    `${key}: ${Array.isArray(value) ? value.join(', ') : String(value)}`
                )
                socket.write(`HTTP/1.1 101 Switching Protocols\r\n${lines.join('\r\n')}\r\n\r\n`)
                if (proxyHead && proxyHead.length > 0) {
                  proxySocket.unshift(proxyHead)
                }
                proxySocket.on('error', () => socket.destroy())
                socket.on('error', () => proxySocket.destroy())
                proxySocket.pipe(socket)
                socket.pipe(proxySocket)
              })
              // 未升级（401/403/404）：把状态如实回给客户端便于排查
              proxyReq.on('response', (proxyResponse) => {
                socket.write(
                  `HTTP/1.1 ${proxyResponse.statusCode} ${proxyResponse.statusMessage}\r\n\r\n`
                )
                proxyResponse.resume()
                socket.destroy()
              })
              proxyReq.on('error', () => socket.destroy())
              if (head && head.length > 0) {
                proxyReq.write(head)
              }
              proxyReq.end()
            })()
          }
        )
      }
    }
  ],

  build: {
    target: 'es2022',
    sourcemap: false,
    // 单个 chunk 超过该体积告警（设计文档 §12.1 的预算门禁基线）
    chunkSizeWarningLimit: 1500
  }
})
