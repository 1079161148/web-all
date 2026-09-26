import { computed, ref, type ComputedRef, type Ref } from 'vue'
import { useRouter } from 'vue-router'
import type { ProCommandItem, ProCommandSelectPayload } from '@admin/ui'
import { feedback } from '@admin/ui'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { usePermissionStore, type MenuNode } from '@/stores/permission'
import { useTabsStore } from '@/stores/tabs'

/**
 * 命令面板的命令来源与动作分发。
 *
 * <h3>命令从哪来：菜单 + 少量应用动作</h3>
 * 主体是<b>菜单</b>，因为它已经是「用户能去的地方」的权威列表 ——
 * 而这一层不需要再做权限过滤：菜单树来自 {@code /auth/menus}，
 * 后端已经按当前用户的权限裁剪过。
 *
 * <p>在客户端再判一次是<b>重复且危险</b>的：两份判断一旦不一致，
 * 就会出现"命令面板里有这一条、但点进去 403"，
 * 或者"有权限却搜不到"——两种都很难归因。
 * <b>服务端是权限的唯一裁决者，前端不重建这套规则。</b>
 *
 * <h3>⚠️ 动作键用前缀区分，而不是靠调用方判断</h3>
 * 菜单命令的 key 就是路由路径（它同时是标识与导航目标，见 ProLayoutMenu 的说明），
 * 而应用动作没有路径。若两者混在同一个命名空间里，
 * 分发时只能靠"看起来像不像路径"来猜 —— 这迟早会错。
 * 因此动作统一加 {@code action:} 前缀。
 */

/** 应用动作的 key 前缀。 */
const ACTION_PREFIX = 'action:'

/**
 * 最近使用命令的本地存储键与容量。
 *
 * <p>刻意放在 <b>localStorage 而不是服务端偏好</b>：命令面板是纯"效率糖"，
 * 丢了没有任何损失 —— 不值得为它付一次网络往返和一个偏好接口的读写。
 * 服务端偏好（见 userPreference 模块）留给"丢了会疼"的表格列布局这类配置。
 * 两条存储通道的判据是<b>损失大小</b>，不是数据大小。
 */
const RECENT_KEY = 'command-recent'
const RECENT_LIMIT = 8

function loadRecent(): string[] {
  try {
    const raw = localStorage.getItem(RECENT_KEY)
    const parsed: unknown = raw === null ? [] : JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string') : []
  } catch {
    // 存储损坏/不可用（隐私模式）：退化为无最近使用，功能不受损
    return []
  }
}

function saveRecent(keys: string[]): void {
  try {
    localStorage.setItem(RECENT_KEY, JSON.stringify(keys))
  } catch {
    // 同上：尽力而为
  }
}

/** 把菜单树压平为叶子命令；分组取一级菜单的名字。 */
function collectMenuCommands(
  nodes: MenuNode[],
  group: string | undefined,
  out: ProCommandItem[]
): void {
  for (const node of nodes) {
    const currentGroup = group ?? node.label
    if (node.children.length > 0) {
      collectMenuCommands(node.children, currentGroup, out)
      continue
    }
    // 只把叶子放进命令面板：目录本身不是可跳转的页面，
    // 点它只会得到一个"什么都没有发生"的结果
    out.push({
      key: node.path,
      label: node.label,
      group: currentGroup,
      hint: '页面'
    })
  }
}

export function useCommands(): {
  commands: ComputedRef<ProCommandItem[]>
  /** 最近使用的命令 key（传给 ProCommand 的 recentKeys）。 */
  recentKeys: Ref<string[]>
  runCommand: (payload: ProCommandSelectPayload) => void
  logout: () => void
} {
  const router = useRouter()
  const appStore = useAppStore()
  const authStore = useAuthStore()
  const permissionStore = usePermissionStore()
  const tabsStore = useTabsStore()

  /**
   * 退出登录。
   *
   * <p>这里刻意<b>只有一份实现</b>（顶栏按钮与命令面板都调它）。
   * 它必须同时做三件事，少任何一件都会留下一个"换个账号就露出来"的问题：
   * <ol>
   *   <li>{@code authStore.logout()} —— 清会话</li>
   *   <li>{@code permissionStore.reset()} —— 否则下一个用户在路由守卫里
   *       看到 {@code loaded === true}，直接沿用上一个用户的菜单与按钮权限</li>
   *   <li>{@code tabsStore.reset()} —— 否则标签栏里留着上一个用户访问过的页面，
   *       而那些页签可能指向他无权访问的路由，<b>点进去就是 403</b></li>
   * </ol>
   * 这类问题的共同特征是"刷新一次就好了"，因此极具迷惑性 ——
   * 把它放在一个地方、且写清为什么，比散在各处各自记得要有用得多。
   *
   * <p>退出是<b>不可逆且中断性</b>的操作（未保存的表单内容会随页面跳转丢失），
   * 因此先弹二次确认，用户确认后才执行清理。
   */
  async function logout(): Promise<void> {
    const confirmed = await feedback.confirm({
      title: '退出登录',
      content: '退出后需要重新登录才能继续操作，确定退出吗？',
      positiveText: '退出',
      negativeText: '取消'
    })
    if (!confirmed) {
      return
    }

    // 退出时所在页面写进 ?redirect，重新登录后直达原页面
    // （LoginView 消费该参数；守卫②③④对未登录访问也会带同样的参数，两处语义一致）
    const current = router.currentRoute.value
    const redirect = current.path === '/login' ? undefined : current.fullPath

    // 先通知服务端注销会话（吊销访问令牌 + 刷新令牌）。
    // ⚠️ 尽力而为：失败（如网络已断）不阻断本地清理 ——
    // 用户此时想走，把人拦在"退出失败"的提示上是本末倒置；
    // 且即使服务端调用失败，访问令牌最多再活 30 分钟（会话 TTL 的下限），
    // 刷新令牌仍在服务端挂着，风险有界
    try {
      const { logout } = await import('@admin/api')
      await logout()
    } catch {
      // 忽略：见上
    }

    authStore.logout()
    permissionStore.reset()
    tabsStore.reset()
    await router.push(redirect ? { path: '/login', query: { redirect } } : '/login')

    feedback.success('已退出登录')
  }

  const commands = computed<ProCommandItem[]>(() => {
    const out: ProCommandItem[] = []
    collectMenuCommands(permissionStore.menuTree, undefined, out)

    out.push(
      {
        key: `${ACTION_PREFIX}theme`,
        label: appStore.themeMode === 'light' ? '切换到暗色主题' : '切换到亮色主题',
        group: '外观'
      },
      {
        key: `${ACTION_PREFIX}logout`,
        label: '退出登录',
        group: '账户',
        hint: '会清除当前会话'
      }
    )

    return out
  })

  const recentKeys = ref(loadRecent())

  function runCommand(payload: ProCommandSelectPayload): void {
    const { key } = payload

    // 记录最近使用：去重置顶 + 容量截断。失败（存储不可用）不影响命令执行
    recentKeys.value = [key, ...recentKeys.value.filter((k) => k !== key)].slice(0, RECENT_LIMIT)
    saveRecent(recentKeys.value)

    // 非动作键即菜单命令：key 就是路径，直接导航（ProLayoutMenu 的约定）
    if (!key.startsWith(ACTION_PREFIX)) {
      void router.push(key)
      return
    }

    switch (key) {
      case `${ACTION_PREFIX}theme`:
        appStore.toggleTheme()
        break
      case `${ACTION_PREFIX}logout`:
        logout()
        break
      default:
        // 未知动作：静默忽略而不是抛错。
        // 命令列表是数据驱动的，多一条未实现的命令不该把界面弄崩
        break
    }
  }

  return { commands, recentKeys, runCommand, logout }
}
