<script setup lang="ts">
import { computed, getCurrentInstance, onMounted, ref, shallowRef, watch } from 'vue'
import { NButton, NSpace } from 'naive-ui'
import { ensureFormCreate } from '../../adapters/lazy'
import { getDictOptions } from '../../registry/dict'
import { hasPermission } from '../../registry/permission'
import AppSkeleton from '../state/AppSkeleton.vue'
import type {
  FormCreateApi,
  FormCreateOptions,
  FormCreateRule
} from '../../adapters/form-create'
import type { ProFormExpose, ProFormGroup, ProFormItem } from '../../types'

/**
 * 数据表单（form-create 内核）。
 *
 * <h3>目标：一个表单只写字段定义 + 提交函数</h3>
 * <pre>{@code
 * <ProForm
 *   :items="[
 *     { field: 'username', title: '账号', required: true },
 *     { field: 'status', title: '状态', type: 'select', dict: 'sys_status' }
 *   ]"
 *   :submit="handleSubmit"
 *   @success="tableRef?.reload(true)"
 * />
 * }</pre>
 *
 * <h3>为什么内核是 form-create 而不是自己在 n-form 上做 schema 渲染</h3>
 * 「根据配置生成表单项」听起来简单，真做起来要处理：嵌套分组、动态增减行、
 * 字段联动显隐、异步选项、校验依赖、子表单的值同步与销毁重建时的状态保持。
 * 这些正是 form-create 打磨多年的部分 —— {@code ui-component-policy} 因此把
 * 「自研动态表单渲染引擎」列为红线。
 *
 * <h3>我们只加四件事（不重写内核能力）</h3>
 * <ol>
 *   <li><b>业务语义的控件名</b>：`type: 'date-range'` 而不是 `datePicker + props.type`</li>
 *   <li><b>字典联动</b>：`dict: 'sys_status'` 自动变成选项，且字典异步到达后会重新生成规则</li>
 *   <li><b>权限字段</b>：`permission: 'iam:user:update'` 无权限则不渲染该字段</li>
 *   <li><b>布局预设</b>：`cols: 2` → 每行两列，并在窄屏自动降为单列</li>
 * </ol>
 *
 * <h3>提交按钮为什么不用内核自带的</h3>
 * form-create 有 `submitBtn` / `resetBtn` 配置，但它的按钮与 ProModal 的底部操作区
 * 会形成两套按钮样式。<b>统一由本组件渲染</b>（关闭内核按钮），
 * 这样表单无论单独用还是放进弹窗，底部操作区长得一样。
 */

const props = withDefaults(
  defineProps<{
    /** 平铺字段。与 `groups` 二选一。 */
    items?: ProFormItem[]
    /** 分组字段（复杂表单）。 */
    groups?: ProFormGroup[]
    /** 初始值 / 编辑回显值。 */
    model?: Record<string, unknown>
    /**
     * 每行几列。
     *
     * <p>响应式由本组件负责：窄屏（< 768px）自动降为单列 ——
     * 两列表单在手机上会让每个控件都窄到不可用。
     */
    cols?: 1 | 2 | 3 | 4
    /** 标签宽度。 */
    labelWidth?: number
    /** 标签位置。 */
    labelPlacement?: 'left' | 'top'
    /** 提交函数。**给了它才会渲染提交按钮** —— 只读表单不该出现"提交"按钮。 */
    submit?: (values: Record<string, unknown>) => void | Promise<void>
    submitText?: string
    resetText?: string
    /** 是否显示重置按钮。 */
    showReset?: boolean
    /** 是否禁用全部字段（查看态）。 */
    disabled?: boolean
    /** 表单 grid 是否撑满高度。 */
    loading?: boolean
  }>(),
  {
    cols: 2,
    labelWidth: 96,
    labelPlacement: 'left',
    submitText: '保存',
    resetText: '重置',
    showReset: true,
    disabled: false,
    loading: false
  }
)

const emit = defineEmits<{
  /** 校验通过且 submit 执行完毕后触发。 */
  (e: 'success', values: Record<string, unknown>): void
  /** 校验失败或 submit 抛错时触发。 */
  (e: 'error', error: unknown): void
  (e: 'reset'): void
  /** 任意字段值变化（实时）。联动显隐 / 跨字段计算以它为驱动。 */
  (e: 'change', values: Record<string, unknown>): void
}>()

/**
 * 业务语义控件名 → form-create 控件名的映射。
 *
 * <p>右侧的名字是对 {@code @form-create/naive-ui} 产物做字符串提取得到的
 * （input / textarea / select / datePicker / inputNumber / switch / radio /
 * checkbox / timePicker / colorPicker / tree / cascader / slider / rate /
 * upload / transfer），不是猜的 —— 控件名写错不会报错，只会渲染成空白。
 */
const CONTROL_TYPES: Record<string, string> = {
  input: 'input',
  textarea: 'input',
  password: 'input',
  number: 'inputNumber',
  select: 'select',
  'multi-select': 'select',
  radio: 'radio',
  checkbox: 'checkbox',
  date: 'datePicker',
  datetime: 'datePicker',
  'date-range': 'datePicker',
  time: 'timePicker',
  switch: 'switch',
  slider: 'slider',
  rate: 'rate',
  color: 'colorPicker',
  tree: 'tree',
  // 树形下拉选择器（NTreeSelect）。注意与 tree（NTree，勾选树）的区别：
  // 表单里"选一个/多个节点值"的场景（如选择上级部门）应使用 treeSelect
  treeSelect: 'treeSelect',
  cascader: 'cascader',
  upload: 'upload'
}

/** 需要额外 props 的类型（没法只靠控件名表达的部分）。 */
const EXTRA_PROPS: Record<string, Record<string, unknown>> = {
  textarea: { type: 'textarea', rows: 3 },
  password: { type: 'password', showPasswordOn: 'click' },
  datetime: { type: 'datetime' },
  'date-range': { type: 'daterange' },
  'multi-select': { multiple: true, clearable: true },
  treeSelect: { clearable: true, filterable: true, defaultExpandAll: true }
}

const fapi = ref<FormCreateApi | null>(null)
const formData = shallowRef<Record<string, unknown>>({})
const submitting = ref(false)

/**
 * 实时值变化（联动场景的入口）。
 *
 * <p>submit 只在提交那一刻给出值；而"字段随值显隐"这类联动需要
 * <b>每一次输入</b>都拿到最新值。form-create 通过 v-model 把实时值
 * 同步进 formData，这里转发给调用方 —— 深层 watch 以覆盖原地修改。
 */
watch(
  formData,
  (values) => {
    emit('change', { ...values })
  },
  { deep: true }
)

/**
 * 表单内核是否就绪。
 *
 * <p>form-create 由 {@code installAdminUi} **按需**注册（不静态引入），
 * 因此挂载前需要先 await 一次加载。静态引入会让 vxe + form-create 一起
 * 进入首屏 chunk（实测约 577 KB gzip），而登录页只需要一个表单。
 */
const kernelReady = ref(false)

const app = getCurrentInstance()?.appContext.app

onMounted(async () => {
  await ensureFormCreate(app)
  kernelReady.value = true
})

const span = computed(() => Math.round(24 / props.cols))

// ---------------------------------------------------------------------
// 规则生成
// ---------------------------------------------------------------------

function allItems(): ProFormItem[] {
  if (props.groups?.length) {
    return props.groups.flatMap((group) => group.items)
  }
  return props.items ?? []
}

function toRule(item: ProFormItem): FormCreateRule {
  const controlType = CONTROL_TYPES[item.type ?? 'input'] ?? 'input'
  const extra = EXTRA_PROPS[item.type ?? ''] ?? {}

  const rule: Record<string, unknown> = {
    field: item.field,
    title: item.title,
    type: controlType,
    // col 用 24 栅格（与 Naive n-grid 一致）。xs 强制 24 实现窄屏单列 ——
    // 不设它的话，两列表单在手机上每个控件都宽不到 150px，基本不可用。
    col: { span: item.span ?? span.value, xs: 24 },
    props: {
      placeholder:
        item.placeholder ??
        (item.type === 'select' || item.type === 'date' ? `请选择${item.title}` : `请输入${item.title}`),
      disabled: item.disabled ?? props.disabled,
      ...extra,
      ...item.props
    }
  }

  if (item.dict) {
    // 读 .value 建立响应式依赖：字典异步到达后会重新生成规则，
    // 否则下拉框会永久为空（刷新才正常）
    rule.options = getDictOptions(item.dict).value.map((option) => ({
      label: option.label,
      value: option.value
    }))
  } else if (item.options?.length) {
    rule.options = item.options
  }

  if (item.required) {
    rule.validate = [
      {
        required: true,
        message: item.message ?? `请${item.type === 'select' ? '选择' : '输入'}${item.title}`
      }
    ]
  }

  if (item.value !== undefined) {
    rule.value = item.value
  }

  return rule as FormCreateRule
}

function visibleItems(items: ProFormItem[]): ProFormItem[] {
  return items.filter((item) => hasPermission(item.permission))
}

/** 高级字段（默认折叠）。 */
const advancedItems = computed(() => visibleItems(allItems().filter((i) => i.advanced === true)))
const basicItems = computed(() => visibleItems(allItems().filter((i) => i.advanced !== true)))

const advancedExpanded = ref(false)

/** 传给 form-create 的规则。分组时用内核的 group 类型承载折叠。 */
const rules = computed<FormCreateRule[]>(() => {
  if (props.groups?.length) {
    return props.groups.map((group) => ({
      type: 'group',
      title: group.title,
      expand: group.collapsed !== true,
      children: visibleItems(group.items).map(toRule)
    })) as unknown as FormCreateRule[]
  }

  const list = basicItems.value.map(toRule)
  if (advancedItems.value.length > 0) {
    list.push({
      type: 'group',
      title: '更多',
      expand: advancedExpanded.value,
      children: advancedItems.value.map(toRule)
    } as unknown as FormCreateRule)
  }
  return list
})

/** 内核选项：关掉自带按钮（见类注释），并把标签宽度/位置透传给 n-form。 */
const options = computed<FormCreateOptions>(
  () =>
    ({
      submitBtn: false,
      resetBtn: false,
      // 字段级错误提示在字段下方内联显示，不弹窗 —— 一个表单弹 N 个 toast 是灾难
      onSubmit: undefined,
      // form 节是 form-create 透传给 n-form 的 props
      form: {
        labelPlacement: props.labelPlacement,
        labelWidth: props.labelWidth
      }
    }) as unknown as FormCreateOptions
)

// ---------------------------------------------------------------------
// 初始值
// ---------------------------------------------------------------------

const initialValues = computed<Record<string, unknown>>(() => {
  const values: Record<string, unknown> = {}
  for (const item of allItems()) {
    if (item.value !== undefined) {
      values[item.field] = item.value
    }
  }
  return props.model ? { ...values, ...props.model } : values
})

watch(
  () => initialValues.value,
  (values) => {
    formData.value = { ...values }
  },
  { immediate: true, deep: true }
)

// ---------------------------------------------------------------------
// 对外方法
// ---------------------------------------------------------------------

function getValues(): Record<string, unknown> {
  const api = fapi.value
  if (api && typeof (api as { formData?: () => unknown }).formData === 'function') {
    return ((api as { formData: () => Record<string, unknown> }).formData() ?? {}) as Record<
      string,
      unknown
    >
  }
  return { ...formData.value }
}

async function validate(): Promise<boolean> {
  const api = fapi.value
  if (!api?.validate) {
    return true
  }
  try {
    await api.validate()
    return true
  } catch {
    return false
  }
}

function setValues(values: Record<string, unknown>): void {
  formData.value = { ...formData.value, ...values }
  const api = fapi.value as { setValue?: (values: Record<string, unknown>) => void } | null
  api?.setValue?.(values)
}

function reset(): void {
  formData.value = { ...initialValues.value }
  const api = fapi.value as { resetFields?: () => void } | null
  api?.resetFields?.()
  emit('reset')
}

async function submit(): Promise<void> {
  if (!(await validate())) {
    emit('error', new Error('表单校验未通过'))
    return
  }
  const values = getValues()
  if (!props.submit) {
    emit('success', values)
    return
  }
  submitting.value = true
  try {
    await props.submit(values)
    emit('success', values)
  } catch (error) {
    // 不在组件内弹提示：提示方式是应用级决策（见 ProTable 的同款说明）
    emit('error', error)
  } finally {
    submitting.value = false
  }
}

function appendRule(item: ProFormItem, afterField?: string): void {
  const api = fapi.value as {
    append?: (rule: unknown, afterField?: string) => void
  } | null
  api?.append?.(toRule(item), afterField)
}

function removeField(field: string): void {
  const api = fapi.value as { removeField?: (field: string) => void } | null
  api?.removeField?.(field)
}

defineExpose<ProFormExpose>({
  validate,
  getValues,
  setValues,
  reset,
  submit,
  appendRule,
  removeField
})
</script>

<template>
  <div class="pro-form">
    <!--
      内核组件由 installAdminUi 全局注册（app.component('FormCreate')）。
      这里用字符串 :is 而非直接写标签：全局注册的组件在 SFC 里没有类型信息，
      直接写标签会让 vue-tsc 报未知组件。pro-form 对外的类型由本组件自己的 props 保证。
    -->
    <!-- 内核就绪前显示表单形状的骨架屏（字段数由 items 推导，形状才贴近真实内容） -->
    <AppSkeleton
      v-if="!kernelReady"
      variant="form"
      :rows="Math.min(allItems().length || 4, 8)"
    />
    <component
      v-else
      :is="'form-create'"
      v-model:api="fapi"
      v-model="formData"
      :rule="rules"
      :option="options"
      :disabled="disabled"
    />

    <!-- 高级字段的展开/收起（分组模式下由内核的 group 承载） -->
    <n-button
      v-if="!groups?.length && advancedItems.length > 0"
      text
      size="small"
      class="pro-form__more"
      @click="advancedExpanded = !advancedExpanded"
    >
      {{ advancedExpanded ? '收起更多条件' : `展开更多条件（${advancedItems.length}）` }}
    </n-button>

    <!--
      ⚠️ 这里必须用 props.submit 而不是 submit：
      本组件里有一个同名的局部函数 submit()（defineExpose 暴露的那个），
      模板里裸写 submit 会解析到局部函数 —— 函数永远为真值，
      footer 就会无条件渲染，在 ProModal 里出现"重置/保存 + 取消/保存"两套按钮
    -->
    <div v-if="props.submit" class="pro-form__footer">
      <n-space :size="8" justify="end">
        <n-button v-if="showReset" @click="reset">{{ resetText }}</n-button>
        <n-button type="primary" :loading="submitting || loading" @click="submit">
          {{ submitText }}
        </n-button>
      </n-space>
    </div>

    <slot name="footer-extra" />
  </div>
</template>

<style scoped>
.pro-form {
  width: 100%;
}

.pro-form__more {
  margin-bottom: var(--wa-spacing-md);
}

.pro-form__footer {
  margin-top: var(--wa-spacing-lg);
}
</style>
