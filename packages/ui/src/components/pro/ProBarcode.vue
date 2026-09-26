<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'

/**
 * ⚠️ {@code jsbarcode} 走**动态导入**，不进首屏 chunk。
 *
 * <p>理由与 ProQrcode 相同：条码多数页面用不到，静态引入是让所有用户
 * 为个别场景付费。动态导入 + 模块级缓存，只在首次渲染条码时拉取一次。
 */
interface BarcodeRenderOptions {
  format: BarcodeFormat
  width: number
  height: number
  displayValue: boolean
  fontSize: number
  margin: number
  lineColor: string
  background: string
  valid?: (valid: boolean) => void
}

/** jsbarcode 本身就是一个函数（CJS 的 {@code export =} 形态）。 */
type JsBarcodeFn = (element: unknown, data: string, options?: BarcodeRenderOptions) => void

let barcodeFn: JsBarcodeFn | null = null

/**
 * 加载 jsbarcode 并抹平 CJS / ESM 的形态差异（同 ProQrcode 的说明）。
 */
async function loadBarcode(): Promise<JsBarcodeFn> {
  if (!barcodeFn) {
    const loaded = (await import('jsbarcode')) as unknown as { default?: JsBarcodeFn } & JsBarcodeFn
    barcodeFn = (loaded.default ?? loaded) as JsBarcodeFn
  }
  return barcodeFn
}

/**
 * 条形码（二次封装 {@code jsbarcode}）。
 *
 * <h3>为什么默认渲染成 SVG 而不是 canvas</h3>
 * 条码的条空宽度是**有物理精度要求**的（EAN/UPC 的模块宽度偏差超过容差就扫不出）。
 * canvas 会按设备像素比与 CSS 尺寸缩放，打印或放大时容易出现"半个像素的模糊边"，
 * 而 SVG 是矢量，任意尺寸都精确。所以默认 SVG，需要位图时用导出的下载能力。
 *
 * <h3>关于内容校验（这里有真实事故）</h3>
 * 不同码制对内容有硬性约束：EAN13 必须 12~13 位数字、CODE39 只接受特定字符集、
 * UPC 必须 11~12 位数字。内容不合法时 jsbarcode 会直接抛异常 —— 若不做处理，
 * 用户在输入框打字的过程中组件就会报错。这里把异常收敛成 `error` 事件 +
 * 组件内的提示，让"边输边试"成为正常操作。
 *
 * <h3>为什么导出只提供 SVG</h3>
 * SVG → PNG 需要把 SVG 序列化成 data URL 再走 Image 解码，过程中容易因
 * 缺少 xmlns、字体未内联而出现"导出的图空白"。业务真正要位图时通常也
 * 需要指定 DPI 与尺寸（印刷场景），那应该由后端生成 —— 组件只导出矢量原件。
 */

/** 常用码制。完整清单见 jsbarcode 文档（如 ITF14 / MSI / pharmacode / codabar）。 */
export type BarcodeFormat =
  | 'CODE128'
  | 'CODE39'
  | 'EAN13'
  | 'EAN8'
  | 'UPC'
  | 'ITF14'
  | 'MSI'

const props = withDefaults(
  defineProps<{
    /** 要编码的内容。各码制对长度与字符集有约束，非法时触发 error 事件。 */
    value: string
    /** 码制。默认 CODE128：可编码任意 ASCII，最通用。 */
    format?: BarcodeFormat
    /** 单条最窄条宽度（px）。低于 1.5 在普通屏幕上会糊。 */
    width?: number
    /** 条码高度（px），不含文字。 */
    height?: number
    /** 是否在条码下方显示可读文本。 */
    displayValue?: boolean
    /** 可读文本的字号（px）。 */
    fontSize?: number
    /** 四周留白（px）。条码两侧需要静区，过小会降低识别率。 */
    margin?: number
    /** 条/字颜色。 */
    lineColor?: string
    /** 背景色。 */
    background?: string
    /** 是否显示下载按钮（导出 SVG 原件）。 */
    downloadable?: boolean
    /** 下载文件名（不带扩展名）。 */
    downloadName?: string
  }>(),
  {
    format: 'CODE128',
    width: 2,
    height: 64,
    displayValue: true,
    fontSize: 14,
    margin: 8,
    lineColor: '#111827',
    background: '#ffffff',
    downloadable: false,
    downloadName: 'barcode'
  }
)

const emit = defineEmits<{
  (e: 'rendered'): void
  /** 内容不符合当前码制约束。业务侧据此提示用户，而不是弹白屏。 */
  (e: 'error', error: unknown): void
}>()

const svgRef = ref<SVGSVGElement | null>(null)
/** 当前内容是否非法：用于渲染内联提示，避免用户以为"组件坏了"。 */
const invalidMessage = ref('')

async function render(): Promise<void> {
  const svg = svgRef.value
  if (!svg) {
    return
  }
  // 先清空：jsbarcode 是"往元素里追加/覆盖"，不清会残留上一次的条
  while (svg.firstChild) {
    svg.removeChild(svg.firstChild)
  }
  if (!props.value) {
    invalidMessage.value = ''
    return
  }
  try {
    const jsbarcode = await loadBarcode()
    jsbarcode(svg, props.value, {
      format: props.format,
      width: props.width,
      height: props.height,
      displayValue: props.displayValue,
      fontSize: props.fontSize,
      margin: props.margin,
      lineColor: props.lineColor,
      background: props.background,
      valid: (valid: boolean) => {
        // jsbarcode 的 valid 回调：内容非法时它不会抛错，只标记无效。
        // 两种情况都要覆盖 —— 有的码制抛错、有的走这个回调。
        invalidMessage.value = valid
          ? ''
          : `${props.format} 不接受当前内容（长度或字符集不符合该码制规范）`
      }
    })
    emit('rendered')
  } catch (error) {
    invalidMessage.value = error instanceof Error ? error.message : '内容无法编码'
    emit('error', error)
  }
}

watch(
  () => [
    props.value,
    props.format,
    props.width,
    props.height,
    props.displayValue,
    props.fontSize,
    props.margin,
    props.lineColor,
    props.background
  ],
  () => {
    void render()
  }
)

onMounted(() => {
  void render()
})

/** 导出 SVG 原件。矢量件可直接用于印刷与后续格式化。 */
function download(): void {
  const svg = svgRef.value
  if (!svg) {
    return
  }
  const clone = svg.cloneNode(true) as SVGSVGElement
  clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
  const source = `<?xml version="1.0" standalone="no"?>\n${new XMLSerializer().serializeToString(clone)}`
  const url = URL.createObjectURL(new Blob([source], { type: 'image/svg+xml;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${props.downloadName}.svg`
  anchor.click()
  URL.revokeObjectURL(url)
}

defineExpose({ download })
</script>

<template>
  <div class="pro-barcode">
    <svg ref="svgRef" class="pro-barcode__svg" />
    <p v-if="invalidMessage" class="pro-barcode__invalid">{{ invalidMessage }}</p>
    <n-button
      v-if="downloadable && !invalidMessage"
      class="pro-barcode__download"
      size="tiny"
      quaternary
      @click="download"
    >
      下载 SVG
    </n-button>
  </div>
</template>

<style scoped>
.pro-barcode {
  display: inline-flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--wa-spacing-xs, 4px);
}

.pro-barcode__svg {
  display: block;
  max-width: 100%;
  border-radius: var(--wa-radius-sm, 4px);
}

.pro-barcode__invalid {
  margin: 0;
  font-size: var(--wa-font-size-xs, 12px);
  line-height: 1.6;
  color: var(--wa-color-warning, #f0a020);
}

.pro-barcode__download {
  font-size: var(--wa-font-size-xs, 12px);
}
</style>
