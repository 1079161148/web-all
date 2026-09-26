/**
 * AI 指挥中心客户端（Agent Tool Calling，SSE 流式）。
 *
 * 事件比聊天多一种：step —— 每次工具调用（工具/参数/结果）。
 * 前端把 step 渲染成执行时间线：指挥者能看到 AI 每一步动了什么、
 * 结果如何 —— Agent 的可信度来自过程的透明，而不是答案的口吻。
 */

import { streamFetch } from '@admin/api'

const API_BASE = (import.meta.env?.VITE_API_BASE_URL as string | undefined) ?? ''
// SSE 专用基址（dev 走 /sse-proxy 手工 pipe 中间件，理由见 api/ai.ts）
const SSE_BASE = import.meta.env.DEV ? '/sse-proxy' : API_BASE

export interface AgentStep {
  tool: string
  args: string
  result: string
}

export interface AgentHandlers {
  onMeta?: (mode: 'real' | 'demo') => void
  onStep?: (step: AgentStep) => void
  onDelta: (text: string) => void
  onError?: (message: string) => void
  onDone?: () => void
}

/**
 * 发起 Agent 指令。返回 abort 函数。SSE 解析与聊天同构（step 事件额外解析 JSON）。
 *
 * <p>认证与 401 处理走 {@code @admin/api} 的 {@code streamFetch}（含静默刷新 +
 * 重试一次），与统一请求层同源 —— 理由见 api/ai.ts 的同一段注释。
 */
export function streamAgent(instruction: string, handlers: AgentHandlers): () => void {
  const controller = new AbortController()
  void (async () => {
    try {
      const response = await streamFetch(
        '/api/v1/ai/agent',
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'text/event-stream'
          },
          body: JSON.stringify({ sessionId: 'command', instruction }),
          signal: controller.signal
        },
        SSE_BASE
      )
      if (!response.ok || !response.body) {
        handlers.onError?.(
          response.status === 401
            ? '登录状态已过期，请刷新页面后重试'
            : `指挥服务响应异常（${response.status}）`
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
              // 与聊天端点同一教训：多行 data 按规范拼接，
              // 空行不能被 falsy 判断吞掉（工具结果里常有换行）
              const part = line.slice(5)
              data += (hasDataLine ? '\n' : '') + (part.startsWith(' ') ? part.slice(1) : part)
              hasDataLine = true
            }
          }
          if (eventName === 'meta') {
            handlers.onMeta?.(data === 'demo' ? 'demo' : 'real')
          } else if (eventName === 'step') {
            try {
              handlers.onStep?.(JSON.parse(data) as AgentStep)
            } catch {
              handlers.onStep?.({ tool: 'unknown', args: '', result: data })
            }
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