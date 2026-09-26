<script setup lang="ts">
import { NCard, NTag } from '@admin/ui'

/**
 * AI 落地方案页（架构叙事 + 接入指引）。
 *
 * <p>它是"AI 落地"菜单组的说明书：讲清楚这套 Harness 的分层、
 * 每一层的职责边界、以及"新增一种 AI 能力"要动哪几处。
 * 方案页用静态内容 —— 它的角色是文档，不是仪表盘。
 */
</script>

<template>
  <div class="ai-overview">
    <NCard title="架构：模型是能力，编排才是产品" :bordered="false" class="ai-overview__card">
      <p class="ai-overview__p">
        本系统的 AI 能力采用<b>后端代理 + 前端封装</b>的 Harness 形态：
        前端只认识「流式对话」这一个协议（SSE：meta → delta… → done），
        模型由谁提供、如何鉴权、上下文从哪来，全部封装在服务端。
        换模型（DeepSeek → OpenAI → 私有化部署）不改前端一行代码。
      </p>
      <pre class="ai-overview__code">
浏览器                    后端（Harness）                    AI 提供方
  │  POST /ai/chat (SSE)     │                                  │
  │ ────────────────────────► │  1. 鉴权（令牌→身份）            │
  │                           │  2. 上下文注入（工单概况=真实DB）│
  │                           │  3. 限流 / 超长截断              │
  │                           │ ─── /chat/completions stream ──► │
  │  ◄─ delta(增量) ───────── │  4. 逐块解析转发                 │
  │  ◄─ done ──────────────── │  5. 降级（无Key→演示模式）       │
      </pre>
      <div class="ai-overview__tags">
        <NTag size="small" type="success">Key 只在服务端</NTag>
        <NTag size="small" type="info">流式转发（首字节优先）</NTag>
        <NTag size="small" type="warning">无 Key 自动降级且明示 demo</NTag>
        <NTag size="small">上下文注入 = 真实数据库</NTag>
      </div>
    </NCard>

    <NCard title="已落地的能力" :bordered="false" class="ai-overview__card">
      <table class="ai-overview__table">
        <thead>
          <tr><th>能力</th><th>形态</th><th>边界与取舍</th></tr>
        </thead>
        <tbody>
          <tr>
            <td>AI 客服工作台</td>
            <td>流式对话（SSE）+ 本地会话 + 停止生成</td>
            <td>会话历史存前端：服务端无状态，删除/新建零成本；代价是历史随请求回传</td>
          </tr>
          <tr>
            <td>平台上下文注入</td>
            <td>每次请求注入当前租户工单概况</td>
            <td>"RAG-lite"：结构化数据注入。接向量检索只需替换注入段，其余链路不动</td>
          </tr>
          <tr>
            <td>演示模式</td>
            <td>无 Key 时确定性生成流式回答</td>
            <td>UX 与真实模式同路径，响应标明 demo —— 降级绝不伪装</td>
          </tr>
        </tbody>
      </table>
    </NCard>

    <NCard title="接入指引" :bordered="false" class="ai-overview__card">
      <p class="ai-overview__p">
        1. 配置环境变量 <code>AI_API_KEY</code>（DeepSeek 或任意 OpenAI 兼容服务），
        可选 <code>AI_BASE_URL</code> / <code>AI_MODEL</code>；
      </p>
      <p class="ai-overview__p">
        2. 重启后端 —— AI 客服页徽标从「演示模式」变为「真实模型」；
      </p>
      <p class="ai-overview__p">
        3. 新增 AI 能力（如工单自动摘要）：后端加一个走
        <code>AiChatService</code> 同款编排的端点，前端复用
        <code>streamAiChat()</code> 的 SSE 解析 —— 两层各自只做一次扩展。
      </p>
    </NCard>
  </div>
</template>

<style scoped>
.ai-overview__card {
  margin-bottom: 16px;
}

.ai-overview__p {
  margin: 0 0 10px;
  font-size: 13px;
  line-height: 1.9;
  color: var(--n-text-color-2, #666);
}

.ai-overview__p code {
  background: rgba(128, 128, 128, 0.12);
  border-radius: 3px;
  padding: 1px 5px;
  font-size: 12px;
}

.ai-overview__code {
  background: rgba(128, 128, 128, 0.08);
  border-radius: 6px;
  padding: 12px;
  font-size: 12px;
  line-height: 1.6;
  overflow-x: auto;
  margin: 0 0 12px;
}

.ai-overview__tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.ai-overview__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.ai-overview__table th,
.ai-overview__table td {
  border-bottom: 1px solid rgba(128, 128, 128, 0.18);
  padding: 9px 10px;
  text-align: left;
  vertical-align: top;
}
</style>
