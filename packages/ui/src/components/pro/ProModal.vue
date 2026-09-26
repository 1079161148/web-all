<script setup lang="ts">
import { computed, ref } from 'vue'
import { NButton, NDrawer, NDrawerContent, NModal, NSpace, NSpin } from 'naive-ui'
import type { ProFormExpose, ProFormGroup, ProFormItem } from '../../types'
import ProForm from './ProForm.vue'

/**
 * 弹窗 / 抽屉表单容器。
 *
 * <h3>它消灭的是每个页面都要重写一遍的四段样板代码</h3>
 * <ol>
 *   <li><code>const visible = ref(false)</code> —— 开关状态</li>
 *   <li><code>const formRef = ref()</code> —— 表单实例引用</li>
 *   <li>提交成功后 <code>tableRef.value?.reload()</code> —— 刷新表格</li>
 *   <li>关闭时 <code>formRef.value?.resetFields()</code> —— 重置，否则下次打开会残留上一次的输入</li>
 * </ol>
 * 第 4 条最容易被漏，而它的表现是"编辑 A 之后点新增，表单里是 A 的数据"——
 * 用户很可能直接改成 B 保存，把 A 的数据覆盖掉。<b>这是一个会改坏数据的疏漏。</b>
 *
 * <h3>为什么弹窗和抽屉是同一个组件</h3>
 * 两者的<b>行为完全一致</b>，只有渲染外壳不同（居中卡片 vs 侧边滑出）。
 * 拆成两个组件会让上面四段逻辑各存一份，改一处忘一处。
 * 因此用 {@code mode} 区分，行为逻辑只有一份。
 * 这与 {@code ui-component-policy} 把两者列在同一行、职责相同的判断一致。
 *
 * <h3>提交按钮不重复渲染</h3>
 * 提交由内层 ProForm 执行（它才知道校验状态）。本组件的底部按钮
 * <b>调用 ProForm 的 submit</b>，而不是自己再走一遍流程 ——
 * 否则会出现"点了按钮，校验提示显示了但加载态没起来"这类时序不一致。
 */
const props = withDefaults(
  defineProps<{
    /** 显隐（v-model:visible）。 */
    visible: boolean
    title: string
    /** 渲染外壳。 */
    mode?: 'modal' | 'drawer'
    /** 宽度（modal 用像素，drawer 用像素）。 */
    width?: number
    /** 表单字段。传了才渲染内置表单，否则使用默认插槽。 */
    items?: ProFormItem[]
    groups?: ProFormGroup[]
    /** 编辑回显值。 */
    model?: Record<string, unknown>
    cols?: 1 | 2 | 3 | 4
    labelWidth?: number
    labelPlacement?: 'left' | 'top'
    /** 提交函数。 */
    submit?: (values: Record<string, unknown>) => void | Promise<void>
    submitText?: string
    /**
     * 提交成功后的回调。
     *
     * <p>典型用法就是刷新表格：<code>:on-success="() => table.reload(false)"</code>。
     * 做成 prop 而不是事件，是为了让"提交后刷新"这条最常见的链路
     * 在模板里一眼可见 —— 它属于这个组件的职责，不是页面的。
     */
    onSuccess?: (values: Record<string, unknown>) => void | Promise<void>
    /** 关闭时是否重置表单。默认 true —— 见类注释第 4 点。 */
    resetOnClose?: boolean
    /** 提交成功后是否自动关闭。 */
    closeOnSuccess?: boolean
    /** 只读/查看态。 */
    readonly?: boolean
    loading?: boolean
  }>(),
  {
    mode: 'modal',
    width: undefined,
    model: undefined,
    cols: 1,
    labelWidth: 96,
    labelPlacement: 'top',
    submitText: '保存',
    resetOnClose: true,
    closeOnSuccess: true,
    readonly: false,
    loading: false
  }
)

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'success', values: Record<string, unknown>): void
  (e: 'error', error: unknown): void
  (e: 'closed'): void
}>()

const formRef = ref<ProFormExpose | null>(null)
const submitting = ref(false)

const resolvedWidth = computed(() =>
  props.width ?? (props.mode === 'drawer' ? 560 : 560)
)

/** 是否有内置表单（决定底部按钮的行为）。 */
const hasInnerForm = computed(() => Boolean(props.items?.length || props.groups?.length))

/**
 * 执行提交。
 *
 * <p>有内置表单时，底部按钮只负责"触发"：校验态由 ProForm 兜底（调
 * {@code form.validate()}），通过后再取真实值、调业务 {@code submit}。
 * 成功 / 失败只有这一条控制流，状态清晰，不会出现两处各判一次的问题。
 * 校验失败在进入业务提交前就 return，不触发 {@code success}。
 */
async function handleSubmit(): Promise<void> {
  if (hasInnerForm.value) {
    const form = formRef.value
    if (!form) {
      return
    }
    submitting.value = true
    try {
      if (!(await form.validate())) {
        emit('error', new Error('表单校验未通过'))
        return
      }
      const values = form.getValues()
      await props.submit?.(values)
      emit('success', values)
      await props.onSuccess?.(values)
      if (props.closeOnSuccess) {
        emit('update:visible', false)
      }
    } catch (error) {
      emit('error', error)
    } finally {
      submitting.value = false
    }
    return
  }

  // 无内置表单：无可提交的数据，交给页面处理
  emit('success', {})
}

function handleClose(): void {
  emit('update:visible', false)
}

function handleAfterClose(): void {
  // 关闭后重置：这是本组件存在的核心理由之一（见类注释第 4 点）
  if (props.resetOnClose && hasInnerForm.value) {
    formRef.value?.reset()
  }
  emit('closed')
}

function handleShowUpdate(value: boolean): void {
  emit('update:visible', value)
}
</script>

<template>
  <!-- ---------------- 弹窗形态 ---------------- -->
  <n-modal
    v-if="mode === 'modal'"
    :show="visible"
    preset="card"
    :title="title"
    :style="{ width: `${resolvedWidth}px` }"
    :mask-closable="false"
    @update:show="handleShowUpdate"
    @after-leave="handleAfterClose"
  >
    <n-spin :show="loading">
      <ProForm
        v-if="hasInnerForm && !readonly"
        ref="formRef"
        :items="items"
        :groups="groups"
        :model="model"
        :cols="cols"
        :label-width="labelWidth"
        :label-placement="labelPlacement"
        :submit="undefined"
        @success="() => {}"
      />
      <ProForm
        v-else-if="hasInnerForm && readonly"
        :items="items"
        :groups="groups"
        :model="model"
        :cols="cols"
        :label-width="labelWidth"
        :label-placement="labelPlacement"
        disabled
      />
      <slot v-else />
    </n-spin>

    <template #footer>
      <n-space :size="8" justify="end">
        <n-button @click="handleClose">取消</n-button>
        <n-button
          v-if="!readonly"
          type="primary"
          :loading="submitting"
          @click="handleSubmit"
        >
          {{ submitText }}
        </n-button>
      </n-space>
    </template>
  </n-modal>

  <!-- ---------------- 抽屉形态 ---------------- -->
  <n-drawer
    v-else
    :show="visible"
    :width="resolvedWidth"
    placement="right"
    :mask-closable="false"
    @update:show="handleShowUpdate"
    @after-leave="handleAfterClose"
  >
    <n-drawer-content :title="title" closable>
      <n-spin :show="loading">
        <ProForm
          v-if="hasInnerForm && !readonly"
          ref="formRef"
          :items="items"
          :groups="groups"
          :model="model"
          :cols="cols"
          :label-width="labelWidth"
          :label-placement="labelPlacement"
        />
        <ProForm
          v-else-if="hasInnerForm && readonly"
          :items="items"
          :groups="groups"
          :model="model"
          :cols="cols"
          :label-width="labelWidth"
          :label-placement="labelPlacement"
          disabled
        />
        <slot v-else />
      </n-spin>

      <template #footer>
        <n-space :size="8" justify="end">
          <n-button @click="handleClose">取消</n-button>
          <n-button
            v-if="!readonly"
            type="primary"
            :loading="submitting"
            @click="handleSubmit"
          >
            {{ submitText }}
          </n-button>
        </n-space>
      </template>
    </n-drawer-content>
  </n-drawer>
</template>

<style scoped>
/* 样式全部引用 Token；此处无需额外视觉定制 */
</style>
