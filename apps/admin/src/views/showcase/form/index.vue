<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import {
  NButton,
  NCard,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NRadioButton,
  NRadioGroup,
  NSelect,
  NSpace,
  NSwitch,
  NTabs,
  NTabPane,
  NTag,
  ProDescriptions,
  ProForm,
  feedback,
  type ProFormItem
} from '@admin/ui'

/**
 * 高级表单演示：四种在生产里反复出现的表单形态。
 *
 * <h3>选型判断（这是本页真正要传达的）</h3>
 * <ul>
 *   <li><b>分步表单</b>：字段多于一屏、且步骤间有先后依赖（后一步的校验
 *       依赖前一步的值）→ 拆成多个 ProForm，每步独立校验，通过才放行</li>
 *   <li><b>Tab 表单</b>：字段多但分组之间<strong>无依赖</strong> → 单实例
 *       ProForm + groups 分组，一次校验一次提交 —— 多实例各自提交是常见的
 *       反模式（提交到一半切 Tab，用户不知道哪些组已保存）</li>
 *   <li><b>联动表单</b>：字段间有"显隐/选项/禁用"依赖 → 联动规则集中声明，
 *       而不是散在各个 change 回调里</li>
 *   <li><b>Schema 驱动</b>：表单结构需要运行时可变 → 把 ProForm 的 items
 *       变成数据（本页演示"编辑 JSON → 实时出表单"）</li>
 * </ul>
 */

// ---------------------------------------------------------------------
// ① 分步表单：3 步，每步独立校验
// ---------------------------------------------------------------------

type StepForm = {
  name: string
  cluster: string
  replicas: number
  cpu: number
  memory: number
  envVars: string
}

const step = ref(0)
const STEPS = ['基础信息', '资源配置', '确认创建']
const stepForm = reactive<StepForm>({
  name: '',
  cluster: 'prod-1',
  replicas: 2,
  cpu: 2,
  memory: 4,
  envVars: ''
})

/** 每步一个 schema（items），由 ProForm 渲染并校验 —— 校验通过才允许前进。 */
const stepSchemas: ProFormItem[][] = [
  [{ field: 'name', title: '服务名', type: 'input', props: { placeholder: '小写字母与中划线' } }],
  [
    { field: 'replicas', title: '副本数', type: 'number', props: { min: 1, max: 32 } },
    { field: 'cpu', title: 'CPU（核）', type: 'number', props: { min: 1, max: 64 } },
    { field: 'memory', title: '内存（GB）', type: 'number', props: { min: 1, max: 256 } },
    { field: 'envVars', title: '环境变量', type: 'textarea' }
  ],
  []
]

/** 第二步的跨字段校验：资源配比（内存/CPU 比例超过 8 属于畸形规格）。
 *  这类"字段间"约束没法表达在单字段 rules 里，必须在步骤推进前显式检查。 */
function validateStep(current: number): string | null {
  if (current === 0 && stepForm.name.trim().length === 0) {
    return '请填写服务名'
  }
  if (current === 1 && stepForm.memory / stepForm.cpu > 8) {
    return '内存/CPU 配比超过 8:1，属于畸形规格，请调整'
  }
  return null
}

async function nextStep(): Promise<void> {
  const problem = validateStep(step.value)
  if (problem) {
    feedback.warning(problem)
    return
  }
  step.value += 1
}

function submitDeployment(): void {
  const problem = validateStep(0) ?? validateStep(1)
  if (problem) {
    feedback.warning(problem)
    step.value = 0
    return
  }
  feedback.success(`部署请求已提交：${stepForm.name} × ${stepForm.replicas}`)
}

/** 确认页的数据视图：ProDescriptions 消费 items({key,label}) + data 对象。 */
const stepSummaryItems = [
  { key: 'name', label: '服务名' },
  { key: 'cluster', label: '集群' },
  { key: 'replicas', label: '副本数' },
  { key: 'spec', label: '资源规格' },
  { key: 'envVars', label: '环境变量' }
]

const stepSummaryData = computed<Record<string, string>>(() => ({
  name: stepForm.name || '—',
  cluster: stepForm.cluster,
  replicas: String(stepForm.replicas),
  spec: `${stepForm.cpu}C / ${stepForm.memory}G`,
  envVars: stepForm.envVars.trim() ? stepForm.envVars : '（无）'
}))

// ---------------------------------------------------------------------
// ② 联动表单：规则集中声明，而不是散在各 change 回调
// ---------------------------------------------------------------------

const linkage = reactive({
  vendor: 'aliyun',
  region: 'cn-hangzhou',
  billing: 'postpaid',
  months: 12,
  autoRenew: false,
  payChannel: 'balance'
})

/** 厂商 → 可用区域。选项联动：换个厂商，区域集合整体变化，当前值必须重置。 */
const REGION_BY_VENDOR: Record<string, Array<{ label: string; value: string }>> = {
  aliyun: [
    { label: '华东1（杭州）', value: 'cn-hangzhou' },
    { label: '华北2（北京）', value: 'cn-beijing' },
    { label: '华南1（深圳）', value: 'cn-shenzhen' }
  ],
  tencent: [
    { label: '上海', value: 'ap-shanghai' },
    { label: '广州', value: 'ap-guangzhou' }
  ]
}

const vendorOptions = Object.entries(REGION_BY_VENDOR).map(([value]) => ({ label: value, value }))

/** 联动副作用统一收敛在一个函数里：换厂商 → 区域重置 → 计费方式不可用项收窄。
 *  散在各 change 回调里的联动，最终的调试体验是"改了 A 怎么 B 也变了"。 */
function onVendorChange(): void {
  const regions = REGION_BY_VENDOR[linkage.vendor] ?? []
  if (!regions.some((region) => region.value === linkage.region)) {
    linkage.region = regions[0]?.value ?? ''
  }
}

/** 显隐联动：包年才需要"购买时长"；自动续费才需要"支付渠道"。 */
const showMonths = computed(() => linkage.billing === 'prepaid')
const showPayChannel = computed(() => linkage.autoRenew)

// ---------------------------------------------------------------------
// ③ Schema 驱动：编辑 JSON → 实时渲染表单
// ---------------------------------------------------------------------

const DEMO_SCHEMA = `[
  { "field": "notify", "title": "通知方式", "type": "select",
    "options": [
      { "label": "邮件", "value": "email" },
      { "label": "短信", "value": "sms" },
      { "label": "不通知", "value": "none" }
    ] },
  { "field": "email", "title": "邮箱", "type": "input",
    "visibleWhen": { "field": "notify", "value": "email" } },
  { "field": "phone", "title": "手机号", "type": "input",
    "visibleWhen": { "field": "notify", "value": "sms" } },
  { "field": "autoRenew", "title": "自动续费", "type": "switch" },
  { "field": "months", "title": "续费时长（月）", "type": "number",
    "visibleWhen": { "field": "autoRenew", "value": true } }
]`

const schemaText = ref(DEMO_SCHEMA)
const schemaItems = ref<ProFormItem[]>([])
const schemaError = ref('')
const schemaValues = ref<Record<string, unknown>>({})

/**
 * 声明式联动：schema 里的 `visibleWhen: { field, value }` 表示
 * "当 field 的当前值等于 value 时本字段才渲染"。
 *
 * <h3>为什么是"过滤 items"而不是往表单内核塞显隐回调</h3>
 * ProForm 的 items 是数据 —— 联动同样用数据表达，渲染层只需要一个
 * computed 过滤。这与"条件散在 change 回调里"的区别和联动表单一节
 * 演示的一样：规则可序列化、可校验、可由后端下发。
 *
 * <h3>⚠️ 隐藏字段的值必须剥离</h3>
 * 字段隐藏 ≠ 字段无值（用户先选了"邮件"填了邮箱、又切回"不通知"）。
 * 提交时把不可见字段的值从载荷里剥离 —— 否则后端会收到
 * "notify=none 却带 email"的自相矛盾数据。这是 schema 驱动联动
 * 最常被漏掉的边界。
 */
const effectiveSchemaItems = computed<ProFormItem[]>(() =>
  schemaItems.value.filter((item) => {
    const linkage = (item as { visibleWhen?: { field: string; value: unknown } }).visibleWhen
    if (!linkage) {
      return true
    }
    return schemaValues.value[linkage.field] === linkage.value
  })
)

const hiddenFieldNames = computed(() => {
  const visible = new Set(effectiveSchemaItems.value.map((item) => item.field))
  return schemaItems.value.filter((item) => !visible.has(item.field)).map((item) => item.field)
})

/** 解析失败不清空上一次的可用 schema：留着旧表单，用户改完 JSON 还能继续。 */
function applySchema(): void {
  try {
    const parsed = JSON.parse(schemaText.value) as Array<Record<string, unknown>>
    if (!Array.isArray(parsed)) {
      throw new Error('schema 必须是数组')
    }
    for (const item of parsed) {
      if (!item.field || !item.title) {
        throw new Error('每一项都必须有 field 与 title')
      }
    }
    schemaItems.value = parsed as unknown as ProFormItem[]
    schemaError.value = ''
  } catch (error) {
    schemaError.value = error instanceof Error ? error.message : 'JSON 解析失败'
  }
}

/** 联动驱动：实时值回流（ProForm 的 change 事件），显隐由此派生。
 *
 *  ⚠️ 必须做浅相等守卫：schemaValues 回写会经过 `:model` → setValues
 *  → formData → 再触发 change 的回环，每次都是新对象 —— 不判等
 *  就是 "Maximum recursive updates exceeded"（实测）。值没变就不回写，
 *  回环在第一圈终止。 */
function onSchemaChange(values: Record<string, unknown>): void {
  const prev = schemaValues.value
  const keys = new Set([...Object.keys(prev), ...Object.keys(values)])
  for (const key of keys) {
    if (prev[key] !== values[key]) {
      schemaValues.value = values
      return
    }
  }
}

function onSchemaSubmit(values: Record<string, unknown>): void {
  schemaValues.value = values
  // 剥离不可见字段的值（见 effectiveSchemaItems 的边界说明）
  const payload: Record<string, unknown> = { ...values }
  for (const field of hiddenFieldNames.value) {
    delete payload[field]
  }
  schemaResultItems.value = Object.keys(payload).map((key) => ({ key, label: key }))
  schemaResultData.value = Object.fromEntries(
    Object.entries(payload).map(([key, value]) => [key, String(value)])
  )
  feedback.success('提交成功（隐藏字段已从载荷剥离），请看右侧结果')
}

const schemaResultItems = ref<Array<{ key: string; label: string }>>([])
const schemaResultData = ref<Record<string, string>>({})
</script>

<template>
  <div class="adv-form">
    <NTabs type="line" :animated="false">
      <!-- ============ ① 分步表单 ============ -->
      <NTabPane name="steps" tab="分步表单">
        <NCard :bordered="false" class="adv-form__card">
          <ol class="adv-form__steps">
            <li
              v-for="(title, index) in STEPS"
              :key="title"
              class="adv-form__step"
              :class="{
                'adv-form__step--active': index === step,
                'adv-form__step--done': index < step
              }"
            >
              <span class="adv-form__step-index">{{ index + 1 }}</span>
              <span>{{ title }}</span>
            </li>
          </ol>

          <div v-if="step === 0" class="adv-form__step-body">
            <NForm label-placement="top">
              <NFormItem label="服务名" required>
                <NInput v-model:value="stepForm.name" placeholder="小写字母与中划线" />
              </NFormItem>
              <NFormItem label="集群">
                <NSelect
                  v-model:value="stepForm.cluster"
                  :options="[
                    { label: 'prod-1（华东）', value: 'prod-1' },
                    { label: 'prod-2（华北）', value: 'prod-2' }
                  ]"
                />
              </NFormItem>
            </NForm>
          </div>

          <div v-else-if="step === 1" class="adv-form__step-body">
            <ProForm
              :items="stepSchemas[1]"
              :model="stepForm"
              :cols="2"
              label-placement="top"
              :submit="nextStep"
              submit-text="下一步"
            />
          </div>

          <div v-else class="adv-form__step-body">
            <ProDescriptions :items="stepSummaryItems" :data="stepSummaryData" :column="2" size="small" />
            <NSpace justify="end" class="adv-form__actions">
              <NButton @click="step = 1">上一步</NButton>
              <NButton type="primary" @click="submitDeployment">提交部署</NButton>
            </NSpace>
          </div>

          <NSpace v-if="step === 0" justify="end" class="adv-form__actions">
            <NButton type="primary" @click="nextStep">下一步</NButton>
          </NSpace>
        </NCard>
      </NTabPane>

      <!-- ============ ② 联动表单 ============ -->
      <NTabPane name="linkage" tab="联动表单">
        <NCard :bordered="false" class="adv-form__card">
          <NForm label-placement="left" label-width="110">
            <NFormItem label="云厂商">
              <NSelect
                v-model:value="linkage.vendor"
                :options="vendorOptions"
                @update:value="onVendorChange"
              />
            </NFormItem>
            <NFormItem label="区域">
              <NSelect
                v-model:value="linkage.region"
                :options="REGION_BY_VENDOR[linkage.vendor] ?? []"
              />
            </NFormItem>
            <NFormItem label="计费方式">
              <NRadioGroup v-model:value="linkage.billing">
                <NRadioButton value="postpaid">按量付费</NRadioButton>
                <NRadioButton value="prepaid">包年包月</NRadioButton>
              </NRadioGroup>
            </NFormItem>
            <NFormItem v-if="showMonths" label="购买时长（月）">
              <NInputNumber v-model:value="linkage.months" :min="1" :max="36" />
            </NFormItem>
            <NFormItem label="自动续费">
              <NSwitch v-model:value="linkage.autoRenew" />
            </NFormItem>
            <NFormItem v-if="showPayChannel" label="支付渠道">
              <NSelect
                v-model:value="linkage.payChannel"
                :options="[
                  { label: '余额', value: 'balance' },
                  { label: '对公转账', value: 'transfer' }
                ]"
              />
            </NFormItem>
          </NForm>
          <p class="adv-form__note">
            联动规则集中在 <NTag size="small">onVendorChange</NTag> 与两个
            <NTag size="small">computed</NTag> 里：
            换厂商会校验"当前区域是否仍可用"并重置；显隐由计费方式与开关派生，
            而不是在 change 回调里手动 set 一个 visible 布尔值。
          </p>
        </NCard>
      </NTabPane>

      <!-- ============ ③ Schema 驱动 ============ -->
      <NTabPane name="schema" tab="Schema 驱动">
        <div class="adv-form__schema">
          <NCard title="表单 Schema（可编辑）" :bordered="false" class="adv-form__card">
            <NInput
              v-model:value="schemaText"
              type="textarea"
              :autosize="{ minRows: 12, maxRows: 18 }"
              class="adv-form__schema-editor"
            />
            <NSpace justify="end" class="adv-form__actions">
              <NTag v-if="schemaError" type="error" size="small">{{ schemaError }}</NTag>
              <NButton type="primary" @click="applySchema">应用 Schema</NButton>
            </NSpace>
          </NCard>
          <NCard title="渲染结果（visibleWhen 字段随当前值显隐）" :bordered="false" class="adv-form__card">
            <ProForm
              v-if="schemaItems.length > 0"
              :items="effectiveSchemaItems"
              :model="schemaValues"
              :cols="2"
              label-placement="top"
              @change="onSchemaChange"
              :submit="onSchemaSubmit"
            />
            <p v-else class="adv-form__note">点击"应用 Schema"渲染表单。</p>
            <p class="adv-form__note">
              把"通知方式"切到 邮件 / 短信 / 不通知，观察邮箱与手机号字段的显隐；
              打开"自动续费"出现时长字段。
              提交时不可见字段的值会从载荷剥离（先填邮箱再切"不通知"再提交即可验证）。
            </p>
            <ProDescriptions
              v-if="schemaResultItems.length > 0"
              class="adv-form__result"
              title="提交值"
              :items="schemaResultItems"
              :data="schemaResultData"
              :column="1"
              size="small"
            />
          </NCard>
        </div>
      </NTabPane>
    </NTabs>
  </div>
</template>

<style scoped>
.adv-form__card {
  margin-bottom: 16px;
}

/* 步骤条：自绘而不是引 NSteps —— 三个状态的样式远小于一个组件的体积 */
.adv-form__steps {
  display: flex;
  gap: 32px;
  margin: 0 0 24px;
  padding: 0;
  list-style: none;
}

.adv-form__step {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--wa-text-disabled, #a8b0ba);
  font-size: var(--wa-font-size-md, 14px);
}

.adv-form__step-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: 1px solid currentcolor;
  border-radius: 50%;
  font-size: 12px;
}

.adv-form__step--active {
  color: var(--wa-color-primary, #2563eb);
  font-weight: 600;
}

.adv-form__step--done {
  color: var(--wa-color-success, #18a058);
}

.adv-form__step-body {
  max-width: 720px;
}

.adv-form__actions {
  margin-top: 16px;
}

.adv-form__note {
  margin: 8px 0 0;
  color: var(--wa-text-secondary, #5c6570);
  font-size: var(--wa-font-size-sm, 13px);
}

.adv-form__schema {
  display: grid;
  grid-template-columns: minmax(360px, 5fr) minmax(400px, 7fr);
  gap: 16px;
  align-items: start;
}

@media (max-width: 1100px) {
  .adv-form__schema {
    grid-template-columns: 1fr;
  }
}

.adv-form__schema-editor {
  font-family: var(--wa-font-mono, Consolas, monospace);
  font-size: 13px;
}

.adv-form__result {
  margin-top: 16px;
}
</style>
