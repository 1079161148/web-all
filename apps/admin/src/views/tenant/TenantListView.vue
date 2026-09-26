<script setup lang="ts">
import { computed, ref } from 'vue'
import { NAlert, NSpace, NTag, PageContainer, ProModal, ProTable, feedback } from '@admin/ui'
import type { ProColumn, ProFormItem, ProRowAction } from '@admin/ui'
import type {
  PlanResponse,
  ProvisionTenantRequest,
  TenantProvisionResponse,
  TenantResponse,
  TenantUsageResponse
} from '@admin/api'
import {
  activateTenantAction,
  closeTenantAction,
  fetchTenantPage,
  fetchTenantPlans,
  fetchTenantUsage,
  provisionTenantAction,
  renewTenantAction,
  suspendTenantAction
} from '@/api/tenant'

/**
 * 租户管理列表页。
 *
 * <h3>本页是「ProTable 抽象是否有效」的验证样本</h3>
 * 整个页面的核心逻辑（搜索、分页、排序、状态矩阵、行操作）都声明在配置里，
 * 业务代码只描述「有什么列、有什么操作」。
 * 目标：典型 CRUD 页面 ≤ 60 行 script（设计文档 §11.4）。
 *
 * <p>对比：RuoYi 同类页面需要 400~600 行，其中大部分是重复的 loading /
 * 分页 / 搜索 / 错误处理代码。
 */

/**
 * ProTable 的操作句柄。
 *
 * 这里显式声明最小接口而非 `InstanceType<typeof ProTable>`：
 * 泛型组件无法用 `InstanceType` 推导，而显式声明同时把「父组件能调用哪些方法」
 * 变成一份可读的契约 —— 比暴露整个组件实例更清晰，也更容易 mock 测试。
 */
const tableRef = ref<{ reload: () => void; refresh: () => void } | null>(null)

/**
 * 取行 ID，缺失即抛错。
 *
 * <p>生成类型里 `id` 是可选的（OpenAPI 未标注 required），但业务上它必然存在。
 * 这里选择<b>显式报错</b>而不是 `?? 0` 兜底：
 * 用 0 会静默地拿一个不存在的 ID 去调接口，得到"租户不存在"这类
 * 与真实原因（数据缺主键）无关的提示，排查方向会被带偏。
 */
function requireId(row: TenantResponse): number {
  if (row.id === undefined) {
    throw new Error('租户数据缺少主键 ID，无法执行该操作')
  }
  return row.id
}

const statusOptions = [
  { label: '待激活', value: 'PENDING' },
  { label: '正常', value: 'ACTIVE' },
  { label: '已暂停', value: 'SUSPENDED' },
  { label: '已过期', value: 'EXPIRED' },
  { label: '已关闭', value: 'CLOSED' }
]

const planOptions = [
  { label: '免费版', value: 'FREE' },
  { label: '专业版', value: 'PRO' },
  { label: '企业版', value: 'ENTERPRISE' }
]

/**
 * 列定义。
 *
 * 注意这里如何声明能力：
 * - `search` 声明后搜索区自动生成控件，无需手写表单
 * - `render: 'datetime'` 声明后自动做时间格式化
 * - `sortable` 声明后走服务端排序，参数名就是 key
 */
const columns: ProColumn<TenantResponse>[] = [
  { key: 'code', title: '租户编码', width: 160, search: 'input', searchPlaceholder: '模糊匹配' },
  { key: 'name', title: '租户名称', minWidth: 180, search: 'input' },
  {
    key: 'status',
    title: '状态',
    width: 110,
    search: 'select',
    options: statusOptions
  },
  {
    key: 'planName',
    title: '套餐',
    width: 110,
    search: 'select',
    options: planOptions,
    // 展示字段是 planName（企业版），而接口筛选参数是 planCode（ENTERPRISE）。
    // 不声明 searchParam 时提交的 key 是 planName，后端收不到参数 → 筛选静默失效
    searchParam: 'planCode',
    searchPlaceholder: '选择套餐'
  },
  { key: 'remainingUsers', title: '剩余用户数', width: 120 },
  { key: 'remainingStorageBytes', title: '剩余存储', width: 120, render: 'bytes' },
  { key: 'expireTime', title: '到期时间', width: 160, sortable: true, render: 'datetime' },
  { key: 'createTime', title: '创建时间', width: 160, sortable: true, render: 'datetime' }
]

/** 行操作：权限码已声明，P1 接入按钮权限指令后即可自动生效。 */
const rowActions: ProRowAction<TenantResponse>[] = [
  {
    key: 'usage',
    label: '用量',
    permission: 'iam:tenant:query',
    onClick: (row) => openUsage(row)
  },
  {
    key: 'activate',
    label: '激活',
    disabled: (row) => row.status === 'ACTIVE',
    onClick: async (row) => {
      await activateTenantAction(requireId(row))
      feedback.success(`租户「${row.name}」已激活`)
      tableRef.value?.refresh()
    }
  },
  {
    key: 'suspend',
    label: '暂停',
    disabled: (row) => row.status !== 'ACTIVE',
    confirm: (row) => `确定暂停租户「${row.name}」？暂停后该租户将无法访问系统。`,
    onClick: async (row) => {
      await suspendTenantAction(requireId(row), '管理后台手动暂停')
      feedback.success(`租户「${row.name}」已暂停`)
      tableRef.value?.refresh()
    }
  },
  {
    key: 'renew',
    label: '续期',
    onClick: async (row) => {
      await renewTenantAction(requireId(row), { months: 12 })
      feedback.success(`租户「${row.name}」已续期 12 个月`)
      tableRef.value?.refresh()
    }
  },
  {
    key: 'close',
    label: '关闭',
    danger: true,
    disabled: (row) => row.status === 'CLOSED',
    confirm: (row) => `关闭后租户「${row.name}」将永久不可恢复（终态），确定继续？`,
    onClick: async (row) => {
      await closeTenantAction(requireId(row), '管理后台手动关闭')
      feedback.success(`租户「${row.name}」已关闭`)
      tableRef.value?.refresh()
    }
  }
]

// ---------------------------------------------------------------------
// 一键开通（向导）
// ---------------------------------------------------------------------

const provisionVisible = ref(false)
const provisionSubmitting = ref(false)
const plans = ref<PlanResponse[]>([])
const provisionResult = ref<TenantProvisionResponse | null>(null)
const resultVisible = ref(false)

/**
 * 开通向导的套餐下拉来自后端 /plans 而不是写死：
 * 套餐的配额定义在服务端（套餐注册表），前端抄一份迟早漂移 ——
 * 表现是"下拉里写着 100 用户，实际套餐已改成 50"。
 */
const provisionItems = computed<ProFormItem[]>(() => [
  {
    field: 'code',
    title: '租户编码',
    required: true,
    placeholder: '6~32 位小写字母、数字或连字符，如 acme-corp',
    tip: '创建后不可修改，将作为租户在所有系统中的稳定标识'
  },
  { field: 'name', title: '租户名称', required: true, placeholder: '如：Acme 科技' },
  {
    field: 'planCode',
    title: '套餐',
    type: 'select',
    required: true,
    value: 'PRO',
    options: plans.value.map((p) => ({
      label: `${p.name}（${p.maxUsers ?? 0} 用户）`,
      value: String(p.code ?? '')
    }))
  },
  {
    field: 'adminUsername',
    title: '管理员账号',
    required: true,
    value: 'admin',
    tip: '账号在租户内唯一；不同租户可以用相同账号（例如都用 admin），互不冲突'
  },
  {
    field: 'adminPassword',
    title: '初始密码',
    placeholder: '留空则使用平台初始密码',
    tip: '只在开通结果里显示一次，请立即转交租户管理员'
  }
])

async function openProvision(): Promise<void> {
  provisionResult.value = null
  provisionVisible.value = true
  if (plans.value.length === 0) {
    try {
      plans.value = await fetchTenantPlans()
    } catch (error) {
      feedback.error(error instanceof Error ? error.message : '读取套餐失败')
    }
  }
}

/**
 * 显式构造 payload 而不是断言表单值：后端需要哪些字段在这一处可见。
 * adminPassword 为空时不下发该字段 —— 让后端走"平台初始密码"的默认逻辑，
 * 与前端自己生成一个空字符串是两回事。
 */
async function submitProvision(values: Record<string, unknown>): Promise<void> {
  const payload: ProvisionTenantRequest = {
    code: String(values.code ?? ''),
    name: String(values.name ?? ''),
    planCode: String(values.planCode ?? 'PRO'),
    adminUsername: String(values.adminUsername ?? 'admin'),
    ...(values.adminPassword ? { adminPassword: String(values.adminPassword) } : {})
  }
  provisionSubmitting.value = true
  try {
    provisionResult.value = await provisionTenantAction(payload)
    provisionVisible.value = false
    resultVisible.value = true
    tableRef.value?.reload()
  } finally {
    provisionSubmitting.value = false
  }
}

// ---------------------------------------------------------------------
// 用量看板
// ---------------------------------------------------------------------

const usageVisible = ref(false)
const usageLoading = ref(false)
const usage = ref<TenantUsageResponse | null>(null)
const usageTenantId = ref<number | null>(null)

function openUsage(row: TenantResponse): void {
  usageTenantId.value = requireId(row)
  usage.value = null
  usageVisible.value = true
  void loadUsage()
}

async function loadUsage(): Promise<void> {
  if (usageTenantId.value === null) {
    return
  }
  usageLoading.value = true
  try {
    usage.value = await fetchTenantUsage(usageTenantId.value)
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '读取用量失败')
  } finally {
    usageLoading.value = false
  }
}

/** 已用占比（0~100）。总量为 0 时不显示进度（无意义）。 */
function percent(used: number, initial: number): number {
  if (initial <= 0) {
    return 0
  }
  return Math.min(100, Math.round((used / initial) * 100))
}

function statusLabel(value: string | undefined): string {
  return statusOptions.find((option) => option.value === value)?.label ?? String(value ?? '-')
}

function formatDateTime(value: string | undefined): string {
  return value ? new Date(value).toLocaleString() : '-'
}
</script>

<template>
  <PageContainer
    title="租户管理"
    description="租户是系统隔离的顶层单位；「一键开通」会同时初始化租户管理员并激活"
  >
    <ProTable
      ref="tableRef"
      :columns="columns"
      :request="fetchTenantPage"
      :toolbar="['create', 'refresh']"
      :row-actions="rowActions"
      empty-action-text="开通第一个租户"
      @create="openProvision"
      @empty-action="openProvision"
    />
  </PageContainer>

  <!-- 一键开通向导 -->
  <ProModal
    v-model:visible="provisionVisible"
    title="一键开通租户"
    :items="provisionItems"
    :cols="1"
    :loading="provisionSubmitting"
    submit-text="开通"
    :submit="submitProvision"
    @error="(error: unknown) => feedback.error(error instanceof Error ? error.message : '开通失败')"
  >
    <p class="tenant-page__wizard-tip">
      将一次完成：创建租户 → 初始化「租户管理员」角色与管理员账号 → 激活。
      任一步失败整体回滚，不会留下无法进入的半成品租户。管理员账号计入该租户的用户配额。
    </p>
  </ProModal>

  <!-- 开通结果：初始密码只在这里显示一次 -->
  <ProModal v-model:visible="resultVisible" title="开通成功" :width="520" submit-text="完成">
    <n-space vertical :size="12">
      <n-alert type="success" :bordered="false">
        租户已开通并激活，管理员现在就能登录使用。
      </n-alert>
      <div v-if="provisionResult" class="tenant-page__result">
        <div>租户编码：<b>{{ provisionResult.tenantCode }}</b></div>
        <div>套餐：{{ provisionResult.planCode }}</div>
        <div>管理员账号：<b>{{ provisionResult.adminUsername }}</b></div>
        <div>
          初始密码：<b class="tenant-page__password">{{ provisionResult.initialPassword }}</b>
        </div>
      </div>
      <p class="tenant-page__wizard-tip">
        初始密码只在本次显示，请立即转交租户管理员；遗失后只能由平台重置。
      </p>
    </n-space>
  </ProModal>

  <!-- 用量看板 -->
  <ProModal
    v-model:visible="usageVisible"
    title="租户用量"
    :width="600"
    :loading="usageLoading"
    submit-text="刷新"
    @success="loadUsage"
  >
    <template v-if="usage">
      <n-space vertical :size="14">
        <div class="tenant-page__usage-head">
          <span class="tenant-page__usage-name">
            {{ usage.tenantName }}（{{ usage.tenantCode }}）
          </span>
          <NTag
            size="small"
            :bordered="false"
            :type="usage.effectiveStatus === 'ACTIVE' ? 'success' : 'warning'"
          >
            {{ statusLabel(usage.effectiveStatus) }}
          </NTag>
        </div>
        <div class="tenant-page__meta">
          套餐：{{ usage.planName }} · 实时用户数：{{ usage.liveUsers }} ·
          到期：{{ formatDateTime(usage.expireTime) }}
        </div>
        <div v-for="dimension in usage.quota" :key="dimension.type" class="tenant-page__quota">
          <div class="tenant-page__quota-head">
            <span>{{ dimension.label }}</span>
            <span class="tenant-page__quota-num">
              已用 {{ dimension.used ?? 0 }} / {{ dimension.initial ?? 0 }}（余 {{ dimension.remaining ?? 0 }}）
            </span>
          </div>
          <div class="tenant-page__quota-bar">
            <div
              class="tenant-page__quota-fill"
              :class="{ 'tenant-page__quota-fill--full': percent(dimension.used ?? 0, dimension.initial ?? 0) >= 90 }"
              :style="{ width: percent(dimension.used ?? 0, dimension.initial ?? 0) + '%' }"
            ></div>
          </div>
        </div>
      </n-space>
    </template>
  </ProModal>
</template>

<style scoped>
.tenant-page__wizard-tip {
  margin: 0;
  font-size: var(--wa-font-size-xs, 12px);
  line-height: 1.6;
  color: var(--wa-text-disabled, #a8b0ba);
}

.tenant-page__result {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-xs, 4px);
  padding: var(--wa-spacing-md, 12px);
  font-size: var(--wa-font-size-md, 14px);
  background: var(--wa-fill-light, #f5f7fa);
  border-radius: var(--wa-radius-md, 4px);
}

.tenant-page__password {
  font-family: monospace;
  font-size: 15px;
  color: var(--wa-color-primary, #2080f0);
}

.tenant-page__usage-head {
  display: flex;
  align-items: center;
  gap: var(--wa-spacing-sm, 8px);
}

.tenant-page__usage-name {
  font-size: var(--wa-font-size-md, 14px);
  color: var(--wa-text-primary, #1f2329);
}

.tenant-page__meta {
  font-size: var(--wa-font-size-xs, 12px);
  color: var(--wa-text-disabled, #a8b0ba);
}

.tenant-page__quota-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 4px;
  font-size: var(--wa-font-size-xs, 12px);
  color: var(--wa-text-primary, #1f2329);
}

.tenant-page__quota-num {
  color: var(--wa-text-disabled, #a8b0ba);
  font-variant-numeric: tabular-nums;
}

.tenant-page__quota-bar {
  height: 6px;
  background: var(--wa-fill-light, #eef0f3);
  border-radius: 3px;
  overflow: hidden;
}

.tenant-page__quota-fill {
  height: 100%;
  background: var(--wa-color-primary, #2080f0);
  border-radius: 3px;
  transition: width 0.3s ease;
}

/* 用量接近上限时变红：这是"该提醒续费/升级了"的信号，不能等超额被拒才发现 */
.tenant-page__quota-fill--full {
  background: var(--wa-color-error, #d03050);
}
</style>
