<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, type Component } from 'vue'
import { NAlert, NSpin } from 'naive-ui'
import type { IDomEditor, IToolbarConfig } from '@wangeditor/editor'
import { ensureEditor } from '../../adapters/lazy'
import type { ProEditorUploadHandler } from '../../types'

/**
 * 富文本编辑器（内核：wangEditor 5）。
 *
 * <pre>{@code
 * <ProEditor v-model:value="form.content" :upload-image="uploadImage" />
 * }</pre>
 *
 * <h3>⚠️ 「服务端净化」这句话必须在界面上说清</h3>
 * 富文本是<b>唯一会把用户输入当作 HTML 再渲染出来</b>的组件。
 * 前端能做的是配置工具栏、过滤明显危险的标签，
 * 但<b>真正的安全边界只能在服务端</b>：存储前必须再净化一次。
 *
 * <p>原因是客户端的任何校验都能被绕过（直接调接口写入）。
 * 若只做前端过滤就以为安全，会在"防御看起来已经做了"的地方留一个洞 ——
 * 这类洞比完全没做更难被发现。因此请把这一点写进使用方文档，
 * 而不是仅在代码注释里提一句。
 *
 * <h3>⚠️ 两个必须做对、否则泄漏或卡顿的点</h3>
 * <ol>
 *   <li><b>编辑器实例用 {@code shallowRef}</b>：它的内部结构非常庞大，
 *       普通 {@code ref} 会为它建立深度响应式代理，输入时明显卡顿</li>
 *   <li><b>卸载时必须 {@code destroy()}</b>：wangEditor 挂着 DOM 监听与选区管理，
 *       不销毁会持续占用内存 —— 在"打开编辑抽屉 → 关闭 → 再打开"的循环里越积越多</li>
 * </ol>
 *
 * <h3>上传为什么是"传入动作"而不是内置实现</h3>
 * 存到哪、走哪个接口、租户路径怎么拼，全是业务决策。
 * 组件只在内核回调的那一瞬间把它转交给应用（见 {@link ProEditorUploadHandler}），
 * 应用侧再从 {@code registry/upload.ts} 取鉴权头 ——
 * 于是"上传鉴权"在全项目只有一处定义。
 */

const props = withDefaults(
  defineProps<{
    /** HTML 内容（v-model:value）。 */
    value?: string
    /** 编辑器模式。`simple` 是精简工具栏（无表格/代码块等）。 */
    mode?: 'default' | 'simple'
    /** 编辑区高度。数字按 px 处理。 */
    height?: number | string
    placeholder?: string
    /** 只读。 */
    readOnly?: boolean
    /**
     * 图片上传动作。**不传则不启用图片上传菜单** ——
     * 与其给一个点了没反应的按钮，不如不显示它。
     */
    uploadImage?: ProEditorUploadHandler
    /** 工具栏配置（原样透传给内核）。 */
    toolbarConfig?: Partial<IToolbarConfig>
    /** 编辑器配置（逃生舱，原样透传给内核）。 */
    editorConfig?: Record<string, unknown>
  }>(),
  {
    value: '',
    mode: 'default',
    height: 360,
    placeholder: '请输入内容…',
    readOnly: false,
    toolbarConfig: undefined,
    editorConfig: undefined
  }
)

const emit = defineEmits<{
  (e: 'update:value', html: string): void
  /** 编辑器创建完成，抛出实例（供应用绑定自定义菜单等）。 */
  (e: 'created', editor: IDomEditor): void
  (e: 'error', error: unknown): void
}>()

const editorRef = shallowRef<IDomEditor | null>(null)

const editorComponent = shallowRef<Component | null>(null)
const toolbarComponent = shallowRef<Component | null>(null)

const kernelLoading = ref(false)
const kernelError = ref<unknown>(null)

const resolvedHeight = computed(() =>
  typeof props.height === 'number' ? `${props.height}px` : props.height
)

/** 与内核双向绑定的内容。 */
const html = computed({
  get: () => props.value,
  set: (next: string) => emit('update:value', next)
})

/**
 * 编辑器配置。
 *
 * <p>{@code MENU_CONF.uploadImage.customUpload} 是内核留给"自定义上传"的口子：
 * 它把选中的文件交给我们，我们转交应用，拿到地址后再调 {@code insertFn} 落进文档。
 * 顺序不能反 —— 先插入再上传会得到一个短暂的、指向不存在资源的图片节点。
 */
const resolvedEditorConfig = computed(() => {
  const base: Record<string, unknown> = {
    placeholder: props.placeholder,
    readOnly: props.readOnly,
    ...props.editorConfig
  }

  if (props.uploadImage) {
    base.MENU_CONF = {
      ...(base.MENU_CONF as Record<string, unknown> | undefined),
      uploadImage: {
        customUpload: async (
          file: File,
          insertFn: (url: string, alt: string, href: string) => void
        ): Promise<void> => {
          try {
            const result = await props.uploadImage!(file)
            insertFn(result.url, result.alt ?? file.name, result.href ?? result.url)
          } catch (error) {
            // 上传失败不插入任何节点：留下一个坏图比"什么都没发生"更难排查
            emit('error', error)
          }
        }
      }
    }
  }

  return base
})

/**
 * 工具栏配置：默认剔除"上传视频"。
 *
 * <p>与图片不同，组件<b>不提供</b>视频上传的注入口 —— 视频这类大文件应走
 * 对象存储直传（见 ProUpload 的说明），塞进表单附件接口是反模式。
 * 内核在没有 {@code MENU_CONF.uploadVideo} 时该菜单点了必然失败，
 * 而"留一个点了没反应的按钮"比不显示它更糟（与不传图片处理器就隐藏图片上传同理）。
 * 需要时用 {@code toolbarConfig} 覆盖即可。
 */
const resolvedToolbarConfig = computed<Partial<IToolbarConfig>>(() => ({
  excludeKeys: ['uploadVideo'],
  ...props.toolbarConfig
}))

async function loadKernel(): Promise<void> {
  kernelLoading.value = true
  try {
    const mod = await ensureEditor()
    editorComponent.value = mod.Editor as Component
    toolbarComponent.value = mod.Toolbar as Component
  } catch (error) {
    kernelError.value = error
    emit('error', error)
  } finally {
    kernelLoading.value = false
  }
}

// 编辑器一挂载就需要内核（不像 ProChart 可以等 option），因此直接触发
void loadKernel()

function handleCreated(editor: IDomEditor): void {
  editorRef.value = editor
  emit('created', editor)
}

onBeforeUnmount(() => {
  // ⚠️ 必须销毁：wangEditor 挂着 DOM 监听与选区管理
  editorRef.value?.destroy()
  editorRef.value = null
})
</script>

<template>
  <div class="pro-editor" :style="{ height: resolvedHeight }">
    <div v-if="kernelLoading || !editorComponent" class="pro-editor__center">
      <n-spin />
    </div>

    <n-alert v-else-if="kernelError" type="error" class="pro-editor__center">
      富文本编辑器加载失败，请刷新重试。
    </n-alert>

    <template v-else>
      <component
        :is="toolbarComponent"
        class="pro-editor__toolbar"
        :editor="editorRef"
        :default-config="resolvedToolbarConfig"
        :mode="mode"
      />
      <component
        :is="editorComponent"
        v-model="html"
        class="pro-editor__body"
        :default-config="resolvedEditorConfig"
        :mode="mode"
        @onCreated="handleCreated"
      />
    </template>
  </div>
</template>

<style scoped>
/*
  结构与尺寸，样式引用 Token（ui-component-policy 强行约束第 5 条）。
  注意编辑区自身的排版由内核的 15 KB CSS 提供（见 adapters/editor.ts），
  本文件不重复定义它 —— 否则会与内核样式互相覆盖。
*/
.pro-editor {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--wa-border);
  border-radius: var(--wa-radius-sm);
  overflow: hidden;
}

.pro-editor__toolbar {
  flex: none;
  border-bottom: 1px solid var(--wa-border);
}

/* min-height: 0 是关键：flex 子项默认不会收缩，少了它编辑区会把容器撑破 */
.pro-editor__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.pro-editor__center {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 1;
}
</style>
