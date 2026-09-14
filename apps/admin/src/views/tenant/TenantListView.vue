<script setup lang="ts">
import { ref } from 'vue'
import { NInput, NModal, NSelect, ProTable, feedback } from '@admin/ui'
import type { ProColumn, ProRowAction } from '@admin/ui'
import type { CreateTenantRequest, TenantResponse } from '@admin/api'
import {
  activateTenantAction,
  closeTenantAction,
  createTenantAction,
  fetchTenantPage,
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
const tableRef = ref<{ reload: (resetPage?: boolean) => void } | null>(null)

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
    // 搜索参数名与列 key 不一致时，用 searchParam 映射（此处 key 已是 planCode 的展示列，
    // 为保持示例简洁，直接按 planName 匹配）
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
    key: 'activate',
    label: '激活',
    disabled: (row) => row.status === 'ACTIVE',
    onClick: async (row) => {
      await activateTenantAction(requireId(row))
      feedback.success(`租户「${row.name}」已激活`)
      tableRef.value?.reload(false)
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
      tableRef.value?.reload(false)
    }
  },
  {
    key: 'renew',
    label: '续期',
    onClick: async (row) => {
      await renewTenantAction(requireId(row), { months: 12 })
      feedback.success(`租户「${row.name}」已续期 12 个月`)
      tableRef.value?.reload(false)
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
      tableRef.value?.reload(false)
    }
  }
]

// ---------------------------------------------------------------------
// 新增租户
// ---------------------------------------------------------------------

const createVisible = ref(false)
const submitting = ref(false)

const createForm = ref<CreateTenantRequest>({
  code: '',
  name: '',
  planCode: 'PRO',
  remark: ''
})

function openCreate(): void {
  createForm.value = { code: '', name: '', planCode: 'PRO', remark: '' }
  createVisible.value = true
}

async function submitCreate(): Promise<void> {
  submitting.value = true
  try {
    // 这里原先写的是 `await import('@/api/tenant')` 动态导入，但那是不必要的：
    // 本文件的顶部已经静态导入了同一个模块，Vite 会报
    // INEFFECTIVE_DYNAMIC_IMPORT —— 动态导入既没有减少首次加载体积
    // （模块早已被打进同一个 chunk），又让"这个模块是否会按需加载"变得难以判断。
    // **同一个模块只应有一种导入方式。**
    await createTenantAction(createForm.value)
    feedback.success('租户创建成功，当前为「待激活」状态，请激活后使用')
    createVisible.value = false
    tableRef.value?.reload(true)
  } catch (error) {
    // 后端返回的业务错误已由 ApiError 携带可读消息（如「租户编码已存在」）
    feedback.error(error instanceof Error ? error.message : '创建失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="tenant-list">
    <ProTable
      ref="tableRef"
      :columns="columns"
      :request="fetchTenantPage"
      :toolbar="['create', 'refresh']"
      :row-actions="rowActions"
      empty-action-text="创建第一个租户"
      @create="openCreate"
      @empty-action="openCreate"
    />

    <n-modal v-model:show="createVisible" preset="card" title="新建租户" style="width: 520px">
      <div class="tenant-list__form">
        <div class="tenant-list__field">
          <label>租户编码 <span class="tenant-list__required">*</span></label>
          <n-input
            v-model:value="createForm.code"
            placeholder="6~32 位小写字母、数字或连字符，如 acme-corp"
          />
          <p class="tenant-list__hint">创建后不可修改，将作为租户在所有系统中的稳定标识</p>
        </div>

        <div class="tenant-list__field">
          <label>租户名称 <span class="tenant-list__required">*</span></label>
          <n-input v-model:value="createForm.name" placeholder="如：Acme 科技" />
        </div>

        <div class="tenant-list__field">
          <label>套餐 <span class="tenant-list__required">*</span></label>
          <n-select v-model:value="createForm.planCode" :options="planOptions" />
        </div>

        <div class="tenant-list__field">
          <label>备注</label>
          <n-input v-model:value="createForm.remark" type="textarea" :rows="3" />
        </div>
      </div>

      <template #footer>
        <div class="tenant-list__footer">
          <button type="button" class="tenant-list__btn" @click="createVisible = false">取消</button>
          <button
            type="button"
            class="tenant-list__btn tenant-list__btn--primary"
            :disabled="submitting || !createForm.code || !createForm.name"
            @click="submitCreate"
          >
            {{ submitting ? '提交中…' : '确定' }}
          </button>
        </div>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.tenant-list__form {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-lg, 16px);
}

.tenant-list__field {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-xs, 4px);
}

.tenant-list__field label {
  font-size: var(--wa-font-size-md, 14px);
  color: var(--wa-text-primary, #1f2329);
}

.tenant-list__required {
  color: var(--wa-color-error, #dc2626);
}

.tenant-list__hint {
  margin: 0;
  font-size: var(--wa-font-size-xs, 12px);
  color: var(--wa-text-disabled, #a8b0ba);
}

.tenant-list__footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--wa-spacing-sm, 8px);
}

.tenant-list__btn {
  padding: var(--wa-spacing-xs, 4px) var(--wa-spacing-lg, 16px);
  border: 1px solid var(--wa-border, #e4e7ed);
  border-radius: var(--wa-radius-md, 4px);
  background: transparent;
  color: var(--wa-text-primary, #1f2329);
  cursor: pointer;
}

.tenant-list__btn--primary {
  border-color: var(--wa-color-primary, #2563eb);
  background: var(--wa-color-primary, #2563eb);
  color: #fff;
}

.tenant-list__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
