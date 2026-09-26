<script setup lang="ts">
import { computed } from 'vue'
import { NSelect } from 'naive-ui'
import { getDictOptions } from '../../registry/dict'

/**
 * 字典下拉框：选项自动来自字典，无需每个页面手工 map 一遍。
 *
 * <pre>{@code
 * <dict-select v-model:value="form.status" dict-type="sys_user_status" />
 * }</pre>
 *
 * <h3>它消除的是三段重复代码</h3>
 * 在没有本组件时，每个用到字典下拉的地方都要写：
 * <ol>
 *   <li>{@code getDictOptions('xxx')} —— 拿到响应式选项，并自己记住要用 {@code .value}</li>
 *   <li>{@code .map(o => ({ label: o.label, value: o.value }))} —— 形状转换</li>
 *   <li>值类型的转换与还原（见下）</li>
 * </ol>
 * 第 3 条是最容易漏的，而且漏了不报错。
 *
 * <h3>⚠️ 字典值一律是字符串，这里替调用方付这笔「税」</h3>
 * {@code DictOption.value} 的类型是 {@code string}（字典表里就是字符串），
 * 而后端契约里很多字段是数字 —— 例如 {@code UserResponse.sex} 是 {@code Integer}，
 * 字典项却是 {@code "0" / "1" / "2"}。
 *
 * <p>若不做转换，{@code v-model} 绑定到数字字段上会得到 {@code "1"}，
 * 提交时后端反序列化 {@code Integer} 直接失败；而且这个失败发生在提交那一刻，
 * 与"选项是从字典来的"看起来毫无关系 —— 典型的高排查成本问题。
 *
 * <p>因此提供 {@code valueType="number"}，把转换<b>收敛到这一个组件内部</b>，
 * 而不是让每个页面各写一遍 {@code Number(...)}。
 *
 * <h3>⚠️ 非数字码值不会被强行转换</h3>
 * 字典里既有 {@code "0"/"1"}，也有 {@code "ACTIVE"}。
 * 若声明了 {@code valueType="number"} 就无条件 {@code Number(v)}，
 * 后者会变成 {@code NaN} —— 下拉里的每一项都匹配不上 {@code NaN}，
 * 表现为<b>「选不中任何一项」</b>，且没有任何报错。
 * 因此这里<b>逐项判断</b>：是合法数字才转，否则保留原字符串。
 */
const props = withDefaults(
  defineProps<{
    /** 字典类型编码。 */
    dictType: string
    /** 当前值。 */
    value?: string | number | Array<string | number> | null
    /** 是否多选。多选时 {@code value} 为数组。 */
    multiple?: boolean
    /** 是否可清空。默认 true —— 筛选与表单场景下"选错了要能取消"是刚需。 */
    clearable?: boolean
    disabled?: boolean
    placeholder?: string
    size?: 'small' | 'medium' | 'large'
    /**
     * 值的类型。
     *
     * <p>{@code 'string'}（默认）直接使用字典存值；
     * {@code 'number'} 在合法数字时转成数字（见上方说明）。
     */
    valueType?: 'string' | 'number'
    /**
     * 是否按 {@code isDefault} 预选默认项。
     *
     * <p>只在 {@code value} 为空时生效 —— "用户已经选过"永远优先于"字典配了默认值"。
     */
    useDefault?: boolean
  }>(),
  {
    value: null,
    multiple: false,
    clearable: true,
    disabled: false,
    placeholder: '请选择',
    size: 'medium',
    valueType: 'string',
    useDefault: false
  }
)

const emit = defineEmits<{
  (e: 'update:value', value: string | number | Array<string | number> | null): void
  (e: 'change', value: string | number | Array<string | number> | null): void
}>()

/**
 * 字符串 → 目标值类型。
 *
 * <p>只有"看起来确实是数字"的才转换。{@code Number('')} 是 0、
 * {@code Number('ACTIVE')} 是 NaN，两者都会静默产生一个选不中的值，
 * 因此用显式判定而不是直接 {@code Number()}。
 */
function toValueType(raw: string): string | number {
  if (props.valueType !== 'number') {
    return raw
  }
  if (raw.trim() === '') {
    return raw
  }
  const numeric = Number(raw)
  return Number.isFinite(numeric) ? numeric : raw
}

const options = computed(() =>
  getDictOptions(props.dictType).value.map((option) => ({
    label: option.label,
    value: toValueType(option.value)
  }))
)

/**
 * 选中值。
 *
 * <p>用 computed 的 getter/setter 而不是自己维护一份内部状态：
 * 本组件是**受控**的（值由调用方持有），内部状态会与外部来源打架 ——
 * 尤其是表单回显（{@code setValues}）时，内部状态不会跟着更新。
 */
const selected = computed({
  get: () => {
    if (props.value !== null && props.value !== undefined && props.value !== '') {
      return props.value
    }
    if (!props.useDefault) {
      return props.multiple ? [] : null
    }
    // 预选默认项：只取第一个标记为默认的（多个默认值属于配置问题，
    // 取第一个比"全部选中"更接近预期）
    const fallback = getDictOptions(props.dictType).value.find((option) => option.isDefault)
    if (!fallback) {
      return props.multiple ? [] : null
    }
    return props.multiple ? [toValueType(fallback.value)] : toValueType(fallback.value)
  },
  set: (next) => {
    emit('update:value', next)
    emit('change', next)
  }
})
</script>

<template>
  <n-select
    v-model:value="selected"
    :options="options"
    :multiple="multiple"
    :clearable="clearable"
    :disabled="disabled"
    :placeholder="placeholder"
    :size="size"
  />
</template>
