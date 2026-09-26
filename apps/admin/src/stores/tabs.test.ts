import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

/**
 * tabs store 单测：状态机 + 持久化 + 恢复边界。
 *
 * <h3>为什么 mock @/router 而不是用真 router</h3>
 * 真实 router 模块顶层执行 createWebHistory 并注册守卫（连带激活
 * auth/permission store 与 API 层）—— 在测试里跑通它的成本远高于
 * 收益：tabs store 对 router 的全部依赖就是 `resolve()` 的「可达性
 * 判定」，mock 它等于把恢复过滤的核心逻辑变成可精确控制的条件。
 */

/** 可达的路由集合（模拟动态路由注册完成后的状态）。 */
const reachable = new Set(['/dashboard', '/showcase/media-lab', '/showcase/monitor'])

const resolveMock = vi.fn((key: string) => {
  if (reachable.has(key)) {
    return { matched: [{ path: key }] }
  }
  // 未注册路径被 catch-all（/:pathMatch(.*)*）兜住 —— 这正是 store
  // 恢复过滤要识别并丢弃的形态
  return { matched: [{ path: '/:pathMatch(.*)*' }] }
})

vi.mock('@/router', () => ({
  router: { resolve: (key: string) => resolveMock(key) }
}))

// vi.mock 会提升到文件顶部，store 的 import 必须放在 mock 之后（提升链保留）
import { useTabsStore } from './tabs'
import { useAppStore } from './app'
import { HOME_PATH } from './permission'

const STORAGE_KEY = 'admin.tabs'

const writeStorage = (value: unknown): void => {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(value))
}

const readStorage = (): string | null => localStorage.getItem(STORAGE_KEY)

const labelOf = (): string[] => useTabsStore().tabs.map((tab) => tab.key)

describe('tabs store：状态机', () => {
  beforeEach(() => {
    localStorage.clear()
    resolveMock.mockClear()
    setActivePinia(createPinia())
  })

  it('初始仅含首页，且首页不可关闭', () => {
    const store = useTabsStore()
    expect(store.tabs).toHaveLength(1)
    expect(store.tabs[0].key).toBe(HOME_PATH)
    expect(store.tabs[0].affix).toBe(true)
  })

  it('openTab：追加新页签；重复打开不产生重复项', () => {
    const store = useTabsStore()
    store.openTab({ key: '/showcase/media-lab', label: '图片视频处理' })
    store.openTab({ key: '/showcase/media-lab', label: '图片视频处理' })
    expect(store.tabs).toHaveLength(2)
    store.openTab({ key: '/showcase/monitor', label: '实时监控大屏' })
    expect(store.tabs.map((t) => t.key)).toEqual([
      HOME_PATH,
      '/showcase/media-lab',
      '/showcase/monitor'
    ])
  })

  it('openTab(首页)：永远唯一且固定在最前', () => {
    const store = useTabsStore()
    store.openTab({ key: '/showcase/media-lab', label: 'x' })
    store.openTab({ key: HOME_PATH, label: '首页' })
    expect(store.tabs.filter((t) => t.key === HOME_PATH)).toHaveLength(1)
    expect(store.tabs[0].key).toBe(HOME_PATH)
  })

  it('closeTab：固定页签不可关闭', () => {
    const store = useTabsStore()
    store.closeTab(HOME_PATH)
    expect(store.tabs).toHaveLength(1)
    store.openTab({ key: '/showcase/media-lab', label: 'x' })
    store.closeTab('/showcase/media-lab')
    expect(store.tabs).toHaveLength(1)
  })

  it('closeOthers / closeLeft / closeRight / closeAll：首页始终保留', () => {
    const store = useTabsStore()
    for (const key of ['/showcase/media-lab', '/showcase/monitor', '/admin/users']) {
      store.openTab({ key, label: key })
    }
    store.closeOthers('/showcase/monitor')
    expect(labelOf()).toEqual([HOME_PATH, '/showcase/monitor'])

    // 每个操作从已知状态出发，只断言该操作本身的语义
    store.closeAll()
    expect(labelOf()).toEqual([HOME_PATH])

    store.openTab({ key: '/showcase/media-lab', label: 'x' })
    store.openTab({ key: '/showcase/monitor', label: 'y' })
    store.closeRight('/showcase/monitor')
    expect(labelOf()).toEqual([
      HOME_PATH,
      '/showcase/media-lab',
      '/showcase/monitor'
    ])

    store.closeLeft('/showcase/monitor')
    expect(labelOf()).toEqual([HOME_PATH, '/showcase/monitor'])
  })

  it('reset：回到仅首页并清掉持久化数据', async () => {
    const store = useTabsStore()
    store.openTab({ key: '/showcase/media-lab', label: 'x' })
    await nextTick()
    expect(readStorage()).not.toBeNull()
    store.reset()
    expect(labelOf()).toEqual([HOME_PATH])
    expect(readStorage()).toBeNull()
  })
})

describe('tabs store：持久化', () => {
  beforeEach(() => {
    localStorage.clear()
    resolveMock.mockClear()
    setActivePinia(createPinia())
    useAppStore().tabsPersistence = true
  })

  it('开页签即写入存储（只存 key/label）', async () => {
    const store = useTabsStore()
    store.openTab({ key: '/showcase/media-lab', label: '图片视频处理' })
    // watch 是 flush:'pre'（异步批处理）：写入发生在下一个更新时机，
    // 同步断言会读到"还没写"—— 生产里用户刷新必然晚于微任务冲刷，无影响
    await nextTick()
    const raw = JSON.parse(readStorage() ?? '[]') as Array<Record<string, unknown>>
    expect(raw).toHaveLength(2)
    expect(Object.keys(raw[1]).sort()).toEqual(['key', 'label'])
  })

  it('【核心回归】关闭开关：不做任何页签操作，存储也必须立即被清空', async () => {
    const store = useTabsStore()
    store.openTab({ key: '/showcase/media-lab', label: 'x' })
    await nextTick()
    expect(readStorage()).not.toBeNull()

    const app = useAppStore()
    app.tabsPersistence = false
    await nextTick()
    expect(readStorage()).toBeNull()
  })

  it('重新打开开关：立即保存当前现场', async () => {
    const store = useTabsStore()
    const app = useAppStore()
    app.tabsPersistence = false
    await nextTick()
    store.openTab({ key: '/showcase/media-lab', label: 'x' })
    await nextTick()
    expect(readStorage()).toBeNull()
    app.tabsPersistence = true
    await nextTick()
    expect(readStorage()).not.toBeNull()
  })
})

describe('tabs store：恢复边界', () => {
  beforeEach(() => {
    localStorage.clear()
    resolveMock.mockClear()
    setActivePinia(createPinia())
    useAppStore().tabsPersistence = true
  })

  const reloadStore = () => {
    setActivePinia(createPinia())
    return useTabsStore()
  }

  it('合法数据恢复；首页不重复；affix 由代码重建', () => {
    writeStorage([
      { key: HOME_PATH, label: '首页' },
      { key: '/showcase/media-lab', label: '图片视频处理' }
    ])
    const store = reloadStore()
    expect(store.tabs.map((t) => t.key)).toEqual([HOME_PATH, '/showcase/media-lab'])
    expect(store.tabs[0].affix).toBe(true)
    expect(store.tabs[1].affix).toBe(false)
  })

  it('重复 key 只留首个；已下线路由（catch-all）丢弃', () => {
    writeStorage([
      { key: '/showcase/media-lab', label: 'a' },
      { key: '/showcase/media-lab', label: '重复' },
      { key: '/deleted/route-xyz', label: '已下线' }
    ])
    const store = reloadStore()
    expect(store.tabs.map((t) => t.key)).toEqual([HOME_PATH, '/showcase/media-lab'])
  })

  it('坏项（null / 缺字段 / 非对象）跳过，不抛异常', () => {
    writeStorage([null, { label: '缺key' }, 'garbage', { key: '/showcase/monitor', label: 'ok' }])
    const store = reloadStore()
    expect(store.tabs.map((t) => t.key)).toEqual([HOME_PATH, '/showcase/monitor'])
  })

  it('存储损坏（非法 JSON）静默降级为仅首页', () => {
    localStorage.setItem(STORAGE_KEY, '{not-json')
    const store = reloadStore()
    expect(store.tabs).toHaveLength(1)
  })

  it('开关关闭时不恢复（干净工作区语义）', () => {
    useAppStore().tabsPersistence = false
    // reloadStore 用的是新 pinia，新 appStore 从 admin.layout 重新读偏好 ——
    // 必须把关闭状态同步写进去，否则恢复会按默认值（true）执行
    const layout = JSON.parse(localStorage.getItem('admin.layout') ?? '{}')
    layout.tabsPersistence = false
    localStorage.setItem('admin.layout', JSON.stringify(layout))
    writeStorage([{ key: '/showcase/media-lab', label: 'x' }])
    const store = reloadStore()
    expect(store.tabs).toHaveLength(1)
    // 关闭语义下旧的存储数据也不该残留到下一次开启
    expect(readStorage()).toBeNull()
  })
})
