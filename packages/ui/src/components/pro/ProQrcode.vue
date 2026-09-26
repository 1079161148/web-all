<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'

/**
 * ⚠️ {@code qrcode} 库走**动态导入**，不进首屏 chunk。
 *
 * <p>实测：静态 import 会让首屏体积直接超出门禁（qrcode 约 20KB）。
 * 二维码是"少数页面、少数场景"才用的能力，为它让所有用户多下一次不值得。
 * 动态导入后，库只在第一次真正渲染二维码时才被拉取；模块级变量保证只拉一次。
 * 与 ProChart 懒加载 echarts 是同一策略。
 */
interface QrcodeRenderOptions {
  width: number
  errorCorrectionLevel: QrcodeLevel
  margin: number
  color: { dark: string; light: string }
}

/** 只声明本组件真正用到的能力，不引用库的完整类型。 */
interface QrcodeLib {
  toCanvas(canvas: HTMLCanvasElement, text: string, options?: QrcodeRenderOptions): Promise<unknown>
}

let qrcodeLib: QrcodeLib | null = null

/**
 * 加载 qrcode 并抹平 CJS / ESM 的形态差异。
 *
 * <p>{@code qrcode} 是 CJS 包（类型上是 {@code export =}），经打包器转成 ESM 后
 * 运行时**既可能是模块对象本身、也可能是它的 default** —— 两种都见过。
 * 这里断言一次并两个都兼容，避免"类型说没有 default、运行时偏偏要给 default"
 * 这类只在某个打包配置下才炸的问题。
 */
async function loadQrcode(): Promise<QrcodeLib> {
  if (!qrcodeLib) {
    const loaded = (await import('qrcode')) as unknown as { default?: QrcodeLib } & QrcodeLib
    qrcodeLib = (loaded.default ?? loaded) as QrcodeLib
  }
  return qrcodeLib
}

/**
 * 二维码（二次封装 {@code qrcode}）。
 *
 * <h3>为什么用 canvas 而不是 img + dataURL</h3>
 * dataURL 方案每次重绘都会生成一个几十 KB 的 base64 字符串并替换 `src`：
 * 二维码最典型的用法是"输入内容即时刷新"，高频重绘会持续制造大字符串垃圾，
 * 且每次都要走一遍图片解码。canvas 直接绘制，没有字符串与解码开销。
 *
 * <h3>竞态：为什么不直接用 watch + await</h3>
 * 渲染是异步的（{@code toCanvas} 返回 Promise）。快速输入时可能出现
 * "后发起的渲染先完成、先发起的后完成"，结果屏幕上留着**旧内容** ——
 * 这类 bug 在人工测试时极难复现。这里用单调递增的序号，渲染完成时
 * 比对序号，过期结果直接丢弃。
 *
 * <h3>防抖</h3>
 * 输入框绑定二维码时，每敲一个字符重编码一次是纯浪费。默认 120ms 尾随防抖：
 * 停下输入后渲染一次，过程中不渲染。需要"零延迟"时可把 debounce 设为 0。
 *
 * <h3>为什么不做成"支持 logo 水印"</h3>
 * 居中挖一块放 logo 会遮挡纠错码字，只有在容错级别 ≥ H（30%）时才安全，
 * 且需要重新计算掩码。业务真需要时应明确指定 `errorLevel="H"`，
 * 否则就是"看起来能用、扫不出来"的经典事故。本组件把选择权留给调用方。
 */

/** 纠错级别：L(7%) / M(15%) / Q(25%) / H(30%)。级别越高越抗污损，但码更密。 */
export type QrcodeLevel = 'L' | 'M' | 'Q' | 'H'

const props = withDefaults(
  defineProps<{
    /** 要编码的内容（URL / 文本 / 短 JSON）。 */
    value: string
    /** 画布边长（px）。二维码是正方形，只用一个尺寸。 */
    size?: number
    /** 纠错级别。内容较长时建议 L/M，需要抗污损或叠 logo 时用 H。 */
    level?: QrcodeLevel
    /** 静区（白边）模块数。低于 2 的静区会让部分扫码器识别率下降。 */
    margin?: number
    /** 前景色（默认近黑，避免纯黑在屏幕上过重）。 */
    foreground?: string
    /** 背景色。透明背景会导致部分扫码器失败，因此默认白底。 */
    background?: string
    /** 防抖毫秒数（0 = 立即渲染）。 */
    debounce?: number
    /** 是否显示下载按钮。 */
    downloadable?: boolean
    /** 下载文件名（不带扩展名）。 */
    downloadName?: string
  }>(),
  {
    size: 160,
    level: 'M',
    margin: 2,
    foreground: '#111827',
    background: '#ffffff',
    debounce: 120,
    downloadable: false,
    downloadName: 'qrcode'
  }
)

const emit = defineEmits<{
  /** 渲染成功（可拿到当前 canvas 用于自定义导出）。 */
  (e: 'rendered', canvas: HTMLCanvasElement): void
  /** 渲染失败（内容过长 / 编码不支持的字符）。 */
  (e: 'error', error: unknown): void
}>()

const canvasRef = ref<HTMLCanvasElement | null>(null)
/** 单调递增的渲染序号：用于丢弃过期结果（见上方竞态说明）。 */
let renderSeq = 0
let debounceTimer = 0

async function render(): Promise<void> {
  const canvas = canvasRef.value
  if (!canvas) {
    return
  }
  const seq = ++renderSeq
  if (!props.value) {
    // 空内容：清空画布，不报错（清空输入框是正常操作）
    const context = canvas.getContext('2d')
    context?.clearRect(0, 0, canvas.width, canvas.height)
    return
  }
  try {
    const qrcode = await loadQrcode()
    await qrcode.toCanvas(canvas, props.value, {
      width: props.size,
      errorCorrectionLevel: props.level,
      margin: props.margin,
      color: { dark: props.foreground, light: props.background }
    })
    if (seq !== renderSeq) {
      return
    }
    emit('rendered', canvas)
  } catch (error) {
    if (seq !== renderSeq) {
      return
    }
    emit('error', error)
  }
}

function schedule(): void {
  if (debounceTimer !== 0) {
    clearTimeout(debounceTimer)
    debounceTimer = 0
  }
  if (props.debounce <= 0) {
    void render()
    return
  }
  debounceTimer = window.setTimeout(() => {
    debounceTimer = 0
    void render()
  }, props.debounce)
}

watch(
  () => [props.value, props.size, props.level, props.margin, props.foreground, props.background],
  schedule,
  { immediate: true }
)

onBeforeUnmount(() => {
  if (debounceTimer !== 0) {
    clearTimeout(debounceTimer)
  }
})

/** 导出 PNG。用 toBlob 而不是 toDataURL：前者不生成 base64 字符串。 */
function download(): void {
  const canvas = canvasRef.value
  if (!canvas) {
    return
  }
  canvas.toBlob((blob) => {
    if (!blob) {
      return
    }
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${props.downloadName}.png`
    anchor.click()
    URL.revokeObjectURL(url)
  }, 'image/png')
}

defineExpose({ download })
</script>

<template>
  <div class="pro-qrcode">
    <canvas ref="canvasRef" class="pro-qrcode__canvas" :width="size" :height="size" />
    <n-button v-if="downloadable" class="pro-qrcode__download" size="tiny" quaternary @click="download">
      下载二维码
    </n-button>
  </div>
</template>

<style scoped>
.pro-qrcode {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: var(--wa-spacing-xs, 4px);
}

.pro-qrcode__canvas {
  display: block;
  border-radius: var(--wa-radius-sm, 4px);
  /* 背景由二维码自身的 light 色提供，这里只兜住图片加载前的底色 */
  background: #fff;
}

.pro-qrcode__download {
  font-size: var(--wa-font-size-xs, 12px);
}
</style>
