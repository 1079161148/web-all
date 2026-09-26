<template>
  <div class="media-lab">
    <NAlert class="media-lab__notice" type="info" :bordered="false">
      <template #header>这个页面是什么</template>
      上半部分是<b>能用的处理台</b>（图片压缩、视频首帧截取、上传），下半部分是同一件事的
      <b>技术边界说明</b>。之所以放在一起：图片处理的坑几乎都藏在"像素内存 / 编码有损性 /
      浏览器能力"这三类边界里，光看代码看不出来，得能亲手试。
      上传环节复用二次封装的 <code>ProUpload</code>；图片处理全程在 Worker 里跑
      （复用 <code>workers/pool</code>）。
    </NAlert>

    <!-- ============================================================
         1. 图片处理台
         ============================================================ -->
    <NCard class="media-lab__card" size="small" :bordered="false" title="图片处理台：探测尺寸 → 解码降采样 → 重编码">
      <div class="media-lab__toolbar">
        <label class="media-lab__pick" :class="{ 'media-lab__pick--busy': processing }">
          <input
            type="file"
            accept="image/png,image/jpeg,image/webp,image/gif"
            multiple
            :disabled="processing"
            @change="onPickImages"
          >
          选择图片（可多选）
        </label>

        <div class="media-lab__field">
          <span class="media-lab__field-label">长边上限</span>
          <NSelect v-model:value="imageOptions.maxEdge" size="small" class="media-lab__select" :options="edgeOptions" />
        </div>

        <div class="media-lab__field">
          <span class="media-lab__field-label">质量</span>
          <NSelect v-model:value="imageOptions.quality" size="small" class="media-lab__select" :options="qualityOptions" />
        </div>

        <div class="media-lab__field">
          <span class="media-lab__field-label">格式</span>
          <NSelect v-model:value="imageOptions.format" size="small" class="media-lab__select" :options="formatOptions" />
        </div>

        <NButton size="small" type="primary" :disabled="processing" @click="runImageBatch">
          按当前参数重跑
        </NButton>
      </div>

      <p class="media-lab__tip">
        处理全程在 Worker 里完成（解码 + 缩放 + 编码），主线程只更新 UI ——
        这正是"选完大图页面不卡"的原因。质量参数只对 JPEG/WebP 生效，选 PNG 时会被忽略。
      </p>

      <div v-if="imageRows.length" class="media-lab__rows">
        <div v-for="row in imageRows" :key="row.id" class="media-lab__row">
          <img class="media-lab__thumb" :src="row.beforeUrl" :alt="row.name">
          <div class="media-lab__row-main">
            <div class="media-lab__row-title">{{ row.name }}</div>
            <div class="media-lab__row-meta">
              <span>{{ row.sourceWidth }}×{{ row.sourceHeight }}</span>
              <span class="media-lab__arrow">→</span>
              <span>{{ row.width }}×{{ row.height }}</span>
              <NTag size="tiny" :bordered="false" class="media-lab__tag">{{ row.type }}</NTag>
              <NTag v-if="row.downscaled" size="tiny" type="warning" :bordered="false" class="media-lab__tag">
                已降采样
              </NTag>
            </div>
            <div class="media-lab__row-meta">
              <span>{{ formatBytes(row.beforeBytes) }} → {{ formatBytes(row.afterBytes) }}</span>
              <span
                class="media-lab__ratio"
                :class="row.ratio <= 0 ? 'media-lab__ratio--warn' : 'media-lab__ratio--ok'"
              >
                {{ row.ratio > 0 ? `减少 ${row.ratio}%` : `反而增大 ${Math.abs(row.ratio)}%` }}
              </span>
              <span class="media-lab__muted">耗时 {{ row.costMs }}ms</span>
            </div>
            <div v-if="row.ratio <= 0" class="media-lab__warn">
              处理结果比原图更大 —— 真实业务这里应自动回退为原文件上传（本页保留结果是为了让你看到这个边界）。
            </div>
          </div>
          <div class="media-lab__row-side">
            <img class="media-lab__thumb media-lab__thumb--after" :src="row.afterUrl" :alt="row.name">
            <NButton size="tiny" quaternary @click="downloadRow(row)">下载</NButton>
          </div>
        </div>
      </div>
    </NCard>

    <!-- ============================================================
         2. 视频首帧
         ============================================================ -->
    <NCard class="media-lab__card" size="small" :bordered="false" title="视频封面：首帧截取（只能在主线程做）">
      <div class="media-lab__toolbar">
        <label class="media-lab__pick" :class="{ 'media-lab__pick--busy': capturing }">
          <input type="file" accept="video/*" :disabled="capturing" @change="onPickVideo">
          选择视频
        </label>
        <div class="media-lab__field">
          <span class="media-lab__field-label">截帧位置（秒）</span>
          <NSelect v-model:value="coverAtSeconds" size="small" class="media-lab__select" :options="coverOptions" />
        </div>
        <NButton size="small" :disabled="!videoFile || capturing" @click="captureCover">重新截帧</NButton>
      </div>

      <p class="media-lab__tip">
        截帧依赖 <code>&lt;video&gt;</code> 元素，因此<b>不能放进 Worker</b>（Worker 里没有 DOM）——
        这就是"主线程 / Worker 分工"的实际边界。默认偏移 0.3 秒而不是 0 秒：
        第 0 帧经常不是关键帧，直接取会得到黑图。
      </p>

      <div v-if="videoInfo" class="media-lab__video">
        <div class="media-lab__video-meta">
          <div><span class="media-lab__muted">文件</span> {{ videoInfo.name }}</div>
          <div><span class="media-lab__muted">大小</span> {{ formatBytes(videoInfo.bytes) }}</div>
          <div><span class="media-lab__muted">时长</span> {{ videoInfo.duration }}s</div>
          <div><span class="media-lab__muted">分辨率</span> {{ videoInfo.width }}×{{ videoInfo.height }}</div>
        </div>
        <div v-if="coverUrl" class="media-lab__cover">
          <img :src="coverUrl" class="media-lab__cover-img" alt="视频封面">
          <NButton size="tiny" quaternary @click="downloadCover">下载封面</NButton>
        </div>
      </div>
    </NCard>

    <!-- ============================================================
         3. 分享与标签：二维码 / 条形码的真实用法
         ============================================================ -->
    <NCard
      class="media-lab__card"
      size="small"
      :bordered="false"
      title="分享与标签：二维码（邀请）+ 条形码（资产）"
    >
      <div class="media-lab__share">
        <div class="media-lab__share-form">
          <NInput v-model:value="inviteUrl" size="small" placeholder="邀请链接（含一次性 token）" />
          <div class="media-lab__share-actions">
            <NButton size="small" @click="regenerateInvite">重新生成</NButton>
            <NButton size="small" type="primary" @click="copyInvite">复制链接</NButton>
          </div>
          <p class="media-lab__tip">
            链接里带的是**一次性 token** 而不是用户 ID：二维码会被截图、被转发，
            把"能加入"的凭据做成可失效、可审计的短期令牌，是这类场景的基本要求。
          </p>
        </div>

        <div class="media-lab__share-code">
          <ProQrcode :value="inviteUrl" :size="150" downloadable download-name="invite" />
          <span class="media-lab__muted">扫码加入（导出 PNG）</span>
        </div>

        <div class="media-lab__share-code">
          <ProBarcode
            :value="assetCode"
            format="CODE128"
            :height="56"
            downloadable
            download-name="asset"
          />
          <span class="media-lab__muted">资产标签（导出 SVG，可直接打印贴标）</span>
        </div>
      </div>
    </NCard>

    <!-- ============================================================
         4. 上传（复用 ProUpload）
         ============================================================ -->
    <NCard class="media-lab__card" size="small" :bordered="false" title="上传：复用 ProUpload（走契约客户端 + 鉴权头）">
      <ProUpload
        :file-list="uploadList"
        image
        :max-size-mb="20"
        :accept="['png', 'jpg', 'jpeg', 'webp']"
        :request="uploadProcessed"
        tip="上传处理后的图片"
        @update:file-list="(files: UploadFileItem[]) => (uploadList = files)"
        @success="onUploaded"
        @error="onUploadError"
      />
      <p class="media-lab__tip">
        ProUpload 的 <code>request</code> 逃生口是关键：Naive 的 finish 事件拿不到服务端响应体，
        而"上传后要拿到附件 ID"是常见需求 —— 自定义 request 后，业务侧在自己的闭包里
        顺手把结果写进状态，不依赖响应格式。
      </p>
    </NCard>

    <!-- ============================================================
         4. 技术边界说明（复用同一套专题内容模型）
         ============================================================ -->
    <NCard class="media-lab__card" size="small" :bordered="false" title="编码格式选型：按用途选，不按「最新最好」选">
      <div class="media-lab__table">
        <div class="media-lab__tr media-lab__tr--head">
          <span v-for="head in mediaTopic.table?.head ?? []" :key="head">{{ head }}</span>
        </div>
        <div v-for="row in mediaTopic.table?.rows ?? []" :key="row[0]" class="media-lab__tr">
          <span
            v-for="(cell, index) in row"
            :key="index"
            :class="{ 'media-lab__td-strong': index === 0 }"
          >
            {{ cell }}
          </span>
        </div>
      </div>
    </NCard>

    <div class="media-lab__points">
      <NCard
        v-for="point in mediaTopic.points"
        :key="point.title"
        class="media-lab__point"
        size="small"
        :bordered="false"
      >
        <h3 class="media-lab__point-title">{{ point.title }}</h3>
        <div class="media-lab__point-row">
          <span class="media-lab__label media-lab__label--pain">难点</span>
          <span>{{ point.pain }}</span>
        </div>
        <div class="media-lab__point-row">
          <span class="media-lab__label media-lab__label--hi">最佳实践</span>
          <span class="media-lab__point-hi">{{ point.highlight }}</span>
        </div>
        <ul class="media-lab__list">
          <li v-for="line in point.detail" :key="line">{{ line }}</li>
        </ul>
        <div class="media-lab__tags">
          <NTag v-for="tag in point.tags" :key="tag" size="tiny" :bordered="false" class="media-lab__tag">
            {{ tag }}
          </NTag>
        </div>
      </NCard>
    </div>

    <div v-if="mediaTopic.lists" class="media-lab__cols">
      <NCard
        v-for="list in mediaTopic.lists"
        :key="list.title"
        class="media-lab__card"
        size="small"
        :bordered="false"
        :title="list.title"
      >
        <div v-for="item in list.items" :key="item.name" class="media-lab__lever">
          <span class="media-lab__lever-name">{{ item.name }}</span>
          <span class="media-lab__lever-desc">{{ item.desc }}</span>
        </div>
      </NCard>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NInput,
  NSelect,
  NTag,
  ProBarcode,
  ProQrcode,
  ProUpload,
  feedback
} from '@admin/ui'
import type { UploadFileItem } from '@admin/ui'
import { uploadSurveyFileAction } from '@/api/survey'
import { createWorkerPool } from '@/workers/pool'
import { mediaTopic } from '../live-tech/content-media'

/**
 * 图片 / 视频处理台。
 *
 * <h3>这个页面为什么要"能试"</h3>
 * 图片处理的坑集中在三类边界：像素内存（大图解码）、编码有损性（格式与参数语义）、
 * 浏览器能力（canvas 上限、跨域、格式支持）。这三类都<b>无法从代码看出对错</b>，
 * 必须能亲手传一张真实大图、看着体积与尺寸变化才建立得起直觉。
 *
 * <h3>与内容页的关系</h3>
 * 技术说明部分复用 live-tech 的内容模型（Topic / TopicPoint），
 * 因此这里的文字与「技术专题 → 图片/视频处理」是同一份数据源，
 * 不会出现"页面讲一套、文档讲一套"。
 *
 * <h3>三处刻意的设计</h3>
 * <ol>
 *   <li><b>处理全程在 Worker</b>：解码 + 缩放 + 编码都在 worker 里（见 image.worker.ts），
 *       主线程只更新 UI —— 大图不卡界面靠的是这个，而不是"加个 loading"</li>
 *   <li><b>视频截帧留在主线程</b>：它依赖 &lt;video&gt; 元素，Worker 里没有 DOM。
 *       这是"分工边界"最直观的例子，所以特意与图片处理放在同一页对照</li>
 *   <li><b>压缩结果变大时不静默纠正</b>：小图转 WebP 有时会更大。真实业务应自动
 *       回退原文件，这里把结果如实展示并给出提示，因为这就是需要被看见的边界</li>
 * </ol>
 */

interface ImageRow {
  id: string
  name: string
  beforeUrl: string
  afterUrl: string
  beforeBytes: number
  afterBytes: number
  sourceWidth: number
  sourceHeight: number
  width: number
  height: number
  type: string
  downscaled: boolean
  costMs: number
  ratio: number
  blob: Blob
}

interface WorkerResult {
  buffer: ArrayBuffer
  type: string
  width: number
  height: number
  sourceWidth: number
  sourceHeight: number
  downscaled: boolean
}

const imageOptions = ref({ maxEdge: 1600, quality: 0.82, format: 'image/webp' as 'image/webp' | 'image/jpeg' })
const imageRows = ref<ImageRow[]>([])
const processing = ref(false)

const edgeOptions = [
  { label: '不缩放（仅重编码）', value: 100000 },
  { label: '长边 640', value: 640 },
  { label: '长边 1280', value: 1280 },
  { label: '长边 1600', value: 1600 },
  { label: '长边 2560', value: 2560 }
]
const qualityOptions = [
  { label: '0.60（激进）', value: 0.6 },
  { label: '0.75（推荐下限）', value: 0.75 },
  { label: '0.82（推荐）', value: 0.82 },
  { label: '0.92（高质量）', value: 0.92 }
]
const formatOptions = [
  { label: 'WebP（体积最优）', value: 'image/webp' },
  { label: 'JPEG（兼容兜底）', value: 'image/jpeg' }
]

/** 图片处理 Worker 池：复用通用池（单请求单响应契约），并发上限由池统一把关。 */
const imagePool = createWorkerPool(
  () => new Worker(new URL('./image.worker.ts', import.meta.url), { type: 'module' })
)

// ---------------------------------------------------------------------
// 图片处理
// ---------------------------------------------------------------------

async function onPickImages(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length) {
    return
  }
  await processFiles(files)
}

/** 按当前参数重跑已选文件（参数是"可反复试"的，所以保留原文件引用）。 */
const lastPicked = ref<File[]>([])

async function runImageBatch(): Promise<void> {
  if (!lastPicked.value.length) {
    feedback.warning('先选择图片')
    return
  }
  await processFiles(lastPicked.value)
}

async function processFiles(files: File[]): Promise<void> {
  lastPicked.value = files
  processing.value = true
  releaseRows()
  const rows: ImageRow[] = []
  try {
    for (const file of files) {
      const row = await processOne(file)
      if (row) {
        rows.push(row)
        imageRows.value = [...rows]
      }
    }
  } finally {
    processing.value = false
  }
}

async function processOne(file: File): Promise<ImageRow | null> {
  if (file.type === 'image/svg+xml') {
    feedback.error('SVG 可内嵌脚本，前端不按图片处理（服务端应净化或拒绝）')
    return null
  }
  const started = performance.now()
  try {
    const buffer = await file.arrayBuffer()
    const handle = imagePool.run<WorkerResult>(
      {
        buffer,
        mime: file.type || 'image/jpeg',
        maxEdge: imageOptions.value.maxEdge,
        quality: imageOptions.value.quality,
        format: imageOptions.value.format
      },
      [buffer]
    )
    const result = await handle.promise
    const costMs = Math.round(performance.now() - started)
    const afterBlob = new Blob([result.buffer], { type: result.type })
    const ratio = Math.round(((file.size - afterBlob.size) / file.size) * 100)
    return {
      id: `${file.name}-${file.size}-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
      name: file.name,
      beforeUrl: URL.createObjectURL(file),
      afterUrl: URL.createObjectURL(afterBlob),
      beforeBytes: file.size,
      afterBytes: afterBlob.size,
      sourceWidth: result.sourceWidth,
      sourceHeight: result.sourceHeight,
      width: result.width,
      height: result.height,
      type: result.type,
      downscaled: result.downscaled,
      costMs,
      ratio,
      blob: afterBlob
    }
  } catch (error) {
    feedback.error(`处理失败：${error instanceof Error ? error.message : String(error)}`)
    return null
  }
}

function releaseRows(): void {
  for (const row of imageRows.value) {
    URL.revokeObjectURL(row.beforeUrl)
    URL.revokeObjectURL(row.afterUrl)
  }
  imageRows.value = []
}

function downloadRow(row: ImageRow): void {
  const suffix = row.type === 'image/webp' ? 'webp' : 'jpg'
  downloadBlob(row.blob, `${row.name.replace(/\.[^.]+$/, '')}-processed.${suffix}`)
}

// ---------------------------------------------------------------------
// 视频首帧（主线程：依赖 <video> 元素，Worker 里没有 DOM）
// ---------------------------------------------------------------------

const videoFile = ref<File | null>(null)
const videoInfo = ref<{ name: string; bytes: number; duration: number; width: number; height: number } | null>(null)
const coverUrl = ref('')
const coverBlob = ref<Blob | null>(null)
const capturing = ref(false)
const coverAtSeconds = ref(0.3)
const coverOptions = [
  { label: '0.1 秒', value: 0.1 },
  { label: '0.3 秒（默认，避开黑帧）', value: 0.3 },
  { label: '1 秒', value: 1 },
  { label: '3 秒', value: 3 }
]

async function onPickVideo(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) {
    return
  }
  videoFile.value = file
  videoInfo.value = null
  await captureCover()
}

async function captureCover(): Promise<void> {
  const file = videoFile.value
  if (!file) {
    return
  }
  capturing.value = true
  // 本地文件用 createObjectURL：FileReader 读 data URL 会把整个视频读进内存
  const objectUrl = URL.createObjectURL(file)
  const video = document.createElement('video')
  video.preload = 'metadata'
  video.muted = true
  video.playsInline = true
  video.src = objectUrl
  try {
    await new Promise<void>((resolve, reject) => {
      video.onloadedmetadata = () => resolve()
      video.onerror = () => reject(new Error('浏览器无法解析该视频格式（或编码不受支持）'))
    })
    // 截帧前先 seek：第 0 帧常不是关键帧，直接取会得到黑图
    const target = Math.min(
      Math.max(coverAtSeconds.value, 0),
      Math.max((video.duration || 1) - 0.05, 0)
    )
    await new Promise<void>((resolve, reject) => {
      video.onseeked = () => resolve()
      video.onerror = () => reject(new Error('seek 到目标位置失败'))
      video.currentTime = target
    })

    const scale = Math.min(1, 1280 / (video.videoWidth || 1280))
    const canvas = document.createElement('canvas')
    canvas.width = Math.max(1, Math.round((video.videoWidth || 1280) * scale))
    canvas.height = Math.max(1, Math.round((video.videoHeight || 720) * scale))
    const context = canvas.getContext('2d')
    if (!context) {
      throw new Error('canvas 2D 上下文不可用')
    }
    context.drawImage(video, 0, 0, canvas.width, canvas.height)
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.85))
    if (!blob) {
      throw new Error('封面导出失败（可能是画布被跨域资源污染）')
    }
    if (coverUrl.value) {
      URL.revokeObjectURL(coverUrl.value)
    }
    coverUrl.value = URL.createObjectURL(blob)
    coverBlob.value = blob
    videoInfo.value = {
      name: file.name,
      bytes: file.size,
      duration: Number((video.duration || 0).toFixed(1)),
      width: video.videoWidth,
      height: video.videoHeight
    }
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : String(error))
    videoInfo.value = null
  } finally {
    URL.revokeObjectURL(objectUrl)
    capturing.value = false
  }
}

function downloadCover(): void {
  if (coverBlob.value) {
    downloadBlob(coverBlob.value, 'cover.jpg')
  }
}

// ---------------------------------------------------------------------
// 上传（复用 ProUpload 的 request 逃生口）
// ---------------------------------------------------------------------

const uploadList = ref<UploadFileItem[]>([])

async function uploadProcessed(file: File): Promise<unknown> {
  // 真正上传时把"处理后的文件"交给文件服务：本页只做能力演示，
  // bizType 用演示值，业务接入时替换为真实业务类型
  const result = await uploadSurveyFileAction(file, 'media-lab')
  return result
}

function onUploaded(): void {
  feedback.success('上传完成')
}

function onUploadError(error: unknown): void {
  feedback.error(error instanceof Error ? error.message : '上传失败')
}

// ---------------------------------------------------------------------
// 分享与标签（二维码 / 条形码的真实用法）
// ---------------------------------------------------------------------

/** 邀请链接。token 用随机串 + 时间戳，真实业务应换成服务端签发的一次性令牌。 */
const inviteUrl = ref('')
/** 资产编号：条码场景用固定编码格式（前缀 + 日期 + 流水），便于扫码后解析。 */
const assetCode = ref('ZD-20260926-0001')

function generateToken(): string {
  // crypto.getRandomValues 在安全上下文可用；这里只要"不可预测"即可，
  // 真实场景必须由服务端签发（客户端生成的令牌无法撤销）
  const bytes = new Uint8Array(8)
  crypto.getRandomValues(bytes)
  return Array.from(bytes)
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')
}

function regenerateInvite(): void {
  inviteUrl.value = `${window.location.origin}/invite?token=${generateToken()}`
}

async function copyInvite(): Promise<void> {
  try {
    await navigator.clipboard.writeText(inviteUrl.value)
    feedback.success('邀请链接已复制')
  } catch {
    // 剪贴板 API 在非安全上下文 / 无权限时会被拒绝，给出可操作的替代方案
    feedback.error('复制失败，请手动选中链接复制')
  }
}

// ---------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------

function formatBytes(bytes: number): string {
  if (bytes >= 1024 * 1024) {
    return `${(bytes / 1024 / 1024).toFixed(2)}MB`
  }
  return `${Math.max(1, Math.round(bytes / 1024))}KB`
}

/** 统一的 Blob 下载：避免每个页面各写一遍 a[download] 的创建与释放。 */
function downloadBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = fileName
  anchor.click()
  URL.revokeObjectURL(url)
}

onMounted(() => {
  regenerateInvite()
})

onBeforeUnmount(() => {
  imagePool.dispose()
  releaseRows()
  if (coverUrl.value) {
    URL.revokeObjectURL(coverUrl.value)
  }
})
</script>

<style scoped>
.media-lab {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.media-lab__notice {
  border-radius: 8px;
}

.media-lab__notice code,
.media-lab__tip code {
  padding: 0 4px;
  border-radius: 3px;
  background: rgba(128, 128, 128, 0.15);
}

.media-lab__card {
  border-radius: 10px;
  box-shadow: 0 1px 6px rgba(0, 0, 0, 0.05);
}

.media-lab__toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.media-lab__pick {
  display: inline-flex;
  align-items: center;
  height: 32px;
  padding: 0 14px;
  border: 1px dashed rgba(128, 128, 128, 0.45);
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
}

.media-lab__pick:hover {
  border-color: var(--n-primary-color, #18a058);
  color: var(--n-primary-color, #18a058);
}

.media-lab__pick--busy {
  opacity: 0.5;
  cursor: not-allowed;
}

.media-lab__pick input {
  display: none;
}

.media-lab__field {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.media-lab__field-label {
  color: var(--n-text-color-2, #666);
}

.media-lab__select {
  min-width: 130px;
}

.media-lab__tip {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--n-text-color-2, #666);
}

.media-lab__rows {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.media-lab__row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px;
  border: 1px solid rgba(128, 128, 128, 0.18);
  border-radius: 8px;
}

.media-lab__row-main {
  flex: 1;
  min-width: 0;
}

.media-lab__row-title {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.media-lab__row-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 4px;
  font-size: 12px;
  color: var(--n-text-color-2, #666);
}

.media-lab__arrow {
  opacity: 0.5;
}

.media-lab__ratio--ok {
  color: #18a058;
  font-weight: 600;
}

.media-lab__ratio--warn {
  color: #f0a020;
  font-weight: 600;
}

.media-lab__muted {
  opacity: 0.7;
}

.media-lab__warn {
  margin-top: 6px;
  padding: 4px 8px;
  border-radius: 4px;
  background: rgba(240, 160, 32, 0.12);
  font-size: 12px;
  line-height: 1.6;
}

.media-lab__thumb {
  width: 64px;
  height: 64px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid rgba(128, 128, 128, 0.2);
}

.media-lab__thumb--after {
  border-color: rgba(24, 160, 88, 0.4);
}

.media-lab__row-side {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.media-lab__video {
  display: flex;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
  margin-top: 12px;
}

.media-lab__video-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
}

.media-lab__cover {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.media-lab__cover-img {
  width: 220px;
  border-radius: 8px;
  border: 1px solid rgba(128, 128, 128, 0.25);
}

/* 分享与标签：左表单 + 右两个码（窄屏自动换行） */
.media-lab__share {
  display: flex;
  align-items: flex-start;
  gap: 24px;
  flex-wrap: wrap;
}

.media-lab__share-form {
  flex: 1;
  min-width: 260px;
}

.media-lab__share-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.media-lab__share-code {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.media-lab__muted {
  font-size: 12px;
  color: var(--n-text-color-2, #666);
}

.media-lab__table {
  font-size: 13px;
}

.media-lab__tr {
  display: grid;
  grid-template-columns: 0.9fr 1.6fr 1.5fr;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid rgba(128, 128, 128, 0.12);
  line-height: 1.6;
}

.media-lab__tr--head {
  font-weight: 600;
  opacity: 0.7;
  border-bottom: 1px solid rgba(128, 128, 128, 0.3);
}

.media-lab__td-strong {
  font-weight: 500;
}

.media-lab__points {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
  gap: 12px;
}

.media-lab__point {
  border-radius: 10px;
  box-shadow: 0 1px 6px rgba(0, 0, 0, 0.05);
}

.media-lab__point-title {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
}

.media-lab__point-row {
  display: flex;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 13px;
  line-height: 1.7;
}

.media-lab__label {
  flex: none;
  height: 20px;
  padding: 0 6px;
  border-radius: 3px;
  font-size: 12px;
  line-height: 20px;
}

.media-lab__label--pain {
  background: rgba(208, 48, 80, 0.14);
  color: #d03050;
}

.media-lab__label--hi {
  background: rgba(24, 160, 88, 0.14);
  color: #18a058;
}

.media-lab__point-hi {
  font-weight: 500;
}

.media-lab__list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.75;
}

.media-lab__list li {
  margin-bottom: 4px;
}

.media-lab__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.media-lab__tag {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
}

.media-lab__cols {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 12px;
}

.media-lab__lever {
  display: flex;
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px dashed rgba(128, 128, 128, 0.18);
  font-size: 13px;
  line-height: 1.65;
}

.media-lab__lever:last-child {
  border-bottom: none;
}

.media-lab__lever-name {
  flex: none;
  width: 116px;
  font-weight: 600;
  word-break: break-word;
}

.media-lab__lever-desc {
  color: var(--n-text-color-2, #666);
}

@media (max-width: 720px) {
  .media-lab__tr {
    grid-template-columns: 1fr;
    gap: 4px;
  }
}
</style>
