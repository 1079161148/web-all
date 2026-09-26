<script setup lang="ts">
import { ref } from 'vue'
import DictSelect from '../components/pro/DictSelect.vue'

/**
 * DictSelect 的 story。
 *
 * <p>这里刻意把「当前值 + 它的 JS 类型」一起显示出来。
 * 值类型是字典方案里最隐蔽的一处坑（字典存字符串、后端要数字），
 * 而它出错时表现为"提交失败"而不是"下拉坏了" ——
 * 把类型摆在界面上，评审时一眼就能看出转换是否发生。
 */

const status = ref<string | number | null>('ACTIVE')
const sexAsNumber = ref<string | number | null>(1)
const statusAsNumber = ref<string | number | null>('ACTIVE')
const multi = ref<Array<string | number>>(['ACTIVE'])
const presetDefault = ref<string | number | null>(null)
const disabledValue = ref<string | number | null>('LOCKED')
const emptyValue = ref<string | number | null>(null)
</script>

<template>
  <Story title="Pro 组件/DictSelect" :layout="{ type: 'grid', width: '420px' }">
    <Variant
      title="基础用法：只说字典编码"
      doc="选项自动来自字典（sys_user_status）。默认值类型是字符串 —— 与字典存储值一致。"
    >
      <DictSelect v-model:value="status" dict-type="sys_user_status" />
      <p class="story-value">
        当前值：<b>{{ status }}</b>（类型 <b>{{ typeof status }}</b>）
      </p>
    </Variant>

    <Variant
      title="valueType=number：与后端 Integer 字段对齐"
      doc="sys_user_sex 的码值是 '0'/'1'/'2'，而后端 sex 是 Integer。声明 valueType=number 后返回值是数字，提交时不会因类型不符失败。"
    >
      <DictSelect v-model:value="sexAsNumber" dict-type="sys_user_sex" value-type="number" />
      <p class="story-value">
        当前值：<b>{{ sexAsNumber }}</b>（类型 <b>{{ typeof sexAsNumber }}</b>）
      </p>
    </Variant>

    <Variant
      title="⚠️ 非数字码值不会被强行转换"
      doc="同一个 valueType=number，但 sys_user_status 的码值是 ACTIVE/DISABLED/LOCKED。这里逐项判断：不是合法数字就保留字符串，否则会变成 NaN，下拉里选不中任何一项且不报错。"
    >
      <DictSelect
        v-model:value="statusAsNumber"
        dict-type="sys_user_status"
        value-type="number"
      />
      <p class="story-value">
        当前值：<b>{{ statusAsNumber }}</b>（类型 <b>{{ typeof statusAsNumber }}</b>）
      </p>
    </Variant>

    <Variant title="多选" doc="多选时值是数组，元素同样受 valueType 影响。">
      <DictSelect v-model:value="multi" dict-type="sys_user_status" multiple />
      <p class="story-value">
        当前值：<b>{{ JSON.stringify(multi) }}</b>
      </p>
    </Variant>

    <Variant
      title="预选字典默认项（useDefault）"
      doc="值为空时才生效 —— 用户已经选过永远优先于字典配的默认值。"
    >
      <DictSelect v-model:value="presetDefault" dict-type="sys_user_status" use-default />
      <p class="story-value">
        当前值：<b>{{ presetDefault }}</b>
      </p>
    </Variant>

    <Variant title="禁用态" doc="禁用时值仍然正确显示，只是不可改 —— 只读表单区域用得上。">
      <DictSelect v-model:value="disabledValue" dict-type="sys_user_status" disabled />
    </Variant>

    <Variant title="空值与占位" doc="值为空时显示 placeholder；清空按钮是默认开启的（选错了要能取消）。">
      <DictSelect
        v-model:value="emptyValue"
        dict-type="sys_user_status"
        placeholder="请选择用户状态"
      />
      <p class="story-value">
        当前值：<b>{{ emptyValue === null ? '(空)' : emptyValue }}</b>
      </p>
    </Variant>
  </Story>
</template>

<style scoped>
.story-value {
  margin: var(--wa-spacing-md) 0 0;
  color: var(--wa-text-secondary);
  font-size: var(--wa-font-size-sm);
}
</style>
