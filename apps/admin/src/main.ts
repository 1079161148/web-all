import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { installAdminUi } from '@admin/ui'
import App from './App.vue'
import router from './router'
import { setupPermissionDirective } from './directives/permission'

/**
 * 应用启动入口。
 *
 * <p>注意这里<b>没有</b>直接引入 `naive-ui` —— UI 库的注册被封装在
 * `@admin/ui` 的 {@link installAdminUi} 中（设计文档 §11.2 的隔离要求）。
 * 业务代码全程看不到第三方 UI 库。
 *
 * <h3>注册顺序无关紧要，但必须都在 mount 之前</h3>
 * 尤其是 `setupPermissionDirective`：若在 mount 之后注册，
 * 首个页面里的 `v-permission` 会因为指令未注册而被 Vue 当作普通属性忽略 ——
 * 结果是<b>无权限的按钮被渲染出来</b>，且不报错。
 * 这类"漏一个注册就静默失效"的初始化必须集中在同一处，便于审查。
 */
const app = createApp(App)

app.use(createPinia())
app.use(router)
installAdminUi(app)
setupPermissionDirective(app)

app.mount('#app')
