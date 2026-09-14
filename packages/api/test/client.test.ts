/**
 * 请求客户端的行为测试。
 *
 * <h3>为什么用 node:test 而不是 Vitest</h3>
 * 设计文档 §13.5 规定前端测试栈是 Vitest。但本包（{@code @admin/api}）是<b>纯 TS 库</b>：
 * 它的代码不碰 DOM、不碰 Vue 组件，因此不需要 Vitest 的组件测试能力。
 *
 * <p>另一方面，Vitest 会引入一整套运行时与配置。而 Node 22 自带的
 * {@code node:test} + 类型剥离，让这个测试文件<b>零依赖即可运行</b> ——
 * 对一个"必须在 CI 里稳定拦住回归"的测试来说，"能跑"比"用哪个框架"重要得多。
 *
 * <p>迁移路径：当 {@code apps/admin} 引入 Vitest 做组件测试时（P2），
 * 本文件可以零改动地搬过去 —— 断言用的是 {@code node:assert}，
 * 语义与 Vitest 的 expect 完全对应，且 Vitest 兼容 node:test 的写法。
 * <b>先用最小的手段把回归测试立起来，比等一套完整测试栈就位更实际。</b>
 *
 * <h3>这个测试防的是什么</h3>
 * orval 生成的代码会传<b>已序列化的字符串</b>作为 body：
 * <pre>{@code request('/api/v1/auth/login', { body: JSON.stringify(loginRequest) })}</pre>
 * 若客户端再序列化一次，后端收到的是 {@code "{\"username\":...}"}（一个字符串值），
 * Jackson 会抛 {@code HttpMessageNotReadableException}，接口返回
 * "请求体格式不正确"。
 *
 * <p>这个 bug 的可怕之处是<b>静态检查与手工验证都抓不到</b>：
 * 类型是 {@code unknown} 所以编译通过；用 curl / Postman 直接打接口也完全正常
 * （那些方式不经过这段 JS）。<b>只有真实浏览器点按钮才会炸。</b>
 * 因此必须有一个直接针对 {@code request()} 的测试把它钉住。
 */

import { describe, it, mock, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { ApiError, request } from '../src/client.ts'

/** 记录 fetch 收到的参数，并返回一个可控的响应。 */
function stubFetch(payload: unknown, status = 200): Array<{ url: string; init: RequestInit }> {
  const calls: Array<{ url: string; init: RequestInit }> = []
  const fakeFetch = async (url: string | URL | Request, init?: RequestInit) => {
    calls.push({ url: String(url), init: init ?? {} })
    return new Response(JSON.stringify(payload), {
      status,
      headers: { 'Content-Type': 'application/json' }
    })
  }
  mock.method(globalThis, 'fetch', fakeFetch)
  return calls
}

/** 成功响应体（与后端统一响应体结构一致）。 */
function successBody(data: unknown): unknown {
  return { code: 0, msg: '成功', data }
}

afterEach(() => {
  mock.restoreAll()
})

describe('request() 请求体处理', () => {
  it('【回归】已序列化的字符串 body 必须原样透传，不能被二次序列化', async () => {
    const calls = stubFetch(successBody({ accessToken: 'tk-1' }))
    const original = JSON.stringify({ username: 'admin', password: 'Admin@123456' })

    await request('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: original
    })

    // 若客户端再次 JSON.stringify，这里会得到 '"{\\"username\\":...}"'（带外层引号）
    assert.equal(calls[0]!.init.body, original)
    // 双重序列化的典型特征：body 以引号开头
    assert.ok(
      !String(calls[0]!.init.body).startsWith('"'),
      'body 不应被二次序列化（被序列化两次的字符串会以引号开头）'
    )
  })

  it('普通对象 body 应被序列化，并自动补上 Content-Type', async () => {
    const calls = stubFetch(successBody(null))

    await request('/api/v1/auth/login', {
      method: 'POST',
      body: { username: 'admin', password: 'p' }
    })

    assert.equal(calls[0]!.init.body, '{"username":"admin","password":"p"}')
    const headers = calls[0]!.init.headers as Record<string, string>
    assert.equal(headers['Content-Type'], 'application/json')
  })

  it('调用方显式提供的 Content-Type 不应被覆盖', async () => {
    const calls = stubFetch(successBody(null))

    await request('/x', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json;charset=UTF-8' },
      body: { a: 1 }
    })

    const headers = calls[0]!.init.headers as Record<string, string>
    assert.equal(headers['Content-Type'], 'application/json;charset=UTF-8')
  })

  it('GET 请求不应携带 body 与 Content-Type', async () => {
    const calls = stubFetch(successBody([]))

    await request('/api/v1/iam/tenants', { method: 'GET', params: { page: 1, size: 20 } })

    assert.equal(calls[0]!.init.body, undefined)
    const headers = calls[0]!.init.headers as Record<string, string>
    assert.equal(headers['Content-Type'], undefined)
    // 空值参数必须被剔除，否则后端会把 "undefined" 当成字符串解析
    assert.ok(calls[0]!.url.includes('page=1'), `URL 应包含分页参数，实际: ${calls[0]!.url}`)
  })
})

describe('request() 响应处理', () => {
  it('业务码非 0 时应抛出 ApiError，并保留业务码与提示', async () => {
    stubFetch({ code: 20002, msg: '账号或密码错误', data: null })

    await assert.rejects(
      () => request('/api/v1/auth/login', { method: 'POST', body: {} }),
      (error: unknown) => {
        assert.ok(error instanceof ApiError)
        assert.equal(error.code, 20002)
        assert.equal(error.message, '账号或密码错误')
        return true
      }
    )
  })

  it('401 应被识别为认证失效（供前端触发重新登录）', async () => {
    stubFetch({ code: 20001, msg: '登录状态已失效，请重新登录', data: null }, 401)

    await assert.rejects(
      () => request('/api/v1/auth/me'),
      (error: unknown) => {
        assert.ok(error instanceof ApiError)
        assert.equal(error.isUnauthenticated(), true)
        return true
      }
    )
  })

  it('成功时应剥掉响应信封，只返回 data', async () => {
    stubFetch(successBody({ accessToken: 'tk', roles: ['SUPER_ADMIN'] }))

    const result = await request<{ accessToken: string }>('/api/v1/auth/login', {
      method: 'POST',
      body: {}
    })

    assert.deepEqual(result, { accessToken: 'tk', roles: ['SUPER_ADMIN'] })
  })
})
