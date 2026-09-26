import { createApp } from 'vue'
import { createPinia } from 'pinia'
// ⚠️ 从窄入口 '@admin/ui/core' 引入，而不是 barrel '@admin/ui'：
// 首屏可达的文件只要引用 barrel 一次，整个 barrel 就会被抽成共享 chunk
// 并写进 index.html 的预加载列表（实测 151 KB gzip，占首屏 45%）。
// 见 packages/ui/src/core.ts 的说明。
import {
  installAdminUi,
  setDictResolver,
  setPermissionResolver,
  setUploadAuthResolver,
  syncVxeTheme
} from '@admin/ui/core'
import App from './App.vue'
import router from './router'
import { setupPermissionDirective } from './directives/permission'
import { setupApiErrorHandler } from './api/error-handler'
import { usePermissionStore } from './stores/permission'
import { dictOptions } from './composables/useDict'
import { setupIcons } from './icons'

/**
 * 应用启动入口。
 *
 * <p>注意这里<b>没有</b>直接引入 `naive-ui` / `vxe-table` / `form-create` ——
 * 三个内核的注册都被封装在 `@admin/ui` 的 {@link installAdminUi} 中
 * （设计文档 §11.2 的隔离要求）。业务代码全程看不到第三方 UI 库。
 *
 * <h3>启动顺序（有依赖，不能随意调换）</h3>
 * <pre>
 *   1. pinia            —— store 依赖它
 *   2. router           —— store 在守卫里被调用
 *   3. installAdminUi   —— 注册三个 UI 内核
 *   4. setupPermissionDirective —— 指令注册
 *   5. 两个 setXxxResolver —— 把"数据从哪来"注入组件库
 * </pre>
 *
 * <p>尤其是 `setupPermissionDirective`：若在 mount 之后注册，
 * 首个页面里的 `v-permission` 会因为指令未注册而被 Vue 当作普通属性忽略 ——
 * 结果是<b>无权限的按钮被渲染出来</b>，且不报错。
 * 这类"漏一个注册就静默失效"的初始化必须集中在同一处，便于审查。
 *
 * <h3>两个 Resolver 是组件库与业务之间的唯一接线点</h3>
 * 组件库刻意不认识"字典接口"和"权限 store"（否则它就无法脱离本项目复用）。
 * 方向反过来：由应用把这两个能力注入进去。
 * <b>漏注入不会报错，只会降级</b> ——
 * 字典列显示码值、权限按钮全部显示，因此这段代码值得在主入口显式存在。
 */
const app = createApp(App)

const pinia = createPinia()
app.use(pinia)
app.use(router)
installAdminUi(app)
setupPermissionDirective(app)

/*
  接线一：字典数据来源。
  dictOptions 返回 Ref，因此字典异步到达后表格会自动重渲染 ——
  若这里传的是普通数组，字典列会永久空白（刷新才正常），属于最难复现的一类问题。
*/
setDictResolver(dictOptions)

/*
  接线二：权限判定。
  store 的 hasPermission 已处理超管通配符 '*'，组件库不必知道这个规则。
*/
const permissionStore = usePermissionStore(pinia)
setPermissionResolver((code) => permissionStore.hasPermission(code))

/*
  接线三：上传鉴权。
  上传走 n-upload 自己的 XHR，不经过 request 层，因此令牌与租户头
  必须单独注入 —— 漏了它的表现是"上传成功但文件进了错误的租户目录"。
*/
setUploadAuthResolver(() => {
  const headers: Record<string, string> = {}
  try {
    const token = sessionStorage.getItem('accessToken')
    const tenantId = sessionStorage.getItem('tenantId')
    if (token) {
      headers.Authorization = `Bearer ${token}`
    }
    if (tenantId) {
      headers['X-Tenant-Id'] = tenantId
    }
  } catch {
    // sessionStorage 不可用（隐私模式）时返回空头，由服务端拒绝
  }
  return headers
})

/*
  接线四：全局错误拦截。
  所有 API 错误汇聚到这一处判断 HTTP 语义（401 跳登录 / 403 提示无权限），
  页面从此不必各写一遍 —— 这是"避免每个请求里手动判断"的落点。
*/
setupApiErrorHandler(router)

/*
  接线五：vxe 主题桥接。
  vxe 有独立的主题系统，与 Naive 的 themeOverrides 互不感知；
  不同步会出现「页面暗色、表格亮色」。这里做首次同步，
  主题切换时需再次调用（见 stores/app.ts 的切换逻辑）。
*/
syncVxeTheme(document.documentElement.dataset.theme === 'dark')

/*
  接线六：图标集。
  @vicons/ionicons5 全量注册到 ProIcon 注册表 —— 后端 menu.icon 字段存的
  就是这套组件名，注册后侧栏与菜单编辑器里的图标都能解析出来。
  漏了它：侧栏图标全部降级为首字形、编辑器图标选择器为空。
*/
setupIcons()

/*
  接线七：前端可观测性（懒加载）。
  采集器是纯后台能力 —— 动态 import 让它进异步 chunk 而非首屏
  （首屏预算棘轮用它换回了 ~2KB）。代价是极小的初始化窗口：
  加载完成前的报错只被 console 捕获，不被上报 —— 可接受。
  errorHandler 同样在 mount 前设置；接管后控制台不再自动打印，
  这里显式 console.error 保留开发时的可见性。
*/
app.config.errorHandler = (error, _instance, info) => {
  console.error('[vue]', error, info)
  void (async () => {
    const m = await import('./composables/observability')
    m.reportVueError(error, info)
  })()
}
void (async () => {
  const m = await import('./composables/observability')
  m.initObservability(router)
})()

app.mount('#app')
