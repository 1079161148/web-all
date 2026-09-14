<script setup lang="ts">
import { computed, h, ref } from 'vue'
import { NInput, NModal, NTag, NTooltip, ProTable, feedback } from '@admin/ui'
import type { ProColumn, ProRowAction } from '@admin/ui'
import type { ConfigRequest } from '@admin/api'
import {
  createConfigAction,
  deleteConfigAction,
  fetchConfigPage,
  updateConfigAction
} from '@/api/iam'

/**
 * 参数配置页。
 *
 * <h3>为什么参数值默认"打码显示"</h3>
 * 系统参数里经常混着不该出现在屏幕上的值（上传密钥、第三方 token 前缀等）。
 * 参数表不是密钥管理系统，但把敏感值显示在列表上是"顺手泄露"的常见来源。
 * 因此列表里参数值默认折叠显示，点击才展开 —— <b>让"看一眼"需要一个主动动作。</b>
 *
 * <p>更彻底的做法是引入独立的密钥管理（Vault / KMS），
 * 但那是 P2 的事。在此之前，这个默认折叠是成本最低、收益明确的一步。
 */

interface ConfigRow {
  id?: number
  configName?: string
  configKey?: string
  configValue?: string
  builtin?: boolean
  remark?: string
  sourceTenantId?: number
}

const tableRef = ref<{ reload: (resetPage?: boolean) => void } | null>(null)

/** 已展开查看的参数行 ID —— 按需展开，而不是全量显示。 */
const revealed = ref<Set<number>>(new Set())

function toggleReveal(row: ConfigRow): void {
  if (row.id === undefined) return
  const next = new Set(revealed.value)
  if (next.has(row.id)) {
    next.delete(row.id)
  } else {
    next.add(row.id)
  }
  revealed.value = next
}

const columns: ProColumn<ConfigRow>[] = [
  { key: 'configName', title: '参数名称', minWidth: 150, search: 'input' },
  {
    key: 'configKey',
    title: '参数键',
    width: 220,
    search: 'input',
    renderFn: (row) => h('code', { class: 'config-page__code' }, row.configKey ?? '')
  },
  {
    key: 'configValue',
    title: '参数值',
    minWidth: 200,
    renderFn: (row) => {
      const value = row.configValue ?? ''
      const isRevealed = row.id !== undefined && revealed.value.has(row.id)
      if (isRevealed) {
        return h('span', { class: 'config-page__value' }, value)
      }
      // 折叠态只显示长度，不显示前后缀：前后缀足以泄露"这是个 token"这类信息
      const masked = '•'.repeat(Math.min(value.length, 24))
      return h(
        NTooltip,
        { trigger: 'hover' },
        {
          trigger: () =>
            h(
              'span',
              {
                class: 'config-page__masked',
                onClick: () => toggleReveal(row),
                title: '点击展开'
              },
              masked || '（空）'
            ),
          default: () => '点击展开查看完整值'
        }
      )
    }
  },
  {
    key: 'builtin',
    title: '类型',
    width: 100,
    renderFn: (row) =>
      row.builtin
        ? h(
            NTooltip,
            { trigger: 'hover' },
            {
              trigger: () =>
                h(NTag, { size: 'small', type: 'warning', bordered: false }, { default: () => '内置' }),
              default: () => '系统内置参数由平台维护，不允许删除（应修改值而非删行）'
            }
          )
        : h('span', { class: 'config-page__muted' }, '自定义')
  },
  {
    key: 'sourceTenantId',
    title: '来源',
    width: 130,
    renderFn: (row) =>
      row.sourceTenantId === 0 || row.sourceTenantId === undefined
        ? h('span', { class: 'config-page__muted' }, '平台默认')
        : h('span', {}, `租户覆盖 #${row.sourceTenantId}`)
  },
  { key: 'remark', title: '备注', minWidth: 150 }
]

const rowActions: ProRowAction<ConfigRow>[] = [
  { key: 'edit', label: '编辑', permission: 'plt:config:update', onClick: (row) => openForm(row) },
  {
    key: 'delete',
    label: '删除',
    permission: 'plt:config:delete',
    danger: true,
    // 内置参数提前禁用 —— 后端也会拒绝，但让限制可见比事后报错友好
    disabled: (row) => row.builtin === true,
    confirm: (row) => `确定删除参数「${row.configName}」？若代码中仍在读取该键，将取不到值。`,
    onClick: async (row) => {
      if (row.id === undefined) return
      await deleteConfigAction(row.id)
      feedback.success('参数已删除')
      tableRef.value?.reload(false)
    }
  }
]

// ---------------------------------------------------------------------

const formVisible = ref(false)
const submitting = ref(false)
const editingId = ref<number | null>(null)
const form = ref<ConfigRequest>({
  configName: '',
  configKey: '',
  configValue: '',
  remark: ''
})
const isEdit = computed(() => editingId.value !== null)

function openForm(row: ConfigRow | null): void {
  if (row) {
    editingId.value = row.id ?? null
    form.value = {
      configName: row.configName ?? '',
      configKey: row.configKey ?? '',
      configValue: row.configValue ?? '',
      remark: row.remark ?? ''
    }
  } else {
    editingId.value = null
    form.value = { configName: '', configKey: '', configValue: '', remark: '' }
  }
  formVisible.value = true
}

async function submitForm(): Promise<void> {
  if (!form.value.configName.trim() || !form.value.configKey.trim()) {
    feedback.warning('请填写参数名称与参数键')
    return
  }
  if (!form.value.configValue.length) {
    feedback.warning('请填写参数值')
    return
  }
  submitting.value = true
  try {
    if (editingId.value !== null) {
      await updateConfigAction(editingId.value, form.value)
    } else {
      await createConfigAction(form.value)
    }
    feedback.success('保存成功')
    formVisible.value = false
    tableRef.value?.reload(false)
  } catch (error) {
    feedback.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="config-page">
    <div class="config-page__notice">
      参数值默认打码显示，<b>点击可展开</b>。这里不适合存放密钥类敏感信息 ——
      密钥应走专门的密钥管理，而不是系统参数表。
    </div>

    <ProTable
      ref="tableRef"
      :columns="columns"
      :request="fetchConfigPage"
      :toolbar="['create', 'refresh']"
      :row-actions="rowActions"
      empty-action-text="创建第一个参数"
      @create="openForm(null)"
      @empty-action="openForm(null)"
    />

    <n-modal v-model:show="formVisible" preset="card"
             :title="isEdit ? '编辑参数' : '新增参数'" style="width: 520px">
      <div class="config-page__form">
        <div class="config-page__field">
          <label>参数名称 <span class="config-page__required">*</span></label>
          <n-input v-model:value="form.configName" placeholder="如 用户初始密码" />
        </div>
        <div class="config-page__field">
          <label>参数键 <span class="config-page__required">*</span></label>
          <n-input v-model:value="form.configKey" placeholder="如 sys.user.init-password" />
          <p class="config-page__tip">
            小写字母开头，可含数字、点、下划线与连字符。代码里按它读取，创建后不建议修改。
          </p>
        </div>
        <div class="config-page__field">
          <label>参数值 <span class="config-page__required">*</span></label>
          <n-input v-model:value="form.configValue" type="textarea" :rows="2" />
        </div>
        <div class="config-page__field">
          <label>备注</label>
          <n-input v-model:value="form.remark" type="textarea" :rows="2" placeholder="说明这个参数的用途与取值含义" />
        </div>
      </div>
      <template #footer>
        <div class="config-page__footer">
          <n-button @click="formVisible = false">取消</n-button>
          <n-button type="primary" :loading="submitting" @click="submitForm">确定</n-button>
        </div>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.config-page__notice {
  margin-bottom: var(--wa-spacing-md, 12px);
  padding: var(--wa-spacing-md, 12px);
  border-left: 3px solid var(--wa-color-warning, #d97706);
  background: var(--wa-bg-elevated, #fff);
  font-size: var(--wa-font-size-xs, 12px);
  line-height: 1.8;
  color: var(--wa-text-secondary, #5c6570);
}

.config-page__code {
  padding: 1px 5px;
  border-radius: 3px;
  background: var(--wa-bg-hover, #f0f2f5);
  font-size: 0.9em;
}

.config-page__value {
  word-break: break-all;
}

.config-page__masked {
  letter-spacing: 2px;
  color: var(--wa-text-disabled, #a8b0ba);
  cursor: pointer;
}

.config-page__muted {
  color: var(--wa-text-disabled, #a8b0ba);
}

.config-page__form {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-lg, 16px);
}

.config-page__field {
  display: flex;
  flex-direction: column;
  gap: var(--wa-spacing-xs, 4px);
}

.config-page__field label {
  font-size: var(--wa-font-size-md, 14px);
  color: var(--wa-text-primary, #1f2329);
}

.config-page__required {
  color: var(--wa-color-error, #dc2626);
}

.config-page__tip {
  margin: 0;
  font-size: var(--wa-font-size-xs, 12px);
  line-height: 1.6;
  color: var(--wa-text-disabled, #a8b0ba);
}

.config-page__footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--wa-spacing-sm, 8px);
}
</style>
