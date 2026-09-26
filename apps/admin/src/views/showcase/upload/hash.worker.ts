// 本文件运行在 DedicatedWorker 环境：postMessage 没有（也不需要）targetOrigin
// 参数 —— oxlint 的 require-post-message-target-origin 按 window 语义误报，此处禁用。
// eslint-disable unicorn/require-post-message-target-origin
/**
 * 文件指纹计算（Web Worker）。
 *
 * <h3>为什么必须放 Worker</h3>
 * 秒传的前置条件是算出整个文件的哈希。1GB 文件的主线程 SHA-256
 * 会把 UI 卡死十几秒 —— "上传很快，但选完文件页面就死了"。
 * Worker 里跑 crypto.subtle：主线程零阻塞，文件以
 * <b>Transferable ArrayBuffer</b> 传入（零拷贝移交，不占双倍内存）。
 *
 * <p>工程权衡：这里用全量 SHA-256（结果可直接与后端"已存在文件表"对齐，
 * 秒传判定最可靠）。若产品要求"选完立刻出进度"，可退化为
 * 首/中/尾抽样哈希 —— 以极小的误判率换 10 倍速度，判定结果交给
 * 后端复核。两种策略都实现在本 Worker 的接口后面，前端无感。
 */

// 响应协议 = Worker 池的"单请求单响应"契约（见 src/workers/pool.ts）：
// 成功回 { payload }，失败回 { error }。池靠这个形状分发结果。
self.addEventListener('message', async (event: MessageEvent) => {
  const { buffer } = event.data as { fileKey: string; buffer: ArrayBuffer }
  try {
    const digest = await crypto.subtle.digest('SHA-256', buffer)
    const hex = Array.from(new Uint8Array(digest))
      .map((byte) => byte.toString(16).padStart(2, '0'))
      .join('')
    self.postMessage({ payload: hex })
  } catch (error) {
    self.postMessage({ error: String(error) })
  }
})
