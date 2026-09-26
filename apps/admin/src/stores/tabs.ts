import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import type { ProLayoutTab } from '@admin/ui'
import { useAppStore } from './app'
import { HOME_PATH } from './permission'
import { router } from '@/router'

/**
 * 首页固定页签。
 *
 * <p>标签栏<b>永远保留</b>它：一方面满足"首页不可关闭、固定在第一位"的产品约束；
 * 另一方面避免"用户把页签一个一个全关掉，界面只剩空白"的死角
 * （固定页签的关闭按钮不渲染，见 ProLayout 的 {@code closable} 绑定）。
 */
const HOME_TAB: ProLayoutTab = { key: HOME_PATH, label: '首页', affix: true }

/**
 * 已访问页面的标签栏状态。
 *
 * <h3>为什么它必须是 store 而不是布局组件的局部状态</h3>
 * 「用户访问过哪些页面」是<b>跨路由切换持续存在</b>的状态：
 * 布局组件会随路由重新渲染，放局部 ref 里会在每次切换时重建，
 * 表现为"标签栏刚出现就没了"。
 *
 * <h3>⚠️ 退出登录时必须 reset</h3>
 * 与 {@code permissionStore} 同理：不重置的话，
 * 下一个登录的用户会看到<b>上一个用户访问过的页面标签</b> ——
 * 那些页签可能对应他无权访问的路由，点进去就是一个 403。
 * 这类"换账号后界面没变干净"的问题，刷新一次就好了，极具迷惑性。
 */
export const useTabsStore = defineStore('tabs', () => {
  // 初始态即含首页固定页签：reset（登出/换账号）后也回到这个状态，
  // 保证任何时刻标签栏至少有一个不可关闭的落脚点
  const tabs = ref<ProLayoutTab[]>([HOME_TAB])

  /**
   * 页签集合的本地持久化。
   *
   * <h3>为什么与布局偏好分两个 key 存</h3>
   * 布局偏好（`admin.layout`）是"用户对界面的选择"，稳定且低频；
   * 页签集合是"工作现场"，每开一个页面就写一次。混在一个 key 里会
   * 让布局偏好的写入频率被页签带起来，且任一方结构损坏会互相牵连。
   *
   * <h3>为什么只存 key / label / icon</h3>
   * 其它字段（如是否可关闭）属于运行时裁定，必须由代码重建而不是从存储恢复 ——
   * 否则手改 localStorage 就能造出"首页可关闭"这种非法状态。
   */
  const TABS_STORAGE_KEY = 'admin.tabs'

  function persistTabs(): void {
    const app = useAppStore()
    try {
      // 开关关闭时顺手清掉历史数据：否则再次打开会恢复"关闭期间"的旧现场，
      // 用户会以为开关根本没生效
      if (!app.tabsPersistence) {
        localStorage.removeItem(TABS_STORAGE_KEY)
        return
      }
      localStorage.setItem(
        TABS_STORAGE_KEY,
        JSON.stringify(
          // 只存 key/label：ProLayoutTab 本身没有 icon 字段（页签图标由路由 meta
          // 提供，恢复时由应用重新推导），多存一个字段也恢复不回去
          tabs.value.map((tab) => ({ key: tab.key, label: tab.label }))
        )
      )
    } catch {
      // 隐私模式 / 配额满：只影响"下次是否记得"，不影响当前使用
    }
  }

  function restoreTabs(): void {
    const app = useAppStore()
    if (!app.tabsPersistence) {
      // 与 persistTabs 的关闭语义对称：恢复被跳过的同时清掉残留数据 ——
      // 否则手改/并发写入留下的旧记录会一直留着，重新打开开关时"复活"
      try {
        localStorage.removeItem(TABS_STORAGE_KEY)
      } catch {
        // 存储不可用时无需处理：恢复本就不会发生
      }
      return
    }
    try {
      const raw = localStorage.getItem(TABS_STORAGE_KEY)
      if (!raw) {
        return
      }
      const saved = JSON.parse(raw) as unknown
      if (!Array.isArray(saved)) {
        return
      }
      // 逐项校验（localStorage 可能被手改）：
      //   1. 缺 key/label 的项会渲染成空白页签 —— 丢弃；
      //   2. 重复 key（两个浏览器标签页并发写、或手改产生）会渲染出
      //      两个一模一样的页签且激活态错乱 —— 只留首个；
      //   3. 路由已不存在（菜单/权限变更后存过的页面被删）的项恢复出来
      //      点进去就是 404 —— 用 router.resolve 验可达性，解析到
      //      catch-all 兜底路由的同样视为不可达。
      const seen = new Set<string>([HOME_PATH])
      const rest: ProLayoutTab[] = []
      for (const item of saved) {
        if (
          !item ||
          typeof item !== 'object' ||
          typeof (item as ProLayoutTab).key !== 'string' ||
          typeof (item as ProLayoutTab).label !== 'string'
        ) {
          continue
        }
        const { key, label } = item as ProLayoutTab
        if (key === HOME_PATH || seen.has(key)) {
          continue
        }
        const resolved = router.resolve(key)
        const isCatchAll = resolved.matched.some((record) =>
          record.path.includes(':pathMatch')
        )
        if (resolved.matched.length === 0 || isCatchAll) {
          continue
        }
        seen.add(key)
        rest.push({ key, label, affix: false })
      }
      tabs.value = [HOME_TAB, ...rest]
    } catch {
      // 损坏的存储只是回到"仅首页"，不该阻塞启动
    }
  }

  // open/close 都是整体替换数组，浅监听足够
  watch(tabs, persistTabs)
  /*
    ⚠️ 开关本身的变化必须立即落盘/清场 —— 这是"页签持久化没生效"的根因：
    只靠上面 tabs 的 watch，切换开关那一刻 tabs 没变、什么都不发生，
    旧数据要拖到下一次开关页签才被清理。用户关掉开关 → 刷新 → 页签还在，
    自然认定这个开关是坏的。
    persistTabs 内部已处理两个方向：关闭 → 删除存储；重新打开 → 立即保存当前现场。
  */
  const appStore = useAppStore()
  watch(
    () => appStore.tabsPersistence,
    () => {
      persistTabs()
    }
  )
  restoreTabs()

  /**
   * 打开（或激活）一个页签。
   *
   * <p>固定规则是<b>按路径</b>（首页）而不是「第一个访问的」：
   * 后者的实现会随登录落点漂移（比如带 redirect 重登直接落在业务页，
   * 那个业务页就变成了不可关闭的固定页签），语义完全错误。
   */
  function openTab(tab: ProLayoutTab): void {
    // 首页：已存在（常态，含初始态）则不动；不存在则强制插回最前
    if (tab.key === HOME_PATH) {
      if (!tabs.value.some((item) => item.key === HOME_PATH)) {
        tabs.value = [HOME_TAB, ...tabs.value]
      }
      return
    }
    if (tabs.value.some((item) => item.key === tab.key)) {
      return
    }
    tabs.value = [...tabs.value, { ...tab, affix: false }]
  }

  /** 关闭页签。固定页签忽略（它的关闭按钮本来也不显示，这里再挡一次）。 */
  function closeTab(key: string): void {
    const target = tabs.value.find((item) => item.key === key)
    if (!target || target.affix) {
      return
    }
    tabs.value = tabs.value.filter((item) => item.key !== key)
  }

  /** 关闭其它页签（保留固定页签与指定页签）。 */
  function closeOthers(key: string): void {
    tabs.value = tabs.value.filter((item) => item.affix || item.key === key)
  }

  /**
   * 关闭指定页签左侧的所有页签（固定页签保留）。
   *
   * <p>「位置」必须在执行前先算好：findIndex 是对<b>旧数组</b>做的，
   * 边写边算会拿到移动中的下标。
   */
  function closeLeft(key: string): void {
    const index = tabs.value.findIndex((item) => item.key === key)
    if (index <= 0) {
      return
    }
    tabs.value = tabs.value.filter((item, i) => i >= index || item.affix)
  }

  /** 关闭指定页签右侧的所有页签（固定页签保留）。 */
  function closeRight(key: string): void {
    const index = tabs.value.findIndex((item) => item.key === key)
    if (index < 0) {
      return
    }
    tabs.value = tabs.value.filter((item, i) => i <= index || item.affix)
  }

  /** 只保留固定页签。 */
  function closeAll(): void {
    tabs.value = tabs.value.filter((item) => item.affix)
  }

  /**
   * 重置。退出登录、或权限变更导致菜单重建时调用；回到「仅首页固定页签」的初始态。
   *
   * <p>必须<b>同时清掉持久化</b>：否则下一个登录的用户会从存储里恢复出上一个
   * 用户的工作现场（还可能包含他无权访问的页面）。这类"换账号后没清干净"
   * 的问题刷新一下就好了，极具迷惑性。
   */
  function reset(): void {
    tabs.value = [HOME_TAB]
    try {
      localStorage.removeItem(TABS_STORAGE_KEY)
    } catch {
      // 存储不可用时无需处理：内存状态已重置
    }
  }

  return {
    tabs,
    openTab,
    closeTab,
    closeOthers,
    closeLeft,
    closeRight,
    closeAll,
    reset
  }
})
