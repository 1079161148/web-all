<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { NButton, NCard, NInput, NTag, feedback } from '@admin/ui'
import { streamAiChat, type AiChatMessage } from '@/api/ai'
import AiMarkdown from '@/components/AiMarkdown.vue'

/**
 * AI 客服工作台。
 *
 * <h3>交互要点</h3>
 * <ul>
 *   <li><b>会话在本地</b>（localStorage 按用户隔离）：服务端无状态，
 *       历史随请求回传（标准 OpenAI 协议形态）—— 删除/新建会话零成本；</li>
 *   <li><b>流式渲染</b>：delta 到达即追加（打字机效果来自服务端流，
 *       不是前端定时器假装的）；</li>
 *   <li><b>停止生成</b>：abort 请求 —— 半截回答保留并标注，而不是整条丢弃；</li>
 *   <li><b>模式徽标</b>：real / demo 明示 —— 降级绝不伪装成真实回答。</li>
 * </ul>
 */

interface Session {
  id: string
  title: string
  messages: Array<{ role: 'user' | 'assistant'; content: string; imageUrl?: string; truncated?: boolean }>
}

const SESSIONS_KEY = 'ai-chat-sessions'

const sessions = ref<Session[]>([])
const activeId = ref('')
const draft = ref('')
/** 待发送的附图（base64 data URL，来自文件选择）。 */
const pendingImage = ref('')
const streaming = ref(false)
const streamMode = ref<'real' | 'demo' | null>(null)
let abortFn: (() => void) | null = null

/**
 * 选择图片文件 → base64 data URL。
 *
 * <p>为什么不走"上传到文件服务再引用 URL"：模型上游需要<b>自己抓取</b>
 * 那个 URL，本系统部署在内网时上游根本够不着 localhost —— data URL
 * 随请求体直传，是唯一不依赖外部图床的形态（OpenAI 协议原生支持）。
 * 5MB 上限：base64 后约 6.7MB，低于后端 3MB 校验线时会给出明确报错。
 */
function onPickImage(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) {
    return
  }
  if (!/^image\/(png|jpeg|webp|gif)$/.test(file.type)) {
    feedback.error('仅支持 png / jpeg / webp / gif 图片')
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    feedback.error('图片不能超过 5MB')
    return
  }
  const reader = new FileReader()
  reader.onload = () => {
    pendingImage.value = String(reader.result)
  }
  reader.onerror = () => {
    feedback.error('图片读取失败')
  }
  reader.readAsDataURL(file)
}

const active = computed(() =>
  sessions.value.find((s) => s.id === activeId.value) ?? null
)

const messagesEl = ref<HTMLDivElement | null>(null)

function scrollBottom(): void {
  void nextTick(() => {
    const el = messagesEl.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

function loadSessions(): void {
  try {
    const raw = localStorage.getItem(SESSIONS_KEY)
    const parsed: unknown = raw === null ? [] : JSON.parse(raw)
    if (Array.isArray(parsed)) {
      sessions.value = parsed.filter((s): s is Session =>
        typeof s === 'object' && s !== null && 'id' in s && 'messages' in s
      )
    }
  } catch {
    sessions.value = []
  }
}

function persist(): void {
  try {
    localStorage.setItem(SESSIONS_KEY, JSON.stringify(sessions.value))
  } catch {
    // 存储满/不可用：会话留在内存，本次会话仍可用
  }
}

function createSession(): Session {
  const session: Session = {
    id: `s_${Date.now()}_${Math.floor(Math.random() * 1e4)}`,
    title: '新对话',
    messages: []
  }
  sessions.value = [session, ...sessions.value]
  activeId.value = session.id
  persist()
  return session
}

function removeSession(id: string): void {
  sessions.value = sessions.value.filter((s) => s.id !== id)
  if (activeId.value === id) {
    activeId.value = sessions.value[0]?.id ?? ''
  }
  persist()
}

/** 消息气泡的定位：助手靠左、用户靠右。 */
function isUser(message: { role: string }): boolean {
  return message.role === 'user'
}

/** 最近 8 条消息用完整 Markdown 渲染，更早的退化纯文本（长会话性能边界）。 */
function isRich(index: number): boolean {
  return index >= (active.value?.messages.length ?? 0) - 8
}

function send(): void {
  const text = draft.value.trim()
  if ((!text && !pendingImage.value) || streaming.value) {
    return
  }
  const session = active.value ?? createSession()
  if (session.messages.length === 0) {
    session.title = text.slice(0, 18)
  }

  // 多模态：附图时按 OpenAI 内容块数组发送。
  // 图片以前端上传文件转出的 base64 data URL 形态传输 —— 标准 OpenAI
  // 协议形态，且不依赖图床（后端校验类型与大小后原样透传给上游模型）
  const url = pendingImage.value
  const userMessage = { role: 'user' as const, content: text, imageUrl: url || undefined }
  session.messages.push(userMessage)
  const reply: Session['messages'][number] = { role: 'assistant', content: '' }
  session.messages.push(reply)
  draft.value = ''
  pendingImage.value = ''
  streaming.value = true
  streamMode.value = null
  scrollBottom()

  // 多轮上下文窗口：只回传最近 12 条（6 轮）。
  // 更早的历史对回答的边际价值递减，而 token 成本与延迟线性上涨 ——
  // 这是所有对话产品的标准取舍（后端另有单条 4000 字与 20 条硬限兜底）。
  const HISTORY_WINDOW = 12
  const history: AiChatMessage[] = session.messages
    .slice(0, -1)
    .slice(-HISTORY_WINDOW)
    .filter((m) => m.content || m.imageUrl)
    .map((m) => ({
      role: m.role,
      // 多模态消息回传内容块数组（与发送时同构 —— 会话历史即提示词）
      content:
        m.imageUrl && m.role === 'user'
          ? [
              { type: 'text', text: m.content || '请看这张图' },
              { type: 'image_url', image_url: { url: m.imageUrl } }
            ]
          : m.content
    }))

  abortFn = streamAiChat(session.id, history, {
    onMeta: (mode) => {
      streamMode.value = mode
    },
    onDelta: (piece) => {
      reply.content += piece
      scrollBottom()
    },
    onError: (message) => {
      // 失败原因必须落在对话流里而不是只弹 toast —— toast 会消失，
      // 而"刚才那条为什么没回答"是用户回头必然要问的问题。
      // ⚠️ 同时清掉"已停止生成"标记：错误信息本身就是终止态，
      // 再叠一行"（已停止生成）"是噪音（实测截图踩过）
      reply.truncated = false
      reply.content = reply.content
        ? `${reply.content}\n\n⚠ ${message}`
        : `⚠ ${message}`
      feedback.error(message)
      scrollBottom()
    },
    onDone: () => {
      if (!reply.content) {
        reply.content = '（未返回内容）'
      }
      // 注意：这里不能清 truncated —— 用户主动停止（AbortError）也走 onDone，
      // "（已停止生成）"必须保留；错误的场景由 onError 清（见上）
      streaming.value = false
      abortFn = null
      persist()
      scrollBottom()
    }
  })
}

function stop(): void {
  abortFn?.()
  const session = active.value
  const last = session?.messages[session.messages.length - 1]
  if (last && last.role === 'assistant' && last.content) {
    last.truncated = true
  }
  streaming.value = false
  abortFn = null
  persist()
}

onMounted(() => {
  loadSessions()
  if (sessions.value.length === 0) {
    createSession()
  } else {
    activeId.value = sessions.value[0].id
  }
})
</script>

<template>
  <div class="ai-chat">
    <div class="ai-chat__sessions">
      <NButton size="small" type="primary" block @click="createSession()">新建对话</NButton>
      <div
        v-for="session in sessions"
        :key="session.id"
        class="ai-chat__session"
        :class="{ 'ai-chat__session--active': session.id === activeId }"
        @click="activeId = session.id"
      >
        <span class="ai-chat__session-title">{{ session.title }}</span>
        <span
          class="ai-chat__session-del"
          @click.stop="removeSession(session.id)"
        >×</span>
      </div>
      <p class="ai-chat__note">
        会话保存在本机浏览器；服务端无状态，历史随请求回传（标准协议形态）。
      </p>
    </div>

    <NCard :bordered="false" class="ai-chat__main">
      <div class="ai-chat__head">
        <span class="ai-chat__title">{{ active?.title ?? 'AI 客服' }}</span>
        <NTag v-if="streamMode" size="small" :type="streamMode === 'real' ? 'success' : 'warning'">
          {{ streamMode === 'real' ? '真实模型' : '演示模式（未配置 Key）' }}
        </NTag>
      </div>

      <div ref="messagesEl" class="ai-chat__messages">
        <template v-if="active">
          <div
            v-for="(message, index) in active.messages"
            :key="index"
            class="ai-chat__bubble"
            :class="isUser(message) ? 'ai-chat__bubble--user' : 'ai-chat__bubble--ai'"
          >
            <!--
              长会话性能：只有最近 8 条消息用 Markdown 渲染（rich），
              更早的退化为纯文本 —— markdown 重渲染成本随消息数累积，
              而"很久之前的回复"几乎不会被重新阅读。
              streaming 中的最后一条永远 rich（打字机效果不受影响）。
            -->
            <template v-if="message.role === 'assistant' && isRich(index)">
              <AiMarkdown
                :text="message.content"
                :streaming="streaming && index === active.messages.length - 1"
              />
              <span v-if="message.truncated" class="ai-chat__truncated">（已停止生成）</span>
            </template>
            <div v-else class="ai-chat__text ai-chat__text--plain">{{ message.content }}<span
              v-if="message.truncated"
              class="ai-chat__truncated"
            >（已停止生成）</span></div>
            <img
              v-if="message.imageUrl"
              :src="message.imageUrl"
              class="ai-chat__image"
              alt="用户附图"
            >
          </div>
        </template>
        <div v-if="!active || active.messages.length === 0" class="ai-chat__welcome">
          <h2 class="ai-chat__welcome-title">今天有什么可以帮到你？</h2>
          <p class="ai-chat__empty">试试问：「现在有几个待处理工单？」—— 回答里的数字来自平台实时上下文。</p>
        </div>
      </div>

      <!-- DeepSeek 风格输入卡片：无边框文本域 + 附件 + 圆形发送钮，
           整体是一张浮动圆角卡片，而不是"输入框 + 散落按钮" -->
      <div class="ai-chat__composer" :class="{ 'ai-chat__composer--wide': !active || active.messages.length === 0 }">
        <div v-if="pendingImage" class="ai-chat__pending">
          <div class="ai-chat__pending-frame">
            <img :src="pendingImage" class="ai-chat__pending-img" alt="待发送附图">
            <button
              type="button"
              class="ai-chat__pending-del"
              aria-label="移除附图"
              @click="pendingImage = ''"
            >×</button>
          </div>
        </div>
        <NInput
          v-model:value="draft"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 6 }"
          :bordered="false"
          placeholder="给 AI 助手发送消息（Enter 发送，Shift+Enter 换行）"
          :disabled="streaming"
          @keydown.enter.exact.prevent="send"
        />
        <div class="ai-chat__composer-bar">
          <!-- 上传文件而不是手填 URL：图片经 base64 data URL 随消息直传，
               不依赖外部图床（上游模型也抓不到内网 URL） -->
          <label class="ai-chat__pick" :class="{ 'ai-chat__pick--disabled': streaming }" title="附图（png/jpeg/webp/gif）">
            <input
              type="file"
              accept="image/png,image/jpeg,image/webp,image/gif"
              :disabled="streaming"
              @change="onPickImage"
            >
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48" />
            </svg>
          </label>
          <span v-if="streaming" class="ai-chat__composer-hint">生成中…</span>
          <NButton v-if="streaming" size="small" quaternary type="warning" @click="stop">停止</NButton>
          <button
            v-else
            type="button"
            class="ai-chat__send"
            :disabled="(!draft.trim() && !pendingImage) || streaming"
            aria-label="发送"
            @click="send"
          >
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M12 19V5M5 12l7-7 7 7" />
            </svg>
          </button>
        </div>
      </div>
    </NCard>
  </div>
</template>

<style scoped>
.ai-chat {
  display: grid;
  grid-template-columns: 220px 1fr;
  gap: 12px;
  height: calc(100vh - 140px);
  min-height: 480px;
}

.ai-chat__sessions {
  display: flex;
  flex-direction: column;
  gap: 6px;
  overflow-y: auto;
}

.ai-chat__session {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  background: rgba(128, 128, 128, 0.08);
}

.ai-chat__session--active {
  background: var(--n-primary, #18a05822);
  color: var(--n-primary, #18a058);
  font-weight: 600;
}

.ai-chat__session-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-chat__session-del {
  opacity: 0.5;
  padding: 0 4px;
}

.ai-chat__session-del:hover {
  opacity: 1;
  color: #d03050;
}

.ai-chat__note {
  font-size: 11px;
  opacity: 0.55;
  margin: 8px 0 0;
  line-height: 1.6;
}

.ai-chat__main {
  display: flex;
  flex-direction: column;
}

.ai-chat__head {
  display: flex;
  gap: 10px;
  align-items: center;
  padding-bottom: 10px;
  border-bottom: 1px solid rgba(128, 128, 128, 0.15);
}

.ai-chat__title {
  font-weight: 600;
}

.ai-chat__messages {
  flex: 1;
  overflow-y: auto;
  padding: 12px 4px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.ai-chat__bubble {
  max-width: 78%;
  padding: 8px 12px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 懒渲染的旧消息：最多展示 12 行，避免超长回答占用渲染树 */
.ai-chat__text--plain {
  display: -webkit-box;
  -webkit-line-clamp: 12;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.ai-chat__bubble--user {
  align-self: flex-end;
  background: var(--n-primary, #18a058);
  color: #fff;
}

.ai-chat__bubble--ai {
  align-self: flex-start;
  background: rgba(128, 128, 128, 0.1);
}

.ai-chat__truncated {
  opacity: 0.6;
  font-size: 11px;
}

.ai-chat__image {
  display: block;
  max-width: 240px;
  max-height: 180px;
  border-radius: 6px;
  margin-top: 8px;
}

.ai-chat__pending {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
}

/* 缩略图 + 右上角圆形 × 删除角标（参考 DeepSeek 附件交互） */
.ai-chat__pending-frame {
  position: relative;
  display: inline-block;
}

.ai-chat__pending-img {
  display: block;
  width: 64px;
  height: 48px;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid rgba(128, 128, 128, 0.3);
}

.ai-chat__pending-del {
  position: absolute;
  top: -7px;
  right: -7px;
  width: 18px;
  height: 18px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.ai-chat__pending-del:hover {
  background: rgba(0, 0, 0, 0.75);
}

/* 空会话：欢迎标题 + 输入卡片在视觉上居中（DeepSeek 首页式） */
.ai-chat__welcome {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 240px;
}

.ai-chat__welcome-title {
  margin: 0 0 10px;
  font-size: 22px;
  font-weight: 700;
}

.ai-chat__composer--wide {
  max-width: 720px;
  margin-left: auto;
  margin-right: auto;
  width: 100%;
}

/* DeepSeek 风格输入卡片：圆角 + 阴影 + 内聚的附件/发送行 */
.ai-chat__composer {
  margin-top: 12px;
  padding: 10px 12px 8px;
  border: 1px solid rgba(128, 128, 128, 0.22);
  border-radius: 16px;
  background: var(--n-card-color, #fff);
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.05);
}

.ai-chat__composer :deep(.n-input) {
  --n-padding-left: 0;
  font-size: 14px;
}

.ai-chat__composer-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}

.ai-chat__composer-hint {
  margin-left: auto;
  font-size: 12px;
  opacity: 0.55;
}

.ai-chat__pick {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  color: var(--n-text-color-2, #666);
  cursor: pointer;
  user-select: none;
}

.ai-chat__pick:hover {
  background: rgba(128, 128, 128, 0.12);
  color: var(--n-primary, #18a058);
}

.ai-chat__pick--disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.ai-chat__pick input {
  display: none;
}

/* 圆形发送按钮（↑），与参考交互一致 */
.ai-chat__send {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  margin-left: auto;
  border: none;
  border-radius: 50%;
  background: var(--n-primary, #18a058);
  color: #fff;
  cursor: pointer;
}

.ai-chat__send:hover:not(:disabled) {
  filter: brightness(1.08);
}

.ai-chat__send:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

/* 生成中把回形针推到左侧、操作聚右 */
.ai-chat__composer-bar .ai-chat__pick {
  margin-right: auto;
}

.ai-chat__composer-bar .n-button:not(.ai-chat__pick) {
  margin-left: 8px;
}

.ai-chat__empty {
  color: var(--n-text-color-2, #999);
  font-size: 13px;
  text-align: center;
  margin-top: 40px;
}
</style>
