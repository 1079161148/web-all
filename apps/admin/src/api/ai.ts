/**
 * AI 对话客户端（SSE 流式）。
 *
 * <h3>为什么用裸 fetch 而不是 EventSource / 请求层</h3>
 * <ul>
 *   <li>EventSource 只支持 GET —— 而对话体（历史消息）必须 POST；</li>
 *   <li>请求层（client.ts）以"等完整 JSON"为前提，流式响应永远等不到结束；</li>
 *   <li>裸 fetch + ReadableStream 是标准方案：逐块读取、按 SSE 帧解析，
 *       且 AbortController 可以随时<b>停止生成</b>（用户点"停止"就是 abort）。</li>
 * </ul>
 */

import { streamFetch } from '@admin/api'

const API_BASE = (import.meta.env?.VITE_API_BASE_URL as string | undefined) ?? ''
// ⚠️ SSE 专用基址（dev）：Vite 的 http-proxy 会缓冲 text/event-stream，
// 流式变成"一次全吐"（实测直连 1s 完成、经代理挂到超时）。dev 走
// vite.config 里的 /sse-proxy 手工 pipe 中间件（逐字节转发）；生产经
// nginx 同源反代（proxy_buffering off），API_BASE 保持同源相对路径。
const SSE_BASE = import.meta.env.DEV ? '/sse-proxy' : API_BASE

/** 多模态内容块（OpenAI 协议形态：text / image_url）。 */
export interface AiContentBlock {
  type: 'text' | 'image_url'
  text?: string
  image_url?: { url: string }
}

export interface AiChatMessage {
  role: 'system' | 'user' | 'assistant'
  /** 纯文本，或多模态内容块数组（后端原样透传给模型）。 */
  content: string | AiContentBlock[]
}

export interface AiStreamHandlers {
  /** meta 事件：real = 真实模型，demo = 演示模式。 */
  onMeta?: (mode: 'real' | 'demo') => void
  /** delta 事件：增量文本（调用方负责拼接）。 */
  onDelta: (text: string) => void
  /** 服务端报错（上游失败等）。 */
  onError?: (message: string) => void
  onDone?: () => void
}

/**
 * 发起流式对话。返回 abort 函数 —— 用户点"停止生成"时调用。
 *
 * <p>SSE 帧格式（text/event-stream 标准）：`event: xxx` 行 + `data: yyy` 行，
 * 空行分隔一帧。注意 data 可能被服务端按行拆分（多行 data = 换行内容），
 * 解析按标准累积处理。
 *
 * <p>⚠️ 认证与 401 处理走 {@code @admin/api} 的 {@link streamFetch}，
 * <b>不再自己读 sessionStorage</b>：流式请求不能走 request()（它等完整 JSON），
 * 但认证语义必须与统一请求层同源 —— 否则访问令牌过期时，普通接口静默续期、
 * 只有 AI 对话拿到 401（实测症状："其他页面都正常，只有 AI 对话报 401"）。
 */
export function streamAiChat(
  sessionId: string,
  messages: AiChatMessage[],
  handlers: AiStreamHandlers
): () => void {
  const controller = new AbortController()
  void (async () => {
    try {
      const response = await streamFetch(
        '/api/v1/ai/chat',
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'text/event-stream'
          },
          body: JSON.stringify({ sessionId, messages }),
          signal: controller.signal
        },
        SSE_BASE
      )
      if (!response.ok || !response.body) {
        handlers.onError?.(
          response.status === 401
            ? '登录状态已过期，请刷新页面后重试'
            : `AI 服务响应异常（${response.status}）`
        )
        handlers.onDone?.()
        return
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let eventName = ''
      for (;;) {
        const { done, value } = await reader.read()
        if (done) {
          break
        }
        buffer += decoder.decode(value, { stream: true })
        // SSE 帧以空行分隔
        let boundary = buffer.indexOf('\n\n')
        while (boundary >= 0) {
          const frame = buffer.slice(0, boundary)
          buffer = buffer.slice(boundary + 2)
          let data = ''
          let hasDataLine = false
          for (const line of frame.split('\n')) {
            if (line.startsWith('event:')) {
              eventName = line.slice(6).trim()
            } else if (line.startsWith('data:')) {
              // ⚠️ 多行 data 按 SSE 规范用 \n 连接 —— 必须用"是否已出现过
              // data 行"跟踪，而不是判 data 是否为真值：模型输出换行时
              // 后端会发出连续的空 data 行（data:""+data:""），用 falsy
              // 判断会把换行全部吞掉（实测：Markdown 列表挤成一行）。
              // 每行只去一个前导空格（规范行为），不用 trim —— 否则代码块缩进会坏。
              const part = line.slice(5)
              data += (hasDataLine ? '\n' : '') + (part.startsWith(' ') ? part.slice(1) : part)
              hasDataLine = true
            }
          }
          if (eventName === 'meta') {
            handlers.onMeta?.(data === 'demo' ? 'demo' : 'real')
          } else if (eventName === 'delta') {
            handlers.onDelta?.(data)
          } else if (eventName === 'error') {
            handlers.onError?.(data)
          } else if (eventName === 'done') {
            handlers.onDone?.()
          }
          eventName = ''
          boundary = buffer.indexOf('\n\n')
        }
      }
      handlers.onDone?.()
    } catch (error) {
      if ((error as Error)?.name === 'AbortError') {
        handlers.onDone?.()
        return
      }
      handlers.onError?.(error instanceof Error ? error.message : '连接失败')
      handlers.onDone?.()
    }
  })()
  return () => controller.abort()
}
