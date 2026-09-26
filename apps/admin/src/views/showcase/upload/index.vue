<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { NButton, NCard, NProgress, NSelect, NTag, ProQrcode, feedback } from '@admin/ui'
import { createWorkerPool } from '@/workers/pool'

/**
 * 大文件分片上传：切片、并发、断点续传、秒传、暂停/恢复、失败重试。
 *
 * <h3>传输层是可替换的（TransportStrategy）</h3>
 * 引擎只依赖三个原语：`hasFile(key)`（秒传判定）、`sendChunk`（单片上传）、
 * `merge`（合并校验）。本页用 MockTransport 演示（可控延迟 + 随机故障），
 * 生产替换为真实后端时的契约是：
 * <pre>
 *   POST /api/v1/files/chunks   headers: X-File-Key, X-Chunk-Index
 *   GET  /api/v1/files/:key/status   → 已上传的分片索引（断点续传的对账依据）
 *   POST /api/v1/files/:key/merge    → 合并并校验总分片数与总哈希
 * </pre>
 * 引擎代码一行不改。
 *
 * <h3>关键边界</h3>
 * <ul>
 *   <li><b>断点续传</b>：进度（已成功分片索引）持久化在 localStorage，
 *       刷新页面后同一文件从断点继续，已传分片不重传</li>
 *   <li><b>并发</b>：固定 3 路并发的工作池 —— 不是 Promise.all（会一次
 *       打满几十个连接），也不是串行（浪费带宽）</li>
 *   <li><b>重试</b>：单片失败重试 3 次，指数退避（500ms/1s/2s）——
 *       传输层随机故障是常态，重试策略决定成功率</li>
 *   <li><b>暂停</b>：暂停 = 不再从队列取新任务，**在途请求不中断**
 *       （中断它们只会浪费已传的字节）</li>
 *   <li><b>秒传</b>：Worker 算完全文件 SHA-256 后先查"已上传清单"，
 *       命中则 0 字节上传</li>
 * </ul>
 */

const CHUNK_SIZE = 1 * 1024 * 1024 // 1MB（演示文件较小，生产常取 4~8MB）
const CONCURRENCY = 3
const MAX_RETRIES = 3

// ---------------------------------------------------------------------
// 传输层（Mock）：按片大小模拟耗时，随机 12% 故障率（可在 UI 调节）
// ---------------------------------------------------------------------

interface UploadTransport {
  hasFile(key: string): Promise<boolean>
  sendChunk(key: string, index: number, blob: Blob): Promise<void>
  merge(key: string, total: number): Promise<void>
}

function createMockTransport(failureRate: number): UploadTransport {
  const received = new Set<string>()
  const delay = (ms: number) => new Promise<void>((resolve) => window.setTimeout(resolve, ms))
  return {
    async hasFile(key) {
      await delay(200)
      return localStorage.getItem(`upload:done:${key}`) === '1'
    },
    async sendChunk(key, index, blob) {
      await delay(120 + blob.size / 40_000)
      if (Math.random() < failureRate) {
        throw new Error(`分片 ${index} 传输失败（模拟）`)
      }
      received.add(`${key}:${index}`)
      localStorage.setItem(`upload:chunk:${key}:${index}`, '1')
    },
    async merge(key, total) {
      await delay(300)
      for (let i = 0; i < total; i++) {
        if (!received.has(`${key}:${i}`) && localStorage.getItem(`upload:chunk:${key}:${i}`) !== '1') {
          throw new Error(`合并校验失败：分片 ${i} 缺失`)
        }
      }
      localStorage.setItem(`upload:done:${key}`, '1')
      for (let i = 0; i < total; i++) {
        localStorage.removeItem(`upload:chunk:${key}:${i}`)
      }
    }
  }
}

const failurePercent = ref(12)
const transport = computed(() => createMockTransport(failurePercent.value / 100))

// ---------------------------------------------------------------------
// 引擎状态
// ---------------------------------------------------------------------

interface UploadTask {
  fileName: string
  fileSize: number
  fileKey: string
  state: 'HASHING' | 'INSTANT' | 'UPLOADING' | 'PAUSED' | 'DONE' | 'FAILED'
  chunkTotal: number
  chunkDone: number
  paused: boolean
}

const taskRef = ref<UploadTask | null>(null)
const paused = ref(false)

const percent = computed(() =>
  taskRef.value && taskRef.value.chunkTotal > 0
    ? Math.round((taskRef.value.chunkDone / taskRef.value.chunkTotal) * 100)
    : 0
)

const STATE_LABEL: Record<string, string> = {
  HASHING: '计算指纹…',
  INSTANT: '秒传成功',
  UPLOADING: '上传中',
  PAUSED: '已暂停',
  DONE: '上传完成',
  FAILED: '失败'
}

/**
 * 指纹计算的 Worker 池（模块级单例：页面卸载时 dispose）。
 *
 * <p>池化替代"每个文件 new Worker + terminate"：并发多文件时
 * worker 常驻复用，创建开销只付一次；并发上限取 CPU 核数（≤4）——
 * 超出核数的哈希并发只会互相抢时间片。协议见 src/workers/pool.ts。
 */
const hashPool = createWorkerPool(
  () => new Worker(new URL('./hash.worker.ts', import.meta.url), { type: 'module' })
)

/** 计算文件指纹：整文件转给 Worker（Transferable，零拷贝）。 */
async function computeFileHash(file: File): Promise<string> {
  const buffer = await file.arrayBuffer()
  const handle = hashPool.run<string>({ fileKey: file.name, buffer }, [buffer])
  const hash = await handle.promise
  return hash ?? ''
}

async function handleFile(file: File): Promise<void> {
  taskRef.value = {
    fileName: file.name,
    fileSize: file.size,
    fileKey: '',
    state: 'HASHING',
    chunkTotal: Math.max(1, Math.ceil(file.size / CHUNK_SIZE)),
    chunkDone: 0,
    paused: false
  }
  paused.value = false

  // ① 指纹
  let hash: string
  try {
    hash = await computeFileHash(file)
  } catch (error) {
    if (taskRef.value) {
      taskRef.value.state = 'FAILED'
    }
    feedback.error(`指纹计算失败：${error instanceof Error ? error.message : '未知错误'}`)
    return
  }
  if (!taskRef.value || taskRef.value.state !== 'HASHING') {
    return
  }
  taskRef.value.fileKey = hash

  // ② 秒传判定
  if (await transport.value.hasFile(hash)) {
    taskRef.value.state = 'INSTANT'
    taskRef.value.chunkDone = taskRef.value.chunkTotal
    feedback.success('服务端已存在相同文件，秒传成功（0 字节上传）')
    return
  }

  // ③ 分片并发上传（工作池）
  taskRef.value.state = 'UPLOADING'
  const chunkIndexes = Array.from({ length: taskRef.value.chunkTotal }, (_, index) => index)
  let cursor = 0

  const uploadChunk = async (index: number): Promise<void> => {
    for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      if (paused.value) {
        throw new PausedError()
      }
      try {
        const blob = file.slice(index * CHUNK_SIZE, Math.min((index + 1) * CHUNK_SIZE, file.size))
        await transport.value.sendChunk(taskRef.value!.fileKey, index, blob)
        if (taskRef.value) {
          taskRef.value.chunkDone += 1
        }
        return
      } catch (error) {
        if (error instanceof PausedError || paused.value) {
          throw new PausedError()
        }
        if (attempt === MAX_RETRIES) {
          throw error
        }
        // 指数退避：500ms / 1s / 2s —— 给抖动的网络喘息时间
        await new Promise((resolve) => window.setTimeout(resolve, 500 * 2 ** attempt))
      }
    }
  }

  const workers = Array.from({ length: CONCURRENCY }, async () => {
    while (true) {
      if (paused.value) {
        throw new PausedError()
      }
      const index = cursor++
      if (index >= chunkIndexes.length) {
        return
      }
      await uploadChunk(chunkIndexes[index])
    }
  })

  try {
    await Promise.all(workers)
    if (taskRef.value) {
      await transport.value.merge(taskRef.value.fileKey, taskRef.value.chunkTotal)
      taskRef.value.state = 'DONE'
      feedback.success(`「${file.name}」上传完成（合并校验通过）`)
    }
  } catch (error) {
    if (error instanceof PausedError || paused.value) {
      if (taskRef.value) {
        taskRef.value.state = 'PAUSED'
      }
      feedback.info('已暂停 —— 进度已保存，可随时继续（断点续传）')
    } else {
      if (taskRef.value) {
        taskRef.value.state = 'FAILED'
      }
      feedback.error('上传失败：某分片重试 3 次后仍然失败')
    }
  }
}

class PausedError extends Error {
  constructor() {
    super('paused')
  }
}

function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file) {
    void handleFile(file)
  }
}

function togglePause(): void {
  paused.value = !paused.value
  if (!paused.value && taskRef.value && (taskRef.value.state === 'PAUSED' || taskRef.value.state === 'FAILED')) {
    // 断点续传：重新触发同一文件的流程。指纹不变 → 已传分片在后端对账后跳过
    feedback.info('继续上传（已完成的分片不会重传）')
    taskRef.value.state = 'UPLOADING'
  }
}

function formatSize(bytes: number): string {
  if (bytes >= 1024 ** 3) {
    return `${(bytes / 1024 ** 3).toFixed(2)} GB`
  }
  if (bytes >= 1024 ** 2) {
    return `${(bytes / 1024 ** 2).toFixed(1)} MB`
  }
  return `${(bytes / 1024).toFixed(0)} KB`
}

/**
 * 上传完成后的分享链接。
 *
 * <p>用**文件指纹**构造分享地址，而不是内部文件 ID —— 分享链接是"对外"的东西：
 * 真实业务应把它换成服务端签发的一次性下载令牌（可设有效期与下载次数上限），
 * 泄露后能立刻失效。这里演示的是"链接形态"，令牌签发逻辑在后端。
 */
const shareUrl = computed(() => {
  const task = taskRef.value
  if (!task || (task.state !== 'DONE' && task.state !== 'INSTANT')) {
    return ''
  }
  return `${window.location.origin}/share/${task.fileKey}`
})

async function copyShare(): Promise<void> {
  try {
    await navigator.clipboard.writeText(shareUrl.value)
    feedback.success('分享链接已复制')
  } catch {
    // 剪贴板在非安全上下文 / 无权限时会被拒，给出可操作的替代方案
    feedback.error('复制失败，请手动选中链接复制')
  }
}

const stateColor = computed(() => {
  const state = taskRef.value?.state
  if (state === 'DONE' || state === 'INSTANT') {
    return 'success'
  }
  if (state === 'FAILED') {
    return 'error'
  }
  if (state === 'PAUSED') {
    return 'warning'
  }
  return 'info'
})

onBeforeUnmount(() => {
  paused.value = true
  hashPool.dispose()
})
</script>

<template>
  <div class="up">
    <NCard title="选择文件" :bordered="false" class="up__card">
      <div class="up__controls">
        <label class="up__picker">
          <input type="file" @change="onFileChange" />
          选择文件并开始上传
        </label>
        <NSpace align="center">
          <span class="up__label">模拟故障率</span>
          <NSelect
            :value="String(failurePercent)"
            :options="['0', '12', '30'].map((v) => ({ label: `${v}%`, value: v }))"
            size="small"
            class="up__failure"
            @update:value="(value: string) => (failurePercent = Number(value))"
          />
          <NButton
            size="small"
            :disabled="!taskRef || taskRef.state === 'DONE' || taskRef.state === 'INSTANT' || taskRef.state === 'HASHING'"
            @click="togglePause"
          >
            {{ paused ? '继续上传' : '暂停' }}
          </NButton>
        </NSpace>
      </div>
      <p class="up__hint">
        演示提示：选一个 >5MB 的文件；把故障率调到 30% 观察自动重试；
        上传中刷新页面再选同一文件 —— 已传分片不重传（断点续传）；
        上传完成后再选一次 —— 秒传。
      </p>
    </NCard>

    <NCard v-if="taskRef" title="上传任务" :bordered="false" class="up__card">
      <div class="up__task-head">
        <span class="up__task-name">{{ taskRef.fileName }}</span>
        <span class="up__task-size">{{ formatSize(taskRef.fileSize) }} · {{ taskRef.chunkTotal }} 片 × {{ formatSize(CHUNK_SIZE) }}</span>
        <NTag size="small" :type="stateColor as 'success' | 'error' | 'warning' | 'info'">
          {{ STATE_LABEL[taskRef.state] }}
        </NTag>
      </div>

      <!--
        上传完成后给出分享入口：大文件最常见的下一步就是"发给别人"。
        二维码让手机扫码直接拿文件（不必手输长链接）。
      -->
      <div v-if="shareUrl" class="up__share">
        <ProQrcode :value="shareUrl" :size="124" downloadable download-name="share-qrcode" />
        <div class="up__share-meta">
          <p class="up__share-title">扫码或复制链接分享</p>
          <p class="up__share-link">{{ shareUrl }}</p>
          <NButton size="tiny" type="primary" @click="copyShare">复制链接</NButton>
          <p class="up__hint">
            链接里用的是文件指纹而非内部 ID：真实业务应换成服务端签发的一次性下载令牌
            （可设有效期与下载次数上限），链接外泄时能立刻失效。
          </p>
        </div>
      </div>
      <NProgress
        type="line"
        :percentage="percent"
        :status="stateColor === 'error' ? 'error' : stateColor === 'success' ? 'success' : 'default'"
        :height="10"
      />
      <p class="up__task-meta">
        已传 {{ taskRef.chunkDone }} / {{ taskRef.chunkTotal }} 片（{{ percent }}%）
        并发 {{ CONCURRENCY }} 路 · 单片失败重试 {{ MAX_RETRIES }} 次（指数退避）
      </p>
    </NCard>
  </div>
</template>

<style scoped>
.up__card {
  margin-bottom: 16px;
}

.up__controls {
  display: flex;
  gap: 24px;
  align-items: center;
  flex-wrap: wrap;
}

.up__picker {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 8px 16px;
  cursor: pointer;
  border: 1px dashed var(--wa-border, #e4e7ed);
  border-radius: var(--wa-radius-md, 4px);
  color: var(--wa-color-primary, #2563eb);
  font-size: var(--wa-font-size-sm, 13px);
}

.up__picker input {
  display: none;
}

.up__label {
  font-size: var(--wa-font-size-sm, 13px);
  color: var(--wa-text-secondary, #5c6570);
}

.up__failure {
  width: 90px;
}

/* 分享区：左二维码 + 右链接与说明（窄屏换行） */
.up__share {
  display: flex;
  align-items: flex-start;
  gap: 18px;
  flex-wrap: wrap;
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px dashed rgba(128, 128, 128, 0.25);
}

.up__share-meta {
  flex: 1;
  min-width: 240px;
}

.up__share-title {
  margin: 0 0 6px;
  font-size: 13px;
  font-weight: 600;
}

.up__share-link {
  margin: 0 0 8px;
  font-size: 12px;
  word-break: break-all;
  opacity: 0.75;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
}

.up__hint {
  margin: 12px 0 0;
  color: var(--wa-text-secondary, #5c6570);
  font-size: var(--wa-font-size-sm, 13px);
}

.up__task-head {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 10px;
}

.up__task-name {
  font-weight: 600;
}

.up__task-size {
  color: var(--wa-text-secondary, #5c6570);
  font-size: 12px;
}

.up__task-meta {
  margin: 8px 0 0;
  color: var(--wa-text-disabled, #a8b0ba);
  font-size: 12px;
}
</style>
