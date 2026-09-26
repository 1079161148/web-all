import { Editor, Toolbar } from '@wangeditor/editor-for-vue'

/**
 * ⚠️ 样式必须显式引入。
 *
 * <p>与 cron 的 Naive 预设同一类坑（见 `adapters/cron.ts`）：
 * wangEditor 的工具栏/编辑区样式在一个独立的 15 KB CSS 文件里，
 * 包<b>不会</b>自动带上它。漏引不报错，得到的是一个"能用但完全没有排版"的编辑器。
 */
import '@wangeditor/editor/dist/css/style.css'

/**
 * 富文本编辑器适配层（内核：wangEditor 5）。
 *
 * <h3>⚠️ 版本必须锁 5.x —— `latest` 是 Vue 2 版</h3>
 * <pre>
 *   @wangeditor/editor-for-vue  dist-tags:
 *     { last: '5.1.0', latest: '1.0.2', next: '5.1.12' }
 * </pre>
 * `latest` 指向的 1.0.2 是 <b>Vue 2</b> 版本；Vue 3 版本发布在 `next` 标签下。
 * 直接按"最新版"安装会静默装上 Vue 2 版 —— 不报错、组件渲染为空。
 * 本项目在 package.json 里锁的是 <b>5.x</b>，这个注释是防止有人"顺手升级到最新"。
 *
 * <h3>为什么不做成"上传也在这里实现"</h3>
 * 图片/附件要存到哪、走哪个接口、路径怎么拼，全是业务决策。
 * 本包只提供<b>注入点</b>（见 `registry/upload.ts`）与调用时机，
 * 上传动作由应用传入。这与字典/权限的处理方式一致。
 */
export { Editor, Toolbar }
