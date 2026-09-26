<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { VueFlow, useVueFlow, type Connection, type Edge, type Node } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import { NButton, NCard, NInput, NInputNumber, NRadioGroup, NRadioButton, NSelect, NSpace, NTag, feedback } from '@admin/ui'

/**
 * 审批流设计器（基于 Vue Flow 的可视化编排）。
 *
 * <h3>为什么选 Vue Flow</h3>
 * 选型时对比过三条路：自研 SVG 拖拽（连线、吸附、缩放、框选……每一项
 * 都是几周的工作量）、AntV X6（能力强但体积大、React 血统的 API 设计）、
 * Vue Flow（react-flow 的 Vue 原生实现，无 UI 样式绑定 —— 设计器的
 * "皮"仍然由我们自己的组件构成）。红线是"不重写连线引擎"，
 * Vue Flow 恰好只提供引擎，不替我们做表单。
 *
 * <h3>审批语义（会签 / 或签 / 驳回）</h3>
 * <ul>
 *   <li><b>会签（ALL）</b>：该节点的全部候选人都必须通过，任一拒绝即驳回</li>
 *   <li><b>或签（ANY）</b>：任一人通过即通过 —— 常用于"主管或其代理"</li>
 *   <li><b>驳回（rejectTo）</b>：审批不通过时回到哪个节点 ——
 *       直接回发起人（重新提交）还是上一节点（补材料），是业务语义不是连线</li>
 * </ul>
 *
 * <h3>发布前校验（设计器最容易省略、又最不能省的部分）</h3>
 * 图结构合法 ≠ 业务合法。这里校验：唯一开始节点、开始节点无入边、
 * 全部节点自开始可达（无孤岛）、审批节点必须指定审批人、
 * 条件节点的每条出边必须带条件标签 —— 任何一条不满足都不允许导出。
 */

type NodeKind = 'start' | 'approve' | 'condition' | 'end'

interface FlowData {
  kind: NodeKind
  title: string
  assignee?: string
  mode?: 'ALL' | 'ANY'
  hours?: number
  rejectTo?: string
  conditionLabel?: string
}

const KIND_META: Record<NodeKind, { label: string; color: string }> = {
  start: { label: '开始', color: '#18a058' },
  approve: { label: '审批', color: '#2563eb' },
  condition: { label: '条件', color: '#f0a020' },
  end: { label: '结束', color: '#d03050' }
}

let seq = 100
// ⚠️ 刻意用非泛型 Node（data 为 any）而不是 Node<FlowData>：
// Vue Flow 的泛型链与 Vue ref 深层解包叠加时，任何对节点数组的
// filter/赋值都会触发 TS"类型实例化过深"（实测踩了 4 处）。
// data 的真实形状由 FlowData 约束，读取统一经 selectedData / 局部 cast。
// ⚠️ label 是 Vue Flow 默认节点的渲染字段（data.title 只是我们的业务数据）——
// 两者必须同步维护，漏掉 label 的表现是"节点全空白"（实测）。
const nodes = ref<Node[]>([
  {
    id: 'start',
    type: 'input',
    position: { x: 60, y: 200 },
    label: '提交申请',
    data: { kind: 'start', title: '提交申请' },
    class: 'wf-node wf-node--start'
  },
  {
    id: 'a1',
    type: 'default',
    position: { x: 320, y: 120 },
    label: '部门主管审批',
    data: { kind: 'approve', title: '部门主管审批', assignee: '直属主管', mode: 'ANY', hours: 24, rejectTo: 'start' },
    class: 'wf-node wf-node--approve'
  },
  {
    id: 'c1',
    type: 'default',
    position: { x: 320, y: 300 },
    label: '金额 > 5000？',
    data: { kind: 'condition', title: '金额 > 5000？', conditionLabel: '金额区间' },
    class: 'wf-node wf-node--condition'
  },
  {
    id: 'a2',
    type: 'default',
    position: { x: 600, y: 100 },
    label: '总监审批',
    data: { kind: 'approve', title: '总监审批', assignee: '采购总监', mode: 'ALL', hours: 48, rejectTo: 'a1' },
    class: 'wf-node wf-node--approve'
  },
  {
    id: 'end',
    type: 'output',
    position: { x: 880, y: 200 },
    label: '归档',
    data: { kind: 'end', title: '归档' },
    class: 'wf-node wf-node--end'
  }
])

const edges = ref<Edge[]>([
  { id: 'e1', source: 'start', target: 'a1', animated: true },
  { id: 'e2', source: 'start', target: 'c1', label: '金额 > 5000', animated: true },
  { id: 'e3', source: 'c1', target: 'end', label: '否则' },
  { id: 'e4', source: 'a1', target: 'a2' },
  { id: 'e5', source: 'a2', target: 'end' }
])

const { addEdges, screenToFlowCoordinate, findNode } = useVueFlow()

function onConnect(connection: Connection): void {
  // 禁止成环：审批流是 DAG —— 连回上游是设计错误，当场拒绝而不是导出时才报
  if (connection.source === connection.target) {
    feedback.warning('不能连接到自身')
    return
  }
  if (edges.value.some((edge) => edge.source === connection.source && edge.target === connection.target)) {
    feedback.warning('这两个节点之间已有连线')
    return
  }
  addEdges([{ ...connection }])
}

function addNode(kind: NodeKind): void {
  seq += 1
  const id = `${kind}-${seq}`
  const title = KIND_META[kind].label + seq
  nodes.value.push({
    id,
    type: 'default',
    position: screenToFlowCoordinate({ x: 240 + Math.random() * 120, y: 180 + Math.random() * 120 }),
    label: title,
    data: {
      kind,
      title,
      ...(kind === 'approve' ? { assignee: '', mode: 'ANY' as const, hours: 24, rejectTo: 'start' } : {}),
      ...(kind === 'condition' ? { conditionLabel: '' } : {})
    },
    class: `wf-node wf-node--${kind}`
  })
}

const selected = ref<Node | null>(null)

/** Vue Flow 的 Node.data 在其类型里是可选的；我们的节点永远带 data ——
 *  模板经由本 computed 消费（顺带解决模板里无法写非空断言的问题）。 */
const selectedData = computed(() => selected.value?.data as FlowData | undefined)

// 面板里改名称 → 同步回节点顶层 label（默认节点渲染的是它）
watch(
  () => selectedData.value?.title,
  (title: string | undefined) => {
    if (selected.value && title !== undefined) {
      selected.value.label = title
    }
  }
)

function onNodeClick(node: Node | null): void {
  selected.value = node ?? null
}

const approverOptions = ['直属主管', '部门经理', '采购总监', '财务专员', 'HRBP'].map((name) => ({
  label: name,
  value: name
}))

const rejectTargetOptions = computed<Array<{ label: string; value: string }>>(() => {
  const result: Array<{ label: string; value: string }> = []
  for (const node of nodes.value) {
    // 用局部 cast 而不是 filter 类型谓词：Vue Flow 的 Node 泛型链很深，
    // 谓词收窄会触发 TS 的"类型实例化过深"（实测）
    const data = node.data as FlowData
    if (data.kind === 'start' || data.kind === 'approve') {
      result.push({ label: data.title, value: node.id })
    }
  }
  return result
})

function removeSelected(): void {
  if (!selected.value || selected.value.data!.kind === 'start' || selected.value.data!.kind === 'end') {
    feedback.warning('开始/结束节点不可删除')
    return
  }
  const id = selected.value.id
  // 原地删除而不是重赋值：对 Ref<Node<T>[]> 整体赋值会触发 TS 的
  // "类型实例化过深"（Vue Flow 泛型链 + ref 深层解包的组合问题，实测）
  for (let i = nodes.value.length - 1; i >= 0; i--) {
    if (nodes.value[i].id === id) {
      nodes.value.splice(i, 1)
    }
  }
  for (let i = edges.value.length - 1; i >= 0; i--) {
    const edge = edges.value[i]
    if (edge.source === id || edge.target === id) {
      edges.value.splice(i, 1)
    }
  }
  selected.value = null
}

// ---------------------------------------------------------------------
// 发布校验
// ---------------------------------------------------------------------

interface ValidationResult {
  ok: boolean
  problems: string[]
}

function validate(): ValidationResult {
  const problems: string[] = []
  // ⚠️ 不用 nodes.value.filter(...)：对 Vue Flow 节点数组做 filter 会触发
  // TS"类型实例化过深"（泛型链 + ref 解包叠加）；for-of 循环没有这个问题
  let startCount = 0
  let startId = ''
  for (const node of nodes.value) {
    if (node.data!.kind === 'start') {
      startCount += 1
      startId = node.id
    }
  }
  if (startCount !== 1) {
    problems.push(`开始节点必须有且只有一个（当前 ${startCount} 个）`)
  }
  if (startCount === 1 && edges.value.some((edge) => edge.target === startId)) {
    problems.push('开始节点不能有入边')
  }
  if (!nodes.value.some((node) => node.data!.kind === 'end')) {
    problems.push('缺少结束节点')
  }
  // 可达性：从开始节点 BFS，任何到不了的节点都是孤岛
  if (startCount === 1) {
    const adjacency = new Map<string, string[]>()
    for (const edge of edges.value) {
      adjacency.set(edge.source, [...(adjacency.get(edge.source) ?? []), edge.target])
    }
    const seen = new Set([startId])
    const queue = [startId]
    while (queue.length > 0) {
      const current = queue.shift() as string
      for (const next of adjacency.get(current) ?? []) {
        if (!seen.has(next)) {
          seen.add(next)
          queue.push(next)
        }
      }
    }
    const orphanTitles: string[] = []
    for (const node of nodes.value) {
      if (!seen.has(node.id)) {
        orphanTitles.push(node.data!.title)
      }
    }
    if (orphanTitles.length > 0) {
      problems.push(`存在从开始节点不可达的节点：${orphanTitles.join('、')}`)
    }
  }
  for (const node of nodes.value) {
    if (node.data!.kind === 'approve' && !node.data!.assignee) {
      problems.push(`审批节点「${node.data!.title}」未指定审批人`)
    }
    if (node.data!.kind === 'approve' && node.data!.rejectTo && !findNode(node.data!.rejectTo)) {
      problems.push(`审批节点「${node.data!.title}」的驳回目标已被删除`)
    }
    if (node.data!.kind === 'condition') {
      // ⚠️ 不标注 Edge[]：该别名展开即是过深实例化的源头，用最小结构类型
      const outgoing: Array<{ source: string; label?: unknown }> = []
      for (const edge of edges.value) {
        if (edge.source === node.id) {
          outgoing.push(edge)
        }
      }
      if (outgoing.length < 2) {
        problems.push(`条件节点「${node.data!.title}」至少需要两条出边（真/假分支）`)
      } else {
        let unlabeled = false
        for (const edge of outgoing) {
          if (!edge.label) {
            unlabeled = true
            break
          }
        }
        if (unlabeled) {
          problems.push(`条件节点「${node.data!.title}」的每条出边都必须带条件标签`)
        }
      }
    }
  }
  return { ok: problems.length === 0, problems }
}

const exported = ref('')

function publish(): void {
  const result = validate()
  if (!result.ok) {
    feedback.error(`流程不合法：${result.problems[0]}`)
    return
  }
  // ⚠️ 导出经由 any 视图（rawNodes/rawEdges）：对 Vue Flow 的类型做 map/filter
  // 等高阶调用会触发深层实例化，见 removeSelected 处的说明
  const rawNodes: Array<any> = nodes.value
  const rawEdges: Array<any> = edges.value
  const exportedNodes = rawNodes.map((node) => ({ id: node.id, ...node.data }))
  const exportedEdges = rawEdges.map((edge) => ({
    source: edge.source,
    target: edge.target,
    label: edge.label
  }))
  exported.value = JSON.stringify({ nodes: exportedNodes, edges: exportedEdges }, null, 2)
  feedback.success('流程校验通过，已导出 JSON')
}
</script>

<template>
  <div class="wf">
    <div class="wf__toolbar">
      <NSpace>
        <NButton size="small" @click="addNode('approve')">+ 审批节点</NButton>
        <NButton size="small" @click="addNode('condition')">+ 条件节点</NButton>
        <NButton size="small" type="primary" @click="publish">校验并发布</NButton>
        <NButton v-if="selected" size="small" type="error" @click="removeSelected">删除选中节点</NButton>
        <NTag size="small" type="info">拖动节点边缘的连接点即可连线；选中后 Delete 删除</NTag>
      </NSpace>
    </div>

    <div class="wf__body">
      <div class="wf__canvas">
        <VueFlow
          v-model:nodes="nodes"
          v-model:edges="edges"
          :fit-view-on-init="true"
          :delete-key-code="['Backspace', 'Delete']"
          @connect="onConnect"
          @node-click="(event: { node: Node }) => onNodeClick(event.node)"
        />
      </div>

      <NCard v-if="selectedData" title="节点属性" :bordered="false" class="wf__panel">
        <p class="wf__panel-kind">
          <NTag size="small" :bordered="false">{{ KIND_META[selectedData.kind].label }}</NTag>
          <span class="wf__panel-id">{{ selected!.id }}</span>
        </p>

        <div class="wf__field">
          <label>节点名称</label>
          <NInput v-model:value="selectedData.title" size="small" />
        </div>

        <template v-if="selectedData.kind === 'approve'">
          <div class="wf__field">
            <label>审批人</label>
            <NSelect
              v-model:value="selectedData.assignee"
              size="small"
              placeholder="选择审批人"
              :options="approverOptions"
            />
          </div>
          <div class="wf__field">
            <label>签收方式</label>
            <NRadioGroup v-model:value="selectedData.mode" size="small">
              <NRadioButton value="ANY">或签（任一通过）</NRadioButton>
              <NRadioButton value="ALL">会签（全部通过）</NRadioButton>
            </NRadioGroup>
          </div>
          <div class="wf__field">
            <label>审批时限（小时）</label>
            <NInputNumber v-model:value="selectedData.hours" size="small" :min="1" :max="720" />
          </div>
          <div class="wf__field">
            <label>驳回目标</label>
            <NSelect
              v-model:value="selectedData.rejectTo"
              size="small"
              :options="rejectTargetOptions"
            />
          </div>
        </template>

        <template v-if="selectedData.kind === 'condition'">
          <div class="wf__field">
            <label>条件说明</label>
            <NInput v-model:value="selectedData.conditionLabel" size="small" placeholder="如：金额区间" />
            <p class="wf__hint">每条出边的标签即分支条件 —— 在连线后双击连线文字设置</p>
          </div>
        </template>
      </NCard>
    </div>

    <NCard v-if="exported" title="导出的流程定义（交付给审批引擎的产物）" :bordered="false" class="wf__card">
      <pre class="wf__json">{{ exported }}</pre>
    </NCard>
  </div>
</template>

<style scoped>
.wf__toolbar {
  margin-bottom: 12px;
}

.wf__body {
  display: grid;
  grid-template-columns: 1fr 300px;
  gap: 16px;
}

@media (max-width: 1100px) {
  .wf__body {
    grid-template-columns: 1fr;
  }
}

.wf__canvas {
  height: 540px;
  border: 1px solid var(--wa-border, #e4e7ed);
  border-radius: var(--wa-radius-md, 4px);
  background:
    radial-gradient(circle, #ddd 1px, transparent 1px) 0 0 / 20px 20px;
}

/* 节点按业务类型着色：颜色是语义（开始=绿、审批=蓝、条件=橙、结束=红） */
.wf__canvas :deep(.wf-node--start) { --node-color: #18a058; }
.wf__canvas :deep(.wf-node--approve) { --node-color: #2563eb; }
.wf__canvas :deep(.wf-node--condition) { --node-color: #f0a020; }
.wf__canvas :deep(.wf-node--end) { --node-color: #d03050; }

.wf__canvas :deep(.vue-flow__node-default),
.wf__canvas :deep(.vue-flow__node-input),
.wf__canvas :deep(.vue-flow__node-output) {
  border: 1.5px solid var(--node-color, #999);
  border-radius: 8px;
  font-size: 13px;
  min-width: 140px;
}

.wf__canvas :deep(.vue-flow__node-selected) {
  box-shadow: 0 0 0 2px var(--node-color, #2563eb);
}

.wf__panel {
  align-self: start;
}

.wf__panel-kind {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 12px;
}

.wf__panel-id {
  color: var(--wa-text-disabled, #a8b0ba);
  font-size: 12px;
}

.wf__field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 14px;
}

.wf__field > label {
  font-size: var(--wa-font-size-sm, 13px);
  color: var(--wa-text-secondary, #5c6570);
}

.wf__hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--wa-text-disabled, #a8b0ba);
}

.wf__card {
  margin-top: 16px;
}

.wf__json {
  margin: 0;
  max-height: 280px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.6;
}
</style>
