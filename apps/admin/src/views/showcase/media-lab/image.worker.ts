// 本文件运行在 DedicatedWorker 环境：postMessage 没有（也不需要）targetOrigin
// 参数 —— oxlint 的 require-post-message-target-origin 按 window 语义误报，此处禁用。
// eslint-disable unicorn/require-post-message-target-origin
/// <reference lib="webworker" />

/**
 * 图片处理 Worker：探测尺寸 → 解码（带降采样）→ 重编码。
 *
 * <h3>为什么整条链路都要在 Worker 里</h3>
 * 一张 8000×6000 的照片解码后是约 192MB 像素数据。在主线程做这件事，
 * 用户看到的是"选完文件页面卡住两秒"，移动端可能直接崩标签页 ——
 * 而这类问题在开发机上（样图通常 1~2MB）根本不会复现。
 *
 * <h3>两个关键优化</h3>
 * <ol>
 *   <li><b>先读文件头拿尺寸，再决定是否降采样</b>：用 createImageBitmap 的
 *       resizeWidth 会在<b>解码阶段</b>就产出小图，峰值内存降一个数量级；
 *       但若原图比目标还小，传 resizeWidth 会把图<b>放大</b> —— 那不是我们想要的。
 *       所以先用 {@link probeSize} 只读文件头（不解码）判断尺寸</li>
 *   <li><b>imageOrientation: 'from-image'</b>：把 EXIF 方向烘焙进像素。
 *       重编码会剥掉 EXIF（顺带剥掉 GPS，这是好事），若不先烘焙方向，
 *       竖拍照片上传后会变成横的</li>
 * </ol>
 *
 * <h3>不支持的边界（有意为之，而不是遗漏）</h3>
 * HEIC/AVIF 等格式的尺寸探测未实现（走"解码后再读尺寸"的兜底路径，
 * 代价是一次全尺寸解码）；SVG 直接拒绝处理（可含脚本，前端按图片处理不安全）。
 * 这些边界由调用方（页面）负责给出可读提示。
 *
 * <p>响应协议与 src/workers/pool.ts 的"单请求单响应"契约一致：
 * 成功回 {@code { payload }}，失败回 {@code { error }}。
 */

/** 处理请求。buffer 以 Transferable 传入（零拷贝）。 */
interface ProcessRequest {
  /** 原始文件字节 */
  buffer: ArrayBuffer
  /** 原始 MIME（用于构造 Blob 让浏览器正确解码） */
  mime: string
  /** 长边上限（像素）。原图长边小于该值时不缩放，避免放大 */
  maxEdge: number
  /** 质量 0~1，仅对有损格式（jpeg/webp）有效 */
  quality: number
  /** 目标编码格式 */
  format: 'image/webp' | 'image/jpeg'
}

/** 处理结果。buffer 同样以 Transferable 回传。 */
interface ProcessResult {
  buffer: ArrayBuffer
  type: string
  width: number
  height: number
  /** 源图尺寸（探测或解码得到）—— 页面用它展示"原图 → 处理后" */
  sourceWidth: number
  sourceHeight: number
  /** 是否发生了降采样（页面据此说明"为什么体积降了"） */
  downscaled: boolean
}

/**
 * 只读文件头探测尺寸（不解码像素）。
 *
 * <p>支持 JPEG / PNG / GIF / WebP 四种最常见的格式；其余返回 null，
 * 由调用方回退到"解码后再读尺寸"。这样常规图片不会付出解码成本。
 */
function probeSize(bytes: Uint8Array): { width: number; height: number } | null {
  // PNG：IHDR 固定在第 16 字节起，宽高各 4 字节大端
  if (
    bytes.length > 24 &&
    bytes[0] === 0x89 &&
    bytes[1] === 0x50 &&
    bytes[2] === 0x4e &&
    bytes[3] === 0x47
  ) {
    return { width: readU32be(bytes, 16), height: readU32be(bytes, 20) }
  }

  // GIF：宽高各 2 字节小端
  if (bytes.length > 10 && bytes[0] === 0x47 && bytes[1] === 0x49 && bytes[2] === 0x46) {
    return { width: readU16le(bytes, 6), height: readU16le(bytes, 8) }
  }

  // JPEG：扫描段标记找 SOF（帧起始），跳过其它段
  if (bytes.length > 4 && bytes[0] === 0xff && bytes[1] === 0xd8) {
    let offset = 2
    while (offset + 9 < bytes.length) {
      if (bytes[offset] !== 0xff) {
        offset += 1
        continue
      }
      const marker = bytes[offset + 1]
      // SOF0~SOF15，排除 DHT(C4)/JPG(C8)/DAC(CC)
      if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
        return {
          height: (bytes[offset + 5] << 8) | bytes[offset + 6],
          width: (bytes[offset + 7] << 8) | bytes[offset + 8]
        }
      }
      const segmentLength = (bytes[offset + 2] << 8) | bytes[offset + 3]
      if (segmentLength <= 0) {
        break
      }
      offset += 2 + segmentLength
    }
    return null
  }

  // WebP：RIFF 容器，VP8X/VP8/VP8L 三种帧头布局不同
  if (
    bytes.length > 30 &&
    readAscii(bytes, 0, 4) === 'RIFF' &&
    readAscii(bytes, 8, 4) === 'WEBP'
  ) {
    const chunk = readAscii(bytes, 12, 4)
    if (chunk === 'VP8X') {
      // 扩展格式：宽高各 3 字节小端，值为"实际尺寸 - 1"
      return {
        width: 1 + readU24le(bytes, 24),
        height: 1 + readU24le(bytes, 27)
      }
    }
    if (chunk === 'VP8 ') {
      // 有损：帧头内 14 位宽 + 14 位高
      return {
        width: ((bytes[27] << 8) | bytes[26]) & 0x3fff,
        height: ((bytes[29] << 8) | bytes[28]) & 0x3fff
      }
    }
    if (chunk === 'VP8L') {
      // 无损：14 位宽高打包在 4 字节里
      const bits = bytes[21] | (bytes[22] << 8) | (bytes[23] << 16) | (bytes[24] << 24)
      return {
        width: (bits & 0x3fff) + 1,
        height: ((bits >> 14) & 0x3fff) + 1
      }
    }
  }

  return null
}

function readU32be(bytes: Uint8Array, offset: number): number {
  return (
    (bytes[offset] << 24) | (bytes[offset + 1] << 16) | (bytes[offset + 2] << 8) | bytes[offset + 3]
  ) >>> 0
}

function readU16le(bytes: Uint8Array, offset: number): number {
  return bytes[offset] | (bytes[offset + 1] << 8)
}

function readU24le(bytes: Uint8Array, offset: number): number {
  return bytes[offset] | (bytes[offset + 1] << 8) | (bytes[offset + 2] << 16)
}

function readAscii(bytes: Uint8Array, offset: number, length: number): string {
  let text = ''
  for (let i = 0; i < length; i += 1) {
    text += String.fromCharCode(bytes[offset + i])
  }
  return text
}

self.addEventListener('message', (event: MessageEvent) => {
  const request = event.data as ProcessRequest
  void (async () => {
    try {
      const bytes = new Uint8Array(request.buffer)
      const probed = probeSize(bytes)
      const source = new Blob([request.buffer], { type: request.mime })

      // 只有"确知原图长边 > 上限"时才降采样：避免把小于上限的图放大
      const needResize = probed !== null && Math.max(probed.width, probed.height) > request.maxEdge
      const targetLongEdge = Math.min(request.maxEdge, 4096)

      const bitmap = await createImageBitmap(source, {
        imageOrientation: 'from-image',
        ...(needResize
          ? {
              resizeWidth:
                probed.width >= probed.height ? targetLongEdge : undefined,
              resizeHeight:
                probed.width < probed.height ? targetLongEdge : undefined,
              resizeQuality: 'high' as const
            }
          : {})
      })

      const canvas = new OffscreenCanvas(bitmap.width, bitmap.height)
      const context = canvas.getContext('2d')
      if (!context) {
        throw new Error('OffscreenCanvas 2D 上下文不可用')
      }
      // 重编码会丢失透明通道（jpeg 无 alpha）：白底兜底，避免透明区域变黑
      if (request.format === 'image/jpeg') {
        context.fillStyle = '#ffffff'
        context.fillRect(0, 0, canvas.width, canvas.height)
      }
      context.drawImage(bitmap, 0, 0)

      const width = bitmap.width
      const height = bitmap.height
      // 尺寸探测在解码前拿，用于向用户说明"原图多大"
      const sourceWidth = probed?.width ?? width
      const sourceHeight = probed?.height ?? height
      bitmap.close()

      const encoded = await canvas.convertToBlob({
        type: request.format,
        quality: request.quality
      })
      const output = await encoded.arrayBuffer()

      const result: ProcessResult = {
        buffer: output,
        type: encoded.type,
        width,
        height,
        sourceWidth,
        sourceHeight,
        downscaled: width !== sourceWidth || height !== sourceHeight
      }
      self.postMessage({ payload: result }, [output])
    } catch (error) {
      self.postMessage({ error: error instanceof Error ? error.message : String(error) })
    }
  })()
})
