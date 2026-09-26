import type { App } from 'vue'
import formCreate from '@form-create/naive-ui'

/**
 * 动态表单内核适配层（@form-create/naive-ui）。
 *
 * <h3>为什么用 form-create 而不是自己在 n-form 上写 schema 渲染</h3>
 * 「根据配置生成表单项」听起来简单，真做起来要处理：
 * 嵌套结构（group / sub-form）、动态增减行、字段联动显隐、异步选项、
 * 校验依赖、subForm 的值同步、销毁重建时的状态保持……
 *
 * <p>这些正是 form-create 花了多年打磨的部分。自研一套薄渲染器，
 * 会在第一个"分组里放一个可动态增删的子表单"需求上暴露。
 * {@code ui-component-policy} 因此把「自研动态表单渲染引擎」列为红线。
 *
 * <h3>样式无需手动引入</h3>
 * form-create 的 dist 用 {@code vite-plugin-css-injected-by-js} 构建，
 * 样式在运行时自动注入 —— 这一点与 vxe 相反（vxe 必须手动 import），
 * 两者容易记混。若某天换成源码引入（非 dist），则需要补 CSS 引入。
 */

/** 注册 form-create（挂 `$formCreate` 并注册全局 `<form-create>` 组件）。 */
export function installFormCreate(app: App): void {
  app.use(formCreate)
}

/**
 * 命令式使用的工厂。
 *
 * <p>导出它是为了支持「不渲染组件、只要一个表单实例」的场景
 * （例如 ProModal 内部需要先拿到 fapi 再决定渲染什么）。
 * 常规用法仍是模板里的 `<form-create>`。
 */
export { formCreate }

export type { Api as FormCreateApi, Options as FormCreateOptions, Rule as FormCreateRule } from '@form-create/naive-ui'
