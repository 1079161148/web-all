<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { NButton, NCard, NInput, NTag, feedback } from '@admin/ui'
import { streamAgent, type AgentStep } from '@/api/agent'
import AiMarkdown from '@/components/AiMarkdown.vue'

/**
 * AI 指挥中心：自然语言指挥系统真实动作。
 *
 * <h3>页面结构 = 一次指挥的三段可见过程</h3>
 * <ol>
 *   <li><b>指令台</b>：自然语言 + 快捷指令；</li>
 *   <li><b>执行时间线</b>：每个 step 事件一张卡片 —— 模型调了什么工具、
 *       参数是什么、结果如何。写操作（抢单/办结）用醒目色标出 ——
 *       Agent 的可信度来自过程透明；</li>
 *   <li><b>最终战报</b>：模型综合工具结果给出的 Markdown 回答。</li>
 * </ol>
 *
 * <p>工具执行会<b>真实改变系统状态</b>（抢单/办结）—— 这是演示的意义所在：
 * 打开工单调度台对照，AI 的每一步动作都能在真实数据里得到印证。
 */

interface TimelineEntry {
  kind: 'step' | 'answer'
  /** step 专用字段 */
  tool?: string
  args?: string
  result?: string
  /** answer 专用字段 */
  text?: string
}

const instruction = ref('')
const running = ref(false)
const mode = ref<'real' | 'demo' | null>(null)
const timeline = ref<TimelineEntry[]>([])
let abortFn: (() => void) | null = null

const QUICK_COMMANDS = [
  '清点当前战况：各状态工单有多少？',
  '把所有待处理的 P1 工单都接手过来',
  '最近有人做过什么操作？查一下审计'
]

const timelineEl = ref<HTMLDivElement | null>(null)

function scrollBottom(): void {
  void nextTick(() => {
    const el = timelineEl.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

function isWriteTool(tool: string): boolean {
  return tool.startsWith('claim_') || tool.startsWith('complete_') || tool.startsWith('release_')
}

function toolLabel(tool: string): string {
  const labels: Record<string, string> = {
    list_tickets: '查询工单',
    ticket_stats: '工单统计',
    claim_ticket: '接手工单',
    complete_ticket: '办结工单',
    release_ticket: '放回工单',
    recent_audit: '查操作审计'
  }
  return labels[tool] ?? tool
}

function runCommand(command: string): void {
  if (running.value || !command.trim()) {
    return
  }
  timeline.value = []
  running.value = true
  mode.value = null
  let answer = ''

  abortFn = streamAgent(command, {
    onMeta: (m) => {
      mode.value = m
    },
    onStep: (step: AgentStep) => {
      timeline.value = [...timeline.value, { kind: 'step', tool: step.tool, args: step.args, result: step.result }]
      scrollBottom()
    },
    onDelta: (piece) => {
      answer += piece
      const last = timeline.value[timeline.value.length - 1]
      if (last && last.kind === 'answer') {
        last.text = answer
        timeline.value = [...timeline.value]
      } else {
        timeline.value = [...timeline.value, { kind: 'answer', text: answer }]
      }
      scrollBottom()
    },
    onError: (message) => {
      feedback.error(message)
    },
    onDone: () => {
      running.value = false
      abortFn = null
      scrollBottom()
    }
  })
}

function stop(): void {
  abortFn?.()
  abortFn = null
  running.value = false
}

onMounted(() => {
  // 空态引导
  timeline.value = []
})
</script>

<template>
  <div class="command">
    <NCard title="AI 指挥中心" :bordered="false" class="command__card">
      <p class="command__desc">
        用自然语言指挥系统真实动作：查询、抢单、办结、放回、查审计。
        每一步工具调用都记录在时间线里 —— 写操作（抢单/办结/放回）会真实改变系统状态，
        可打开「工单调度台」对照验证。
      </p>
      <div class="command__input">
        <NInput
          v-model:value="instruction"
          placeholder="下达指令，例如：清点当前战况，然后把所有 P1 待处理工单都接手过来"
          :disabled="running"
          @keydown.enter.exact.prevent="runCommand(instruction)"
        />
        <NButton v-if="running" size="small" type="warning" @click="stop">停止</NButton>
        <NButton
          v-else
          size="small"
          type="primary"
          :disabled="!instruction.trim()"
          @click="runCommand(instruction)"
        >
          执行
        </NButton>
      </div>
      <div class="command__quick">
        <NButton
          v-for="quick in QUICK_COMMANDS"
          :key="quick"
          size="tiny"
          :disabled="running"
          @click="runCommand(quick)"
        >
          {{ quick }}
        </NButton>
        <NTag size="small" :type="mode === 'real' ? 'success' : mode === 'demo' ? 'warning' : 'default'">
          {{ mode === 'real' ? '真实模型' : mode === 'demo' ? '演示模式' : '待命' }}
        </NTag>
      </div>
    </NCard>

    <NCard title="执行过程" :bordered="false" class="command__card">
      <div ref="timelineEl" class="command__timeline">
        <div v-if="timeline.length === 0" class="command__empty">
          下达第一条指令开始指挥。模型每调用一次工具，这里就会出现一张执行卡片。
        </div>
        <template v-for="(entry, index) in timeline" :key="index">
          <div
            v-if="entry.kind === 'step'"
            class="command__step"
            :class="{ 'command__step--write': entry.tool && isWriteTool(entry.tool) }"
          >
            <div class="command__step-head">
              <NTag size="small" :type="isWriteTool(entry.tool ?? '') ? 'error' : 'info'">
                {{ toolLabel(entry.tool ?? '') }}
              </NTag>
              <code class="command__step-args">{{ entry.args }}</code>
            </div>
            <div class="command__step-result">{{ entry.result }}</div>
          </div>
          <div v-else class="command__answer">
            <AiMarkdown :text="entry.text ?? ''" :streaming="running && index === timeline.length - 1" />
          </div>
        </template>
      </div>
    </NCard>
  </div>
</template>

<style scoped>
.command__card {
  margin-bottom: 16px;
}

.command__desc {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--n-text-color-2, #666);
}

.command__input {
  display: flex;
  gap: 8px;
}

.command__quick {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 10px;
  align-items: center;
}

.command__timeline {
  min-height: 320px;
  max-height: 640px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.command__empty {
  color: var(--n-text-color-2, #999);
  font-size: 13px;
  text-align: center;
  padding: 48px 0;
}

.command__step {
  border: 1px solid rgba(128, 128, 128, 0.2);
  border-left: 3px solid var(--n-info, #2080f0);
  border-radius: 6px;
  padding: 8px 12px;
}

.command__step--write {
  border-left-color: #d03050;
}

.command__step-head {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 6px;
}

.command__step-args {
  font-size: 12px;
  opacity: 0.7;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.command__step-result {
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-word;
}

.command__answer {
  border: 1px solid rgba(24, 160, 88, 0.35);
  border-radius: 6px;
  padding: 10px 14px;
  background: rgba(24, 160, 88, 0.05);
}
</style>