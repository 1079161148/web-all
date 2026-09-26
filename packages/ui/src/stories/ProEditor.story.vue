<script setup lang="ts">
import { ref } from 'vue'
import { NTag } from 'naive-ui'
import ProEditor from '../components/pro/ProEditor.vue'
import type { ProEditorUploadResult } from '../index'

/**
 * ProEditor 的 story。
 *
 * <p>⚠️ 这里的 {@code uploadImage} 是**假的**：它用 localStorage 里的一张
 * data-URL 图片来演示完整链路（选中 → 上传 → 插入），不发起任何请求。
 * 真实实现里应当走 {@code registry/upload.ts} 的鉴权头 + 业务接口。
 */

const content = ref(
  '<h3>发布说明</h3><p>这是一段<strong>富文本</strong>内容，用于验证编辑器渲染。</p>'
)
const readonlyContent = ref('<p>只读态的内容应该可见但不可编辑。</p>')
const simpleMode = ref('<p>精简工具栏：只有常用格式。</p>')
const lastError = ref('')

/**
 * 假的"上传"：把一张内联 SVG 转成 data-URL 当作服务端返回的地址。
 *
 * <p>刻意带 600ms 延迟 —— 没有延迟就看不到上传中的等待，
 * 而"上传期间用户继续输入"正是这个流程最容易出问题的时段。
 */
async function fakeUpload(file: File): Promise<ProEditorUploadResult> {
  await new Promise((resolve) => setTimeout(resolve, 600))
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="160" height="90">
    <rect width="160" height="90" fill="#2563eb"/>
    <text x="80" y="50" font-size="13" fill="#fff" text-anchor="middle">${file.name}</text>
  </svg>`
  return {
    url: `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`,
    alt: file.name
  }
}

/** 演示失败路径：上传被拒绝时不插入任何节点。 */
async function failingUpload(): Promise<ProEditorUploadResult> {
  await new Promise((resolve) => setTimeout(resolve, 400))
  throw new Error('模拟的上传失败：文件类型不被允许')
}

function handleError(error: unknown): void {
  lastError.value = error instanceof Error ? error.message : String(error)
}
</script>

<template>
  <Story title="Pro 组件/ProEditor" :layout="{ type: 'grid', width: '100%' }">
    <Variant
      title="基础用法"
      doc="v-model 绑定 HTML。工具栏与编辑区样式来自内核自带的 15 KB CSS（漏引会得到一个完全没有排版的编辑器）。"
    >
      <ProEditor v-model:value="content" :height="300" />
    </Variant>

    <Variant
      title="图片上传（接上传动作）"
      doc="点工具栏的图片按钮选一张图：选中 → 600ms 模拟上传 → 插入。不传 uploadImage 时这个菜单根本不会出现 —— 与其给一个点了没反应的按钮，不如不显示。"
    >
      <ProEditor v-model:value="content" :height="300" :upload-image="fakeUpload" />
    </Variant>

    <Variant
      title="⚠️ 上传失败：不插入任何节点"
      doc="上传被拒绝时抛出错误，组件只把错误抛出去，**不留下一个指向不存在资源的图片节点** —— 坏图比"什么都没发生"更难排查。"
    >
      <n-space vertical :size="12">
        <ProEditor
          v-model:value="content"
          :height="260"
          :upload-image="failingUpload"
          @error="handleError"
        />
        <n-tag v-if="lastError" type="error" size="small">{{ lastError }}</n-tag>
        <n-tag v-else type="default" size="small">点图片按钮并选一张图，可看到失败被抛出</n-tag>
      </n-space>
    </Variant>

    <Variant title="只读态" doc="只读时工具栏仍然可见但不可用（内核行为），内容可选中复制。">
      <ProEditor v-model:value="readonlyContent" :height="240" read-only />
    </Variant>

    <Variant title="精简工具栏（simple）" doc="simple 模式只保留常用格式，适合评论、备注这类场景。">
      <ProEditor v-model:value="simpleMode" :height="240" mode="simple" />
    </Variant>

    <Variant
      title="⚠️ 安全边界：内容必须由服务端净化"
      doc="富文本是唯一会把用户输入当 HTML 再渲染出来的组件。前端过滤可以被绕过（直接调接口写入），所以真正的边界只能在服务端 —— 存储前必须再净化一次。组件不做也无法代替这件事。"
    >
      <n-alert type="warning" title="使用方须知">
        客户端的任何校验都能被绕过。请确保写入接口在落库前对 HTML 做净化，
        渲染第三方内容时也优先经服务端返回净化后的结果。
      </n-alert>
    </Variant>
  </Story>
</template>
