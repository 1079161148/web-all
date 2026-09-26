import { setApiErrorHandler } from '@admin/api'
// 错误钩子在首屏安装（要早于第一个请求），因此走窄入口
import { feedback } from '@admin/ui/core'
import type { Router } from 'vue-router'
import { usePermissionStore } from '@/stores/permission'
import { clearDictCache } from '@/composables/useDict'

/**
 * 全局 API 错误拦截。
 *
 * <h3>它消灭的是「每个请求里手动判断」这件事</h3>
 * 没有它时，每个页面的每个调用点都要想一遍"失败了怎么办"，
 * 实际结果必然是三种处理并存：有的页面跳登录、有的只弹错、
 * 有的什么都不做（用户视角是"点了没反应"）。
 *
 * <p>现在所有 API 错误都汇聚到 {@code request} 层的钩子里（见
 * {@code packages/api/src/client.ts} 的 {@code setApiErrorHandler}），
 * 本文件是那个钩子的实现 —— <b>全应用只有这一处判断 HTTP 语义</b>。
 *
 * <h3>⚠️ 401 必须做「并发去重」</h3>
 * 一个列表页常同时发多个请求（菜单 + 权限 + 列表 + 字典）。
 * 令牌过期时它们会<b>一起</b>返回 401，于是：
 * <ul>
 *   <li>弹 4 个内容相同的"登录已失效"提示，叠成一摞</li>
 *   <li>触发 4 次 {@code router.push('/login')} —— 会往历史里塞多次跳转，
 *       用户点"返回"时要在登录页之间来回退</li>
 * </ul>
 * 因此这里用 {@code handling401} 做闸门：第一个 401 开始处理，
 * 处理完成（或 2 秒后）才允许下一次。
 */
export function setupApiErrorHandler(router: Router): void {
  setApiErrorHandler((error) => {
    if (error.isUnauthenticated()) {
      handleUnauthenticated(router)
      return
    }

    if (error.isForbidden()) {
      // 提示"无权限"而不是后端原文：后端可能返回 "Access Denied"，
      // 对使用者没有意义；而"没有权限执行该操作"能让他知道该找谁
      feedback.error('没有权限执行该操作')
      return
    }

    // 其余错误（含业务码错误、网络失败）直接展示后端文案 ——
    // 后端的错误消息是面向使用者的（如"用户名已存在"），比前端重写更准确
    feedback.error(error.message)
  })
}

/** 401 处理闸门。 */
let handling401 = false

function handleUnauthenticated(router: Router): void {
  if (handling401) {
    return
  }
  handling401 = true

  clearSession()

  void router
    .push({
      path: '/login',
      // 带上来源，登录后能回到原来想去的页面 ——
      // 少了它用户每次被踢都要自己重新点一遍菜单
      query: { redirect: router.currentRoute.value.fullPath }
    })
    .finally(() => {
      // 延迟解锁而不是立即：跳转完成时其它并发请求的 401 可能还在路上，
      // 立即解锁会让它们再触发一轮跳转
      window.setTimeout(() => {
        handling401 = false
      }, 2000)
    })
}

/**
 * 清理会话痕迹。
 *
 * <p>三处都要清，漏一个就会出现"上一个用户的残留"：
 * <ul>
 *   <li>{@code sessionStorage} 里的令牌与租户 —— 下一次请求会带着过期令牌重试</li>
 *   <li>权限 store —— 换账号登录后菜单仍显示上一个用户的</li>
 *   <li><b>字典缓存</b> —— 字典支持租户级覆盖，不清会让新租户看到旧租户的字典值。
 *       这一条最容易漏，且表现得像"数据串了"</li>
 * </ul>
 */
export function clearSession(): void {
  try {
    sessionStorage.removeItem('accessToken')
    sessionStorage.removeItem('tenantId')
  } catch {
    // sessionStorage 在隐私模式下可能不可用，不影响主流程
  }
  usePermissionStore().reset()
  clearDictCache()
}
