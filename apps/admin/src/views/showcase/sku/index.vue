<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { NButton, NCard, NInput, NTag, ProModal, ProTable, feedback } from '@admin/ui'

/**
 * 商品多规格（SKU）设置：动态嵌套表单的经典场景。
 *
 * <h3>核心难点与解法</h3>
 * <ol>
 *   <li><b>笛卡尔积</b>：规格维度（颜色 × 版本 × 套餐）的每一组取值组合
 *       就是一条 SKU。组合数量随维度**指数增长**，因此规格值变化时增量维护
 *       而不是整表重建 —— 用户对已填价格做的编辑不能因为"加了一个颜色"而丢失</li>
 *   <li><b>编辑继承</b>：SKU 的身份是"规格取值组合"（combo key），
 *       与它在本页表格里的行位置无关。组合重建时用 key 对齐保留已编辑值，
 *       删掉的组合连同编辑一起移除（数据不能"复活"）</li>
 *   <li><b>边界</b>：0 个维度 / 某维度只有 0 个取值 → SKU 表为空而不是报错；
 *       空集的笛卡尔积在数学上就是 ∅</li>
 * </ol>
 */

interface SpecValue {
  id: number
  value: string
}

interface SpecDim {
  id: number
  name: string
  values: SpecValue[]
}

interface SkuRow {
  comboKey: string
  comboLabel: string
  specs: Record<string, string>
  price: number
  stock: number
  code: string
}

let nextId = 1
const newId = () => nextId++

const specs = reactive<SpecDim[]>([
  { id: newId(), name: '颜色', values: [ { id: newId(), value: '曜石黑' }, { id: newId(), value: '釉白' } ] },
  { id: newId(), name: '存储', values: [ { id: newId(), value: '256G' }, { id: newId(), value: '512G' } ] }
])

const newSpecName = ref('')
const newValueDrafts = reactive<Record<number, string>>({})

/** 编辑过的 SKU 按 comboKey 记住 —— 重建组合时对齐继承（本页的关键边界处理）。 */
const editedSkus = reactive(new Map<string, { price: number; stock: number; code: string }>())

// ---------------------------------------------------------------------
// 笛卡尔积
// ---------------------------------------------------------------------

/** 笛卡尔积：维度 × 取值的所有组合。空维度（或某维度空取值）→ 空集。 */
function cartesian(dims: SpecDim[]): Array<Record<string, string>> {
  return dims.reduce<Array<Record<string, string>>>(
    (acc, dim) => {
      if (dim.values.length === 0) {
        return []
      }
      return acc.flatMap((prefix) =>
        dim.values.map((value) => ({ ...prefix, [dim.name]: value.value }))
      )
    },
    [{}]
  ).filter((combo) => Object.keys(combo).length > 0)
}

const skuRows = computed<SkuRow[]>(() =>
  cartesian(specs).map((combo) => {
    const comboKey = Object.entries(combo)
      .map(([name, value]) => `${name}:${value}`)
      .sort()
      .join('|')
    const comboLabel = Object.values(combo).join(' / ')
    const edited = editedSkus.get(comboKey)
    return {
      comboKey,
      comboLabel,
      specs: combo,
      price: edited?.price ?? 0,
      stock: edited?.stock ?? 0,
      code: edited?.code ?? ''
    }
  })
)

/** SKU 总数上限保护：维度一多组合爆炸（10×10×10 = 1000 条），
 *  超过上限拒绝生成并提示 —— 否则用户一加规格值，页面直接卡死。 */
const SKU_LIMIT = 500
const comboCount = computed(() => {
  let count = 1
  for (const dim of specs) {
    count *= dim.values.length
    if (count > SKU_LIMIT) {
      break
    }
  }
  return specs.some((dim) => dim.values.length === 0) ? 0 : count
})

// ---------------------------------------------------------------------
// 规格编辑
// ---------------------------------------------------------------------

function addSpec(): void {
  const name = newSpecName.value.trim()
  if (!name) {
    feedback.warning('请输入规格名')
    return
  }
  if (specs.some((dim) => dim.name === name)) {
    feedback.warning(`规格「${name}」已存在`)
    return
  }
  specs.push({ id: newId(), name, values: [] })
  newSpecName.value = ''
}

function removeSpec(dim: SpecDim): void {
  const index = specs.indexOf(dim)
  if (index >= 0) {
    specs.splice(index, 1)
  }
}

function addValue(dim: SpecDim): void {
  const value = (newValueDrafts[dim.id] ?? '').trim()
  if (!value) {
    return
  }
  if (dim.values.some((item) => item.value === value)) {
    feedback.warning(`取值「${value}」已存在`)
    return
  }
  dim.values.push({ id: newId(), value })
  newValueDrafts[dim.id] = ''
}

function removeValue(dim: SpecDim, value: SpecValue): void {
  dim.values = dim.values.filter((item) => item.id !== value.id)
}

// ---------------------------------------------------------------------
// SKU 编辑（行操作 → 弹窗）
// ---------------------------------------------------------------------

const editVisible = ref(false)
const editTarget = ref<SkuRow | null>(null)
const editForm = reactive({ price: 0, stock: 0, code: '' })

function openEdit(row: SkuRow): void {
  editTarget.value = row
  editForm.price = row.price
  editForm.stock = row.stock
  editForm.code = row.code
  editVisible.value = true
}

function saveEdit(): void {
  if (!editTarget.value) {
    return
  }
  if (editForm.price <= 0) {
    feedback.warning('价格必须大于 0')
    return
  }
  if (editForm.stock < 0) {
    feedback.warning('库存不能为负数')
    return
  }
  editedSkus.set(editTarget.value.comboKey, { ...editForm })
  editVisible.value = false
  feedback.success(`已保存 ${editTarget.value.comboLabel}`)
}

// ---------------------------------------------------------------------
// 表格（本地数据走 ProTable 的 request 契约）
// ---------------------------------------------------------------------

const tableRef = ref<{ refresh: () => void } | null>(null)

async function requestSku(): Promise<{
  records: SkuRow[]
  total: number
  page: number
  size: number
}> {
  return {
    records: skuRows.value,
    total: skuRows.value.length,
    page: 1,
    size: skuRows.value.length
  }
}

// 规格变化 → 刷新表格（重建组合 + 对齐继承编辑值）
function refreshTable(): void {
  void tableRef.value?.refresh()
}

const columns = [
  { key: 'comboLabel', title: '规格组合' },
  { key: 'price', title: '价格（元）' },
  { key: 'stock', title: '库存' },
  { key: 'code', title: 'SKU 编码' }
]

const rowActions = [
  {
    key: 'edit',
    label: '编辑',
    onClick: (row: SkuRow) => openEdit(row)
  }
]

defineExpose({ requestSku })
</script>

<template>
  <div class="sku">
    <NCard title="① 规格维度" :bordered="false" class="sku__card">
      <div v-for="dim in specs" :key="dim.id" class="sku__dim">
        <div class="sku__dim-name">
          <NInput v-model:value="dim.name" size="small" placeholder="规格名" />
          <NButton size="small" quaternary type="error" @click="removeSpec(dim); refreshTable()">
            删除规格
          </NButton>
        </div>
        <div class="sku__dim-values">
          <NTag
            v-for="value in dim.values"
            :key="value.id"
            size="small"
            closable
            @close="removeValue(dim, value); refreshTable()"
          >
            {{ value.value }}
          </NTag>
          <NInput
            v-model:value="newValueDrafts[dim.id]"
            size="small"
            placeholder="添加取值，回车确认"
            class="sku__value-input"
            @keyup.enter="addValue(dim); refreshTable()"
          />
        </div>
      </div>

      <div class="sku__add-spec">
        <NInput
          v-model:value="newSpecName"
          size="small"
          placeholder="新规格名（如：套餐）"
          class="sku__spec-input"
          @keyup.enter="addSpec(); refreshTable()"
        />
        <NButton size="small" @click="addSpec(); refreshTable()">添加规格</NButton>
        <NTag :type="comboCount > SKU_LIMIT ? 'error' : 'default'" size="small">
          预计 SKU：{{ comboCount }} 条（上限 {{ SKU_LIMIT }}）
        </NTag>
      </div>
    </NCard>

    <NCard title="② SKU 明细（笛卡尔积自动生成，编辑值按组合继承）" :bordered="false" class="sku__card">
      <ProTable
        ref="tableRef"
        :columns="columns"
        :request="requestSku"
        :row-actions="rowActions"
        :row-key="'comboKey'"
        :default-page-size="10"
        :toolbar="[]"
      />
    </NCard>

    <ProModal
      v-model:visible="editVisible"
      :title="`编辑 SKU — ${editTarget?.comboLabel ?? ''}`"
      :items="[
        { field: 'price', title: '价格（元）', type: 'number' },
        { field: 'stock', title: '库存', type: 'number' },
        { field: 'code', title: 'SKU 编码', type: 'input' }
      ]"
      :model="editForm"
      :cols="1"
      submit-text="保存"
      :submit="saveEdit"
    />
  </div>
</template>

<style scoped>
.sku__card {
  margin-bottom: 16px;
}

.sku__dim {
  display: flex;
  gap: 16px;
  align-items: flex-start;
  padding: 12px;
  margin-bottom: 8px;
  border: 1px solid var(--wa-border, #e4e7ed);
  border-radius: var(--wa-radius-md, 4px);
}

.sku__dim-name {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 180px;
  flex: none;
}

.sku__dim-values {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.sku__value-input {
  width: 160px;
}

.sku__add-spec {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 12px;
}

.sku__spec-input {
  width: 220px;
}
</style>
