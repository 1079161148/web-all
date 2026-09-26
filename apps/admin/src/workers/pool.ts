/**
 * 通用 Worker 池（单请求单响应协议）。
 *
 * <h3>解决什么</h3>
 * 之前两处 Worker 用法各写各的：hash.worker 每个文件 `new Worker` → 用完
 * terminate —— 小文件高频上传时，创建/销毁的开销比算哈希本身还大。
 * 池化后 worker 常驻复用，并给"同时最多 N 个"一个明确的闸门
 * （机器核数之外的并发只会互相抢时间片，不会更快）。
 *
 * <h3>协议边界：单请求单响应</h3>
 * 池假设 worker 收到一条消息后<b>恰好回一条</b>结果消息
 * （{ payload } 或 { error }，见 run() 的注释）。
 * 这个假设覆盖了"把数据丢进去算个结果"的全部场景（哈希、压缩、图片处理）；
 * <b>有状态的长会话 worker 不适用</b> —— 例如百万级图表页的 stock.worker
 * 在自身内存里缓存了数据集、支持多轮窗口重采样，它必须独占一个实例，
 * 塞进池里会被其它任务"污染"其内部状态。那类 worker 请保持专用。
 *
 * <h3>取消的两层语义</h3>
 * <ul>
 *   <li>排队中：直接移除并 reject（零成本）；</li>
 *   <li>执行中：只能 terminate 整个 worker 再补一个 —— worker 内部的
 *       同步计算没有"响应中断"的机制，强杀是唯一手段。</li>
 * </ul>
 */

export interface PoolHandle<R> {
  /** 结果 Promise。任务被取消时以 Error reject。 */
  promise: Promise<R>
  /** 取消任务。排队中直接移除；执行中 terminate 所在 worker。 */
  cancel: () => void
}

export interface WorkerPool {
  /**
   * 提交一个任务。
   *
   * @param message 发给 worker 的消息
   * @param transfer 可转移的 Transferable（省一次结构化克隆拷贝）
   */
  run<R>(message: unknown, transfer?: Transferable[]): PoolHandle<R>
  /** 停掉池内所有 worker（页面卸载/组件销毁时调用）。 */
  dispose(): void
}

interface QueueItem {
  message: unknown
  transfer?: Transferable[]
  resolve: (value: never) => void
  reject: (error: Error) => void
  cancelled: boolean
}

const CANCELLED = 'pool task cancelled'

export function createWorkerPool(
  createWorker: () => Worker,
  size = Math.max(
    1,
    Math.min(4, (typeof navigator !== 'undefined' ? navigator.hardwareConcurrency : 2) || 2)
  )
): WorkerPool {
  const slots: Array<{ worker: Worker; busy: QueueItem | null }> = []
  const queue: QueueItem[] = []
  let disposed = false

  function spawn(): void {
    const slot: { worker: Worker; busy: QueueItem | null } = {
      worker: createWorker(),
      busy: null
    }
    slot.worker.addEventListener('message', (event: MessageEvent) => {
      const item = slot.busy
      slot.busy = null
      const data = event.data as { error?: string; payload?: unknown } | null
      if (item && !item.cancelled) {
        if (data && typeof data === 'object' && 'error' in data && data.error !== undefined) {
          item.reject(new Error(String(data.error)))
        } else {
          item.resolve((data as { payload: unknown }).payload as never)
        }
      }
      pump()
    })
    slot.worker.addEventListener('error', (event) => {
      // worker 崩溃（脚本异常/加载失败）：当前任务失败 + 换一个新 worker
      const item = slot.busy
      slot.busy = null
      if (item && !item.cancelled) {
        item.reject(new Error(event.message || 'worker crashed'))
      }
      slot.worker.terminate()
      slot.worker = createWorker()
      pump()
    })
    slots.push(slot)
  }

  function pump(): void {
    if (disposed) {
      return
    }
    // 先补槽（有排队任务且槽未满时才扩容 —— 空闲时不养多余 worker）
    while (slots.length < size && queue.length > slots.length) {
      spawn()
    }
    for (const slot of slots) {
      if (slot.busy !== null) {
        continue
      }
      // 队列头部可能堆着已取消的任务：跳过并清理
      let item = queue.shift()
      while (item && item.cancelled) {
        item.reject(new Error(CANCELLED))
        item = queue.shift()
      }
      if (!item) {
        return
      }
      slot.busy = item
      slot.worker.postMessage(item.message, item.transfer ?? [])
    }
  }

  return {
    run<R>(message: unknown, transfer?: Transferable[]): PoolHandle<R> {
      let resolve!: (value: never) => void
      let reject!: (error: Error) => void
      const promise = new Promise<R>((res, rej) => {
        resolve = res as (value: never) => void
        reject = rej
      })
      const item: QueueItem = { message, transfer, resolve, reject, cancelled: false }
      const handle: PoolHandle<R> = {
        promise,
        cancel: () => {
          if (item.cancelled) {
            return
          }
          item.cancelled = true
          const index = queue.indexOf(item)
          if (index >= 0) {
            queue.splice(index, 1)
            reject(new Error(CANCELLED))
            return
          }
          // 已在执行：找到占用它的槽，terminate 强杀并补新 worker
          const slot = slots.find((s) => s.busy === item)
          if (slot) {
            slot.busy = null
            slot.worker.terminate()
            slot.worker = createWorker()
            reject(new Error(CANCELLED))
            pump()
          }
        }
      }
      queue.push(item)
      pump()
      return handle
    },
    dispose(): void {
      disposed = true
      for (const slot of slots) {
        slot.worker.terminate()
        if (slot.busy && !slot.busy.cancelled) {
          slot.busy.reject(new Error('worker pool disposed'))
        }
      }
      slots.length = 0
      queue.length = 0
    }
  }
}
