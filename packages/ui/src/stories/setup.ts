import { defineSetupVue3 } from '@histoire/plugin-vue'
import { ref, type Ref } from 'vue'
import { installAdminUi } from '../plugin'
import { setDictResolver, type DictOption } from '../registry/dict'
import { setPermissionResolver } from '../registry/permission'
import { setUploadAuthResolver } from '../registry/upload'

/**
 * Histoire 全局接线。
 *
 * <h3>为什么 story 必须接线，而不能"裸跑"</h3>
 * 本组件库的三个能力都刻意<b>不认识业务</b>（UI 库要能脱离本项目复用）：
 * <ul>
 *   <li>字典从哪来 —— {@code setDictResolver}</li>
 *   <li>权限怎么判 —— {@code setPermissionResolver}</li>
 *   <li>上传鉴权头怎么带 —— {@code setUploadAuthResolver}</li>
 * </ul>
 * 不接线时它们会<b>静默降级</b>：字典列显示码值、权限按钮全部放行。
 * 于是 story 里看到的是一个<b>和线上不一样</b>的组件 ——
 * 这会给出错误的信心，比没有 story 更糟。
 *
 * <h3>为什么用固定的假数据而不是 mock 接口</h3>
 * story 的价值在于"改一个 props 立刻看到结果"，要求它先起后端会毁掉这个循环。
 * 固定假数据还带来一个额外好处：<b>渲染结果完全确定</b>，
 * 将来接视觉回归时不会因数据抖动产生假失败。
 */

/** 假字典。刻意包含一个 {@code cssClass: 'danger'} —— 用来验证语义映射。 */
const FAKE_DICTS: Record<string, DictOption[]> = {
  sys_user_status: [
    { label: '正常', value: 'ACTIVE', cssClass: 'success', isDefault: true },
    { label: '停用', value: 'DISABLED', cssClass: 'info' },
    // ⚠️ danger 不是 Naive 的取值（Naive 用 error）。
    // 这里保留 danger 是为了让 story 能覆盖"词表映射"这条路径
    { label: '锁定', value: 'LOCKED', cssClass: 'danger' }
  ],
  sys_user_sex: [
    { label: '未知', value: '0', cssClass: 'default' },
    { label: '男', value: '1', cssClass: 'primary' },
    { label: '女', value: '2', cssClass: 'warning' }
  ],
  sys_yes_no: [
    { label: '是', value: '1', cssClass: 'success' },
    { label: '否', value: '0', cssClass: 'default' }
  ],
  // 刻意保留一个"未配置某码值"的情况，用于覆盖 DictTag 的未命中态
  sys_menu_type: [
    { label: '目录', value: 'DIR', cssClass: 'primary' },
    { label: '菜单', value: 'MENU', cssClass: 'info' }
  ]
}

/**
 * 缓存 Ref。
 *
 * <p>必须缓存：`getDictOptions` 的返回值会被组件用作响应式依赖，
 * 若每次调用都新建一个 Ref，同一字典在不同组件里就是**不同的依赖源**，
 * 而且每次重渲染都产生新 Ref，会让 computed 反复失效。
 * 这也是真实应用里 `useDict` 必须做缓存的原因。
 */
const dictRefs = new Map<string, Ref<DictOption[]>>()

function dictRef(type: string): Ref<DictOption[]> {
  let cached = dictRefs.get(type)
  if (!cached) {
    cached = ref(FAKE_DICTS[type] ?? [])
    dictRefs.set(type, cached)
  }
  return cached
}

/**
 * 默认授予全部权限。
 *
 * <p>理由：story 的第一步是"看到组件正常工作"。
 * 若默认不授权，打开工作台会看到一堆隐藏的按钮和降级的字段，
 * 分不清是权限拦的还是组件坏了。
 * 「无权限长什么样」由 {@code AuthButton.story.vue} 用显式变体覆盖 ——
 * <b>默认态要正常，异常态要显式构造。</b>
 */
let granted: (code: string) => boolean = () => true

/** 供 story 临时切换权限（AuthButton 的 story 会用）。 */
export function setStoryPermission(predicate: (code: string) => boolean): void {
  granted = predicate
}

/** 供 story 恢复默认（全放行）。 */
export function resetStoryPermission(): void {
  granted = () => true
}

export const setupVue3 = defineSetupVue3(({ app }) => {
  // 注册 Naive（vxe / form-create 由 ProTable / ProForm 自身按需加载，
  // 因此 story 第一次打开表格时会有一次内核加载 —— 这就是线上的真实行为）
  installAdminUi(app)

  setDictResolver(dictRef)
  setPermissionResolver((code) => granted(code))
  // 上传在 story 里不会真的发出请求（没有可用后端），
  // 但注入空头仍然必要：否则控制台会打印一次"未注入鉴权"的警告，
  // 让人误以为 story 配置有问题
  setUploadAuthResolver(() => ({}))
})
