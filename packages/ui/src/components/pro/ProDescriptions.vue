<script setup lang="ts" generic="T extends object">
import { computed } from 'vue'
import { NDescriptions, NDescriptionsItem, NEmpty } from 'naive-ui'
import { hasPermission } from '../../registry/permission'
import type { ProDescriptionItem } from '../../types'
import { ProCell } from './ProCell'

/**
 * 详情展示（描述列表）。
 *
 * <pre>{@code
 * const items: ProDescriptionItem<User>[] = [
 *   { key: 'username', label: '账号' },
 *   { key: 'status', label: '状态', dict: 'sys_user_status' },
 *   { key: 'createTime', label: '创建时间', render: 'datetime' },
 *   { key: 'phone', label: '手机号', permission: 'iam:user:phone' }
 * ]
 * <ProDescriptions :items="items" :data="detail" :columns="2" />
 * }</pre>
 *
 * <h3>它消灭的是「详情页各自手写一遍」</h3>
 * 没有本组件时，每个详情页都要重复三件事，且三件事都容易做错：
 * <ol>
 *   <li><b>字典翻译</b> —— 漏了就会在详情页显示码值 {@code ACTIVE} 而不是「正常」。
 *       表格页有 ProTable 兜底，详情页没有，于是同一个字段在列表里是中文、
 *       点进去变成英文 —— 这类不一致几乎总被当成"后端返回错了"</li>
 *   <li><b>字段权限</b> —— 详情页比列表页更需要它（列表可以少一列，
 *       详情页少一项才是对的），而手写时通常直接被漏掉</li>
 *   <li><b>空值表现</b> —— 不处理就会渲染出 {@code null} / {@code undefined} 字样</li>
 * </ol>
 *
 * <h3>⚠️ 两个与 ProForm 不一致、必须记住的地方</h3>
 * <table>
 *   <tr><th></th><th>单位</th><th>含义</th></tr>
 *   <tr><td>{@code columns}</td><td>列数</td><td>一屏放几项</td></tr>
 *   <tr><td>{@code item.span}</td><td><b>列</b>（不是 24 栅格）</td>
 *       <td>该项占几列。<b>默认占 1 列，不是占满</b></td></tr>
 * </table>
 * 而 {@code ProForm} 的 {@code span} 是 24 栅格制。
 * 两者不同是<b>内核的既定语义</b>（n-descriptions 与 n-grid 本就不同），
 * 强行统一反而要在中间做一层换算，且换算后仍要理解"列数"——
 * 所以这里选择<b>如实透传 + 显著说明</b>，而不是造一个假的统一。
 *
 * <h3>渲染复用 {@link ProCell} 而不是重写</h3>
 * 详情页与表格单元格需要的渲染完全一样（字典标签 / 时间格式 / 字节 / 进度）。
 * 共用一份的好处是<b>改一处两边同时正确</b>：例如以后调整时间格式，
 * 不会出现"列表里改了、详情页忘了"。
 *
 * <p>注意分工：<b>非空值的渲染</b>归 ProCell；<b>空值与权限</b>归本组件 ——
 * 因为"空值显示什么"是详情页的语义（表格里固定是 {@code -}，详情页可配置）。
 */

const props = withDefaults(
  defineProps<{
    /** 详情项定义。 */
    items: ProDescriptionItem<T>[]
    /**
     * 数据对象。
     *
     * <p>⚠️ 与 ProTable 的泛型 props 同理，<b>不给它 {@code withDefaults} 默认值</b>
     * （见 ProTable 中 {@code rowActions} 的说明）：泛型 prop 一旦有默认值，
     * Vue 的泛型推断会放弃并从调用方传入的类型退回泛型约束，
     * 表现为"传进去的类型没生效，报出一个指不到真因的错误"。
     */
    data?: T | null
    /** 每行显示几项。 */
    columns?: 1 | 2 | 3 | 4
    /** 标签位置。 */
    labelPlacement?: 'left' | 'top'
    /** 是否显示边框。 */
    bordered?: boolean
    size?: 'small' | 'medium' | 'large'
    /** 空值占位文案。 */
    placeholder?: string
    /** 标题。 */
    title?: string
    /** 整体无数据时的提示。 */
    emptyText?: string
  }>(),
  {
    columns: 2,
    labelPlacement: 'left',
    bordered: true,
    size: 'small',
    placeholder: '-',
    title: undefined,
    emptyText: '暂无数据'
  }
)

/** 空值判定：null / undefined / 空串都算空（0 与 false 是有效值）。与 ProCell 保持一致。 */
function isEmptyValue(value: unknown): boolean {
  return value === null || value === undefined || value === ''
}

/**
 * 详情数据的可索引视图。
 *
 * <p>为什么需要一次断言：本组件的泛型是 {@code T extends object}（不是
 * {@code Record<string, unknown>}）—— 后者会让后端生成的 {@code UserResponse}
 * 等 interface 无法赋值（TS 的 interface 不生成隐式索引签名），
 * 这个坑 {@code ProCell.ts} 里有完整记录。
 *
 * <p>而"按字段名取值"是<b>运行期行为</b>：{@code key} 来自后端契约，
 * 编译期无法证明它一定在 {@code T} 上。因此这里做一次断言，并把
 * "我们确实要按名取值"这件事<b>显式写在唯一的一处</b>，
 * 而不是让每个调用点各自绕过类型。
 */
const rowData = computed<Record<string, unknown>>(
  () => (props.data ?? {}) as Record<string, unknown>
)

const hasData = computed(() => props.data !== null && props.data !== undefined)

/** 渲染行：已按权限与空值规则过滤。 */
interface RenderRow {
  item: ProDescriptionItem<T>
  /**
   * 该项因缺少权限而以替代文案呈现。
   *
   * <p>与"直接隐藏"的区别是一个真实取舍：
   * <ul>
   *   <li><b>默认隐藏</b> —— 字段的<b>存在</b>本身就是信息
   *       （"这里有『身份证号』一项"本身就是泄露）。与 ProTable 的列权限行为一致</li>
   *   <li><b>显式声明 {@code deniedText}</b> —— 用于"用户需要知道有这个字段，
   *       但看不到值"的场景，例如提示去申请权限</li>
   * </ul>
   */
  denied: boolean
}

const renderRows = computed<RenderRow[]>(() => {
  const rows: RenderRow[] = []

  for (const item of props.items) {
    const allowed = hasPermission(item.permission)

    // 无权限且没有声明替代文案 → 整项不渲染（连标签都不出现）
    if (!allowed && item.deniedText === undefined) {
      continue
    }

    // 仅对"有权限的项"应用隐藏空值规则：
    // 无权限项的值本来就取不到，若也参与判断，会被误判成"空"而漏掉提示
    if (allowed && item.hideWhenEmpty && isEmptyValue(rowData.value[item.key])) {
      continue
    }

    rows.push({ item, denied: !allowed })
  }

  return rows
})

/**
 * 组装 ProCell 需要的列信息。
 *
 * <p>无需断言类型：{@code renderFn} 的形参在逆变位置，
 * 所以 {@code (data: T) => VNode} 可直接赋给 ProCell 的 {@code (row: never) => VNode}
 * （{@code never} 可赋给任何类型）—— 这正是 ProCell 当初这样设计的目的。
 */
function toCellColumn(item: ProDescriptionItem<T>) {
  return {
    key: item.key,
    dict: item.dict,
    render: item.render,
    renderFn: item.renderFn
  }
}
</script>

<template>
  <n-empty v-if="!hasData" :description="emptyText" class="pro-descriptions__empty" />

  <n-descriptions
    v-else
    :title="title"
    :column="columns"
    :label-placement="labelPlacement"
    :bordered="bordered"
    :size="size"
  >
    <n-descriptions-item
      v-for="row in renderRows"
      :key="row.item.key"
      :label="row.item.label"
      :span="row.item.span"
    >
      <span v-if="row.denied" class="pro-descriptions__denied">{{ row.item.deniedText }}</span>

      <span
        v-else-if="isEmptyValue(rowData[row.item.key])"
        class="pro-descriptions__placeholder"
      >
        {{ placeholder }}
      </span>

      <ProCell v-else :column="toCellColumn(row.item)" :row="rowData" />
    </n-descriptions-item>
  </n-descriptions>
</template>

<style scoped>
/*
  无一处硬编码颜色/间距（ui-component-policy 强行约束第 5 条），
  尺寸与色值全部来自 Token。
*/
.pro-descriptions__placeholder {
  color: var(--wa-text-disabled);
}

/* 无权限的替代文案：中性色 + 斜体，与"空值"在视觉上明确区分 ——
   两者都用灰色会让"没权限"看起来像"没填" */
.pro-descriptions__denied {
  color: var(--wa-text-secondary);
  font-style: italic;
}

.pro-descriptions__empty {
  padding: var(--wa-spacing-xl) 0;
}
</style>
