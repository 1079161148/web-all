import type { App, Directive, DirectiveBinding } from 'vue'
import { usePermissionStore } from '@/stores/permission'

/**
 * 按钮级权限指令：`v-permission="'iam:user:add'"`。
 *
 * <h3>为什么用「移除节点」而不是 `v-if`</h3>
 * 设计文档 §7.2 明确要求移除节点，理由是<b>更难绕过</b>：
 * <ul>
 *   <li>{@code v-if} 在组件更新时可能被重新求值，某些边界（如被 keep-alive 缓存的页面
 *       在权限变更后重新激活）会重新渲染出按钮</li>
 *   <li>移除节点后，元素在 DOM 中根本不存在，无法通过开发者工具"改个 class 让它显示" ——
 *       虽然用户仍可以改 JS，但那已经属于主动攻击，会落到后端的 {@code @PreAuthorize} 上</li>
 * </ul>
 *
 * <h3>⚠️ 再强调一次：这不是安全机制</h3>
 * 隐藏按钮只避免"用户点了必然失败"的糟糕体验。
 * 真正的边界在后端 —— 任何人都可以直接调接口。
 * <b>把前端权限当安全措施是严重的误判。</b>
 *
 * <h3>两种用法</h3>
 * <pre>{@code
 * <n-button v-permission="'iam:user:add'">新增</n-button>
 * <n-button v-permission="['iam:user:add', 'iam:user:update']">批量操作</n-button>  <!-- 任一命中即可 -->
 * }</pre>
 *
 * <h3>关于 bind 时机</h3>
 * 用 {@code mounted} 而非 {@code created}：需要真实 DOM 节点才能安全移除。
 * 之所以能在挂载后立刻判断权限，是因为路由守卫保证进入任何业务页之前
 * 权限已经加载完毕（`permissionStore.loaded === true`）。
 * <b>这个前提一旦被破坏（比如某个页面绕过守卫渲染），本指令会因权限集合为空
 * 而把按钮全部移除</b> —— 表现为"页面所有按钮消失"，
 * 这是 fail-closed 方向上的合理后果，也便于发现问题。
 */
export const permissionDirective: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    if (hasPermission(binding)) {
      return
    }
    // 用 remove 而不是 display:none —— 后者仍留在 DOM 中，可被轻易恢复显示
    el.parentNode?.removeChild(el)
  }
}

function hasPermission(binding: DirectiveBinding<string | string[]>): boolean {
  const store = usePermissionStore()
  const value = binding.value

  if (!value) {
    // 没有传权限码：视为"不需要权限"，保留元素。
    // 这里刻意不抛异常 —— 指令写错就抛错会让整个页面白屏，代价过大；
    // 而"保留元素"最多是该隐藏的没隐藏（后端仍会拦），风险可控。
    return true
  }

  if (Array.isArray(value)) {
    return store.hasAnyPermission(value)
  }
  return store.hasPermission(value)
}

/** 注册指令。 */
export function setupPermissionDirective(app: App): void {
  app.directive('permission', permissionDirective)
}
