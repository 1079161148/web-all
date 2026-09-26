<script setup lang="ts">
import { computed, ref } from 'vue'
import { NImage, NUpload, NUploadDragger } from 'naive-ui'
import type { UploadCustomRequestOptions, UploadFileInfo } from 'naive-ui'
import { feedback } from '../../adapters/feedback'
import { getUploadAuthHeaders } from '../../registry/upload'

/**
 * 文件 / 图片上传。
 *
 * <h3>它收敛掉的重复逻辑</h3>
 * 一个"能用的上传控件"至少要处理：上传地址、鉴权头、
 * 类型与大小校验、进度、回显、删除、多文件管理。
 * 这些在每个需要上传的页面里逐条实现，必然出现"这个页面限了 2MB、那个忘了限"。
 *
 * <h3>校验为什么必须在客户端做一遍</h3>
 * 服务端当然要再验一次（前端校验可被绕过），但客户端校验的价值在于
 * <b>避免让用户传完 50MB 才被拒绝</b>。两者职责不同，都要有。
 *
 * <h3>为什么用 Naive 的 n-upload 而不是 Uppy + tus</h3>
 * {@code ui-component-policy} 的选型矩阵写的是「分片上传 / 断点续传 → Uppy + tus」——
 * 那针对的是<b>大文件与弱网</b>场景。当前需求是普通表单附件（图片、
 * 文档，通常几 MB 以内），n-upload 完全覆盖，且少引入两个依赖与一套协议。
 *
 * <p>⚠️ 一旦出现"上传 500MB 的安装包"或"地铁里断网续传"这类需求，
 * 应切换到 Uppy + tus，而不是在 n-upload 上自己加切片逻辑 ——
 * 自研分片协议是明确红线（切片顺序、并发、校验、断点记录都有大量边界）。
 */
const props = withDefaults(
  defineProps<{
    /**
     * 上传地址（后端接口路径）。
     *
     * <p>显式传入：组件库不知道后端的上传接口在哪，也不该猜。
     * 写死一个约定路径会让"改接口"变成改组件库。
     *
     * <p>与 {@link request} 二选一：传了 {@code request} 时本项不再使用。
     */
    action?: string
    /**
     * 自定义上传动作（走 n-upload 的 custom-request）。
     *
     * <h3>为什么必须有这个逃生口</h3>
     * n-upload 的 {@code finish} 事件只给出 {@code UploadFileInfo}，
     * 而 Naive 的该类型<b>不包含服务端响应体</b>（2.45 版实测无 response 字段）。
     * 于是"上传后需要拿到后端返回的附件 ID"这类需求，用 action 模式根本做不到 ——
     * 除非去猜响应结构。
     *
     * <p>交给调用方自行传输（通常是走契约客户端，自带鉴权头与错误处理），
     * 并在自己的闭包里顺手把结果写进页面状态：职责清晰，也不依赖响应格式。
     */
    request?: (file: File) => Promise<unknown>
    /** 已上传的文件列表（v-model:fileList 用）。 */
    fileList?: UploadFileItem[]
    /** 允许多选。 */
    multiple?: boolean
    /** 最大文件数。 */
    maxCount?: number
    /** 单个文件大小上限（MB）。 */
    maxSizeMb?: number
    /**
     * 允许的扩展名（不含点），如 `['png','jpg']`。
     *
     * <p>用扩展名而不是 MIME：MIME 由浏览器给出，可以伪造，
     * 且同一格式在不同系统上的 MIME 写法并不一致（{@code image/jpg} vs {@code image/jpeg}）。
     * 扩展名是用户能直观理解的，报错信息也更容易写清楚。
     */
    accept?: string[]
    /** 是否图片模式（缩略图回显 + 点击放大）。 */
    image?: boolean
    /** 是否禁用。 */
    disabled?: boolean
    /** 上传栏提示文案。 */
    tip?: string
  }>(),
  {
    multiple: false,
    maxCount: 1,
    maxSizeMb: 10,
    image: false,
    disabled: false
  }
)

/**
 * 上传文件项。
 *
 * <p>直接复用 Naive 的 {@link UploadFileInfo} 而不再自定义一个"最小集"：
 * 自定义类型看起来更干净，但会在与 n-upload 交互的每一处产生类型不兼容
 * （实测：`fileList` / `on-finish` / `on-error` / `on-update:file-list` 四处全报错），
 * 需要一连串断言才能抹平。**在"透传型"组件里，复用内核类型是更省事的正确做法。**
 */
export type UploadFileItem = UploadFileInfo

const emit = defineEmits<{
  (e: 'update:fileList', files: UploadFileItem[]): void
  (e: 'success', file: UploadFileItem): void
  (e: 'error', error: unknown): void
  (e: 'remove', file: UploadFileItem): void
}>()

const innerList = ref<UploadFileItem[]>(props.fileList ?? [])

const list = computed(() =>
  props.fileList === undefined ? innerList.value : props.fileList
)

/** 合并鉴权头与业务侧可能额外需要的头。 */
function headers(): Record<string, string> {
  return getUploadAuthHeaders()
}

/** 上传地址可能带查询串（如预签名场景），拼租户参数时不能直接追加 `?`。 */
const resolvedAction = computed(() => props.action)

/**
 * 上传前的校验。
 *
 * <p>返回 {@code false} 时 n-upload 会中止该文件的上传。
 * 用 {@code feedback.warning} 而不是抛错：校验失败是正常的用户行为，
 * 不该产生一条错误日志。
 */
function beforeUpload(data: { file: UploadFileInfo }): boolean {
  const file = data.file.file
  if (!file) {
    return true
  }

  if (props.accept?.length) {
    const ext = (file.name.split('.').pop() ?? '').toLowerCase()
    if (!props.accept.map((e) => e.toLowerCase()).includes(ext)) {
      feedback.warning(`只允许上传 ${props.accept.join(' / ')} 格式的文件`)
      return false
    }
  }

  if (props.maxSizeMb && file.size > props.maxSizeMb * 1024 * 1024) {
    // 提示里同时给出"实际大小"与"上限"：只说"文件过大"用户不知道该压到多少
    feedback.warning(
      `文件大小 ${(file.size / 1024 / 1024).toFixed(1)}MB 超过上限 ${props.maxSizeMb}MB`
    )
    return false
  }

  return true
}

function updateList(files: UploadFileInfo[]): void {
  if (props.fileList === undefined) {
    innerList.value = files
  }
  emit('update:fileList', files)
}

function handleFinish(options: { file: UploadFileInfo }): void {
  emit('success', options.file)
}

function handleError(options: { file: UploadFileInfo; event?: ProgressEvent }): void {
  emit('error', options.event ?? options.file)
}

/**
 * 自定义传输的适配层：把 {@link ProUploadProps.request} 的 Promise
 * 翻译成 n-upload 需要的 onFinish / onError 回调。
 *
 * <p>失败时同时 {@code emit('error')} 与 {@code options.onError()}：
 * 前者让应用能统一提示，后者让控件把文件标成错误态（两个都要，缺一不可）。
 * 不发 {@code emit('success')} —— 那是 {@code finish} 事件的职责，
 * 走 onFinish 后 n-upload 会自己触发，重复发会出现两次提示。
 */
function handleCustomRequest(options: UploadCustomRequestOptions): void {
  const file = options.file.file
  if (!file || !props.request) {
    options.onError()
    return
  }
  props
    .request(file)
    .then(() => options.onFinish())
    .catch((error: unknown) => {
      emit('error', error)
      options.onError()
    })
}

const customRequest = computed(() => (props.request ? handleCustomRequest : undefined))
</script>

<template>
  <div class="pro-upload" :class="{ 'pro-upload--image': image }">
    <!--
      ⚠️ n-upload 这里**不要**再加 :show-file-list 条件。
      原实现是 `:show-file-list="!image || list.length > 0"`，已移除，原因：
      image-card 模式下"添加入口"本身就是文件列表里的一格 —— 隐藏 list 会把
      上传入口一起藏掉（实测：图片模式 + 一个文件都没选时，页面上看不到任何
      上传控件，用户完全无从下手）。缩略图网格本身就是图片模式的"列表"，
      不存在"需要隐藏文字列表"的问题。
    -->
    <n-upload
      :action="resolvedAction"
      :custom-request="customRequest"
      :headers="headers()"
      :file-list="list"
      :multiple="multiple"
      :max="maxCount"
      :accept="accept?.map((e) => `.${e}`).join(',')"
      :disabled="disabled"
      :list-type="image ? 'image-card' : 'text'"
      @before-upload="beforeUpload"
      @update:file-list="updateList"
      @finish="handleFinish"
      @error="handleError"
      @remove="(data: { file: UploadFileInfo; fileList: UploadFileInfo[] }) => emit('remove', data.file)"
    >
      <template v-if="image">
        <!-- 图片模式：直接以缩略图网格呈现，点击可放大 -->
        <div class="pro-upload__image-trigger">
          <span class="pro-upload__plus">+</span>
          <span class="pro-upload__hint">{{ tip ?? '上传图片' }}</span>
        </div>
      </template>
      <n-upload-dragger v-else>
        <div class="pro-upload__dragger">
          <p class="pro-upload__hint">{{ tip ?? '点击或拖拽文件到此处上传' }}</p>
          <p class="pro-upload__limit">
            <template v-if="accept?.length">支持 {{ accept.join(' / ') }}；</template>
            单个文件不超过 {{ maxSizeMb }}MB<template v-if="maxCount > 1">；最多 {{ maxCount }} 个</template>
          </p>
        </div>
      </n-upload-dragger>
    </n-upload>

    <!-- 图片预览：用 n-image 的 group 让多图可以左右切换 -->
    <div v-if="image && list.length" class="pro-upload__preview">
      <n-image
        v-for="file in list.filter((f) => f.status === 'finished' || f.url)"
        :key="String(file.id ?? file.url)"
        :src="String(file.url ?? '')"
        width="72"
        height="72"
        object-fit="cover"
      />
    </div>

    <slot name="extra" />
  </div>
</template>

<style scoped>
.pro-upload {
  width: 100%;
}

.pro-upload__dragger {
  padding: var(--wa-spacing-lg) 0;
}

.pro-upload__hint {
  margin: 0;
  font-size: var(--wa-font-size-md);
  color: var(--wa-text-primary);
}

.pro-upload__limit {
  margin: var(--wa-spacing-xs) 0 0;
  font-size: var(--wa-font-size-xs);
  color: var(--wa-text-disabled);
}

.pro-upload__image-trigger {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--wa-spacing-xs);
  width: 100%;
  height: 100%;
  color: var(--wa-text-secondary);
}

.pro-upload__plus {
  font-size: var(--wa-font-size-xl);
  line-height: 1;
}

.pro-upload__preview {
  display: flex;
  flex-wrap: wrap;
  gap: var(--wa-spacing-sm);
  margin-top: var(--wa-spacing-md);
}
</style>
